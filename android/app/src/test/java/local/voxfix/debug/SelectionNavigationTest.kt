package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class SelectionNavigationTest {
    // WebView 排序回读，但 Shift 保留 DOM 的活动端并滚动到它。
    private class Host {
        val drag = SelectionDrag().apply { beginPositions(468, 468) }
        var selection = 468 to 468
        var time = 0L
        var reports = 0
        var keyRequests = 0
        var blocked = false
        var notify = true
        var domAnchor = 468
        var domHead = 468
        var scrollTarget = 468
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val nav = SelectionNavigation(drag, { selection }, { columns, lines, edge ->
            keyRequests++
            post(3) {
                val head = (domHead + columns + lines * 30).coerceIn(0, 1556)
                domHead = (if (edge < 0) head / 30 * 30 else if (edge > 0) head / 30 * 30 + 29 else head).coerceAtMost(1556)
                scrollTarget = domHead
                selection = minOf(domAnchor, domHead) to maxOf(domAnchor, domHead)
                if (notify) update(selection.first, selection.second)
            }
        }, ::post, { blocked = it; reports++ }, { head, columns, lines ->
            if (if (lines != 0) lines > 0 else columns > 0) head < 1556 else head > 0
        })
        fun own(anchor: Int, head: Int) {
            domAnchor = anchor; domHead = head; drag.beginPositions(anchor, head)
            selection = minOf(anchor, head) to maxOf(anchor, head)
        }
        fun post(delay: Long, action: () -> Unit) { tasks.add(time + delay to action) }
        fun update(start: Int, end: Int) {
            selection = start to end
            if (!nav.observe(start, end)) drag.observe(start, end)
        }
        fun flush() {
            while (tasks.isNotEmpty()) {
                val task = tasks.minBy { it.first }; tasks.remove(task)
                time = task.first; task.second()
            }
        }
        fun move(x: Int = 0, y: Int = 0, edge: Int = 0) { nav.run { nav.move(x, y, edge) } }
    }
    @Test fun webViewScrollMustFollowTheBackwardExtentWithoutRewritingTheRange() {
        val host = Host()
        repeat(3) { host.move(y = -1); host.flush() }
        assertEquals(378 to 468, host.selection)
        assertEquals("WebView must scroll to the moving extent, not the fixed anchor", 378, host.scrollTarget)
    }
    @Test fun repeatedUpKeeps468AsAnchorInsteadOfCollapsingTo438() {
        val host = Host()
        host.move(y = -1); host.flush()
        assertEquals(438 to 468, host.selection)
        host.move(y = -1); host.flush()
        assertEquals(408 to 468, host.selection)
        assertEquals(468, host.drag.anchor)
        assertEquals(408, host.drag.head)
        assertFalse(host.blocked)
    }
    @Test fun coalescedHostCallbacksDoNotRequireTheTemporaryCollapsedCaret() {
        val host = Host()
        host.notify = false
        repeat(2) { host.move(y = -1) }
        host.flush()
        assertEquals(408 to 468, host.selection)
        assertEquals(468, host.drag.anchor)
        assertTrue("A coalesced callback must finish without waiting for a missing collapse", host.time <= 400)
    }
    @Test fun queuedUpwardDiagonalsAndTurnsAlwaysStartAtTheActiveHead() {
        val host = Host()
        host.move(y = -1, edge = 1)
        host.move(y = -1, edge = -1)
        host.move(y = -1, edge = 1)
        host.move(x = 1)
        host.flush()
        assertEquals(468, host.drag.anchor)
        assertEquals(390, host.drag.head)
        assertEquals(390 to 468, host.selection)
        assertEquals(4, host.reports)
    }
    @Test fun crossingTheAnchorDoesNotRelockItAndCopyWaitsForRestore() {
        val host = Host()
        host.move(y = -1)
        host.move(y = 2)
        var copied: Pair<Int, Int>? = null
        host.nav.run { copied = host.selection }
        host.flush()
        assertEquals(468 to 498, copied)
        assertEquals(468, host.drag.anchor)
        assertEquals(498, host.drag.head)
    }
    @Test fun boundarySettlesAndManualRepositionIsNotSwallowedAfterward() {
        val host = Host()
        host.own(0, 0)
        repeat(20) { host.move(y = -1) }; host.flush()
        assertTrue(host.blocked)
        assertFalse(host.nav.busy)
        assertTrue("Queued repeats at a blocked boundary must not stall later edits", host.time < 500)
        assertFalse(host.nav.observe(17, 17))
        assertTrue(host.drag.isExternalChange(17, 17, 0, false, true))
    }
    @Test fun repeatedTouchesAtDocumentStartNeverCollapseOrSendNavigation() {
        val host = Host()
        host.own(192, 0)
        repeat(2) { host.move(y = -1); host.flush() }
        assertEquals(0 to 192, host.selection)
        assertEquals(0, host.keyRequests)
    }
    @Test fun aLargeDragStopsBeforeSendingTheNextKeyBeyondDocumentStart() {
        val host = Host()
        host.own(192, 70)
        host.move(y = -10); host.flush()
        assertEquals(0 to 192, host.selection)
        assertEquals(3, host.keyRequests)
        assertTrue(host.blocked)
    }
    @Test fun repeatedTouchesAtDocumentEndNeverCollapseOrSendNavigation() {
        val host = Host()
        host.own(192, 1556)
        repeat(2) { host.move(y = 1); host.flush() }
        assertEquals(0, host.keyRequests)
        assertEquals(192 to 1556, host.selection)
    }
    @Test fun externalRangeIsReleasedWithoutRestoringTheOldOrigin() {
        val host = Host()
        host.move(y = -1)
        host.post(4) { host.update(12, 15) }
        host.flush()
        assertEquals(-1, host.drag.anchor)
        assertEquals(12 to 15, host.selection)
        assertFalse(host.nav.busy)
    }
    @Test fun changingInputCancelsPendingRestoreAndQueuedEdits() {
        val host = Host()
        host.move(y = -1)
        var edited = false
        host.nav.run { edited = true }
        host.nav.cancel(); host.drag.clear(); host.flush()
        assertEquals(0, host.reports)
        assertFalse(edited)
        assertFalse(host.nav.busy)
    }
    @Test fun forwardMovesKeepTheWholeSelectionWithoutTemporaryCollapse() {
        val host = Host()
        repeat(5) { host.move(y = 1) }; host.flush()
        assertEquals(468 to 618, host.selection)
    }
}
