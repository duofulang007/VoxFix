package local.voxfix.debug

import kotlin.math.*

/** selection_bounds.rs: arrow/line frames exclude heads/stroke and include cubic extrema. */
internal object ScreenshotSelectionGeometry {
    fun bounds(stroke: ScreenshotDrawingStroke): SelBox {
        val points=stroke.points
        val curve=(stroke.tool==ScreenshotDrawingTool.LINE && stroke.style.curved) ||
            (stroke.tool==ScreenshotDrawingTool.ARROW && stroke.style.arrowType==ScreenshotArrowType.CURVE)
        var left=points.minOf { it.x }; var right=points.maxOf { it.x }
        var top=points.minOf { it.y }; var bottom=points.maxOf { it.y }
        fun include(p: ScreenshotDrawingPoint) { left=min(left,p.x); right=max(right,p.x); top=min(top,p.y); bottom=max(bottom,p.y) }
        if(curve && points.size>=3) for(i in 0 until points.lastIndex) {
            val a=points[i]; val d=points[i+1]
            val prev=if(i==0) a else points[i-1]; val next=if(i+2<points.size) points[i+2] else d
            val b=ScreenshotDrawingPoint(a.x+(d.x-prev.x)/6,a.y+(d.y-prev.y)/6)
            val c=ScreenshotDrawingPoint(d.x-(next.x-a.x)/6,d.y-(next.y-a.y)/6)
            for(t in roots(a.x,b.x,c.x,d.x)+roots(a.y,b.y,c.y,d.y)) {
                val u=1-t
                include(ScreenshotDrawingPoint((u*u*u*a.x+3*u*u*t*b.x+3*u*t*t*c.x+t*t*t*d.x).toFloat(),
                    (u*u*u*a.y+3*u*u*t*b.y+3*u*t*t*c.y+t*t*t*d.y).toFloat()))
            }
        }
        return SelBox(left,top,right,bottom)
    }
    private fun roots(start: Float,c1: Float,c2: Float,end: Float): List<Double> {
        val a=-start.toDouble()+3*c1-3*c2+end; val b=3*start.toDouble()-6*c1+3*c2; val c=-3*start.toDouble()+3*c1
        if(abs(a)<1e-9) return if(abs(b)<1e-9) emptyList() else listOf(-c/(2*b)).filter { it>0 && it<1 }
        val qa=3*a; val qb=2*b; val discriminant=qb*qb-4*qa*c
        if(discriminant<0 || abs(2*qa)<1e-9) return emptyList()
        val root=sqrt(discriminant)
        return listOf((-qb+root)/(2*qa),(-qb-root)/(2*qa)).filter { it>0 && it<1 }
    }
}
