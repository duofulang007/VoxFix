package local.voxfix.debug

import local.voxfix.debug.ScreenshotBindingGeometry.Bounds
import local.voxfix.debug.ScreenshotBindingGeometry.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Drag-time glue: what one endpoint drag writes onto the stroke. */
class ScreenshotBindingDragTest {

    private fun element() = ScreenshotBindingGeometry.Bindable(5L, Shape.RECTANGLE, 0f, 0f, 100f, 50f)

    private fun arrowStroke(vararg points: Pair<Float, Float>) = ScreenshotDrawingStroke(
        tool = ScreenshotDrawingTool.ARROW,
        points = points.map { ScreenshotDrawingPoint(it.first, it.second) },
        color = 0xFFF5222D.toInt(),
        width = 2f,
        id = 9L
    )

    @Test
    fun droppingTheTipInsideAnElementPinsIt() {
        val stroke = arrowStroke(10f to 10f, 50f to 25f)
        val world = stroke.points
        val result = ScreenshotBindingDrag.dragEndpoint(
            stroke, world, world.lastIndex, ScreenshotDrawingPoint(50f, 25f), listOf(element())
        )
        assertNotNull(result)
        assertNull(result!!.startBinding)
        assertEquals(ScreenshotBindMode.INSIDE, result.endBinding!!.mode)
        assertEquals(5L, result.endBinding!!.elementId)
        // the tip is pulled onto the pinned ratio, not left where the finger was
        assertEquals(50.01, result.points.last().x.toDouble(), 0.05)
        assertEquals(25.005, result.points.last().y.toDouble(), 0.05)
    }

    @Test
    fun droppingTheTipInEmptySpaceClearsTheBinding() {
        val stroke = arrowStroke(10f to 10f, 50f to 25f)
        // the caller has already written the dragged tip into the world points
        val world = stroke.points.toMutableList()
        world[world.lastIndex] = ScreenshotDrawingPoint(400f, 400f)
        val result = ScreenshotBindingDrag.dragEndpoint(
            stroke, world, world.lastIndex, ScreenshotDrawingPoint(400f, 400f), listOf(element())
        )
        assertNotNull(result)
        assertNull(result!!.endBinding)
        assertNull(result.startBinding)
        assertEquals(400f, result.points.last().x)
    }

    @Test
    fun middleVerticesAndNonArrowsAreLeftAlone() {
        val polyline = arrowStroke(0f to 0f, 40f to 10f, 80f to 0f)
        assertNull(
            ScreenshotBindingDrag.dragEndpoint(
                polyline, polyline.points, 1, ScreenshotDrawingPoint(50f, 25f), listOf(element())
            )
        )
        val pen = ScreenshotDrawingStroke(
            ScreenshotDrawingTool.PEN, listOf(ScreenshotDrawingPoint(0f, 0f), ScreenshotDrawingPoint(5f, 5f)),
            0, 2f, id = 10L
        )
        assertNull(
            ScreenshotBindingDrag.dragEndpoint(
                pen, pen.points, 1, ScreenshotDrawingPoint(50f, 25f), listOf(element())
            )
        )
    }

    @Test
    fun anExistingPinSurvivesDraggingTheOtherEnd() {
        val pin = ScreenshotFixedPointBinding(5L, ScreenshotBindMode.INSIDE, 1.0, 0.5)
        val stroke = arrowStroke(100f to 25f, 300f to 300f).copy(startBinding = pin)
        val world = stroke.points.toMutableList()
        world[1] = ScreenshotDrawingPoint(400f, 400f)
        val result = ScreenshotBindingDrag.dragEndpoint(
            stroke, world, 1, ScreenshotDrawingPoint(400f, 400f), listOf(element())
        )
        assertNotNull(result)
        assertEquals(pin, result!!.startBinding)
        // the free end moved to the pointer, the pinned end stayed on the element edge
        assertEquals(100.0, result.points.first().x.toDouble(), 0.05)
    }

    @Test
    fun bindableBoundsComeFromTheElementBox() {
        val bounds = Bounds(0f, 0f, 100f, 50f)
        assertEquals(100f, bounds.right - bounds.left)
        assertEquals(50f, bounds.bottom - bounds.top)
    }
}
