package local.voxfix.debug

import kotlin.math.*

/** Fixed source presentation.rs, arrow_ops.rs and arrow/data.rs. Elbow routing is separate. */
internal object ScreenshotLinearHandles {
    data class Segment(val index: Int,val center: ScreenshotDrawingPoint)
    fun pointIndices(stroke: ScreenshotDrawingStroke): List<Int> =
        if (ScreenshotElbowSegment.isElbow(stroke) && stroke.points.size >= 2) listOf(0, stroke.points.lastIndex)
        else stroke.points.indices.toList()
    private fun distance(a: ScreenshotDrawingPoint,b: ScreenshotDrawingPoint)=hypot(a.x-b.x,a.y-b.y)
    fun frameVisible(strokes: Collection<ScreenshotDrawingStroke>)=strokes.size!=1 || strokes.single().let {
        it.tool !in setOf(ScreenshotDrawingTool.LINE,ScreenshotDrawingTool.ARROW) || it.points.size!=2
    }
    fun loop(stroke: ScreenshotDrawingStroke,points: List<ScreenshotDrawingPoint>,unit: Float)=
        stroke.tool==ScreenshotDrawingTool.LINE && points.size>=3 && distance(points.first(),points.last())<=9*unit
    fun pointHit(stroke: ScreenshotDrawingStroke,points: List<ScreenshotDrawingPoint>,p: ScreenshotDrawingPoint,unit: Float): Int? {
        val loop=loop(stroke,points,unit)
        if(loop) {
            val center=ScreenshotDrawingPoint((points.first().x+points.last().x)/2,(points.first().y+points.last().y)/2)
            val d=distance(center,p)
            if(d<=5*unit) return 0
            if(d<=10*unit) return points.lastIndex
        }
        return if (points.isEmpty()) null else pointIndices(stroke).firstOrNull { i ->
            !(loop && (i==0 || i==points.lastIndex)) && distance(points[i],p)<=6f*1.11f*unit
        }
    }
    fun segments(stroke: ScreenshotDrawingStroke,points: List<ScreenshotDrawingPoint>,unit: Float=1f): List<Segment> {
        if (ScreenshotElbowSegment.isElbow(stroke)) {
            if (!ScreenshotElbowSegment.isOrthogonal(stroke.points)) return emptyList()
            return points.zipWithNext().mapIndexedNotNull { i, (a, b) ->
                if (distance(a,b)<5*unit) null
                else Segment(i+1,ScreenshotDrawingPoint((a.x+b.x)/2,(a.y+b.y)/2))
            }
        }
        if(points.size<2 || stroke.tool !in setOf(ScreenshotDrawingTool.LINE,ScreenshotDrawingTool.ARROW) ||
            (stroke.tool==ScreenshotDrawingTool.ARROW && points.size>2)) return emptyList()
        val curve=if(stroke.tool==ScreenshotDrawingTool.LINE) stroke.style.curved else stroke.style.arrowType==ScreenshotArrowType.CURVE
        return (1 until points.size).map { index ->
            val a=points[index-1]; val d=points[index]
            val center=if(curve && points.size>=3) {
                val prev=if(index==1) a else points[index-2]; val next=points.getOrElse(index+1) { d }
                val b=ScreenshotDrawingPoint(a.x+(d.x-prev.x)/6,a.y+(d.y-prev.y)/6)
                val c=ScreenshotDrawingPoint(d.x-(next.x-a.x)/6,d.y-(next.y-a.y)/6)
                ScreenshotDrawingPoint((a.x+3*b.x+3*c.x+d.x)/8,(a.y+3*b.y+3*c.y+d.y)/8)
            } else ScreenshotDrawingPoint((a.x+d.x)/2,(a.y+d.y)/2)
            Segment(index,center)
        }
    }
    fun segmentHit(stroke: ScreenshotDrawingStroke,points: List<ScreenshotDrawingPoint>,p: ScreenshotDrawingPoint,unit: Float)=
        segments(stroke,points,unit).firstOrNull { distance(it.center,p)<=6f*1.43f*unit }
    fun insert(points: List<ScreenshotDrawingPoint>,segment: Segment,delta: ScreenshotDrawingPoint,shift: Boolean,unit: Float): List<ScreenshotDrawingPoint> {
        if(segment.index<=0 || segment.index>=points.size || hypot(delta.x,delta.y)<10*unit) return points
        val target=ScreenshotDrawingPoint(segment.center.x+delta.x,segment.center.y+delta.y)
        val point=if(shift) ScreenshotObjectTransform.lockPoint(points[segment.index-1],target) else target
        return points.toMutableList().apply { add(segment.index,point) }
    }
}
