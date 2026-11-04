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
        return "SCREENSHOT_FILTER_STABILITY_PASS: 32-growing-mosaic+8-blur,pen/rectangle,cache-eviction,retained-frame,maxFrameMs=${maxFrame.get()}"
    }
}
