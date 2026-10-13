package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class ParagraphSelectionTest {
    @Test fun consecutiveParagraphsAndReverseKeepRealNewlineBoundaries() {
        val text = "甲😀乙\n第二段\n\n最后"
        assertEquals(4, ParagraphSelection.boundary(text, 1, 1))
        assertEquals(8, ParagraphSelection.boundary(text, 4, 1))
        assertEquals(9, ParagraphSelection.boundary(text, 8, 1))
        assertEquals(text.length, ParagraphSelection.boundary(text, 9, 1))
        assertEquals(10, ParagraphSelection.boundary(text, text.length, -1))
        assertEquals(5, ParagraphSelection.boundary(text, 8, -1))
        assertEquals(0, ParagraphSelection.boundary(text, 0, -1))
    }
}
