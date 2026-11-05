package local.voxfix.debug

import android.graphics.*
import kotlin.math.*

/** Pen-only styles: existing arrow, highlight and filter paint must not inherit dash or fill state. */
internal object ScreenshotPenRendering {
    /**
     * A smoothed stroke records its own closure decision (the vertex is dropped and re-closed on
     * render); only legacy polylines still need the radius guess.
     */
    fun closed(stroke: ScreenshotDrawingStroke): Boolean {
        if(stroke.smooth) return stroke.closedPath
        val first=stroke.points.first(); val last=stroke.points.last()
        return stroke.points.size>=4 && hypot(last.x-first.x,last.y-first.y)<=max(10*stroke.width/stroke.style.width.coerceAtLeast(1),stroke.width*.5f)
    }
    fun effect(pattern: ScreenshotStrokePattern,width: Float): PathEffect? = when(pattern) {
        ScreenshotStrokePattern.SOLID -> null
        ScreenshotStrokePattern.DASHED -> DashPathEffect(floatArrayOf(width*2,width*2.4f),0f)
        ScreenshotStrokePattern.DOTTED -> DashPathEffect(floatArrayOf(width*.0001f,width*1.9999f),0f)
    }
    fun draw(canvas: Canvas,stroke: ScreenshotDrawingStroke,path: Path,paint: Paint) {
        val style=stroke.style
        paint.reset(); paint.isAntiAlias=true; paint.strokeCap=Paint.Cap.ROUND; paint.strokeJoin=Paint.Join.ROUND
        val first=stroke.points.first()
        val unit=stroke.width/style.width.coerceAtLeast(1)
        if(closed(stroke) && style.hasFill) {
            // Same hatch routine as the shape tool, so a filled pen stroke and a filled rectangle
            // share one texture and stay in step when the width changes.
            ScreenshotShapeRendering.fill(canvas,Path(path).apply { close() },style,style.fillColor,unit,paint)
        }
        paint.style=Paint.Style.STROKE; paint.strokeCap=Paint.Cap.ROUND; paint.color=stroke.color
        paint.alpha=(Color.alpha(stroke.color)*style.opacity).toInt().coerceIn(0,255)
        paint.strokeWidth=stroke.width; paint.pathEffect=effect(style.strokePattern,stroke.width.coerceAtLeast(.01f))
        if(stroke.points.all { it==first }) {
            paint.pathEffect=null; paint.style=Paint.Style.FILL; canvas.drawCircle(first.x,first.y,stroke.width/2,paint)
        } else canvas.drawPath(path,paint)
    }
}
