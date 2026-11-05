package local.voxfix.debug

import android.graphics.*
import android.util.LruCache
import kotlin.math.ceil
import kotlin.math.sqrt

/** Snow Shot 1.1.8 snow_canvas_fill_render.cpp: 4x tiles, canvas-anchored and mirrored for cross-line. */
internal object ScreenshotHatchRendering {
    private data class Key(val color: Int,val lineWidth: Float)
    private val tiles=object : LruCache<Key,Bitmap>(64) {}
    fun draw(canvas: Canvas,path: Path,color: Int,pattern: ScreenshotFillPattern,referenceWidth: Float,
             unit: Float,opacity: Float,paint: Paint) {
        if(Color.alpha(color)==0 || !referenceWidth.isFinite() || !unit.isFinite() || unit<=0) return
        val lineWidth=(1f+(referenceWidth-1)*.6f).coerceIn(.5f,6f)
        val tileSize=(lineWidth*4).coerceIn(3f,21f)*sqrt(2f)
        val key=Key(color,lineWidth)
        val tile=tiles.get(key) ?: run {
            val pixels=ceil(tileSize*4).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(pixels,pixels,Bitmap.Config.ARGB_8888).also { bitmap ->
                val brushScale=tileSize/(pixels/4f)
                val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color=color; style=Paint.Style.STROKE; strokeCap=Paint.Cap.BUTT
                    strokeWidth=lineWidth/brushScale*4
                }
                val texture=Canvas(bitmap)
                for(diagonal in -1..2) texture.drawLine(0f,diagonal*pixels.toFloat(),pixels.toFloat(),(diagonal-1)*pixels.toFloat(),ink)
                tiles.put(key,bitmap)
            }
        }
        val shader=BitmapShader(tile,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT)
        paint.style=Paint.Style.FILL; paint.pathEffect=null; paint.color=Color.WHITE
        paint.alpha=(opacity.coerceIn(0f,1f)*255).toInt(); paint.isFilterBitmap=true
        paint.shader=shader
        try {
            val scale=tileSize/tile.width*unit
            shader.setLocalMatrix(Matrix().apply { setScale(scale,scale) })
            canvas.drawPath(path,paint)
            if(pattern==ScreenshotFillPattern.CROSS_LINE) {
                shader.setLocalMatrix(Matrix().apply { setScale(scale,-scale) })
                canvas.drawPath(path,paint)
            }
        } finally { paint.shader=null; paint.isFilterBitmap=false }
    }
}
