package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScreenshotAutoFilterTest {
    private val image = ScreenshotAutoFilter.Region(ScreenshotDocRect(0, 0, 100, 100), 2)
    private val label = ScreenshotAutoFilter.Region(ScreenshotDocRect(20, 20, 30, 30), 0)
    private val regions = listOf(image, label)
    private val style = ScreenshotDrawingStyle(area = ScreenshotAreaMode.PEN)

    @Test fun picksSmallestHitAndMarqueeUsesStrictIntersection() {
        assertEquals(label, ScreenshotAutoFilter.hit(regions, 21f, 22f))
        assertEquals(image, ScreenshotAutoFilter.hit(regions, 30f, 22f))
        assertEquals(listOf(image), ScreenshotAutoFilter.intersecting(regions,
            ScreenshotDrawingPoint(1f, 1f), ScreenshotDrawingPoint(20f, 22f)))
        assertEquals(regions, ScreenshotAutoFilter.intersecting(regions,
            ScreenshotDrawingPoint(25f, 25f), ScreenshotDrawingPoint(1f, 1f)))
    }

    @Test fun categoryIsOneUndoAndTapTogglesOnlyItsRegion() {
        val history = ScreenshotDrawing()
        history.fillAutoRegions(regions, style, 1f, toggle = false)
        assertEquals(listOf(2, 0), history.items.map { it.autoFilterCategory })
        assertEquals(listOf(ScreenshotAreaMode.RECTANGLE, ScreenshotAreaMode.RECTANGLE), history.items.map { it.style.area })
        history.fillAutoRegions(listOf(label), style, 1f, toggle = true)
        assertEquals(listOf(2), history.items.map { it.autoFilterCategory })
        history.undo()
        assertEquals(listOf(2, 0), history.items.map { it.autoFilterCategory })
        history.undo()
        assertEquals(emptyList<ScreenshotDrawingStroke>(), history.items)
        assertFalse(history.canUndo)
    }
}
