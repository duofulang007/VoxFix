package local.voxfix.debug

import android.app.Instrumentation
import android.graphics.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/** A growing drag must evict old frames without blocking the input thread. */
internal object ScreenshotFilterStabilitySmoke {
    fun run(test: Instrumentation): String {
        val error=AtomicReference<Throwable?>()
        val maxFrame=AtomicLong()
        val worker=Thread({
            val image=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
            val renderer=ScreenshotDrawingRenderer(image) { 1f }
            val output=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888)
            try {
                val points=mutableListOf(ScreenshotDrawingPoint(30f,50f),ScreenshotDrawingPoint(1050f,2350f))
                val mark=ScreenshotDrawingStroke(ScreenshotDrawingTool.FILTER,points,Color.BLACK,8f,
                    ScreenshotDrawingStyle(filter=ScreenshotFilter.MOSAIC,strength=0.2f))
                val canvas=Canvas(output)
                var retained:Bitmap?=null
                val cache=renderer.javaClass.getDeclaredField("filters").apply { isAccessible=true }.get(renderer) as android.util.LruCache<*,*>
                for(type in ScreenshotFilter.values()) repeat(if(type==ScreenshotFilter.MOSAIC) 32 else 8) { index ->
                    points[1]=ScreenshotDrawingPoint(1050f-index*3,2350f-index*3)
                    val frame=mark.copy(style=mark.style.copy(filter=type,area=if(index%2==0) ScreenshotAreaMode.RECTANGLE else ScreenshotAreaMode.PEN))
                    val start=android.os.SystemClock.elapsedRealtimeNanos()
                    renderer.draw(canvas,listOf(frame),Rect(0,0,1080,2400))
                    maxFrame.set(maxOf(maxFrame.get(),(android.os.SystemClock.elapsedRealtimeNanos()-start)/1_000_000))
                    if(retained==null) retained=cache.snapshot().values.first() as Bitmap
                }
                // 逐像素核对上游公式（灰度系数、预乘反色、亮度白点、浮雕中灰）。强度与上游一致是 0..1。
                val flat=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                val box=Rect(0,0,8,8)
                fun probe(type: ScreenshotFilter,strength: Float): Int {
                    val out=ScreenshotDrawingFilters.apply(flat,box,type,strength,1f,1)
                    return try { out.getPixel(4,4) } finally { out.recycle() }
                }
                check(probe(ScreenshotFilter.GRAYSCALE,0.24f)==0xFF363636.toInt()) {
                    "灰度像素不对：${Integer.toHexString(probe(ScreenshotFilter.GRAYSCALE,0.24f))}" }
                check(probe(ScreenshotFilter.INVERT,0.24f)==0xFF00FFFF.toInt()) { "反色像素不对" }
                // 上游 0..1 强度：1 → scale 131072 → 全白；0 → 全黑；0.5 → 65536 中性。
                check(probe(ScreenshotFilter.BRIGHTNESS,1f)==0xFFFFFFFF.toInt()) { "亮度最亮不是白" }
                check(probe(ScreenshotFilter.BRIGHTNESS,0f)==0xFF000000.toInt()) { "亮度最暗不是黑" }
                check(probe(ScreenshotFilter.BRIGHTNESS,0.5f)==0xFFFF0000.toInt()) { "亮度中性点改了像素" }
                // 纯色上浮雕 delta=0 → 灰阶 alpha/2。
                check(probe(ScreenshotFilter.EMBOSS,0.24f)==0xFF808080.toInt()) { "浮雕中性灰不对" }

                // 马赛克：块边长 = round((2+10s)×密度)，块内取中心像素。用竖条棋盘量块宽。
                val white=Paint().apply { color=Color.WHITE }
                val black=Paint().apply { color=Color.BLACK }
                // 马赛克：块边长 = round((2+10s)×密度)，块内取中心像素，块边界锚在图像原点。
                // 用伪随机噪声底，保证相邻块的颜色几乎不会撞色，块内均匀即可反推块宽。
                val noise=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888)
                val noisePixels=IntArray(96*96)
                var seed=12345
                for(index in noisePixels.indices) {
                    seed=seed*1103515245+12345
                    val value=(seed ushr 16) and 0xFF
                    noisePixels[index]=0xFF000000.toInt() or (value shl 16) or (value shl 8) or value
                }
                noise.setPixels(noisePixels,0,96,0,0,96,96)
                fun blockUniform(strength: Float,block: Int): Boolean {
                    val out=ScreenshotDrawingFilters.apply(noise,Rect(0,0,96,96),ScreenshotFilter.MOSAIC,strength,1f,1)
                    return try {
                        val pixels=IntArray(96*96); out.getPixels(pixels,0,96,0,0,96,96)
                        var uniform=true
                        var by=0
                        while(by<96 && uniform) {
                            var bx=0
                            while(bx<96 && uniform) {
                                val color=pixels[by*96+bx]
                                var y=by
                                while(y<minOf(by+block,96) && uniform) {
                                    var x=bx
                                    while(x<minOf(bx+block,96)) {
                                        if(pixels[y*96+x]!=color) { uniform=false; break }
                                        ++x
                                    }
                                    ++y
                                }
                                bx+=block
                            }
                            by+=block
                        }
                        uniform
                    } finally { out.recycle() }
                }
                // 强度 0 → 块 2 像素；强度 1 → 块 12 像素（密度固定 1）。
                check(blockUniform(0f,2)) { "马赛克最弱强度的块不是 2×2 同色" }
                check(blockUniform(1f,12)) { "马赛克最强强度的块不是 12×12 同色" }

                // 高斯模糊：纯色保持原色，黑白边界必须出现中间灰（证明真的在模糊而不是取样）。
                fun probePixel(bitmap: Bitmap,type: ScreenshotFilter,strength: Float,x: Int,y: Int): Int {
                    val out=ScreenshotDrawingFilters.apply(bitmap,Rect(0,0,bitmap.width,bitmap.height),type,strength,1f,1)
                    return try { out.getPixel(x,y) } finally { out.recycle() }
                }
                val solid=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
                check(probePixel(solid,ScreenshotFilter.BLUR,1f,16,16)==0xFF3366CC.toInt()) { "模糊改了纯色像素" }
                val halves=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
                Canvas(halves).apply {
                    drawRect(Rect(0,0,32,64),white); drawRect(Rect(32,0,64,64),black)
                }
                val edge=probePixel(halves,ScreenshotFilter.BLUR,1f,32,32)
                val edgeRed=edge ushr 16 and 0xFF
                check(edgeRed in 1..254) { "模糊边界没有中间色：${Integer.toHexString(edge)}" }
                solid.recycle(); halves.recycle(); noise.recycle()
                flat.recycle()
                check(cache.size()<=24*1024*1024)
                check(retained?.isRecycled==false) { "cache eviction recycled an image retained by a frame" }
                renderer.close()
                check(retained?.isRecycled==false)
                check(maxFrame.get()<1000) { "single filter frame blocked for ${maxFrame.get()}ms" }
            } catch(failure: Throwable) { error.set(failure) }
            finally { renderer.close(); image.recycle(); output.recycle() }
        },"filter-drag-regression").apply { isDaemon=true }
        worker.start(); worker.join(20_000)
        check(!worker.isAlive) { "filter cache stalled during a growing drag: ${worker.stackTrace.take(7).joinToString()}" }
        error.get()?.let { throw it }
        return "SCREENSHOT_FILTER_STABILITY_PASS: 32-growing-mosaic+8-per-type,pen/rectangle,cache-eviction,retained-frame," +
            "gray/invert/brightness/emboss,mosaic-block-2/12,blur-edge,mixed-0..1,maxFrameMs=${maxFrame.get()}"
    }
}
