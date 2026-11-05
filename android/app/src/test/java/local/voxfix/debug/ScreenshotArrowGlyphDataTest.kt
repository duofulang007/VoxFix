package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Icon table from the fixed commit's `arrowhead-*.svg` / `arrow-type-*.svg` / `arrow-shaft-*.svg`.
 * Only the data is asserted here; drawing needs `android.graphics.Path`, which unit tests stub.
 */
class ScreenshotArrowGlyphDataTest {
    @Test
    fun panelOffersTheFourteenUpstreamHeads() {
        assertEquals(14, ScreenshotArrowGlyphs.PANEL_HEADS.size)
        assertEquals(ScreenshotArrowhead.NONE, ScreenshotArrowGlyphs.PANEL_HEADS.first())
        assertEquals(ScreenshotArrowhead.CROWFOOT_ONE_OR_MANY, ScreenshotArrowGlyphs.PANEL_HEADS.last())
        // Upstream has the Square enum value but neither an icon nor a palette entry.
        assertFalse(ScreenshotArrowGlyphs.PANEL_HEADS.contains(ScreenshotArrowhead.SQUARE))
        assertEquals(14, ScreenshotArrowGlyphs.PANEL_HEADS.toSet().size)
    }

    @Test
    fun everyHeadResolvesToAnIcon() {
        for (head in ScreenshotArrowGlyphs.PANEL_HEADS + ScreenshotArrowhead.SQUARE) {
            val glyph = ScreenshotArrowGlyphs.head(head)
            assertTrue("$head has no parts", glyph.parts.isNotEmpty())
            assertTrue("$head has neither fill nor stroke", glyph.parts.any { it.fill || it.stroke })
        }
    }

    @Test
    fun headStripsUseTheUpstreamViewBoxes() {
        for (head in ScreenshotArrowGlyphs.PANEL_HEADS) {
            val glyph = ScreenshotArrowGlyphs.head(head)
            if (head == ScreenshotArrowhead.NONE) {
                assertEquals(24f, glyph.width, 1e-6f)
                assertEquals(24f, glyph.height, 1e-6f)
                assertEquals(.3f, glyph.alpha, 1e-6f)
            } else {
                assertEquals(40f, glyph.width, 1e-6f)
                assertEquals(20f, glyph.height, 1e-6f)
                assertEquals(1f, glyph.alpha, 1e-6f)
            }
        }
    }

    /** The crowfoot files and `none` author the head on the left, so only their end form mirrors. */
    @Test
    fun onlyTheLeftAuthoredHeadsFlipForTheEnd() {
        val leftAuthored = setOf(ScreenshotArrowhead.NONE, ScreenshotArrowhead.CROWFOOT_ONE,
            ScreenshotArrowhead.CROWFOOT_MANY, ScreenshotArrowhead.CROWFOOT_ONE_OR_MANY)
        for (head in ScreenshotArrowGlyphs.PANEL_HEADS)
            assertEquals("$head flip", head in leftAuthored, ScreenshotArrowGlyphs.head(head).flipForEnd)
    }

    @Test
    fun shaftAndTypeIconsArePresent() {
        for (type in ScreenshotArrowType.values()) {
            val glyph = ScreenshotArrowGlyphs.type(type)
            assertEquals(1024f, glyph.width, 1e-6f)
            assertTrue("$type has no parts", glyph.parts.isNotEmpty())
        }
        for (shaft in ScreenshotArrowShaftType.values())
            assertTrue("$shaft has no parts", ScreenshotArrowGlyphs.shaft(shaft).parts.isNotEmpty())
        assertTrue(ScreenshotArrowGlyphs.RATIO.parts.size >= 5)
    }
}
