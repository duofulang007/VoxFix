package local.voxfix.debug

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import kotlin.math.*

internal class ScreenshotDrawingRenderer(private val image: Bitmap, private val fontUnit: () -> Float) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
    private data class FilterKey(val left:Int,val top:Int,val right:Int,val bottom:Int,val type:ScreenshotFilter,val strength:Int)
    // Recorded hardware frames can outlive cache entries; GC owns the bitmap lifetime.
    private val filters=object : LruCache<FilterKey,Bitmap>(24*1024*1024) {
        override fun sizeOf(key: FilterKey,value: Bitmap)=value.allocationByteCount
    }
    fun close() { filters.evictAll() }
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
        val first=stroke.points.first()
        if(stroke.tool==ScreenshotDrawingTool.TEXT) {
            val layout=textLayout(stroke)
            return RectF(first.x,first.y,first.x+layout.width,first.y+maxOf(layout.height.toFloat(),stroke.textHeight))
        }
        if(stroke.tool==ScreenshotDrawingTool.SERIAL) {
            val radius=stroke.style.fontSize*fontUnit()*stroke.fontScale*.85f
            return RectF(first.x-radius,first.y-radius,first.x+radius,first.y+radius)
        }
        val points=if(stroke.tool==ScreenshotDrawingTool.ARROW) stroke.points+arrowHead(stroke) else stroke.points
        return RectF(points.minOf { it.x },points.minOf { it.y },points.maxOf { it.x },points.maxOf { it.y }).apply { inset(-stroke.width/2,-stroke.width/2) }
    }
    /**
     * [outline] is the precise hit: a hollow rectangle or an unfilled text box only answers on its
     * stroke. The caller first asks for outlines, then for bodies, so a small shape inside a large
     * one still wins on its own line while an empty area of that large one selects the large one.
     */
    fun hit(stroke: ScreenshotDrawingStroke,p: ScreenshotDrawingPoint,tolerance: Float,crop: Rect? = null,outline: Boolean = true): Boolean {
        val box=bounds(stroke).apply { inset(-tolerance,-tolerance) }
        if(!box.contains(p.x,p.y)) return false
        // An object that sticks out of the current capture box has no reachable outline inside it;
        // tapping its visible body is the only way to select it again after the box was shrunk.
        val clipped=crop!=null && !RectF(crop).contains(bounds(stroke))
        if(outline && clipped) return true
        if(stroke.tool==ScreenshotDrawingTool.RECTANGLE) {
            if(!outline) return true
            val inner=RectF(bounds(stroke)).apply { inset(tolerance+stroke.width,tolerance+stroke.width) }
            return !inner.contains(p.x,p.y)
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
            if(stroke.points.size==1) return hypot(p.x-stroke.points[0].x,p.y-stroke.points[0].y)<=tolerance+stroke.width/2
            val segments=stroke.points.zipWithNext().toMutableList()
            if(stroke.tool==ScreenshotDrawingTool.ARROW) {
                val last=stroke.points.last()
                arrowHead(stroke).forEach { segments.add(last to it) }
            }
            return segments.any { (a,b) ->
                val dx=b.x-a.x; val dy=b.y-a.y
                val t=if(dx*dx+dy*dy==0f) 0f else (((p.x-a.x)*dx+(p.y-a.y)*dy)/(dx*dx+dy*dy)).coerceIn(0f,1f)
                hypot(p.x-a.x-t*dx,p.y-a.y-t*dy)<=tolerance+stroke.width/2
            }
        }
        return true
    }
    fun draw(canvas: Canvas,marks: List<ScreenshotDrawingStroke>,crop: Rect) {
        val spots=marks.filter { it.tool==ScreenshotDrawingTool.SPOTLIGHT }
        paint.blendMode=BlendMode.SRC_OVER
        if(spots.isNotEmpty()) {
            val holes=Path()
            spots.forEach { stroke -> holes.op(Path().apply { addRect(bounds(stroke),Path.Direction.CW) },Path.Op.UNION) }
            val mask=Path().apply { addRect(RectF(crop),Path.Direction.CW); op(holes,Path.Op.DIFFERENCE) }
            val last=spots.last(); paint.style=Paint.Style.FILL; paint.color=last.color; paint.alpha=(last.style.opacity*255).toInt()
            canvas.drawPath(mask,paint)
        }
        for(stroke in marks) if(stroke.tool!=ScreenshotDrawingTool.SPOTLIGHT) drawStroke(canvas,stroke)
    }
    private fun path(stroke: ScreenshotDrawingStroke)=Path().apply {
        moveTo(stroke.points.first().x,stroke.points.first().y)
        stroke.points.drop(1).forEach { lineTo(it.x,it.y) }
    }
    private fun textOffset(stroke: ScreenshotDrawingStroke,layout: StaticLayout): Float {
        val extra=(stroke.textHeight-layout.height).coerceAtLeast(0f)
        return when(stroke.style.verticalAlign) { 1 -> extra/2; 2 -> extra; else -> 0f }
    }
    private fun arrowHead(stroke: ScreenshotDrawingStroke): List<ScreenshotDrawingPoint> {
        val first=stroke.points.first(); val last=stroke.points.last()
        val angle=atan2(last.y-first.y,last.x-first.x)
        val head=min(hypot(last.x-first.x,last.y-first.y)*.45f,max(stroke.width*4,18f))
        return listOf(-.55f,.55f).map { offset -> ScreenshotDrawingPoint(last.x-head*cos(angle+offset),last.y-head*sin(angle+offset)) }
    }
    private fun drawStroke(canvas: Canvas,stroke: ScreenshotDrawingStroke) {
        val first=stroke.points.first(); val last=stroke.points.last()
        paint.color=stroke.color; paint.alpha=(Color.alpha(stroke.color)*stroke.style.opacity).toInt().coerceIn(0,255)
        paint.blendMode=if(stroke.tool==ScreenshotDrawingTool.HIGHLIGHT) BlendMode.MULTIPLY else BlendMode.SRC_OVER
        paint.strokeWidth=stroke.width; paint.style=Paint.Style.STROKE
        when(stroke.tool) {
            ScreenshotDrawingTool.RECTANGLE -> canvas.drawRect(min(first.x,last.x),min(first.y,last.y),max(first.x,last.x),max(first.y,last.y),paint)
            ScreenshotDrawingTool.ARROW -> {
                canvas.drawLine(first.x,first.y,last.x,last.y,paint)
                arrowHead(stroke).forEach { canvas.drawLine(last.x,last.y,it.x,it.y,paint) }
            }
            ScreenshotDrawingTool.PEN,ScreenshotDrawingTool.HIGHLIGHT -> {
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
                if(stroke.style.hasFill) {
                    paint.style=Paint.Style.FILL; paint.color=stroke.style.fillColor
                    paint.alpha=(Color.alpha(paint.color)*stroke.style.opacity).toInt()
                    val radius=stroke.style.cornerRadius*fontUnit()*stroke.fontScale
                    canvas.drawRoundRect(bounds(stroke),radius,radius,paint)
                }
                canvas.save(); canvas.translate(first.x,first.y+textOffset(stroke,layout))
                if(stroke.style.outlineWidth>0) textLayout(stroke,true).draw(canvas)
                layout.draw(canvas); canvas.restore()
            }
            ScreenshotDrawingTool.SERIAL -> {
                val radius=stroke.style.fontSize*fontUnit()*stroke.fontScale*.85f
                val style=stroke.style.serialShape
                val filled=(style==ScreenshotSerialShape.SOLID_CIRCLE || style==ScreenshotSerialShape.SOLID_SQUARE) && stroke.style.hasFill
                paint.style=if(filled) Paint.Style.FILL else Paint.Style.STROKE
                paint.color=if(filled && stroke.style.hasFill) stroke.style.fillColor else stroke.style.outlineColor
                paint.alpha=(Color.alpha(paint.color)*stroke.style.opacity).toInt()
                paint.strokeWidth=maxOf(1f,stroke.style.outlineWidth*fontUnit())
                if(style in setOf(ScreenshotSerialShape.OUTLINED_SQUARE,ScreenshotSerialShape.SOLID_SQUARE))
                    canvas.drawRoundRect(first.x-radius,first.y-radius,first.x+radius,first.y+radius,radius*.15f,radius*.15f,paint)
                else if(style!=ScreenshotSerialShape.CIRCLED_NUMBER || stroke.number !in 1..20) canvas.drawCircle(first.x,first.y,radius,paint)
                paint.style=Paint.Style.FILL; paint.textSize=stroke.style.fontSize*fontUnit()*stroke.fontScale
                paint.typeface=Typeface.create(stroke.style.fontFamily,if(stroke.style.bold) Typeface.BOLD else Typeface.NORMAL); paint.textAlign=Paint.Align.CENTER
                paint.color=stroke.color; paint.alpha=(Color.alpha(paint.color)*stroke.style.opacity).toInt()
                val label=if(style==ScreenshotSerialShape.CIRCLED_NUMBER && stroke.number in 1..20) (0x2460+stroke.number-1).toChar().toString() else stroke.number.toString()
                canvas.drawText(label,first.x,first.y-(paint.ascent()+paint.descent())/2,paint)
                paint.textAlign=Paint.Align.LEFT; paint.typeface=Typeface.DEFAULT
            }
            ScreenshotDrawingTool.FILTER -> {
                val box=bounds(stroke)
                val safe=Rect(floor(box.left).toInt().coerceAtLeast(0),floor(box.top).toInt().coerceAtLeast(0),ceil(box.right).toInt().coerceAtMost(image.width),ceil(box.bottom).toInt().coerceAtMost(image.height))
                if(safe.width()<1 || safe.height()<1) return
                // Drag points mutate between frames; only immutable source-region values may be cache keys.
                val key=FilterKey(safe.left,safe.top,safe.right,safe.bottom,stroke.style.filter,stroke.style.strength.coerceIn(4,64))
                val bitmap=filters.get(key) ?: ScreenshotDrawingFilters.apply(image,safe,key.type,key.strength).also { filters.put(key,it) }
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
}
