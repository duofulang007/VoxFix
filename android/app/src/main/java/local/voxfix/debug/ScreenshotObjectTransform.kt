package local.voxfix.debug

import kotlin.math.*

/** Snow Shot 1.1.8 selection_edit.rs: rotate member centres, then add the same angle. */
internal object ScreenshotObjectTransform {
    /** selection_bounds.rs: SAT on world X/Y and the two rotated rectangle axes. */
    fun intersects(box: SelBox,rotation: Float,marquee: SelBox): Boolean {
        if(box.width<=0 || box.height<=0 || marquee.width<=0 || marquee.height<=0) return false
        val center=ScreenshotDrawingPoint(box.centerX,box.centerY)
        val a=ScreenshotSelection.corners(box).map { rotate(it,center,rotation) }
        val b=ScreenshotSelection.corners(marquee)
        val axes=listOf(ScreenshotDrawingPoint(1f,0f),ScreenshotDrawingPoint(0f,1f),
            ScreenshotDrawingPoint(cos(rotation),sin(rotation)),ScreenshotDrawingPoint(-sin(rotation),cos(rotation)))
        return axes.all { axis ->
            val left=a.map { it.x*axis.x+it.y*axis.y }; val right=b.map { it.x*axis.x+it.y*axis.y }
            left.max()>=right.min() && right.max()>=left.min()
        }
    }
    fun normalize(angle: Float): Float {
        val turn=(2*PI).toFloat()
        val value=angle%turn
        return when { value>PI.toFloat() -> value-turn; value< -PI.toFloat() -> value+turn; else -> value }
    }
    fun rotate(point: ScreenshotDrawingPoint,center: ScreenshotDrawingPoint,angle: Float): ScreenshotDrawingPoint {
        val x=point.x-center.x; val y=point.y-center.y
        return ScreenshotDrawingPoint(center.x+x*cos(angle)-y*sin(angle),center.y+x*sin(angle)+y*cos(angle))
    }
    fun rotate(stroke: ScreenshotDrawingStroke,ownCenter: ScreenshotDrawingPoint,selectionCenter: ScreenshotDrawingPoint,
        angle: Float): ScreenshotDrawingStroke {
        val next=rotate(ownCenter,selectionCenter,angle)
        return stroke.copy(points=stroke.points.map { ScreenshotDrawingPoint(it.x+next.x-ownCenter.x,it.y+next.y-ownCenter.y) },
            rotation=normalize(stroke.rotation+angle))
    }
    fun rotationDelta(center: ScreenshotDrawingPoint,start: ScreenshotDrawingPoint,end: ScreenshotDrawingPoint,
        originalRotation: Float,shift: Boolean): Float {
        val delta=atan2(end.y-center.y,end.x-center.x)-atan2(start.y-center.y,start.x-center.x)
        if(!shift) return delta
        val step=(PI/12).toFloat()
        return roundAway((originalRotation+delta)/step)*step-originalRotation
    }
    private fun roundAway(value: Float)=if(value<0) ceil(value-.5f) else floor(value+.5f)
    /** arrow_ops.rs: project onto the nearest 15-degree axis, without preserving radial length. */
    fun lockPoint(origin: ScreenshotDrawingPoint,target: ScreenshotDrawingPoint): ScreenshotDrawingPoint {
        val dx=target.x-origin.x; val dy=target.y-origin.y; val step=(PI/12).toFloat()
        val angle=roundAway(atan2(dy,dx)/step)*step
        val length=dx*cos(angle)+dy*sin(angle)
        return ScreenshotDrawingPoint(origin.x+length*cos(angle),origin.y+length*sin(angle))
    }
    fun finishLinearPoints(points: List<ScreenshotDrawingPoint>,index: Int,isLine: Boolean,threshold: Float): List<ScreenshotDrawingPoint> {
        if(points.size<3) return points
        fun near(a: ScreenshotDrawingPoint,b: ScreenshotDrawingPoint)=hypot(a.x-b.x,a.y-b.y)<=threshold
        if(index>0 && index<points.lastIndex) {
            return if(near(points[index],points[index-1]) || near(points[index],points[index+1]))
                points.filterIndexed { i,_ -> i!=index } else points
        }
        if(!isLine) return points
        val other=if(index==0) points.lastIndex else 0
        return if(near(points[index],points[other])) points.mapIndexed { i,p -> if(i==index) points[other] else p } else points
    }
}
