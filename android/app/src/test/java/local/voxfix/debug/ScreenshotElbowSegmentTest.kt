package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class ScreenshotElbowSegmentTest {
    private fun p(x: Int, y: Int) = ScreenshotDrawingPoint(x.toFloat(), y.toFloat())

    @Test fun movingLastSegmentAfterFixingMiddleRetainsPreviewOnRelease() {
        val initial = ScreenshotElbowSegment.Route(
            listOf(p(-80,50),p(0,50),p(0,-50),p(80,-50)), emptySet(), 2)
        val middle = ScreenshotElbowSegment.move(initial,p(25,0))
        assertEquals(listOf(p(-80,50),p(25,50),p(25,-50),p(80,-50)),middle.points)
        val target = p(52,-20)
        val last = ScreenshotElbowSegment.move(middle.copy(active=3),target)
        assertEquals(listOf(p(-80,50),p(25,50),p(25,-20),p(80,-20),p(80,-50)),last.points)
        assertEquals(setOf(2,3),last.fixed)
        assertSame(last,ScreenshotElbowSegment.move(last,target))
    }

    @Test fun draggingTheOnlySegmentAddsBothEndsAndRemapsActiveIndex() {
        val initial = ScreenshotElbowSegment.Route(listOf(p(0,0),p(80,0)),emptySet(),1)
        val first = ScreenshotElbowSegment.move(initial,p(30,20))
        assertEquals(listOf(p(0,0),p(0,20),p(80,20),p(80,0)),first.points)
        assertEquals(2,first.active)
        assertEquals(setOf(2),first.fixed)
        val second = ScreenshotElbowSegment.move(first,p(40,40))
        assertEquals(listOf(p(0,0),p(0,40),p(80,40),p(80,0)),second.points)
        assertEquals(setOf(2),second.fixed)
    }

    @Test fun firstSegmentShiftsExistingFixedIndices() {
        val initial = ScreenshotElbowSegment.Route(
            listOf(p(0,0),p(30,0),p(30,40),p(80,40)),setOf(2),1)
        val next = ScreenshotElbowSegment.move(initial,p(10,10))
        assertEquals(listOf(p(0,0),p(0,10),p(30,10),p(30,40),p(80,40)),next.points)
        assertEquals(setOf(2,3),next.fixed)
    }

    @Test fun shortSegmentsHideAtTheOriginalViewportThreshold() {
        val points = listOf(p(0,0),p(4,0),p(4,5),p(14,5))
        val elbow = ScreenshotDrawingStroke(ScreenshotDrawingTool.ARROW,points,0,2f,
            ScreenshotDrawingStyle(arrowType=ScreenshotArrowType.ELBOW))
        assertEquals(listOf(2,3),ScreenshotLinearHandles.segments(elbow,points).map { it.index })
        assertEquals(listOf(3),ScreenshotLinearHandles.segments(elbow,points,2f).map { it.index })
        val legacy = listOf(p(0,0),p(14,5))
        assertTrue(ScreenshotLinearHandles.segments(elbow.copy(points=legacy),legacy).isEmpty())
    }

    @Test fun routesMatchFixedSourceAtAlignedDiagonalAndOverlappingEndpoints() {
        val start = p(0,0)
        assertEquals(listOf(start,p(80,0)),ScreenshotElbowRouter.route(start,p(80,0)))
        assertEquals(listOf(start,p(40,0),p(40,50),p(80,50)),ScreenshotElbowRouter.route(start,p(80,50)))
        assertEquals(listOf(start,p(0,-2),p(23,-2),p(23,8),p(8,8)),ScreenshotElbowRouter.route(start,p(8,8)))
        assertEquals(listOf(start,p(1,0),p(1,1)),ScreenshotElbowRouter.route(start,p(1,1)))
        assertEquals(listOf(start,start),ScreenshotElbowRouter.route(start,start))
    }

    @Test fun endpointDragReconnectsTheFixedRunAndKeepsInteriorVerticesHidden() {
        val points = listOf(p(0,0),p(0,20),p(80,20),p(80,0))
        val elbow = ScreenshotDrawingStroke(ScreenshotDrawingTool.ARROW,points,0,2f,
            ScreenshotDrawingStyle(arrowType=ScreenshotArrowType.ELBOW),fixedElbowSegments=setOf(2))
        assertNull(ScreenshotLinearHandles.pointHit(elbow,points,points[1],1f))
        assertEquals(3,ScreenshotLinearHandles.pointHit(elbow,points,points.last(),1f))
        val startMoved = ScreenshotElbowSegment.moveEndpoint(elbow,0,p(-15,10))
        assertEquals(listOf(p(-15,10),p(-15,20),p(80,20),p(80,0)),startMoved.points)
        val endMoved = ScreenshotElbowSegment.moveEndpoint(startMoved,3,p(100,60))
        assertEquals(listOf(p(-15,10),p(-15,20),p(100,20),p(100,60)),endMoved.points)
        assertEquals(setOf(2),endMoved.fixedElbowSegments)
        assertSame(elbow,ScreenshotElbowSegment.moveEndpoint(elbow,0,points.first()))
    }
}
