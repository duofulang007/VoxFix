package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class CursorBoundaryTest {
    private fun boundary(startOffset: Int = 0, selectionStart: Int, selectionEnd: Int = selectionStart, textLength: Int, completeText: Boolean = true) =
        CursorBoundary(selectionStart != selectionEnd, completeText, startOffset, selectionStart, selectionEnd, textLength)

    @Test fun collapsedCaretStopsAtBothEnds() {
        val atStart = boundary(selectionStart = 0, textLength = 6)
        assertFalse("回到文本开头后不能再发左方向键", atStart.canStep(false))
        assertTrue(atStart.canStep(true))

        val atEnd = boundary(selectionStart = 6, textLength = 6)
        assertTrue(atEnd.canStep(false))
        assertFalse("到文本末尾后不能再发右方向键", atEnd.canStep(true))
    }

    @Test fun caretInTheMiddleMovesBothWays() {
        val middle = boundary(selectionStart = 3, textLength = 6)
        assertTrue(middle.canStep(false))
        assertTrue(middle.canStep(true))
    }

    @Test fun startOffsetShiftsTheBoundary() {
        val firstCharOfWholeText = boundary(startOffset = 10, selectionStart = 0, textLength = 5)
        assertFalse(firstCharOfWholeText.canStep(false))

        val insideExtract = boundary(startOffset = 10, selectionStart = 1, textLength = 5)
        assertTrue(insideExtract.canStep(false))
    }

    @Test fun nonCollapsedSelectionOnlyCollapses() {
        val selection = boundary(selectionStart = 0, selectionEnd = 6, textLength = 6)
        assertTrue("有选区时左方向键只折叠选区", selection.canStep(false))
        assertTrue("有选区时右方向键只折叠选区", selection.canStep(true))
    }

    @Test fun partialTextNeverBlocks() {
        val partialStart = boundary(selectionStart = 0, textLength = 20, completeText = false)
        assertTrue("局部文本要留给方向键跨段", partialStart.canStep(false))
        val partialEnd = boundary(selectionStart = 20, textLength = 20, completeText = false)
        assertTrue(partialEnd.canStep(true))
    }
}
