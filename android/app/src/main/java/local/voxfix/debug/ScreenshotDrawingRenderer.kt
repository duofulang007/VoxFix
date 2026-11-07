package local.voxfix.debug

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import kotlin.math.*

internal class ScreenshotDrawingRenderer(private var image: Bitmap,
    /** 设备密度：浮雕的「1 逻辑像素」采样半径要按它换成设备像素。 */
    private val density: () -> Float = { 1f },
    private val fontUnit: () -> Float) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
    private val penPaint=Paint()
    private data class FilterKey(val left:Int,val top:Int,val right:Int,val bottom:Int,val type:ScreenshotFilter,val strength:Float)
    // Recorded hardware frames can outlive cache entries; GC owns the bitmap lifetime.
    private val filters=object : LruCache<FilterKey,Bitmap>(24*1024*1024) {
        override fun sizeOf(key: FilterKey,value: Bitmap)=value.allocationByteCount
    }
    fun close() { filters.evictAll() }
    fun replaceSource(next: Bitmap) {
        close()
        image = next
    }
    fun fitSerialLabel(stroke: ScreenshotDrawingStroke): ScreenshotDrawingStroke {
        val centerX=stroke.points.first().x+stroke.textWidth/2
        val centerY=stroke.points.first().y+(stroke.textHeight.takeIf { it>0 } ?: textLayout(stroke).height.toFloat())/2
        val measuring=textLayout(stroke.copy(textWidth=240*fontUnit()))
        val width=(0 until measuring.lineCount).maxOf { measuring.getLineWidth(it) }.let { ceil(it).coerceAtLeast(1f) }
        val height=max(measuring.height.toFloat(),stroke.style.fontSize*fontUnit()*stroke.fontScale*1.2f)
        return stroke.copy(points=listOf(ScreenshotDrawingPoint(centerX-width/2,centerY-height/2)),textWidth=width,textHeight=height)
    }
    private fun labelInkBounds(stroke: ScreenshotDrawingStroke): RectF {
        if(stroke.text.isEmpty()) return bounds(stroke)
        val layout=textLayout(stroke)
        val point=stroke.points.first()
        val top=point.y+textOffset(stroke,layout)
        return RectF(point.x+(0 until layout.lineCount).minOf { layout.getLineLeft(it) },top,
            point.x+(0 until layout.lineCount).maxOf { layout.getLineRight(it) },top+layout.height).also {
            if(stroke.rotation!=0f) rotationMatrix(stroke).mapRect(it)
        }
    }
    private fun textLayout(stroke: ScreenshotDrawingStroke,outline: Boolean=false): StaticLayout {
        val textPaint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color=if(outline) stroke.style.outlineColor else stroke.color
            alpha=(Color.alpha(color)*stroke.style.opacity).toInt().coerceIn(0,255)
            textSize=stroke.style.fontSize*fontUnit()*stroke.fontScale
            typeface=Typeface.create(stroke.style.fontFamily,if(stroke.style.bold) Typeface.BOLD else Typeface.NORMAL)
            style=if(outline) Paint.Style.STROKE else Paint.Style.FILL
            strokeWidth=stroke.style.outlineWidth*fontUnit()*stroke.fontScale
        }
        val width=(stroke.textWidth.takeIf { it > 0 } ?: (image.width-stroke.points.first().x)).toInt().coerceAtLeast(1)
        return StaticLayout.Builder.obtain(stroke.text,0,stroke.text.length,textPaint,width)
            .setIncludePad(false).setAlignment(when(stroke.style.align) { 1->Layout.Alignment.ALIGN_CENTER; 2->Layout.Alignment.ALIGN_OPPOSITE; else->Layout.Alignment.ALIGN_NORMAL }).build()
    }
    fun bounds(stroke: ScreenshotDrawingStroke): RectF {
        val box=localBounds(stroke)
        if(stroke.rotation!=0f) rotationMatrix(stroke,box).mapRect(box)
        return box
    }
    /** Element proxies, not expanded paint bounds, own the selection frame. */
    fun selectionBounds(stroke: ScreenshotDrawingStroke): RectF {
        val painted=localBounds(stroke)
        val box=when(stroke.tool) {
            ScreenshotDrawingTool.TEXT -> RectF(painted)
            ScreenshotDrawingTool.SERIAL -> if(stroke.style.serialGeometryVersion==1) {
                val p=stroke.points.first(); val r=ScreenshotSerialRendering.radius(stroke,fontUnit())
                RectF(p.x-r,p.y-r,p.x+r,p.y+r)
            } else RectF(painted)
            else -> ScreenshotSelectionGeometry.bounds(stroke).let { RectF(it.left,it.top,it.right,it.bottom) }.apply {
                if(stroke.tool==ScreenshotDrawingTool.FILTER && stroke.style.area==ScreenshotAreaMode.PEN) inset(-stroke.width/2,-stroke.width/2)
            }
        }
        val center=ScreenshotObjectTransform.rotate(ScreenshotDrawingPoint(box.centerX(),box.centerY()),
            ScreenshotDrawingPoint(painted.centerX(),painted.centerY()),stroke.rotation)
        val width=box.width().coerceAtLeast(1f); val height=box.height().coerceAtLeast(1f)
        return RectF(center.x-width/2,center.y-height/2,center.x+width/2,center.y+height/2)
    }
    fun selectionWorldBounds(stroke: ScreenshotDrawingStroke)=selectionBounds(stroke).also { box ->
        if(stroke.rotation!=0f) Matrix().apply { setRotate(Math.toDegrees(stroke.rotation.toDouble()).toFloat(),box.centerX(),box.centerY()) }.mapRect(box)
    }
    /** Host measurement for fixed-width single-text reflow; no estimated character widths. */
    fun measureText(stroke: ScreenshotDrawingStroke): RectF=localBounds(stroke.copy(textHeight=0f))

    /**
     * Natural layout (no wrap): widest laid-out line and laid-out height, the phone equivalent of
     * the host's `TextLayoutSize` for auto-resizing labels.
     */
    fun naturalLayout(stroke: ScreenshotDrawingStroke): Pair<Float,Float> {
        val layout=textLayout(stroke.copy(textWidth=image.width*2f))
        val widest=(0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
        return ceil(widest).toFloat() to layout.height.toFloat()
    }

    /**
     * `text_with_pinned_alignment_layout` on the top-left point model: the box takes the natural
     * layout and the aligned edges stay pinned (left: point unchanged, center: symmetric shrink,
     * right: left edge follows the width change; the top edge always holds vertically).
     */
    fun naturalFit(stroke: ScreenshotDrawingStroke): ScreenshotDrawingStroke {
        if(stroke.tool!=ScreenshotDrawingTool.TEXT) return stroke
        val (width,height)=naturalLayout(stroke)
        val point=stroke.points.first()
        // point = centre - size/2, so the pinned-edge shifts collapse to: left stays, centre
        // shrinks symmetrically, right follows the width change; the top edge always holds.
        val shiftX=when(stroke.style.align) {
            1->(stroke.textWidth-width)/2f
            2->stroke.textWidth-width
            else->0f
        }
        return stroke.copy(points=listOf(ScreenshotDrawingPoint(point.x+shiftX,point.y)),
            textWidth=width,textHeight=height)
    }

    /** `text_with_wrapped_layout`: keep the wrap width, take the measured height, pin the top edge. */
    fun wrappedFit(stroke: ScreenshotDrawingStroke): ScreenshotDrawingStroke {
        if(stroke.tool!=ScreenshotDrawingTool.TEXT || stroke.textWidth<=0f) return stroke
        val height=textLayout(stroke).height.toFloat()
        return stroke.copy(textHeight=height)
    }
    fun rotationMatrix(stroke: ScreenshotDrawingStroke,box: RectF=localBounds(stroke))=Matrix().apply {
        setRotate(Math.toDegrees(stroke.rotation.toDouble()).toFloat(),box.centerX(),box.centerY())
    }
    /** Rebase edited global vertices while preserving the persisted element rotation. */
    fun worldPoints(stroke: ScreenshotDrawingStroke): List<ScreenshotDrawingPoint> {
        val box=localBounds(stroke); val center=ScreenshotDrawingPoint(box.centerX(),box.centerY())
        return stroke.points.map { ScreenshotObjectTransform.rotate(it,center,stroke.rotation) }
    }
    fun withWorldPoints(stroke: ScreenshotDrawingStroke,points: List<ScreenshotDrawingPoint>): ScreenshotDrawingStroke {
        if(stroke.rotation==0f) return stroke.copy(points=points)
        val origin=ScreenshotDrawingPoint(0f,0f)
        val projected=points.map { ScreenshotObjectTransform.rotate(it,origin,-stroke.rotation) }
        val box=localBounds(stroke.copy(points=projected,rotation=0f))
        val center=ScreenshotDrawingPoint(box.centerX(),box.centerY())
        val target=ScreenshotObjectTransform.rotate(center,origin,stroke.rotation)
        return stroke.copy(points=projected.map { ScreenshotDrawingPoint(it.x+target.x-center.x,it.y+target.y-center.y) })
    }
    fun localBounds(stroke: ScreenshotDrawingStroke): RectF {
        val first=stroke.points.first()
        if(stroke.tool==ScreenshotDrawingTool.TEXT) {
            val layout=textLayout(stroke)
            return RectF(first.x,first.y,first.x+layout.width,first.y+maxOf(layout.height.toFloat(),stroke.textHeight))
        }
        if(stroke.tool==ScreenshotDrawingTool.SERIAL) {
            if(stroke.style.serialGeometryVersion==1) return ScreenshotSerialRendering.bounds(stroke,fontUnit())
            val radius=stroke.style.fontSize*fontUnit()*stroke.fontScale*.85f
            return RectF(first.x-radius,first.y-radius,first.x+radius,first.y+radius)
        }
        if(stroke.tool==ScreenshotDrawingTool.PEN && stroke.smooth) {
            // Upstream free_draw_bounds measures the laid-out curve, not the vertex hull.
            val curve=ScreenshotStrokeGeometry.smoothBounds(stroke.points,ScreenshotPenRendering.closed(stroke))
            return RectF(curve[0]-stroke.width/2,curve[1]-stroke.width/2,curve[2]+stroke.width/2,curve[3]+stroke.width/2)
        }
        val points=if(stroke.tool==ScreenshotDrawingTool.ARROW) stroke.points+
            ScreenshotArrowRendering.boundsPoints(stroke.points,stroke.style.startHead,true,stroke.width,stroke.style.arrowRatio)+
            ScreenshotArrowRendering.boundsPoints(stroke.points,stroke.style.endHead,false,stroke.width,stroke.style.arrowRatio) else stroke.points
        return RectF(points.minOf { it.x },points.minOf { it.y },points.maxOf { it.x },points.maxOf { it.y }).apply { inset(-stroke.width/2,-stroke.width/2) }
    }
    /**
     * [outline] is the precise hit: a hollow rectangle or an unfilled text box only answers on its
     * stroke. The caller first asks for outlines, then for bodies, so a small shape inside a large
     * one still wins on its own line while an empty area of that large one selects the large one.
     */
    fun hit(stroke: ScreenshotDrawingStroke,p: ScreenshotDrawingPoint,tolerance: Float,crop: Rect? = null,outline: Boolean = true): Boolean {
        if(stroke.rotation!=0f) {
            val box=localBounds(stroke)
            val local=ScreenshotObjectTransform.rotate(p,ScreenshotDrawingPoint(box.centerX(),box.centerY()),-stroke.rotation)
            return hit(stroke.copy(rotation=0f),local,tolerance,null,outline)
        }
        val box=bounds(stroke).apply { inset(-tolerance,-tolerance) }
        if(!box.contains(p.x,p.y)) return false
        if(stroke.tool==ScreenshotDrawingTool.SERIAL && stroke.style.serialGeometryVersion==1)
            return ScreenshotSerialRendering.hit(stroke,p,tolerance,fontUnit(),crop ?: Rect(0,0,image.width,image.height))
        // An object that sticks out of the current capture box has no reachable outline inside it;
        // tapping its visible body is the only way to select it again after the box was shrunk.
        val clipped=crop!=null && !RectF(crop).contains(bounds(stroke))
        if(outline && clipped) return true
        if(stroke.tool==ScreenshotDrawingTool.RECTANGLE) {
            // Rectangle, ellipse and diamond share one bounding box; only the outline differs.
            val shape=RectF(min(stroke.points.first().x,stroke.points.last().x),min(stroke.points.first().y,stroke.points.last().y),
                max(stroke.points.first().x,stroke.points.last().x),max(stroke.points.first().y,stroke.points.last().y))
            val clip=crop ?: Rect(0,0,image.width,image.height)
            val radius=stroke.style.cornerRadius*stroke.width/stroke.style.width.coerceAtLeast(1)
            val radii=ScreenshotShapeRendering.scaledRadii(stroke.style,stroke.width,shape)
            val band=ScreenshotShapeRendering.strokeContains(stroke.style.shape,shape,p,stroke.width,tolerance,clip,radius,radii)
            if(outline) return band
            return ScreenshotShapeRendering.contains(stroke.style.shape,shape,p,clip,radius,radii) || band
        }
        if(stroke.tool==ScreenshotDrawingTool.LINE) {
            val clip=crop ?: Rect(0,0,image.width,image.height)
            val band=Path()
            Paint().apply { style=Paint.Style.STROKE; strokeWidth=stroke.width+tolerance*2
                strokeJoin=Paint.Join.ROUND; strokeCap=Paint.Cap.ROUND }.getFillPath(linePath(stroke),band)
            return Region().apply { setPath(band,Region(clip)) }.contains(p.x.toInt(),p.y.toInt())
        }
        if(stroke.tool==ScreenshotDrawingTool.TEXT && !stroke.style.hasFill) {
            if(!outline) return true
            val layout=textLayout(stroke)
            val y=p.y-stroke.points.first().y-textOffset(stroke,layout)
            if(y < -tolerance || y > layout.height+tolerance) return false
            val line=layout.getLineForVertical(y.toInt().coerceIn(0,layout.height))
            val x=p.x-stroke.points.first().x
            return x>=layout.getLineLeft(line)-tolerance && x<=layout.getLineRight(line)+tolerance
        }
        if(stroke.tool==ScreenshotDrawingTool.SERIAL) {
            val center=stroke.points.first(); val radius=bounds(stroke).width()/2
            val shape=stroke.style.serialShape
            if(shape in setOf(ScreenshotSerialShape.OUTLINED_CIRCLE,ScreenshotSerialShape.SOLID_CIRCLE,ScreenshotSerialShape.CIRCLED_NUMBER) &&
                hypot(p.x-center.x,p.y-center.y)>radius+tolerance) return false
        }
        if(stroke.tool in setOf(ScreenshotDrawingTool.PEN,ScreenshotDrawingTool.ARROW) ||
            (stroke.tool in setOf(ScreenshotDrawingTool.HIGHLIGHT,ScreenshotDrawingTool.FILTER) && stroke.style.area==ScreenshotAreaMode.PEN)) {
            if(stroke.tool==ScreenshotDrawingTool.PEN && stroke.style.hasFill && ScreenshotPenRendering.closed(stroke)) {
                val fill=Path(path(stroke)).apply { close() }
                val area=Region().apply { setPath(fill,Region(0,0,image.width,image.height)) }
                if(area.contains(p.x.toInt(),p.y.toInt())) return true
            }
            if(stroke.points.size==1) return hypot(p.x-stroke.points[0].x,p.y-stroke.points[0].y)<=tolerance+stroke.width/2
            // Upstream free_draw_hit_test samples the laid-out curve, not the stored chords.
            if(stroke.tool==ScreenshotDrawingTool.PEN && stroke.smooth)
                return ScreenshotStrokeGeometry.hitTestSmooth(stroke.points,ScreenshotPenRendering.closed(stroke),p,tolerance,stroke.width)
            val segments=stroke.points.zipWithNext().toMutableList()
            if(stroke.tool==ScreenshotDrawingTool.ARROW &&
                (ScreenshotArrowRendering.hitTest(stroke.points,stroke.style.startHead,true,stroke.width,stroke.style.arrowRatio,p,tolerance) ||
                 ScreenshotArrowRendering.hitTest(stroke.points,stroke.style.endHead,false,stroke.width,stroke.style.arrowRatio,p,tolerance))) return true
            return segments.any { (a,b) ->
                val dx=b.x-a.x; val dy=b.y-a.y
                val t=if(dx*dx+dy*dy==0f) 0f else (((p.x-a.x)*dx+(p.y-a.y)*dy)/(dx*dx+dy*dy)).coerceIn(0f,1f)
                hypot(p.x-a.x-t*dx,p.y-a.y-t*dy)<=tolerance+stroke.width/2
            }
        }
        return true
    }
    fun draw(canvas: Canvas,marks: List<ScreenshotDrawingStroke>,crop: Rect,watermark: ScreenshotWatermark? = null) {
        // The watermark sits on the screenshot and under every annotation, so marks stay readable.
        watermark?.draw(canvas,crop,fontUnit())
        val spots=marks.filter { it.tool==ScreenshotDrawingTool.SPOTLIGHT }
        paint.blendMode=BlendMode.SRC_OVER
        if(spots.isNotEmpty()) {
            val holes=Path()
            spots.forEach { stroke -> holes.op(Path().apply {
                addRect(localBounds(stroke),Path.Direction.CW); transform(rotationMatrix(stroke))
            },Path.Op.UNION) }
            val mask=Path().apply { addRect(RectF(crop),Path.Direction.CW); op(holes,Path.Op.DIFFERENCE) }
            val last=spots.last(); paint.style=Paint.Style.FILL; paint.pathEffect=null; paint.color=last.color
            paint.alpha=(Color.alpha(last.color)*last.style.opacity.coerceIn(0f,1f)).toInt()
            canvas.drawPath(mask,paint)
        }
        val byId=marks.associateBy { it.id }
        for(stroke in marks) if(stroke.tool!=ScreenshotDrawingTool.SPOTLIGHT) {
            if(stroke.tool==ScreenshotDrawingTool.SERIAL && stroke.boundTextId!=0L)
                byId[stroke.boundTextId]?.takeIf { it.tool==ScreenshotDrawingTool.TEXT }?.let {
                    ScreenshotSerialLabels.draw(canvas,stroke,labelInkBounds(it),fontUnit())
                }
            if(stroke.rotation==0f) drawStroke(canvas,stroke)
            else if(stroke.tool==ScreenshotDrawingTool.FILTER) drawRotatedFilter(canvas,stroke)
            else {
                canvas.save(); canvas.concat(rotationMatrix(stroke))
                try { drawStroke(canvas,stroke) } finally { canvas.restore() }
            }
        }
    }
    /**
     * Stored touch polylines stay polylines: every project written before the smoothing port must
     * keep its outline. Pen strokes recorded after it carry [ScreenshotDrawingStroke.smooth] and are
     * rebuilt through the same stabilizer/simplify/Catmull-Rom pipeline that produced the vertices.
     */
    private fun path(stroke: ScreenshotDrawingStroke): Path {
        val path=Path()
        if(stroke.smooth) {
            ScreenshotStrokeGeometry.buildPath(ScreenshotStrokeGeometry.toGeo(stroke.points),stroke.closedPath,path)
            return path
        }
        path.moveTo(stroke.points.first().x,stroke.points.first().y)
        stroke.points.drop(1).forEach { path.lineTo(it.x,it.y) }
        return path
    }
    /**
     * Line tool geometry. Straight is one segment; Curve is the original's cubic chain over the drag
     * points (`curve_bezier_segments` with tension 1, open endpoints: the missing neighbour is the
     * segment end itself, not a mirrored phantom).
     */
    private fun linePath(stroke: ScreenshotDrawingStroke): Path {
        val points=stroke.points
        val path=Path()
        if(points.isEmpty()) return path
        path.moveTo(points.first().x,points.first().y)
        if(!stroke.style.curved) {
            // `linear_path_commands`: every stored point is a vertex, so a curve switched back to
            // Straight keeps the corner it was drawn with instead of collapsing to a chord.
            for(index in 1 until points.size) path.lineTo(points[index].x,points[index].y)
            return path
        }
        addCurve(path,points)
        return path
    }
    /** Shared cubic chain for the Line and Arrow Curve shafts. */
    private fun addCurve(path: Path,points: List<ScreenshotDrawingPoint>) {
        if(points.size<3) {
            path.lineTo(points.last().x,points.last().y)
            return
        }
        for(index in 0 until points.size-1) {
            val start=points[index]; val end=points[index+1]
            val previous=if(index==0) start else points[index-1]
            val next=if(index+2<points.size) points[index+2] else end
            val c1x=start.x+(end.x-previous.x)/6f; val c1y=start.y+(end.y-previous.y)/6f
            val c2x=end.x-(next.x-start.x)/6f; val c2y=end.y-(next.y-start.y)/6f
            path.cubicTo(c1x,c1y,c2x,c2y,end.x,end.y)
        }
    }
    /**
     * Arrow shaft. Straight follows the stored vertices (the ends for a drag), Elbow the routed
     * dog-leg whose points were computed on lift, Curve the shared cubic chain.
     */
    private fun arrowPath(stroke: ScreenshotDrawingStroke): Path {
        val points=stroke.points
        val path=Path()
        if(points.isEmpty()) return path
        path.moveTo(points.first().x,points.first().y)
        if(stroke.style.arrowType==ScreenshotArrowType.CURVE) addCurve(path,points)
        else for(index in 1 until points.size) path.lineTo(points[index].x,points[index].y)
        return path
    }
    private fun textOffset(stroke: ScreenshotDrawingStroke,layout: StaticLayout): Float {
        val extra=(stroke.textHeight-layout.height).coerceAtLeast(0f)
        return when(stroke.style.verticalAlign) { 1 -> extra/2; 2 -> extra; else -> 0f }
    }
    /**
     * Snow Shot paints one pill per laid-out line (`snow_canvas_text_render.cpp:drawBackground`):
     * padding is 0.32/0.1 of the 1.2×line height and the corner radius clamps to half of the
     * shorter side of that line, so a wrapped paragraph gets stacked pills instead of one box.
     */
    private fun drawTextBackground(canvas: Canvas,stroke: ScreenshotDrawingStroke,layout: StaticLayout,left: Float,top: Float) {
        val style=stroke.style
        if(Color.alpha(style.fillColor)==0) return
        val size=style.fontSize*fontUnit()*stroke.fontScale
        if(size<=0f) return
        val lineHeight=size*1.2f
        val padX=lineHeight*.32f; val padY=lineHeight*.1f
        val radius=style.cornerRadius*fontUnit()*stroke.fontScale
        for(index in 0 until layout.lineCount) {
            val box=RectF(left+layout.getLineLeft(index)-padX,top+layout.getLineTop(index)-padY,
                left+layout.getLineRight(index)+padX,top+layout.getLineBottom(index)+padY)
            if(box.width()<1f) box.right=box.left+1f
            if(box.height()<1f) box.bottom=box.top+1f
            val round=radius.coerceAtMost(minOf(box.width(),box.height())/2f)
            val path=Path().apply { addRoundRect(box,round,round,Path.Direction.CW) }
            paint.style=Paint.Style.FILL; paint.color=style.fillColor
            paint.alpha=(Color.alpha(style.fillColor)*style.opacity).toInt().coerceIn(0,255)
            if(style.fillPattern==ScreenshotFillPattern.SOLID) canvas.drawPath(path,paint)
            else ScreenshotHatchRendering.draw(canvas,path,style.fillColor,style.fillPattern,
                style.fontSize/24f,fontUnit()*stroke.fontScale,style.opacity,paint)
        }
    }
    private fun drawStroke(canvas: Canvas,stroke: ScreenshotDrawingStroke) {
        val first=stroke.points.first(); val last=stroke.points.last()
        paint.color=stroke.color; paint.alpha=(Color.alpha(stroke.color)*stroke.style.opacity).toInt().coerceIn(0,255)
        paint.blendMode=if(stroke.tool==ScreenshotDrawingTool.HIGHLIGHT) BlendMode.MULTIPLY else BlendMode.SRC_OVER
        paint.strokeWidth=stroke.width; paint.style=Paint.Style.STROKE
        // Pen and shape strokes set their own dash effect; clearing here keeps the shared paint from
        // leaking a dasher into the next arrow, highlight or rectangle.
        paint.pathEffect=null
        when(stroke.tool) {
            ScreenshotDrawingTool.PEN -> ScreenshotPenRendering.draw(canvas,stroke,path(stroke),penPaint)
            ScreenshotDrawingTool.LINE -> canvas.drawPath(linePath(stroke),paint)
            ScreenshotDrawingTool.RECTANGLE -> ScreenshotShapeRendering.draw(canvas,stroke.style.shape,
                RectF(min(first.x,last.x),min(first.y,last.y),max(first.x,last.x),max(first.y,last.y)),
                stroke.color,stroke.width,stroke.style,paint)
            ScreenshotDrawingTool.ARROW -> {
                // Hollow heads punch through to the screenshot instead of the document background
                // colour the original paints with; a screenshot has no background colour of its own.
                val punch: (Canvas,Path) -> Unit = { target,path ->
                    target.save(); target.clipPath(path)
                    val inverse=Matrix(); rotationMatrix(stroke).invert(inverse); target.concat(inverse)
                    target.drawBitmap(image,0f,0f,null); target.restore()
                }
                val tapered=ScreenshotArrowRendering.taperedContours(stroke)
                if(tapered!=null && tapered.contours.isNotEmpty()) {
                    fun drawContour(contour: List<FloatArray>,hollow: Boolean) {
                        val path=Path().apply {
                            contour.firstOrNull()?.let { moveTo(it[0],it[1]) }
                            for(index in 1 until contour.size) lineTo(contour[index][0],contour[index][1])
                            close()
                        }
                        if(hollow) punch(canvas,path)
                        else { paint.style=Paint.Style.FILL; canvas.drawPath(path,paint) }
                    }
                    tapered.contours.forEachIndexed { index,contour -> drawContour(contour,tapered.hollow && index==0) }
                    tapered.separateHead?.let { head -> drawContour(head.contour().map{floatArrayOf(it[0],it[1])},tapered.separateIsOutline) }
                } else {
                    canvas.drawPath(arrowPath(stroke),paint)
                    ScreenshotArrowRendering.draw(canvas,stroke.points,stroke.style.startHead,true,
                        stroke.color,stroke.width,stroke.style.opacity,stroke.style.arrowRatio,paint,punch)
                    ScreenshotArrowRendering.draw(canvas,stroke.points,stroke.style.endHead,false,
                        stroke.color,stroke.width,stroke.style.opacity,stroke.style.arrowRatio,paint,punch)
                }
            }
            ScreenshotDrawingTool.HIGHLIGHT -> {
                if(stroke.tool==ScreenshotDrawingTool.HIGHLIGHT && stroke.style.area==ScreenshotAreaMode.RECTANGLE) {
                    paint.style=Paint.Style.FILL
                    canvas.drawRect(min(first.x,last.x),min(first.y,last.y),max(first.x,last.x),max(first.y,last.y),paint)
                    if(stroke.style.outlineWidth>0) {
                        paint.style=Paint.Style.STROKE; paint.color=stroke.style.outlineColor
                        paint.alpha=(Color.alpha(paint.color)*stroke.style.opacity).toInt()
                        paint.strokeWidth=stroke.style.outlineWidth*fontUnit()
                        canvas.drawRect(min(first.x,last.x),min(first.y,last.y),max(first.x,last.x),max(first.y,last.y),paint)
                    }
                } else if(stroke.points.all { it==first }) {
                    paint.style=Paint.Style.FILL; canvas.drawCircle(first.x,first.y,stroke.width/2,paint)
                } else canvas.drawPath(path(stroke),paint)
            }
            ScreenshotDrawingTool.TEXT -> {
                val layout=textLayout(stroke)
                val offset=textOffset(stroke,layout)
                if(stroke.style.hasFill) drawTextBackground(canvas,stroke,layout,first.x,first.y+offset)
                canvas.save(); canvas.translate(first.x,first.y+offset)
                if(stroke.style.outlineWidth>0) textLayout(stroke,true).draw(canvas)
                layout.draw(canvas); canvas.restore()
            }
            ScreenshotDrawingTool.SERIAL -> {
                if(stroke.style.serialGeometryVersion==1) {
                    ScreenshotSerialRendering.draw(canvas,stroke,fontUnit(),paint)
                    return
                }
                val radius=stroke.style.fontSize*fontUnit()*stroke.fontScale*.85f
                val style=stroke.style.serialShape
                val filled=(style==ScreenshotSerialShape.SOLID_CIRCLE || style==ScreenshotSerialShape.SOLID_SQUARE) && stroke.style.hasFill
                paint.style=if(filled) Paint.Style.FILL else Paint.Style.STROKE
                paint.color=if(filled && stroke.style.hasFill) stroke.style.fillColor else stroke.style.outlineColor
                paint.alpha=(Color.alpha(paint.color)*stroke.style.opacity).toInt()
                paint.strokeWidth=maxOf(1f,stroke.style.outlineWidth*fontUnit())
                if(style in setOf(ScreenshotSerialShape.OUTLINED_SQUARE,ScreenshotSerialShape.SOLID_SQUARE))
                    canvas.drawRoundRect(first.x-radius,first.y-radius,first.x+radius,first.y+radius,radius*.15f,radius*.15f,paint)
                else if(style!=ScreenshotSerialShape.CIRCLED_NUMBER || stroke.number !in 1L..20L) canvas.drawCircle(first.x,first.y,radius,paint)
                paint.style=Paint.Style.FILL; paint.textSize=stroke.style.fontSize*fontUnit()*stroke.fontScale
                paint.typeface=Typeface.create(stroke.style.fontFamily,if(stroke.style.bold) Typeface.BOLD else Typeface.NORMAL); paint.textAlign=Paint.Align.CENTER
                paint.color=stroke.color; paint.alpha=(Color.alpha(paint.color)*stroke.style.opacity).toInt()
                val label=if(style==ScreenshotSerialShape.CIRCLED_NUMBER && stroke.number in 1L..20L) (0x2460+stroke.number-1).toInt().toChar().toString() else stroke.number.toString()
                canvas.drawText(label,first.x,first.y-(paint.ascent()+paint.descent())/2,paint)
                paint.textAlign=Paint.Align.LEFT; paint.typeface=Typeface.DEFAULT
            }
            ScreenshotDrawingTool.FILTER -> {
                val box=localBounds(stroke)
                val safe=Rect(floor(box.left).toInt().coerceAtLeast(0),floor(box.top).toInt().coerceAtLeast(0),ceil(box.right).toInt().coerceAtMost(image.width),ceil(box.bottom).toInt().coerceAtMost(image.height))
                if(safe.width()<1 || safe.height()<1) return
                // Drag points mutate between frames; only immutable source-region values may be cache keys.
                val key=FilterKey(safe.left,safe.top,safe.right,safe.bottom,stroke.style.filter,ScreenshotFilterMath.clamp(stroke.style.strength))
                val bitmap=filters.get(key) ?: ScreenshotDrawingFilters.apply(image,safe,key.type,key.strength,
                    density(), ScreenshotFilterMath.embossRadius(density())).also { filters.put(key,it) }
                canvas.save()
                if(stroke.style.area==ScreenshotAreaMode.PEN) {
                    val mask=Path(); paint.style=Paint.Style.STROKE; paint.strokeWidth=stroke.width
                    if(stroke.points.all { it==first }) mask.addCircle(first.x,first.y,stroke.width/2,Path.Direction.CW)
                    else paint.getFillPath(path(stroke),mask)
                    canvas.clipPath(mask)
                } else canvas.clipRect(min(first.x,last.x),min(first.y,last.y),max(first.x,last.x),max(first.y,last.y))
                paint.alpha=255; paint.isFilterBitmap=stroke.style.filter==ScreenshotFilter.BLUR
                canvas.drawBitmap(bitmap,null,safe,paint); paint.isFilterBitmap=false; canvas.restore()
            }
            else -> Unit
        }
    }
    private fun drawRotatedFilter(canvas: Canvas,stroke: ScreenshotDrawingStroke) {
        val mask=Path()
        if(stroke.style.area==ScreenshotAreaMode.PEN) {
            if(stroke.points.all { it==stroke.points.first() })
                mask.addCircle(stroke.points.first().x,stroke.points.first().y,stroke.width/2,Path.Direction.CW)
            else Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style=Paint.Style.STROKE; strokeWidth=stroke.width; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND
            }.getFillPath(path(stroke),mask)
        } else {
            val first=stroke.points.first(); val last=stroke.points.last()
            mask.addRect(min(first.x,last.x),min(first.y,last.y),max(first.x,last.x),max(first.y,last.y),Path.Direction.CW)
        }
        mask.transform(rotationMatrix(stroke))
        val box=RectF(); mask.computeBounds(box,true)
        val safe=Rect(floor(box.left).toInt().coerceAtLeast(0),floor(box.top).toInt().coerceAtLeast(0),
            ceil(box.right).toInt().coerceAtMost(image.width),ceil(box.bottom).toInt().coerceAtMost(image.height))
        if(safe.width()<1 || safe.height()<1) return
        val key=FilterKey(safe.left,safe.top,safe.right,safe.bottom,stroke.style.filter,ScreenshotFilterMath.clamp(stroke.style.strength))
        val bitmap=filters.get(key) ?: ScreenshotDrawingFilters.apply(image,safe,key.type,key.strength,density()).also { filters.put(key,it) }
        canvas.save(); canvas.clipPath(mask)
        paint.alpha=255; paint.blendMode=BlendMode.SRC_OVER; paint.isFilterBitmap=stroke.style.filter==ScreenshotFilter.BLUR
        canvas.drawBitmap(bitmap,null,safe,paint); paint.isFilterBitmap=false; canvas.restore()
    }
}
