package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class SelectionDragTest {
    @Test fun longPressMaySelectANewWordWithoutFirstCollapsingTheOldSelection() {
        val drag = SelectionDrag()
        assertTrue(drag.beginHostSelection(192, 194, 194, 195))
        assertTrue(drag.finishHostSelection())
        assertEquals(194, drag.anchor)
        assertEquals(195, drag.head)
        assertTrue(drag.beginHostSelection(194, 195, 192, 194))
        assertTrue(drag.finishHostSelection())
        assertTrue(drag.beginHostSelection(0, 192, 65, 67))
        assertTrue(drag.finishHostSelection())
    }
    @Test fun hostWordSelectionMayExpandFromOneCharacterToTwo() {
        val drag = SelectionDrag()
        assertTrue(drag.beginHostSelection(17, 17, 16, 17))
        drag.observe(15, 17)
        assertTrue(drag.beginHostSelection(16, 17, 15, 17))
        assertEquals(15, drag.anchor)
        assertEquals(17, drag.head)
        assertTrue(drag.finishHostSelection())
        assertFalse(drag.finishHostSelection())
    }
    @Test fun hostSelectionCanContainSeveralCharactersOnItsFirstCallback() {
        val drag = SelectionDrag()
        assertTrue(drag.beginHostSelection(17, 17, 15, 17))
        assertTrue(drag.finishHostSelection())
        assertEquals(15, drag.anchor)
        assertEquals(17, drag.head)
    }
    @Test fun collapsedOrKeyboardCancelledHostSelectionDoesNotOpenLater() {
        val drag = SelectionDrag()
        assertTrue(drag.beginHostSelection(17, 17, 16, 17))
        drag.observe(20, 20)
        assertFalse(drag.finishHostSelection())
        assertTrue(drag.beginHostSelection(20, 20, 18, 22))
        drag.cancelHostSelection()
        assertFalse(drag.finishHostSelection())
        assertFalse(drag.beginHostSelection(18, 22, 17, 22))
    }
    @Test fun settledHostSelectionStillReleasesForManualHandleMovement() {
        val drag = SelectionDrag()
        assertTrue(drag.beginHostSelection(17, 17, 15, 17))
        assertTrue(drag.finishHostSelection())
        assertTrue(drag.isExternalChange(14, 17, 15, false, false))
        drag.clear()
        assertFalse(drag.beginHostSelection(15, 17, 14, 17))
    }
    @Test fun hostLongPressAfterManualRepositionStartsAFreshSelection() {
        val drag = SelectionDrag()
        drag.beginPositions(10, 18)
        assertTrue(drag.isExternalChange(17, 17, 10, false, true))
        drag.clear()
        assertFalse(drag.beginHostSelection(10, 18, 17, 17))
        assertTrue(drag.beginHostSelection(17, 17, 15, 19))
        assertEquals(15, drag.anchor)
        assertEquals(19, drag.head)
    }
    @Test fun movingExistingHostHandlesDoesNotReopenTheEditor() {
        val drag = SelectionDrag()
        drag.beginPositions(15, 19)
        assertTrue(drag.isExternalChange(14, 19, 15, false, false))
        drag.clear()
        assertFalse(drag.beginHostSelection(15, 19, 14, 19))
        assertFalse(drag.beginHostSelection(14, 19, 13, 19))
        assertEquals(-1, drag.anchor)
        assertFalse(drag.beginHostSelection(-1, -1, 13, 19))
        assertFalse(drag.beginHostSelection(17, 17, -1, -1))
    }
    @Test fun manualCaretAndHandleMovesReleaseTheOldLock() {
        val drag = SelectionDrag()
        drag.beginPositions(10, 18)
        assertTrue(drag.isExternalChange(17, 17, 10, false, true))
        assertTrue(drag.isExternalChange(10, 10, 10, false, false))
        assertTrue(drag.isExternalChange(6, 18, 10, false, false))
        // 窗口内、且有一端还压在旧活动端上（18）：这是宿主重算词边界，按精修跟随，不能收面板。
        assertFalse(drag.isExternalChange(6, 18, 10, false, true))
        assertFalse(drag.isExternalChange(10, 18, 10, false, false))
    }
    @Test fun keyboardMotionAndLateCollapseKeepTheAnchor() {
        val drag = SelectionDrag()
        drag.beginPositions(10, 18)
        assertFalse(drag.isExternalChange(10, 10, 10, true, false))
        assertFalse(drag.isExternalChange(10, 10, 10, false, true))
        drag.clear() // 全选由 edit() 清掉旧活动端，但保留锁定起点。
        assertFalse(drag.isExternalChange(0, 30, 10, false, true))
        assertFalse(drag.isExternalChange(17, 17, -1, false, false))
        assertFalse(drag.isExternalChange(-1, -1, 10, false, false))
    }
    @Test fun explicitLongPressRelocksTheCurrentHead() {
        val drag = SelectionDrag()
        drag.begin("abcdefgh", 10, 16, 16); drag.move(12)
        drag.lock("abcdefgh", 10, drag.head)
        assertEquals(12, drag.anchor)
        drag.move(18)
        assertEquals(12, drag.anchor)
        assertTrue(drag.matches("abcdefgh", 10, 12, 18))
    }
    @Test fun resumeKeepsTheOriginalAnchorInBothDirections() {
        val drag = SelectionDrag()
        drag.begin("abcdefgh", 10, 18, 18)
        drag.move(15)
        drag.begin("abcdefgh", 10, 15, 18)
        assertEquals(18, drag.anchor)
        assertEquals(15, drag.head)
        drag.move(12)
        assertEquals(18, drag.anchor)
        drag.move(18)
        drag.move(20)
        assertEquals(18, drag.head)
        drag.begin("abcdefgh", 10, 12, 12)
        drag.move(16)
        drag.begin("abcdefgh", 10, 12, 16)
        assertEquals(12, drag.anchor)
        drag.move(10)
        assertEquals(12, drag.anchor)
    }
    @Test fun editedTextOrExternalCursorStartsANewSelection() {
        val drag = SelectionDrag()
        drag.begin("abcd", 0, 4, 4); drag.move(2)
        drag.begin("abXd", 0, 2, 4)
        assertEquals(2, drag.anchor)
        drag.begin("abXd", 0, 1, 1)
        assertEquals(1, drag.anchor)
        drag.clear()
        assertFalse(drag.matches("abXd", 0, 1, 1))
    }
    // 解除起点锁定（gestureUnlock）靠 head 收起选区：反向拖选后 head 是手指那一端，
    // 不是较大的索引 —— 用 max(anchor, head) 会跳到起点那一侧。
    @Test fun unlockingCollapsesOntoTheActiveHeadEvenWhenReversed() {
        val drag = SelectionDrag()
        drag.begin("abcdefgh", 10, 18, 18)
        drag.move(15)
        drag.move(12)
        assertEquals(18, drag.anchor)
        assertEquals(12, drag.head)
        val collapsed = drag.head
        drag.clear()
        assertEquals(12, collapsed)
        assertEquals(-1, drag.anchor)
        assertFalse(drag.matches("abcdefgh", 10, 12, 12))
    }
    // 宿主自己改选区时（onUpdateSelection → observe）活动端同样跟着手指走，
    // 这样紧接着按滑轨长按解除锁定才能落在正确的一侧。
    @Test fun observedHostSelectionKeepsTrackingTheActiveEnd() {
        val drag = SelectionDrag()
        drag.beginPositions(20, 20)
        drag.observe(18, 20)
        assertEquals(20, drag.anchor)
        assertEquals(18, drag.head)
        drag.observe(20, 24)
        assertEquals(20, drag.anchor)
        assertEquals(24, drag.head)
        drag.observe(19, 19)
        assertEquals(-1, drag.anchor)
    }

    // 真机复现（2026-10-14）：收起键盘后长按选词，编辑框开出来约 90ms 就被收掉，只剩键盘。
    // 日志里是 `taken=(972,973)` 之后紧跟 `new=971,973` 被判成 external——宿主把词边界重算、整段平了一格。
    // 这种"自己刚改的窗口内的小幅平移"必须跟随，不能当手动重定位。
    @Test fun ownWordBoundaryRefinementRightAfterOpeningIsNotAManualMove() {
        val drag = SelectionDrag()
        drag.beginPositions(972, 973)
        assertFalse(drag.isExternalChange(971, 973, 972, false, true))
        drag.observe(971, 973)
        assertEquals(971, drag.anchor)
        assertEquals(973, drag.head)
    }

    // 但同一窗口内如果选区整段跑到别处（差两个字以上），仍然算手动重定位，得让位给用户。
    @Test fun farAwayRangeIsStillExternalEvenInsideTheOwnChangeWindow() {
        val drag = SelectionDrag()
        drag.beginPositions(972, 973)
        assertTrue(drag.isExternalChange(400, 420, 972, false, true))
        drag.observe(400, 420)
        assertEquals(-1, drag.anchor)
    }

    // 窗口过后（不是自己刚改的），小幅平移照旧算手动重定位——原来的语义不能丢。
    @Test fun smallShiftAfterTheOwnChangeWindowIsStillAManualMove() {
        val drag = SelectionDrag()
        drag.beginPositions(972, 973)
        assertTrue(drag.isExternalChange(971, 973, 972, false, false))
    }
}
