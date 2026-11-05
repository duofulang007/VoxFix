package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class ScreenshotObjectTransformTest {
    private fun point(x: Float,y: Float)=ScreenshotDrawingPoint(x,y)
    @Test fun normalizingStoredAnglesPreservesExactLegacyZeroAndInRangeValues() {
        for(angle in listOf(0f,.5f,-.5f,PI.toFloat(),-PI.toFloat()))
            assertEquals(angle,ScreenshotObjectTransform.normalize(angle))
    }
    @Test fun marqueeUsesRotatedRectanglesRatherThanTheirLargerAabb() {
        val box=SelBox(-50f,-5f,50f,5f)
        assertFalse(ScreenshotObjectTransform.intersects(box,(PI/4).toFloat(),SelBox(30f,-35f,35f,-30f)))
        assertTrue(ScreenshotObjectTransform.intersects(box,(PI/4).toFloat(),SelBox(30f,30f,35f,35f)))
        assertTrue(ScreenshotObjectTransform.intersects(box,0f,SelBox(50f,0f,55f,5f)))
        assertFalse(ScreenshotObjectTransform.intersects(box,0f,SelBox(50f,0f,50f,5f)))
    }
    @Test fun groupRotationMovesCentresWithoutDistortingMemberGeometry() {
        val stroke=ScreenshotDrawingStroke(ScreenshotDrawingTool.RECTANGLE,listOf(point(10f,20f),point(30f,60f)),123,4f)
        val rotated=ScreenshotObjectTransform.rotate(stroke,point(20f,40f),point(100f,100f),(PI/2).toFloat())
        assertEquals(150f,rotated.points.first().x,.001f)
        assertEquals(0f,rotated.points.first().y,.001f)
        assertEquals(20f,rotated.points.last().x-rotated.points.first().x,.001f)
        assertEquals(40f,rotated.points.last().y-rotated.points.first().y,.001f)
        assertEquals((PI/2).toFloat(),rotated.rotation,.001f)
        assertEquals(stroke.width,rotated.width)
    }
    @Test fun shiftSnapsAbsoluteRotationRatherThanTheDragDelta() {
        val start=point(1f,0f)
        val angle=Math.toRadians(4.0)
        val end=point(kotlin.math.cos(angle).toFloat(),kotlin.math.sin(angle).toFloat())
        val delta=ScreenshotObjectTransform.rotationDelta(point(0f,0f),start,end,Math.toRadians(10.0).toFloat(),true)
        assertEquals(Math.toRadians(5.0).toFloat(),delta,.0001f)
    }
    @Test fun linearVertexEditingProjectsToAngleAndOnlyLinesCloseLoops() {
        val locked=ScreenshotObjectTransform.lockPoint(point(0f,0f),point(100f,5f))
        assertEquals(100f,locked.x,.001f); assertEquals(0f,locked.y,.001f)
        val points=listOf(point(0f,0f),point(100f,0f),point(2f,3f))
        assertEquals(points.first(),ScreenshotObjectTransform.finishLinearPoints(points,2,true,8f).last())
        assertEquals(points,ScreenshotObjectTransform.finishLinearPoints(points,2,false,8f))
        val nearNeighbor=listOf(point(0f,0f),point(1f,2f),point(100f,0f))
        assertEquals(listOf(nearNeighbor.first(),nearNeighbor.last()),ScreenshotObjectTransform.finishLinearPoints(nearNeighbor,1,true,8f))
    }
    @Test fun batchTransformKeepsIdsAndCommitsExactlyOneUndoStep() {
        val history=ScreenshotDrawing()
        repeat(3) { history.add(ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN,listOf(point(it*10f,20f)),123,4f)) }
        val before=history.items
        history.replaceAll(mapOf(0 to before[0].copy(rotation=1f,id=999),2 to before[2].transformed(0f,0f,4f,8f)))
        val after=history.items
        assertEquals(before.map { it.id },after.map { it.id })
        assertEquals(before[1],after[1]); assertNotEquals(before[0],after[0])
        history.undo(); assertEquals(before,history.items)
        history.redo(); assertEquals(after,history.items)
        history.replaceAll(mapOf(0 to after[0])); history.undo(); assertEquals(before,history.items)
    }
}
