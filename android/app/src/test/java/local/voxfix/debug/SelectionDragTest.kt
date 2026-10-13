package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class SelectionDragTest {
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
}
