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
                    ScreenshotDrawingStyle(filter=ScreenshotFilter.MOSAIC,strength=12))
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
                // 新接的四种滤镜：在纯色底上逐像素核对上游公式（灰度系数、预乘反色、亮度白点、浮雕中灰）。
                val flat=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                val box=Rect(0,0,8,8)
                fun probe(type: ScreenshotFilter,strength: Int): Int {
                    val out=ScreenshotDrawingFilters.apply(flat,box,type,strength,1)
                    return try { out.getPixel(4,4) } finally { out.recycle() }
                }
                check(probe(ScreenshotFilter.GRAYSCALE,24)==0xFF363636.toInt()) {
                    "灰度像素不对：${Integer.toHexString(probe(ScreenshotFilter.GRAYSCALE,24))}" }
                check(probe(ScreenshotFilter.INVERT,24)==0xFF00FFFF.toInt()) { "反色像素不对" }
                // 强度 64 → 归一化 1 → scale 131072 → 全白；强度 4 → 全黑。
                check(probe(ScreenshotFilter.BRIGHTNESS,64)==0xFFFFFFFF.toInt()) { "亮度最亮不是白" }
                check(probe(ScreenshotFilter.BRIGHTNESS,4)==0xFF000000.toInt()) { "亮度最暗不是黑" }
                // 强度 34 → 归一化 0.5 → scale 65536 → 中性，不改像素。
                check(probe(ScreenshotFilter.BRIGHTNESS,34)==0xFFFF0000.toInt()) { "亮度中性点改了像素" }
                // 纯色上浮雕 delta=0 → 灰阶 alpha/2。
                check(probe(ScreenshotFilter.EMBOSS,24)==0xFF808080.toInt()) { "浮雕中性灰不对" }
                flat.recycle()
                check(cache.size()<=24*1024*1024)
                check(retained?.isRecycled==false) { "cache eviction recycled an image retained by a frame" }
                renderer.close()
                check(retained?.isRecycled==false)
                check(maxFrame.get()<1000) { "single filter frame blocked for ${maxFrame.get()}ms" }
            } catch(failure: Throwable) { error.set(failure) }
            finally { renderer.close(); image.recycle(); output.recycle() }
        },"filter-drag-regression").apply { isDaemon=true }
        worker.start(); worker.join(3000)
        check(!worker.isAlive) { "filter cache stalled during a growing drag: ${worker.stackTrace.take(7).joinToString()}" }
        error.get()?.let { throw it }
        return "SCREENSHOT_FILTER_STABILITY_PASS: 32-growing-mosaic+8-per-type,pen/rectangle,cache-eviction,retained-frame," +
            "gray/invert/brightness/emboss,maxFrameMs=${maxFrame.get()}"
    }
}
