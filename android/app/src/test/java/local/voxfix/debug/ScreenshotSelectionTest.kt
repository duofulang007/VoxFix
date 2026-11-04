package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class ScreenshotSelectionTest {
    private fun box(l: Float, t: Float, r: Float, b: Float) = SelBox(l, t, r, b)

    /** Shrinking the capture box used to freeze both axes because the clamp range came out empty. */
    @Test fun oversizedObjectMovesOnBothAxes() {
        val object_ = box(90f, 110f, 250f, 270f)
        val crop = box(200f, 200f, 340f, 340f)
        assertEquals(-40f, ScreenshotSelection.moveX(object_, crop, -40f), 0.001f)
        assertEquals(-40f, ScreenshotSelection.moveY(object_, crop, -40f), 0.001f)
        assertEquals(120f, ScreenshotSelection.moveX(object_, crop, 120f), 0.001f)
    }

    @Test fun fittingObjectStaysInsideTheCaptureBox() {
        val object_ = box(10f, 10f, 60f, 60f)
        val crop = box(0f, 0f, 100f, 100f)
        assertEquals(-10f, ScreenshotSelection.moveX(object_, crop, -80f), 0.001f)
        assertEquals(40f, ScreenshotSelection.moveX(object_, crop, 80f), 0.001f)
        assertEquals(0f, ScreenshotSelection.moveY(object_, crop, 0f), 0.001f)
    }

    @Test fun cornerHandleBeatsAnInteriorGrab() {
        val object_ = box(0f, 0f, 100f, 100f)
        assertEquals(0, ScreenshotSelection.handle(object_, 2f, 3f, 10f))
        assertEquals(2, ScreenshotSelection.handle(object_, 99f, 98f, 10f))
        assertEquals(ScreenshotSelection.NO_HANDLE, ScreenshotSelection.handle(object_, 50f, 50f, 10f))
        assertTrue(ScreenshotSelection.inside(object_, 50f, 50f, 7f))
        assertFalse(ScreenshotSelection.inside(object_, 120f, 50f, 7f))
    }

    @Test fun resizeAnchorsTheOppositeCornerAndHonoursTheMinimum() {
        val object_ = box(0f, 0f, 100f, 100f)
        val grown = ScreenshotSelection.resize(object_, 2, 160f, 140f, 12f)
        assertEquals(SelBox(0f, 0f, 160f, 140f), grown)
        val topLeft = ScreenshotSelection.resize(object_, 0, -20f, -20f, 12f)
        assertEquals(100f, topLeft.right, 0.001f)
        assertEquals(100f, topLeft.bottom, 0.001f)
        assertEquals(-20f, topLeft.left, 0.001f)
        val collapsed = ScreenshotSelection.resize(object_, 2, -50f, -50f, 12f)
        assertEquals(12f, collapsed.width, 0.001f)
        assertEquals(12f, collapsed.height, 0.001f)
    }

    @Test fun objectIdentitySurvivesTransformsAndReload() {
        val history = ScreenshotDrawing()
        val rectangle = ScreenshotDrawingStroke(ScreenshotDrawingTool.RECTANGLE,
            listOf(ScreenshotDrawingPoint(10f, 10f), ScreenshotDrawingPoint(60f, 60f)), 0, 2f)
        history.add(rectangle)
        val id = history.items.single().id
        assertTrue(id > 0L)
        history.replace(0, history.items[0].transformed(10f, 10f, 20f, 20f))
        assertEquals(id, history.items.single().id)
        val reloaded = ScreenshotDrawing()
        reloaded.load(history.items)
        assertEquals(id, reloaded.items.single().id)
        assertFalse(reloaded.canUndo)
        reloaded.add(ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN,
            listOf(ScreenshotDrawingPoint(1f, 1f)), 0, 2f))
        assertNotEquals(id, reloaded.items.last().id)
        assertTrue(reloaded.items.last().id > id)
    }
}
