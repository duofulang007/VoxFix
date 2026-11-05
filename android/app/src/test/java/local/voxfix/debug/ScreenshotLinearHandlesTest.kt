package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class ScreenshotLinearHandlesTest {
    private fun p(x: Float,y: Float)=ScreenshotDrawingPoint(x,y)
    private val points=listOf(p(0f,0f),p(100f,0f),p(100f,100f))
    private fun stroke(tool: ScreenshotDrawingTool=ScreenshotDrawingTool.LINE,curved: Boolean=false)=
        ScreenshotDrawingStroke(tool,points,123,4f,ScreenshotDrawingStyle(curved=curved))
    @Test fun linesExposeEverySegmentButNonElbowArrowsOnlyExposeTheirTwoPointMidpoint() {
        assertEquals(listOf(1,2),ScreenshotLinearHandles.segments(stroke(),points).map { it.index })
        val arrow=stroke(ScreenshotDrawingTool.ARROW)
        assertTrue(ScreenshotLinearHandles.segments(arrow,points).isEmpty())
        assertEquals(p(50f,0f),ScreenshotLinearHandles.segments(arrow,points.take(2)).single().center)
        assertTrue(ScreenshotLinearHandles.segments(stroke(ScreenshotDrawingTool.PEN),points).isEmpty())
    }
    @Test fun curveMidpointsAreBezierHalfParametersRatherThanChordMidpoints() {
        val mids=ScreenshotLinearHandles.segments(stroke(curved=true),points)
        assertEquals(p(50f,-6.25f),mids[0].center)
        assertEquals(p(106.25f,50f),mids[1].center)
    }
    @Test fun insertionThresholdIncludesExactlyTenAndKeepsPressOffset() {
        val segment=ScreenshotLinearHandles.segments(stroke(),points).first()
        assertSame(points,ScreenshotLinearHandles.insert(points,segment,p(0f,9.99f),false,1f))
        val next=ScreenshotLinearHandles.insert(points,segment,p(0f,10f),false,1f)
        assertEquals(listOf(p(0f,0f),p(50f,10f),p(100f,0f),p(100f,100f)),next)
        assertEquals(3,points.size)
    }
    @Test fun shiftLocksInsertedVertexAgainstTheSegmentStartWithProjection() {
        val segment=ScreenshotLinearHandles.segments(stroke(),points).first()
        val next=ScreenshotLinearHandles.insert(points,segment,p(50f,3f),true,1f)
        assertEquals(p(100f,0f),next[1])
        assertEquals(points.last(),next.last())
    }
    @Test fun midpointHitUsesTheOriginalAddableRadiusAndPointerSpaceScale() {
        val s=stroke()
        assertNotNull(ScreenshotLinearHandles.segmentHit(s,points,p(50f,8.58f),1f))
        assertNull(ScreenshotLinearHandles.segmentHit(s,points,p(50f,8.59f),1f))
        assertNotNull(ScreenshotLinearHandles.segmentHit(s,points,p(50f,17f),2f))
    }
    @Test fun closedLineInnerAndOuterRingsAddressDifferentEndpoints() {
        val loop=points+p(0f,0f); val line=stroke().copy(points=loop)
        assertTrue(ScreenshotLinearHandles.loop(line,loop,1f))
        assertEquals(0,ScreenshotLinearHandles.pointHit(line,loop,p(5f,0f),1f))
        assertEquals(3,ScreenshotLinearHandles.pointHit(line,loop,p(5.01f,0f),1f))
        assertEquals(3,ScreenshotLinearHandles.pointHit(line,loop,p(10f,0f),1f))
        assertNull(ScreenshotLinearHandles.pointHit(line,loop,p(10.01f,0f),1f))
        assertFalse(ScreenshotLinearHandles.loop(line.copy(tool=ScreenshotDrawingTool.ARROW),loop,1f))
        assertEquals(1,ScreenshotLinearHandles.pointHit(line,loop,p(100f,5f),1f))
    }
    @Test fun ordinaryPointHitsUseDocumentOrderAndOriginalTurningRadius() {
        assertEquals(0,ScreenshotLinearHandles.pointHit(stroke(),points,p(6.65f,0f),1f))
        assertNull(ScreenshotLinearHandles.pointHit(stroke(),points,p(6.67f,0f),1f))
        val close=listOf(p(0f,0f),p(8f,0f),p(20f,0f))
        assertEquals(0,ScreenshotLinearHandles.pointHit(stroke(),close,p(5f,0f),1f))
    }
    @Test fun twoPointLineOrArrowHidesGenericFrameAndMultipleSelectionRetainsIt() {
        val line=stroke().copy(points=points.take(2))
        assertFalse(ScreenshotLinearHandles.frameVisible(listOf(line)))
        assertFalse(ScreenshotLinearHandles.frameVisible(listOf(line.copy(tool=ScreenshotDrawingTool.ARROW))))
        assertTrue(ScreenshotLinearHandles.frameVisible(listOf(stroke())))
        assertTrue(ScreenshotLinearHandles.frameVisible(listOf(line,line)))
        assertTrue(ScreenshotLinearHandles.frameVisible(listOf(line.copy(tool=ScreenshotDrawingTool.RECTANGLE))))
    }
}
