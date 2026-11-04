package local.voxfix.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

internal object ScreenshotDrawingFilters {
    fun apply(source: Bitmap, crop: Rect, type: ScreenshotFilter, strength: Int): Bitmap {
        val region = Bitmap.createBitmap(source,crop.left,crop.top,crop.width(),crop.height())
        try {
            if (type == ScreenshotFilter.MOSAIC) {
                val tiny = Bitmap.createScaledBitmap(region,maxOf(1,crop.width()/strength),maxOf(1,crop.height()/strength),false)
                try {
                    val scale=minOf(1f,1600f/maxOf(crop.width(),crop.height()))
                    val result = Bitmap.createBitmap(maxOf(1,(crop.width()*scale).toInt()),maxOf(1,(crop.height()*scale).toInt()),Bitmap.Config.ARGB_8888)
                    Canvas(result).drawBitmap(tiny,null,Rect(0,0,result.width,result.height),Paint())
                    return result
                } finally { if (tiny !== region && tiny !== source) tiny.recycle() }
            }
            val scale = minOf(1f,900f/maxOf(crop.width(),crop.height()))
            val small = Bitmap.createScaledBitmap(region,maxOf(1,(crop.width()*scale).toInt()),maxOf(1,(crop.height()*scale).toInt()),true)
            val w=small.width; val h=small.height
            var pixels=IntArray(w*h); small.getPixels(pixels,0,w,0,0,w,h)
            if (small !== region && small !== source) small.recycle()
            val radius=(strength*scale/2).toInt().coerceIn(1,32)
            // Three separable box passes approximate the Gaussian kernel in linear pixel time.
            repeat(3) { pixels=pass(pass(pixels,w,h,radius,true),w,h,radius,false) }
            return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        } finally { if (region !== source) region.recycle() }
    }

    private fun pass(input: IntArray,w: Int,h: Int,radius: Int,horizontal: Boolean): IntArray {
        val output=IntArray(input.size)
        val lines=if(horizontal) h else w; val length=if(horizontal) w else h
        fun index(line: Int,position: Int)=if(horizontal) line*w+position else position*w+line
        for (line in 0 until lines) {
            var red=0; var green=0; var blue=0; val count=radius*2+1
            fun add(position: Int,sign: Int) {
                val p=input[index(line,position.coerceIn(0,length-1))]
                red+=((p ushr 16) and 255)*sign; green+=((p ushr 8) and 255)*sign; blue+=(p and 255)*sign
            }
            for (position in -radius..radius) add(position,1)
            for (position in 0 until length) {
                output[index(line,position)]=0xFF000000.toInt() or ((red/count) shl 16) or ((green/count) shl 8) or (blue/count)
                add(position-radius,-1); add(position+radius+1,1)
            }
        }
        return output
    }
}
