package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ScreenshotDrawingTest {
    @Test fun duplicateRemapsLinkedTextAndOffsetsBothInOneUndoStep() {
        val history = ScreenshotDrawing()
        history.addSerialWithText(stroke(ScreenshotDrawingTool.SERIAL),stroke(ScreenshotDrawingTool.TEXT))
        val before = history.items
        assertEquals(setOf(2),history.duplicate(setOf(0)))
        val (serial,label) = history.items.takeLast(2)
        assertNotEquals(before.first().id,serial.id)
        assertEquals(label.id,serial.boundTextId)
        assertEquals(before[1].points.map { ScreenshotDrawingPoint(it.x+12,it.y+12) },label.points)
        history.undo()
        assertEquals(before,history.items)
    }

    @Test fun templateKeepsPaintOrderAndRemapsOnlyInternalLinksInOneUndoStep() {
        val history = ScreenshotDrawing()
        val insideBinding = ScreenshotFixedPointBinding(1, ScreenshotBindMode.ORBIT, .5, .5)
        val outside = insideBinding.copy(elementId = 4)
        history.load(listOf(
            stroke(ScreenshotDrawingTool.RECTANGLE).copy(id = 1),
            stroke(ScreenshotDrawingTool.ARROW).copy(id = 2, startBinding = insideBinding, endBinding = outside),
            stroke(ScreenshotDrawingTool.SERIAL).copy(id = 3, boundTextId = 5),
            stroke(ScreenshotDrawingTool.RECTANGLE).copy(id = 4),
            stroke(ScreenshotDrawingTool.TEXT).copy(id = 5)
        ))
        val before = history.items
        val template = history.templateObjects(linkedSetOf(2, 1, 0))
        assertEquals(listOf(1L, 2L, 3L, 5L), template.map { it.id })
        assertEquals(setOf(5, 6, 7), history.insertTemplate(template, setOf(1, 2, 3), 20f, -30f))
        val (shape, arrow, serial, label) = history.items.takeLast(4)
        assertNotEquals(1L, shape.id)
        assertEquals(shape.id, arrow.startBinding?.elementId)
        assertNull(arrow.endBinding)
        assertEquals(label.id, serial.boundTextId)
        assertEquals(template[0].points.map { ScreenshotDrawingPoint(it.x + 20, it.y - 30) }, shape.points)
        history.undo()
        assertEquals(before, history.items)
        assertFalse(history.canUndo)
    }

    @Test fun alignmentUsesUnionEdgesAndDistributionPreservesOuterUnits() {
        fun box(start: Float,end: Float) = SelBox(start,start,end,end)
        val separate = mapOf(0 to box(0f,10f),1 to box(20f,40f),2 to box(70f,100f))
        assertEquals(ScreenshotDrawingPoint(-20f,0f),ScreenshotSelectionAlignment.LEFT.deltas(separate)[1])
        assertEquals(ScreenshotDrawingPoint(0f,20f),ScreenshotSelectionAlignment.CENTER_VERTICAL.deltas(separate)[1])
        assertEquals(ScreenshotDrawingPoint(10f,0f),ScreenshotSelectionAlignment.DISTRIBUTE_HORIZONTAL.deltas(separate)[1])
        val overlap = mapOf(0 to box(0f,45f),1 to box(30f,55f),2 to box(40f,70f))
        val delta = ScreenshotSelectionAlignment.DISTRIBUTE_VERTICAL.deltas(overlap)
        assertEquals(mapOf(1 to ScreenshotDrawingPoint(0f,-3.75f)),delta)
        assertTrue(ScreenshotSelectionAlignment.DISTRIBUTE_HORIZONTAL.deltas(separate.filterKeys { it!=1 }).isEmpty())
    }

    @Test fun layerActionsKeepRelativeOrderAndCommitOnlyWhenTheOrderChanges() {
        val history = ScreenshotDrawing()
        val strokes = (1L..6L).map { stroke(ScreenshotDrawingTool.RECTANGLE).copy(id=it) }
        val expected = mapOf(
            ScreenshotDrawing.Order.BACK to listOf(2L,4L,5L,1L,3L,6L),
            ScreenshotDrawing.Order.BACKWARD to listOf(2L,1L,4L,5L,3L,6L),
            ScreenshotDrawing.Order.FORWARD to listOf(1L,3L,2L,6L,4L,5L),
            ScreenshotDrawing.Order.FRONT to listOf(1L,3L,6L,2L,4L,5L)
        )
        for ((action, ids) in expected) {
            history.load(strokes)
            assertTrue(history.reorder(setOf(1,3,4),action))
            assertEquals(ids,history.items.map { it.id })
            history.undo()
            assertEquals(strokes,history.items)
            assertFalse(history.canUndo)
            history.redo()
            assertEquals(ids,history.items.map { it.id })
        }
        history.load(strokes)
        assertFalse(history.reorder(setOf(0,1),ScreenshotDrawing.Order.BACKWARD))
        assertFalse(history.reorder(strokes.indices.toSet(),ScreenshotDrawing.Order.FRONT))
        assertFalse(history.canUndo)
    }

    @Test fun serialAndLabelAreCreatedAsOneUndoStep() {
        val history=ScreenshotDrawing()
        history.addSerialWithText(stroke(ScreenshotDrawingTool.SERIAL),stroke(ScreenshotDrawingTool.TEXT))
        val pair=history.items
        assertEquals(pair[1].id,pair[0].boundTextId)
        assertNotEquals(pair[0].id,pair[1].id)
        history.undo(); assertTrue(history.items.isEmpty())
        history.redo(); assertEquals(pair,history.items)
    }
    @Test fun attachingAgainReusesTheLabelWithoutAnotherHistoryEntry() {
        val history=ScreenshotDrawing(); history.add(stroke(ScreenshotDrawingTool.SERIAL))
        val serialId=history.items.first().id
        val first=history.attachSerialText(serialId,stroke(ScreenshotDrawingTool.TEXT))
        assertEquals(first,history.attachSerialText(serialId,stroke(ScreenshotDrawingTool.TEXT)))
        assertEquals(2,history.items.size)
        history.undo(); assertEquals(1,history.items.size); assertEquals(0L,history.items.single().boundTextId)
    }
    @Test fun deletingALabelUnlinksAndUndoRestoresBoth() {
        val history=ScreenshotDrawing()
        history.addSerialWithText(stroke(ScreenshotDrawingTool.SERIAL),stroke(ScreenshotDrawingTool.TEXT))
        val before=history.items
        history.remove(setOf(1)); assertEquals(0L,history.items.single().boundTextId)
        history.undo(); assertEquals(before,history.items)
        history.remove(setOf(0)); assertEquals(ScreenshotDrawingTool.TEXT,history.items.single().tool)
    }
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
    @Test fun noOpsDoNotConsumeAnUndoStep() {
        val history = ScreenshotDrawing()
        val stroke = stroke(ScreenshotDrawingTool.RECTANGLE)
        history.add(stroke)
        // Rewriting the same value (slider released at its current position) is not an edit.
        history.replace(0, stroke.copy(color = stroke.color))
        history.remove(emptySet())
        history.add(ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN, emptyList(), 0, 2f))
        history.undo()
        assertTrue(history.items.isEmpty())
        assertFalse(history.canUndo)
    }
    @Test fun spotlightStyleIsOneAtomicCanvasEdit() {
        val history=ScreenshotDrawing()
        history.add(stroke(ScreenshotDrawingTool.SPOTLIGHT))
        history.add(stroke(ScreenshotDrawingTool.PEN))
        history.add(stroke(ScreenshotDrawingTool.SPOTLIGHT))
        val before=history.items
        history.restyleSpotlights(ScreenshotDrawingStyle(color=123,opacity=.25f))
        assertEquals(before[1],history.items[1])
        assertTrue(history.items.filter { it.tool==ScreenshotDrawingTool.SPOTLIGHT }.all { it.color==123 && it.style.opacity==.25f })
        history.undo(); assertEquals(before,history.items)
        history.redo(); assertEquals(.25f,history.items.first().style.opacity)
    }
    @Test fun newSerialGeometryDoesNotChangeLegacyStyleDefaults() {
        val serial=defaultScreenshotDrawingStyle(ScreenshotDrawingTool.SERIAL)
        assertEquals(24f,serial.fontSize,0f)
        assertEquals(1,serial.serialGeometryVersion)
        assertEquals(0,ScreenshotDrawingStyle().serialGeometryVersion)
        assertEquals(.64f,defaultScreenshotDrawingStyle(ScreenshotDrawingTool.SPOTLIGHT).opacity)
        assertEquals(6,defaultScreenshotDrawingStyle(ScreenshotDrawingTool.RECTANGLE).cornerRadius)
    }
    @Test fun textDefaultsFollowTheScreenshotDefaultsHeader() {
        val text=defaultScreenshotDrawingStyle(ScreenshotDrawingTool.TEXT)
        assertEquals(0xFFF5222D.toInt(),text.color)
        assertEquals(30f,text.fontSize,0f)
        assertEquals(0xFFFFCCC7.toInt(),text.outlineColor)
        assertEquals(0,text.outlineWidth)
        assertEquals(6,text.cornerRadius)
        assertEquals(0,text.align)
        assertEquals(1,text.verticalAlign)
        assertEquals(0,(text.fillColor ushr 24) and 0xFF)
        assertFalse(text.hasFill)
    }
    @Test fun fontSizeBoundsMatchTheOriginalEngineAndTheBadgeIsWider() {
        assertEquals(6f,ScreenshotFontSize.MIN,0f)
        assertEquals(256f,ScreenshotFontSize.max(ScreenshotDrawingTool.TEXT),0f)
        assertEquals(512f,ScreenshotFontSize.max(ScreenshotDrawingTool.SERIAL),0f)
        assertEquals(6f,ScreenshotFontSize.clamp(ScreenshotDrawingTool.TEXT,1f),0f)
        assertEquals(256f,ScreenshotFontSize.clamp(ScreenshotDrawingTool.TEXT,9000f),0f)
        assertEquals(512f,ScreenshotFontSize.clamp(ScreenshotDrawingTool.SERIAL,9000f),0f)
        assertEquals(30f,ScreenshotFontSize.clamp(ScreenshotDrawingTool.TEXT,Float.NaN),0f)
        assertEquals(24.5f,ScreenshotFontSize.clamp(ScreenshotDrawingTool.TEXT,24.5f),0f)
        assertEquals(30f,ScreenshotFontSize.parse(ScreenshotDrawingTool.TEXT,"30")!!,0f)
        assertEquals(24.5f,ScreenshotFontSize.parse(ScreenshotDrawingTool.TEXT," 24.5 ")!!,0f)
        assertNull(ScreenshotFontSize.parse(ScreenshotDrawingTool.TEXT,"5.9"))
        assertNull(ScreenshotFontSize.parse(ScreenshotDrawingTool.TEXT,"257"))
        assertNotNull(ScreenshotFontSize.parse(ScreenshotDrawingTool.SERIAL,"300"))
        assertNull(ScreenshotFontSize.parse(ScreenshotDrawingTool.TEXT,"abc"))
        assertEquals("30",ScreenshotFontSize.format(30f))
        assertEquals("24.5",ScreenshotFontSize.format(24.5f))
        assertEquals("42",ScreenshotFontSize.format(41.999f))
    }
    @Test fun watermarkChangesShareTheObjectHistoryStack() {
        val history=ScreenshotDrawing()
        history.add(stroke(ScreenshotDrawingTool.RECTANGLE))
        val marked=ScreenshotWatermark(text="WM",fontSize=24f,angle=0f,gap=20f)
        history.watermark=marked
        assertTrue(history.canUndo)
        assertEquals(marked,history.watermark)
        assertEquals(1,history.items.size)
        history.undo()
        assertEquals(ScreenshotWatermark(),history.watermark)
        assertEquals(1,history.items.size)
        history.redo()
        assertEquals(marked,history.watermark)
        // Same value twice is one step, not two.
        history.watermark=marked
        history.watermark=marked
        history.undo(); history.undo()
        assertEquals(ScreenshotWatermark(),history.watermark)
        assertFalse(history.canUndo)
    }
    @Test fun watermarkRidesTheRedoBranchTruncation() {
        val history=ScreenshotDrawing()
        history.add(stroke(ScreenshotDrawingTool.RECTANGLE))
        history.watermark=ScreenshotWatermark(text="A")
        history.undo()
        history.watermark=ScreenshotWatermark(text="B")
        assertFalse(history.canRedo)
        assertEquals(ScreenshotWatermark(text="B"),history.watermark)
    }
    @Test fun taperedRibbonRampsWidthAlongAStraightShaft() {
        val points=listOf(floatArrayOf(0f,0f),floatArrayOf(100f,0f))
        val contour=ScreenshotTaperedArrow.ribbon(points,listOf(0f,100f),100f,0f,10f,0f,100f)
        // Left side runs +normal, right side runs reversed −normal; ends are the wide head end.
        assertTrue(contour.any { abs(it[1]-10f)<1e-3 && abs(it[0]-100f)<1e-3 })
        assertTrue(contour.any { abs(it[1]+10f)<1e-3 && abs(it[0]-100f)<1e-3 })
        assertTrue(contour.any { abs(it[0])<1e-3 && abs(it[1])<1e-3 })
        assertTrue(contour.all { it[1] in -10.01f..10.01f })
    }
    @Test fun flattenCurveCollapsesAStraightCubicAndKeepsTheEndpoint() {
        val out=ArrayList<Pt>().apply { add(floatArrayOf(0f,0f)) }
        ScreenshotTaperedArrow.flattenCurve(out,floatArrayOf(0f,0f),floatArrayOf(10f,0f),floatArrayOf(20f,0f),floatArrayOf(30f,0f),0)
        // The caller seeds the start point; a straight cubic only emits the end.
        assertEquals(2,out.size)
        assertEquals(0f,out[0][0],1e-4f)
        assertEquals(30f,out[1][0],1e-4f)
    }
    @Test fun taperedHeadGeometryOrdersShouldersAndNeck() {
        // Tip at +x, shoulders symmetric about the axis: a above, b below after ordering.
        val head=ScreenshotTaperedArrow.headGeometry(ScreenshotArrowhead.TRIANGLE,
            floatArrayOf(100f,0f),floatArrayOf(80f,-10f),floatArrayOf(80f,10f))
        assertEquals(80f,head.back[0],1e-4f)
        assertEquals(10f,head.a[1],1e-4f)
        assertEquals(-10f,head.b[1],1e-4f)
        assertEquals(5f,head.width,1e-4f)
        assertEquals(80f,head.neck[0],1e-4f)
        val indented=ScreenshotTaperedArrow.headGeometry(ScreenshotArrowhead.INDENTED_TRIANGLE,
            floatArrayOf(100f,0f),floatArrayOf(80f,-10f),floatArrayOf(80f,10f))
        assertEquals(82.5f,indented.neck[0],1e-4f)
    }
    /** `arrow_is_degenerate`: a tap creates nothing, a one-pixel drag is still an arrow. */
    @Test fun zeroLengthArrowsAndLinesAreNotCommitted() {
        fun arrow(tool: ScreenshotDrawingTool,vararg points: ScreenshotDrawingPoint)=
            ScreenshotDrawingStroke(tool,points.toList(),0xFFFF0000.toInt(),4f)
        val tap=ScreenshotDrawingPoint(30f,30f)
        val history=ScreenshotDrawing()
        history.add(arrow(ScreenshotDrawingTool.ARROW,tap,tap))
        history.add(arrow(ScreenshotDrawingTool.LINE,tap,tap))
        history.add(arrow(ScreenshotDrawingTool.ARROW,tap))
        assertTrue(history.items.isEmpty()); assertFalse(history.canUndo)
        history.add(arrow(ScreenshotDrawingTool.ARROW,tap,ScreenshotDrawingPoint(31f,30f)))
        assertEquals(1,history.items.size)
        // The rule is the arrow's: a zero-size rectangle or a text anchor is still a real object.
        history.add(arrow(ScreenshotDrawingTool.RECTANGLE,tap,tap))
        history.add(arrow(ScreenshotDrawingTool.TEXT,tap))
        assertEquals(3,history.items.size)
    }
    @Test fun loadingAProjectStartsAFreshUndoStack() {
        val history = ScreenshotDrawing()
        history.add(stroke(ScreenshotDrawingTool.ARROW)); history.add(stroke(ScreenshotDrawingTool.PEN))
        history.load(listOf(stroke(ScreenshotDrawingTool.RECTANGLE)))
        assertEquals(1,history.items.size)
        assertFalse(history.canUndo); assertFalse(history.canRedo)
    }
}
