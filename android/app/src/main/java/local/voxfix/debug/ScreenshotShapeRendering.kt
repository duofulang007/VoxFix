package local.voxfix.debug

import android.graphics.*

/**
 * Shape tool geometry and paint, shared by rectangle, ellipse and diamond.
 *
 * Source of the kind list and the fill rules: Snow Shot 1.1.8
 * `snow_draw_engine_qt/include/snow_draw_engine_qt/snow_canvas_types.h`
 * (`SnowCanvasRectangleShape` = Rectangle/Ellipse/Diamond) and
 * `snow_canvas_fill_render.cpp` for the width-dependent hatch anchored to canvas coordinates.
 */
internal object ScreenshotShapeRendering {
    /**
     * The shape outline inside [box]. Ellipse and diamond are inscribed in the same box as the
     * rectangle, which is what makes dragging feel identical until the user switches kind.
     */
    fun path(shape: ScreenshotRectangleShape, box: RectF,cornerRadius: Float=0f): Path = Path().apply {
        when (shape) {
            ScreenshotRectangleShape.RECTANGLE -> if(cornerRadius>0) {
                val radius=cornerRadius.coerceAtMost(minOf(box.width(),box.height())/2)
                addRoundRect(box,radius,radius,Path.Direction.CW)
            } else addRect(box, Path.Direction.CW)
            ScreenshotRectangleShape.ELLIPSE -> addOval(box, Path.Direction.CW)
            ScreenshotRectangleShape.DIAMOND -> {
                moveTo(box.centerX(), box.top)
                lineTo(box.right, box.centerY())
                lineTo(box.centerX(), box.bottom)
                lineTo(box.left, box.centerY())
                close()
            }
        }
    }
    /**
     * Fill for a closed path. Callers reset the paint first; the shader-less hatch follows the
     * original's width-dependent spacing anchored to canvas coordinates, so a moved object keeps a
     * stable texture instead of sliding with itself.
     */
    fun fill(canvas: Canvas, path: Path, style: ScreenshotDrawingStyle, fillColor: Int, unit: Float, paint: Paint) {
        paint.color=fillColor
        paint.alpha=(Color.alpha(fillColor)*style.opacity).toInt().coerceIn(0,255)
        if(style.fillPattern==ScreenshotFillPattern.SOLID) { paint.style=Paint.Style.FILL; canvas.drawPath(path,paint); return }
        ScreenshotHatchRendering.draw(canvas,path,fillColor,style.fillPattern,style.width.toFloat(),unit,style.opacity,paint)
    }
    /** Fill first, then the patterned stroke; the paint is left as a stroke paint for the caller. */
    fun draw(canvas: Canvas, shape: ScreenshotRectangleShape, box: RectF, strokeColor: Int, strokeWidth: Float,
             style: ScreenshotDrawingStyle, paint: Paint) {
        val path=path(shape,box,style.cornerRadius*strokeWidth/style.width.coerceAtLeast(1))
        paint.reset(); paint.isAntiAlias=true; paint.strokeCap=Paint.Cap.ROUND; paint.strokeJoin=Paint.Join.ROUND
        if(style.hasFill) fill(canvas,path,style,style.fillColor,strokeWidth/style.width.coerceAtLeast(1),paint)
        if(strokeWidth<=0f || Color.alpha(strokeColor)==0) return
        paint.style=Paint.Style.STROKE
        paint.color=strokeColor
        paint.alpha=(Color.alpha(strokeColor)*style.opacity).toInt().coerceIn(0,255)
        paint.strokeWidth=strokeWidth
        paint.pathEffect=ScreenshotPenRendering.effect(style.strokePattern,strokeWidth)
        canvas.drawPath(path,paint)
    }
    /** Half-open test used by eraser and selection: interior counts, the bounding-box corners do not. */
    fun contains(shape: ScreenshotRectangleShape, box: RectF, point: ScreenshotDrawingPoint, clip: Rect,cornerRadius: Float=0f): Boolean {
        val region=Region().apply { setPath(path(shape,box,cornerRadius),Region(clip)) }
        return region.contains(point.x.toInt(),point.y.toInt())
    }
    /** The visible outline band, widened by the caller's touch slop. */
    fun strokeContains(shape: ScreenshotRectangleShape, box: RectF, point: ScreenshotDrawingPoint,
                       strokeWidth: Float, tolerance: Float, clip: Rect,cornerRadius: Float=0f): Boolean {
        val band=Path()
        Paint().apply {
            style=Paint.Style.STROKE; this.strokeWidth=strokeWidth+tolerance*2; strokeJoin=Paint.Join.ROUND
        }.getFillPath(path(shape,box,cornerRadius),band)
        val region=Region().apply { setPath(band,Region(clip)) }
        return region.contains(point.x.toInt(),point.y.toInt())
    }
}
