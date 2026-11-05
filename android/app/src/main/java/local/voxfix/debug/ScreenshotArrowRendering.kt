package local.voxfix.debug

import android.graphics.*
import kotlin.math.*

/**
 * Arrowhead geometry from Snow Shot 1.1.8 (`snow_canvas_render_core.cpp`
 * `get_arrowhead_size` / `get_arrowhead_angle` / `get_arrowhead_points` /
 * `get_arrowhead_render_primitives`).
 *
 * Sizes are canvas units before the arrow ratio: Arrow 25, diamond family 12, crowfoot 20, the rest
 * 15, all ×1.25. Thicker strokes scale the head by sqrt(width/2) and a head never exceeds half the
 * shaft length it sits on (diamond family: a quarter).
 */
internal object ScreenshotArrowRendering {
    /** Resolved head geometry for one end: tip, shoulders, the rotated base point and a round radius. */
    class Head(val tip: PointF, val left: PointF, val right: PointF, val back: PointF, val radius: Float)

    private fun baseSize(head: ScreenshotArrowhead): Float = when(head) {
        ScreenshotArrowhead.ARROW -> 25f
        ScreenshotArrowhead.DIAMOND, ScreenshotArrowhead.DIAMOND_OUTLINE, ScreenshotArrowhead.SQUARE -> 12f
        ScreenshotArrowhead.CROWFOOT_ONE, ScreenshotArrowhead.CROWFOOT_MANY,
        ScreenshotArrowhead.CROWFOOT_ONE_OR_MANY -> 20f
        else -> 15f
    } * 1.25f

    /** Stroke growth: a thicker stroke gets a bigger head by sqrt(width/2), never below the base. */
    fun strokeScale(strokeWidth: Float): Float = sqrt((strokeWidth/2f).coerceAtLeast(1f))

    /**
     * How far a head may reach back from its tip: half of the segment it sits on, a quarter for the
     * diamond family (`get_arrowhead_points` `length_multiplier`). Because start and end heads are
     * each confined to their own segment and to half of it, two heads on one segment can touch at
     * the midpoint but never overlap (`tests/arrowhead_scaling.rs`, `short_arrow_endpoint_sizes...`).
     */
    fun headReach(head: ScreenshotArrowhead, segment: Float): Float =
        segment * if(isDiamond(head)) .25f else .5f

    /** Base head length before rotation, already capped by [headReach]. */
    fun headLength(head: ScreenshotArrowhead, strokeWidth: Float, ratio: Float, segment: Float): Float =
        min(baseSize(head) * strokeScale(strokeWidth) * ratio, headReach(head, segment))

    /** Round heads answer with a diameter; the stroke adjustment survives the cap, as upstream does. */
    fun headDiameter(head: ScreenshotArrowhead, strokeWidth: Float, ratio: Float, segment: Float): Float {
        val adjustment = min(strokeWidth, 2f) - 2f
        val base = baseSize(head) * strokeScale(strokeWidth)
        return min((base + adjustment) * ratio, headReach(head, segment) + adjustment)
    }

    private fun angle(head: ScreenshotArrowhead): Float = when(head) {
        ScreenshotArrowhead.BAR -> 90f
        ScreenshotArrowhead.ARROW -> 20f
        else -> 25f
    }

    private fun isDiamond(head: ScreenshotArrowhead) =
        head==ScreenshotArrowhead.DIAMOND || head==ScreenshotArrowhead.DIAMOND_OUTLINE || head==ScreenshotArrowhead.SQUARE

    private fun isOutline(head: ScreenshotArrowhead) =
        head==ScreenshotArrowhead.CIRCLE_OUTLINE || head==ScreenshotArrowhead.TRIANGLE_OUTLINE ||
            head==ScreenshotArrowhead.DIAMOND_OUTLINE

    private fun isRound(head: ScreenshotArrowhead) =
        head==ScreenshotArrowhead.DOT || head==ScreenshotArrowhead.CIRCLE || head==ScreenshotArrowhead.CIRCLE_OUTLINE

