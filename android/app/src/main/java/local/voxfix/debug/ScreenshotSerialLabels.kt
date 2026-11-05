package local.voxfix.debug

import android.graphics.*
import kotlin.math.*

/** Fixed Snow Shot 1.1.8 document_geometry.rs: serial_number_bound_text_rect / serial_text_attachment. */
internal object ScreenshotSerialLabels {
    private fun radius(serial: ScreenshotDrawingStroke,unit: Float)=if(serial.style.serialGeometryVersion==1)
        ScreenshotSerialRendering.radius(serial,unit) else serial.style.fontSize*unit*serial.fontScale*.85f
    private fun width(serial: ScreenshotDrawingStroke,unit: Float)=if(serial.style.serialGeometryVersion==1)
        serial.style.width*serial.style.fontSize/20f*unit*serial.fontScale*(if(serial.style.serialShape==ScreenshotSerialShape.CIRCLE) 1.5f else 1f)
        else max(1f,serial.style.outlineWidth*unit)
    fun create(serial: ScreenshotDrawingStroke,textStyle: ScreenshotDrawingStyle,fontUnit: Float,pixelUnit: Float,
               center: ScreenshotDrawingPoint?=null): ScreenshotDrawingStroke {
        // serial.rs: the label starts at the badge's own font size, carrying the default text style.
        val style=textStyle.copy(fontSize=ScreenshotFontSize.clamp(ScreenshotDrawingTool.SERIAL,serial.style.fontSize*serial.fontScale))
        val height=style.fontSize*fontUnit*1.2f
        val gap=max(18*fontUnit,max(style.fontSize*fontUnit*18/21,2*width(serial,fontUnit)))
        val position=center ?: ScreenshotDrawingPoint(serial.points.first().x+radius(serial,fontUnit)+gap+.5f,serial.points.first().y)
        return ScreenshotDrawingStroke(ScreenshotDrawingTool.TEXT,listOf(ScreenshotDrawingPoint(position.x-.5f,position.y-height/2)),
            style.color,style.width*pixelUnit,style,textWidth=1f,textHeight=height)
    }
    /** A connector and optional underline; neither covers the badge nor runs through text ink. */
    fun connection(serial: ScreenshotDrawingStroke,textBounds: RectF,unit: Float): Path? {
        if(textBounds.width()<=0 || textBounds.height()<=0) return null
        val center=serial.points.first(); val radius=radius(serial,unit); val width=width(serial,unit)
        if(width<=0 || radius<=0) return null
        val square=serial.style.serialShape in setOf(ScreenshotSerialShape.OUTLINED_SQUARE,ScreenshotSerialShape.SOLID_SQUARE)
        val extent=radius*(if(square) abs(cos(serial.rotation))+abs(sin(serial.rotation)) else 1f)+width/2
        val serialBounds=RectF(center.x-extent,center.y-extent,center.x+extent,center.y+extent)
        val aligned=center.x in textBounds.left..textBounds.right
        val above=aligned && textBounds.bottom<serialBounds.top
        val below=aligned && textBounds.top>serialBounds.bottom
        val anchorX=center.x.coerceIn(textBounds.left,textBounds.right)
        val anchorY=if(below) textBounds.top else textBounds.bottom
        val dx=anchorX-center.x; val dy=anchorY-center.y; val distance=hypot(dx,dy)
        if(distance<=width/2) return null
        val ux=dx/distance; val uy=dy/distance
        val solid=serial.style.serialShape in setOf(ScreenshotSerialShape.SOLID_CIRCLE,ScreenshotSerialShape.SOLID_SQUARE)
        val half=radius+if(solid) width/2 else 0f
        var edge=half
        if(square) {
            val corner=(serial.style.fontSize*.2f*unit*serial.fontScale+(if(solid) width/2 else 0f)).coerceAtMost(half)
            val inner=half-corner
            val localX=cos(serial.rotation)*ux+sin(serial.rotation)*uy
            val localY=-sin(serial.rotation)*ux+cos(serial.rotation)*uy
            var low=0f; var high=half*sqrt(2f)
            repeat(48) {
                val mid=(low+high)/2; val x=abs(localX*mid); val y=abs(localY*mid)
                if(x<=half && y<=half && max(x-inner,0f).pow(2)+max(y-inner,0f).pow(2)<=corner*corner) low=mid else high=mid
            }
            edge=low
        }
        val start=edge+8*unit
        if(distance<=start+width/2) return null
        return Path().apply {
            moveTo(center.x+ux*start,center.y+uy*start)
            lineTo(anchorX-ux*width/2,anchorY-uy*width/2)
            if(!above && !below) { moveTo(textBounds.left,textBounds.bottom); lineTo(textBounds.right,textBounds.bottom) }
        }
    }
    fun draw(canvas: Canvas,serial: ScreenshotDrawingStroke,textBounds: RectF,unit: Float) {
        val path=connection(serial,textBounds,unit) ?: return
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=serial.color; alpha=(Color.alpha(color)*serial.style.opacity).toInt().coerceIn(0,255)
            style=Paint.Style.STROKE; strokeWidth=width(serial,unit); strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND
        }
        canvas.drawPath(path,paint)
    }
}
