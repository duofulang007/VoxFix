package local.voxfix.debug

import android.graphics.Bitmap
import android.graphics.Rect

internal object ScreenshotDrawingFilters {
    /** 滤镜变换的并行度：全屏拖动是热路径，但小区域不值得付线程开销。 */
    private val FILTER_THREADS=Runtime.getRuntime().availableProcessors().coerceIn(1,4)
    private val pool=java.util.concurrent.Executors.newFixedThreadPool(FILTER_THREADS) { runnable ->
        Thread(runnable,"voxfix-filter").apply { isDaemon=true }
    }

    /**
     * [strength] 是上游的 0..1 强度（手机滑杆 0..100 除以 100），[density] 用来把逻辑像素换成
     * 设备像素——马赛克块边长与模糊 sigma 都定义在逻辑像素上。
     */
    fun apply(source: Bitmap, crop: Rect, type: ScreenshotFilter, strength: Float,
              density: Float, embossRadius: Int = 1): Bitmap {
        // 马赛克与模糊有各自的尺度语义（块边长 / sigma），不走逐像素变换。
        if (type == ScreenshotFilter.MOSAIC) {
            return mosaic(source, crop, ScreenshotFilterMath.mosaicBlockSize(strength), density)
        }
        if (type == ScreenshotFilter.BLUR) {
            val sigma = ScreenshotFilterMath.blurSigma(strength).toDouble() * maxOf(1f, density).toDouble()
            return ScreenshotGaussianBlur.apply(source, crop, sigma)
        }
        val region = Bitmap.createBitmap(source,crop.left,crop.top,crop.width(),crop.height())
        try {
            // 灰度／反色／亮度／浮雕按上游公式逐像素处理，不做缩放：它们没有块状抽样，
            // 缩小再放大只会糊掉细节，尤其浮雕的 1 逻辑像素采样半径会被抹平。
            return transform(region, type, strength, embossRadius)
        } finally { if (region !== source) region.recycle() }
    }

    /**
     * 上游 `mosaic()`：块边长 `round(logicalBlockSize × density)`，网格锚在图像原点（不是选区左上角），
     * 每块取**中心像素**铺满整块。因此拖动选区时块图案保持不动，与上游一致。
     */
    private fun mosaic(source: Bitmap, crop: Rect, logicalBlockSize: Float, density: Float): Bitmap {
        val block = maxOf(1, ScreenshotFilterMath.qRound(logicalBlockSize.toDouble() * maxOf(1f, density).toDouble()))
        val left = Math.floorDiv(crop.left, block) * block
        val top = Math.floorDiv(crop.top, block) * block
        val right = -Math.floorDiv(-crop.right, block) * block
        val bottom = -Math.floorDiv(-crop.bottom, block) * block
        // 采样点在块中心，可能落在 crop 之外，所以按整块范围读源像素。
        val readLeft = left.coerceAtLeast(0)
        val readTop = top.coerceAtLeast(0)
        val readRight = right.coerceAtMost(source.width)
        val readBottom = bottom.coerceAtMost(source.height)
        val readWidth = maxOf(1, readRight - readLeft)
        val readHeight = maxOf(1, readBottom - readTop)
        val read = IntArray(readWidth * readHeight)
        source.getPixels(read, 0, readWidth, readLeft, readTop, readWidth, readHeight)
        val width = crop.width()
        val height = crop.height()
        val out = IntArray(width * height)
        var blockY = top
        while (blockY < bottom) {
            val rowStart = maxOf(blockY, crop.top)
            val rowEnd = minOf(blockY + block, crop.bottom)
            if (rowStart < rowEnd) {
                val sampleY = (blockY + block / 2).coerceIn(0, source.height - 1)
                var blockX = left
                while (blockX < right) {
                    val columnStart = maxOf(blockX, crop.left)
                    val columnEnd = minOf(blockX + block, crop.right)
                    if (columnStart < columnEnd) {
                        val sampleX = (blockX + block / 2).coerceIn(0, source.width - 1)
                        val color = read[(sampleY - readTop).coerceIn(0, readHeight - 1) * readWidth +
                            (sampleX - readLeft).coerceIn(0, readWidth - 1)]
                        for (y in rowStart until rowEnd) {
                            val base = (y - crop.top) * width
                            for (x in columnStart until columnEnd) out[base + x - crop.left] = color
                        }
                    }
                    blockX += block
                }
            }
            blockY += block
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * 灰度／反色／亮度／浮雕：全分辨率逐像素，公式见 `ScreenshotFilterMath`。
     *
     * 拖动时每一帧的滤镜矩形都不同（缓存必 miss），全屏 260 万像素单线程要几百毫秒，
     * 所以按行分块到最多 4 个线程并行，小区域仍走单线程避免线程开销。
     */
    private fun transform(region: Bitmap, type: ScreenshotFilter, strength: Float, embossRadius: Int): Bitmap {
        val w=region.width; val h=region.height
        if(w<1 || h<1) return region.copy(Bitmap.Config.ARGB_8888,false)
        val pixels=IntArray(w*h); region.getPixels(pixels,0,w,0,0,w,h)
        val normalized=ScreenshotFilterMath.clamp(strength)
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
}