    private fun rotate(point: PointF, around: PointF, radians: Float): PointF {
        val cos=cos(radians); val sin=sin(radians)
        val dx=point.x-around.x; val dy=point.y-around.y
        return PointF(around.x+dx*cos-dy*sin,around.y+dx*sin+dy*cos)
    }

    /**
     * `default_elbow_arrow_points`: a mid-point dog-leg across the longer axis; an exactly vertical
     * or horizontal drag stays one segment. Editing keeps the routed points as the centerline.
     */
    fun elbowPoints(start: ScreenshotDrawingPoint,end: ScreenshotDrawingPoint): List<ScreenshotDrawingPoint> {
        val dx=end.x-start.x; val dy=end.y-start.y
        if(abs(dx)<=1e-6f || abs(dy)<=1e-6f) return listOf(start,end)
        return if(abs(dx)>=abs(dy)) {
            val midX=(start.x+end.x)/2f
            listOf(start,ScreenshotDrawingPoint(midX,start.y),ScreenshotDrawingPoint(midX,end.y),end)
        } else {
            val midY=(start.y+end.y)/2f
            listOf(start,ScreenshotDrawingPoint(start.x,midY),ScreenshotDrawingPoint(end.x,midY),end)
        }
    }

    private fun geometry(points: List<ScreenshotDrawingPoint>, head: ScreenshotArrowhead, atStart: Boolean,
                         strokeWidth: Float, ratio: Float): Head? {
        if(head==ScreenshotArrowhead.NONE || points.size<2) return null
        val tip=if(atStart) points.first() else points.last()
        val inner=if(atStart) points[1] else points[points.size-2]
        val dx=tip.x-inner.x; val dy=tip.y-inner.y
        val segment=hypot(dx,dy)
        if(segment<=1e-6f) return null
        val dirX=dx/segment; val dirY=dy/segment
        val tipPoint=PointF(tip.x,tip.y)
        if(isRound(head)) return Head(tipPoint,tipPoint,tipPoint,tipPoint,
            headDiameter(head,strokeWidth,ratio,segment)/2f)
        val minSize=headLength(head,strokeWidth,ratio,segment)
        val back=PointF(tip.x-dirX*minSize,tip.y-dirY*minSize)
        val radians=Math.toRadians(angle(head).toDouble()).toFloat()
        return Head(tipPoint,rotate(back,tipPoint,-radians),rotate(back,tipPoint,radians),back,0f)
    }

