package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class ScreenshotDrawingTest {
    @Test fun eraseMoveAndStyleChangesAreUndoable() {
        val history=ScreenshotDrawing()
        val original=stroke(ScreenshotDrawingTool.RECTANGLE)
        history.add(original)
        val moved=original.transformed(10f,20f,30f,60f,2f,3f)
        history.replace(0,moved)
        assertEquals(ScreenshotDrawingPoint(90f,180f),history.items.single().points.last())
        history.replace(0,moved.copy(color=123))
        history.remove(setOf(0)); assertTrue(history.items.isEmpty())
        history.undo(); assertEquals(123,history.items.single().color)
        history.undo(); assertEquals(moved,history.items.single())
        history.undo(); assertEquals(original,history.items.single())
        history.redo(); assertEquals(moved,history.items.single())
    }
    private fun stroke(tool: ScreenshotDrawingTool) = ScreenshotDrawingStroke(tool,
        listOf(ScreenshotDrawingPoint(10f,20f),ScreenshotDrawingPoint(40f,60f)),0xFFFF0000.toInt(),4f)
    @Test fun drawingHistoryKeepsOrderAndDiscardsRedoAfterNewStroke() {
        val history = ScreenshotDrawing()
        val arrow = stroke(ScreenshotDrawingTool.ARROW); val rectangle = stroke(ScreenshotDrawingTool.RECTANGLE)
        history.add(arrow); history.add(rectangle); history.undo()
        assertEquals(listOf(arrow),history.items); assertTrue(history.canRedo)
        history.redo(); assertEquals(listOf(arrow,rectangle),history.items)
        history.undo(); history.add(stroke(ScreenshotDrawingTool.PEN))
        assertFalse(history.canRedo); history.redo(); assertEquals(ScreenshotDrawingTool.PEN,history.items.last().tool)
    }
    @Test fun emptyHistoryAndInputListCannotChangeExistingStrokes() {
        val history = ScreenshotDrawing(); history.undo(); history.redo()
        val points = mutableListOf(ScreenshotDrawingPoint(1f,2f))
        history.add(ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN,points,0,2f)); points.clear()
        assertEquals(1,history.items.single().points.size)
        history.undo(); assertFalse(history.canUndo); assertTrue(history.canRedo)
    }
}
