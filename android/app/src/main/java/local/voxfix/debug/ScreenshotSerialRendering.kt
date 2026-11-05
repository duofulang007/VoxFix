package local.voxfix.debug

import android.graphics.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Snow Shot 1.1.8 document_geometry.rs and snow_canvas_renderer.cpp; legacy objects stay in the old renderer. */
internal object ScreenshotSerialRendering {
    private fun solid(shape: ScreenshotSerialShape)=shape in setOf(ScreenshotSerialShape.SOLID_CIRCLE,ScreenshotSerialShape.SOLID_SQUARE)
    private fun square(shape: ScreenshotSerialShape)=shape in setOf(ScreenshotSerialShape.OUTLINED_SQUARE,ScreenshotSerialShape.SOLID_SQUARE)
    fun radius(stroke: ScreenshotDrawingStroke,unit: Float): Float {
        val size=stroke.style.fontSize.toFloat()
        val diameter=if(stroke.style.serialShape==ScreenshotSerialShape.CIRCLE) size*.5f
            else (max(stroke.number.coerceAtLeast(0).toString().length*.6f,1.2f)+.48f)*size
        return diameter*unit*stroke.fontScale/2
    }
    private fun width(stroke: ScreenshotDrawingStroke,unit: Float)=stroke.style.width*stroke.style.fontSize/20f*unit*stroke.fontScale*
        if(stroke.style.serialShape==ScreenshotSerialShape.CIRCLE) 1.5f else 1f
    fun bounds(stroke: ScreenshotDrawingStroke,unit: Float): RectF {
        val point=stroke.points.first(); val radius=radius(stroke,unit)+width(stroke,unit)/2
        return RectF(point.x-radius,point.y-radius,point.x+radius,point.y+radius)
    }
    private fun path(stroke: ScreenshotDrawingStroke,unit: Float,outset: Float=0f): Path {
        val point=stroke.points.first(); val radius=radius(stroke,unit)+outset
        val box=RectF(point.x-radius,point.y-radius,point.x+radius,point.y+radius)
        return Path().apply {
            if(square(stroke.style.serialShape)) {
                val corner=stroke.style.fontSize*.2f*unit*stroke.fontScale+outset
                addRoundRect(box,corner,corner,Path.Direction.CW)
            } else addOval(box,Path.Direction.CW)
        }
    }
    fun hit(stroke: ScreenshotDrawingStroke,point: ScreenshotDrawingPoint,tolerance: Float,unit: Float,clip: Rect): Boolean =
        Region().apply { setPath(path(stroke,unit,width(stroke,unit)/2+tolerance),Region(clip)) }.contains(point.x.toInt(),point.y.toInt())
    private fun contrast(color: Int): Int {
        fun linear(value: Int): Double { val x=value/255.0; return if(x<=.04045) x/12.92 else ((x+.055)/1.055).pow(2.4) }
        val luminance=.2126*linear(Color.red(color))+.7152*linear(Color.green(color))+.0722*linear(Color.blue(color))
        return if(1.05/(luminance+.05)>=(luminance+.05)/((31.0/255)*luminance+.05)) Color.WHITE else 0xE0000000.toInt()
    }
    fun draw(canvas: Canvas,stroke: ScreenshotDrawingStroke,unit: Float,paint: Paint) {
        val style=stroke.style; val shape=style.serialShape; val width=width(stroke,unit)
        val outline=path(stroke,unit)
        paint.reset(); paint.isAntiAlias=true
        if(solid(shape)) {
            paint.style=Paint.Style.FILL; paint.color=stroke.color
            paint.alpha=(Color.alpha(stroke.color)*style.opacity).toInt().coerceIn(0,255)
            canvas.drawPath(path(stroke,unit,width/2),paint)
        } else {
            if(style.hasFill) {
                if(style.fillPattern==ScreenshotFillPattern.SOLID) {
                    paint.color=style.fillColor; paint.alpha=(Color.alpha(style.fillColor)*style.opacity).toInt().coerceIn(0,255)
                    paint.style=Paint.Style.FILL; canvas.drawPath(outline,paint)
                } else ScreenshotHatchRendering.draw(canvas,outline,style.fillColor,style.fillPattern,
                    style.fontSize/24f,unit*stroke.fontScale,style.opacity,paint)
            }
            paint.style=Paint.Style.STROKE; paint.strokeWidth=width; paint.strokeJoin=Paint.Join.ROUND; paint.strokeCap=Paint.Cap.ROUND
            paint.color=stroke.color; paint.alpha=(Color.alpha(stroke.color)*style.opacity).toInt().coerceIn(0,255)
            paint.pathEffect=ScreenshotPenRendering.effect(style.strokePattern,width.coerceAtLeast(.01f))
            canvas.drawPath(outline,paint); paint.pathEffect=null
        }
        if(shape==ScreenshotSerialShape.CIRCLE) return
        val text=stroke.number.coerceAtLeast(0).toString()
        paint.style=Paint.Style.FILL; paint.typeface=Typeface.create(style.fontFamily,if(style.bold) Typeface.BOLD else Typeface.NORMAL)
        paint.textSize=style.fontSize*unit*stroke.fontScale
        val ink=Rect(); paint.getTextBounds(text,0,text.length,ink)
        val content=(radius(stroke,unit)*2-width*2).coerceAtLeast(1f)
        paint.textSize*=min(1f,min(content/ink.width().coerceAtLeast(1),content/ink.height().coerceAtLeast(1)))
        paint.getTextBounds(text,0,text.length,ink)
        paint.color=if(solid(shape)) contrast(stroke.color) else stroke.color
        paint.alpha=(Color.alpha(paint.color)*style.opacity).toInt().coerceIn(0,255)
        val point=stroke.points.first()
        canvas.drawText(text,point.x-(ink.left+ink.right)/2f,point.y-(ink.top+ink.bottom)/2f,paint)
    }
}