    fun draw(canvas: Canvas, points: List<ScreenshotDrawingPoint>, head: ScreenshotArrowhead, atStart: Boolean,
             strokeColor: Int, strokeWidth: Float, opacity: Float, ratio: Float, paint: Paint,
             punch: ((Canvas, Path) -> Unit)? = null) {
        val shape=geometry(points,head,atStart,strokeWidth,ratio) ?: return
        val tip=shape.tip; val left=shape.left; val right=shape.right
        val alpha=(Color.alpha(strokeColor)*opacity).toInt().coerceIn(0,255)
        paint.reset(); paint.isAntiAlias=true; paint.color=strokeColor; paint.alpha=alpha
        paint.strokeCap=Paint.Cap.ROUND; paint.strokeJoin=Paint.Join.ROUND; paint.strokeWidth=strokeWidth
        val outline=isOutline(head)
        fun finish(polygon: Path) {
            if(outline) punch?.invoke(canvas,polygon)
            if(outline) { paint.style=Paint.Style.STROKE; paint.strokeWidth=strokeWidth } else paint.style=Paint.Style.FILL
            canvas.drawPath(polygon,paint)
        }
        when(head) {
            ScreenshotArrowhead.DOT, ScreenshotArrowhead.CIRCLE -> {
                paint.style=Paint.Style.FILL; canvas.drawCircle(tip.x,tip.y,shape.radius,paint)
            }
            ScreenshotArrowhead.CIRCLE_OUTLINE -> {
                val circle=Path().apply { addCircle(tip.x,tip.y,shape.radius,Path.Direction.CW) }
                finish(circle)
            }
            ScreenshotArrowhead.DIAMOND, ScreenshotArrowhead.DIAMOND_OUTLINE, ScreenshotArrowhead.SQUARE -> {
                // Rear vertex sits twice the head length behind the tip, on the shaft axis.
                val rear=PointF(tip.x+(shape.back.x-tip.x)*2f,tip.y+(shape.back.y-tip.y)*2f)
                finish(Path().apply {
                    moveTo(tip.x,tip.y); lineTo(left.x,left.y); lineTo(rear.x,rear.y); lineTo(right.x,right.y); close()
                })
            }
            ScreenshotArrowhead.INDENTED_TRIANGLE -> {
                val midX=(left.x+right.x)*0.5f; val midY=(left.y+right.y)*0.5f
                val notchX=midX+0.25f*(tip.x-midX); val notchY=midY+0.25f*(tip.y-midY)
                paint.style=Paint.Style.FILL
                canvas.drawPath(Path().apply {
                    moveTo(tip.x,tip.y); lineTo(left.x,left.y); lineTo(notchX,notchY); lineTo(right.x,right.y); close()
                },paint)
            }
            ScreenshotArrowhead.TRIANGLE, ScreenshotArrowhead.TRIANGLE_OUTLINE -> {
                finish(Path().apply {
                    moveTo(tip.x,tip.y); lineTo(left.x,left.y); lineTo(right.x,right.y); close()
                })
            }
            ScreenshotArrowhead.CROWFOOT_ONE -> {
                paint.style=Paint.Style.STROKE; canvas.drawLine(left.x,left.y,right.x,right.y,paint)
            }
            ScreenshotArrowhead.CROWFOOT_MANY, ScreenshotArrowhead.CROWFOOT_ONE_OR_MANY -> {
                paint.style=Paint.Style.STROKE
                canvas.drawLine(left.x,left.y,tip.x,tip.y,paint)
                canvas.drawLine(right.x,right.y,tip.x,tip.y,paint)
                if(head==ScreenshotArrowhead.CROWFOOT_ONE_OR_MANY) canvas.drawLine(left.x,left.y,right.x,right.y,paint)
            }
            else -> {
                paint.style=Paint.Style.STROKE
                canvas.drawLine(left.x,left.y,tip.x,tip.y,paint)
                canvas.drawLine(right.x,right.y,tip.x,tip.y,paint)
            }
        }
    }
    /** Points the head occupies, so selection bounds cover it. */
    fun boundsPoints(points: List<ScreenshotDrawingPoint>, head: ScreenshotArrowhead, atStart: Boolean,
                     strokeWidth: Float, ratio: Float): List<ScreenshotDrawingPoint> {
        val shape=geometry(points,head,atStart,strokeWidth,ratio) ?: return emptyList()
        if(shape.radius>0f && isRound(head)) {
            val r=shape.radius
            return listOf(ScreenshotDrawingPoint(shape.tip.x-r,shape.tip.y-r),ScreenshotDrawingPoint(shape.tip.x+r,shape.tip.y+r))
        }
        return listOf(ScreenshotDrawingPoint(shape.left.x,shape.left.y),ScreenshotDrawingPoint(shape.right.x,shape.right.y))
    }
    /** Round heads answer inside their disc; the rest answer near the two shoulder segments. */
    fun hitTest(points: List<ScreenshotDrawingPoint>, head: ScreenshotArrowhead, atStart: Boolean,
                strokeWidth: Float, ratio: Float, point: ScreenshotDrawingPoint, tolerance: Float): Boolean {
        val shape=geometry(points,head,atStart,strokeWidth,ratio) ?: return false
        val slop=tolerance+strokeWidth/2f
        if(isRound(head)) return hypot(point.x-shape.tip.x,point.y-shape.tip.y)<=shape.radius+slop
        fun near(from: PointF,to: PointF): Boolean {
            val dx=to.x-from.x; val dy=to.y-from.y
            val lengthSq=dx*dx+dy*dy
            val t=if(lengthSq==0f) 0f else (((point.x-from.x)*dx+(point.y-from.y)*dy)/lengthSq).coerceIn(0f,1f)
            return hypot(point.x-from.x-t*dx,point.y-from.y-t*dy)<=slop
        }
        if(near(shape.tip,shape.left) || near(shape.tip,shape.right)) return true
        if(head==ScreenshotArrowhead.CROWFOOT_ONE || head==ScreenshotArrowhead.CROWFOOT_ONE_OR_MANY)
            return near(shape.left,shape.right)
        return false
    }

