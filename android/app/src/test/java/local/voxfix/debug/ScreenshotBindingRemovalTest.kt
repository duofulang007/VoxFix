package local.voxfix.debug

import local.voxfix.debug.ScreenshotBindingGeometry.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Deleting a shape must not leave an arrow pointing at nothing. */
class ScreenshotBindingRemovalTest {

    private fun shape(id: Long) = ScreenshotDrawingStroke(
        tool = ScreenshotDrawingTool.RECTANGLE,
        points = listOf(ScreenshotDrawingPoint(0f, 0f), ScreenshotDrawingPoint(100f, 50f)),
        color = 0xFFF5222D.toInt(), width = 4f, id = id
    )

    private fun arrow(id: Long, start: ScreenshotFixedPointBinding?, end: ScreenshotFixedPointBinding?) =
        ScreenshotDrawingStroke(
            tool = ScreenshotDrawingTool.ARROW,
            points = listOf(ScreenshotDrawingPoint(120f, 25f), ScreenshotDrawingPoint(160f, 25f)),
            color = 0xFFF5222D.toInt(), width = 2f, id = id,
            startBinding = start, endBinding = end
        )

    @Test
    fun deletingTheTargetClearsOnlyTheBindingsThatPointAtIt() {
        val history = ScreenshotDrawing()
        val pin = ScreenshotFixedPointBinding(1L, ScreenshotBindMode.INSIDE, 1.0, 0.5)
        history.add(shape(1L))
        history.add(shape(2L))
        history.add(arrow(3L, pin, ScreenshotFixedPointBinding(2L, ScreenshotBindMode.INSIDE, 0.0, 0.5)))
        assertEquals(3, history.items.size)

        history.remove(setOf(0))  // delete the first shape

        val remaining = history.items.single { it.id == 3L }
        assertNull(remaining.startBinding)
        assertEquals(2L, remaining.endBinding!!.elementId)
        assertEquals(2, history.items.size)
    }

    @Test
    fun undoingTheDeleteBringsTheBindingBack() {
        val history = ScreenshotDrawing()
        val pin = ScreenshotFixedPointBinding(1L, ScreenshotBindMode.ORBIT, 0.5, 0.0)
        history.add(shape(1L))
        history.add(arrow(3L, null, pin))
        history.remove(setOf(0))
        assertNull(history.items.single().endBinding)
        history.undo()
        assertEquals(pin, history.items.single { it.id == 3L }.endBinding)
    }

    @Test
    fun aBindableStillProducesRectangleProxies() {
        val proxy = ScreenshotBindingDocument.bindables(
            listOf(shape(1L)),
            { BoundsOf(it) },
            { 0 }
        ).single()
        assertEquals(Shape.RECTANGLE, proxy.shape)
        assertEquals(1L, proxy.id)
    }

    private fun BoundsOf(stroke: ScreenshotDrawingStroke) = ScreenshotBindingGeometry.Bounds(
        stroke.points.minOf { it.x }, stroke.points.minOf { it.y },
        stroke.points.maxOf { it.x }, stroke.points.maxOf { it.y }
    )
}
