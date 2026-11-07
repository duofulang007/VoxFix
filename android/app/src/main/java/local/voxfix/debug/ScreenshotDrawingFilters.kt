package local.voxfix.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

internal object ScreenshotDrawingFilters {
    /** 滤镜变换的并行度：全屏拖动是热路径，但小区域不值得付线程开销。 */
    private val FILTER_THREADS=Runtime.getRuntime().availableProcessors().coerceIn(1,4)
    private val pool=java.util.concurrent.Executors.newFixedThreadPool(FILTER_THREADS) { runnable ->
        Thread(runnable,"voxfix-filter").apply { isDaemon=true }
    }
    fun apply(source: Bitmap, crop: Rect, type: ScreenshotFilter, strength: Int,
              embossRadius: Int = 1): Bitmap {
        val region = Bitmap.createBitmap(source,crop.left,crop.top,crop.width(),crop.height())
        try {
            // 灰度／反色／亮度／浮雕按上游公式逐像素处理，不做缩放：它们没有 Moasic 那种块状抽样，
            // 缩小再放大只会糊掉细节，尤其浮雕的 1 逻辑像素采样半径会被抹平。
            if (type != ScreenshotFilter.MOSAIC && type != ScreenshotFilter.BLUR) {
                return transform(region, type, strength, embossRadius)
            }
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

    /**
     * 灰度／反色／亮度／浮雕：全分辨率逐像素，公式见 `ScreenshotFilterMath`。
     *
     * 拖动时每一帧的滤镜矩形都不同（缓存必 miss），全屏 260 万像素单线程要几百毫秒，
     * 所以按行分块到最多 4 个线程并行，小区域仍走单线程避免线程开销。
     */
    private fun transform(region: Bitmap, type: ScreenshotFilter, strength: Int, embossRadius: Int): Bitmap {
        val w=region.width; val h=region.height
        if(w<1 || h<1) return region.copy(Bitmap.Config.ARGB_8888,false)
        val pixels=IntArray(w*h); region.getPixels(pixels,0,w,0,0,w,h)
        val normalized=ScreenshotFilterMath.normalize(strength)
        when(type) {
            ScreenshotFilter.GRAYSCALE -> rowsParallel(h) { from,to ->
                for(index in from*w until to*w) pixels[index]=ScreenshotFilterMath.grayscale(pixels[index])
            }
            ScreenshotFilter.INVERT -> rowsParallel(h) { from,to ->
                for(index in from*w until to*w) pixels[index]=ScreenshotFilterMath.invert(pixels[index])
            }
            ScreenshotFilter.BRIGHTNESS -> {
                val scale=ScreenshotFilterMath.brightnessScale(normalized)
                if(scale!=65_536) rowsParallel(h) { from,to ->
                    // 逐通道内联（不走 lambda）：全屏每帧 780 万次通道运算。
                    val white=(scale-65_536).coerceAtLeast(0)
                    val retained=scale-2*white
                    for(index in from*w until to*w) {
                        val pixel=pixels[index]
                        val alpha=pixel ushr 24 and 0xFF
                        val bias=alpha*white+32_768
                        var red=(pixel ushr 16 and 0xFF)*retained+bias shr 16
                        var green=(pixel ushr 8 and 0xFF)*retained+bias shr 16
                        var blue=(pixel and 0xFF)*retained+bias shr 16
                        if(red>alpha) red=alpha
                        if(green>alpha) green=alpha
                        if(blue>alpha) blue=alpha
                        pixels[index]=(alpha shl 24) or (red shl 16) or (green shl 8) or blue
                    }
                }
            }
            ScreenshotFilter.EMBOSS -> {
                // 上游 EmbossRow：负样本取 (x-r,y-r)、正样本取 (x+r,y+r)，越界夹到边缘。
                val radius=embossRadius.coerceAtLeast(1)
                val table=ScreenshotFilterMath.embossTable(normalized)
                val opaque=ScreenshotFilterMath.embossOpaqueTable(table)
                val output=IntArray(pixels.size)
                rowsParallel(h) { from,to ->
                    for(y in from until to) {
                        val negativeRow=(y-radius).coerceAtLeast(0)*w
                        val positiveRow=(y+radius).coerceAtMost(h-1)*w
                        for(x in 0 until w) {
                            val negative=pixels[negativeRow+(x-radius).coerceAtLeast(0)]
                            val positive=pixels[positiveRow+(x+radius).coerceAtMost(w-1)]
                            val center=pixels[y*w+x]
                            val alpha=center ushr 24 and 0xFF
                            output[y*w+x]=if(alpha==255)
                                ScreenshotFilterMath.embossOpaque(negative,positive,opaque)
                            else ScreenshotFilterMath.emboss(negative,positive,alpha,table)
                        }
                    }
                }
                System.arraycopy(output,0,pixels,0,pixels.size)
            }
            else -> Unit
        }
        return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
    }

    /** 按行切给最多 4 个线程；行数少时直接顺序执行。 */
    private fun rowsParallel(rows: Int, body: (Int,Int) -> Unit) {
        val workers=if(rows<64) 1 else FILTER_THREADS
        if(workers<=1) { body(0,rows); return }
        val chunk=(rows+workers-1)/workers
        val futures=ArrayList<java.util.concurrent.Future<*>>(workers)
        var start=0
        while(start<rows) {
            val from=start; val to=minOf(rows,start+chunk)
            futures+=pool.submit { body(from,to) }
            start=to
        }
        for(future in futures) future.get()
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