    /**
     * `tapered_arrow_geometry`: only the four standard heads taper; anything else renders plain
     * without changing the stored preference. The primary head is at the end unless the end head
     * is absent, the centreline is the flattened drawn path oriented toward it, and the secondary
     * head joins the contour unless either side is a TriangleOutline.
     */
    fun taperedContours(stroke: ScreenshotDrawingStroke): ScreenshotTaperedArrow.Shaft? {
        val style=stroke.style
        if(style.arrowShaftType!=ScreenshotArrowShaftType.TAPERED || stroke.width<=0f) return null
        val supported=style.startHead in taperedHeads || style.endHead in taperedHeads
        if(!supported ||
            listOfNotNull(style.startHead.takeIf{it!=ScreenshotArrowhead.NONE},style.endHead.takeIf{it!=ScreenshotArrowhead.NONE})
                .any{it !in taperedHeads}) return null
        val reverse=style.endHead==ScreenshotArrowhead.NONE
        var centerline=centerlineOf(stroke)
        if(reverse) centerline=centerline.asReversed()
        if(centerline.size<2) return null
        val primaryHead=if(reverse) style.startHead else style.endHead
        val oppositeHead=if(reverse) style.endHead else style.startHead
        val primaryRender=geometry(centerline,primaryHead,false,stroke.width,style.arrowRatio) ?: return null
        val primary=ScreenshotTaperedArrow.headGeometry(primaryHead,
            floatArrayOf(primaryRender.tip.x,primaryRender.tip.y),
            floatArrayOf(primaryRender.left.x,primaryRender.left.y),
            floatArrayOf(primaryRender.right.x,primaryRender.right.y))
        val secondary=oppositeHead.takeIf{it!=ScreenshotArrowhead.NONE}?.let {
            val rendered=geometry(centerline,it,true,stroke.width,style.arrowRatio) ?: return null
            ScreenshotTaperedArrow.headGeometry(it,
                floatArrayOf(rendered.tip.x,rendered.tip.y),
                floatArrayOf(rendered.left.x,rendered.left.y),
                floatArrayOf(rendered.right.x,rendered.right.y))
        }
        val hollow=primaryHead==ScreenshotArrowhead.TRIANGLE_OUTLINE
        val integrate=secondary!=null && !hollow && oppositeHead!=ScreenshotArrowhead.TRIANGLE_OUTLINE
        val centerlineFloats=centerline.map{floatArrayOf(it.x,it.y)}
        return ScreenshotTaperedArrow.shaft(centerlineFloats,primary,secondary,stroke.width,
            style.strokePattern,hollow,integrate)
    }
    private val taperedHeads=setOf(ScreenshotArrowhead.ARROW,ScreenshotArrowhead.TRIANGLE,
        ScreenshotArrowhead.TRIANGLE_OUTLINE,ScreenshotArrowhead.INDENTED_TRIANGLE)

    /** The drawn centreline as a polyline: stored vertices, or the flattened cubic for Curve. */
    private fun centerlineOf(stroke: ScreenshotDrawingStroke): List<ScreenshotDrawingPoint> {
        val points=stroke.points
        if(stroke.style.arrowType!=ScreenshotArrowType.CURVE || points.size<3) return points
        val out=ArrayList<Pt>().apply { add(floatArrayOf(points.first().x,points.first().y)) }
        for(index in 0 until points.size-1) {
            val start=points[index]; val end=points[index+1]
            val previous=if(index==0) start else points[index-1]
            val next=if(index+2<points.size) points[index+2] else end
            val c1=ScreenshotTaperedArrow.pt(start.x+(end.x-previous.x)/6f,start.y+(end.y-previous.y)/6f)
            val c2=ScreenshotTaperedArrow.pt(end.x-(next.x-start.x)/6f,end.y-(next.y-start.y)/6f)
            ScreenshotTaperedArrow.flattenCurve(out,floatArrayOf(start.x,start.y),c1,c2,floatArrayOf(end.x,end.y),0)
        }
        return out.map{ScreenshotDrawingPoint(it[0],it[1])}
    }
}
