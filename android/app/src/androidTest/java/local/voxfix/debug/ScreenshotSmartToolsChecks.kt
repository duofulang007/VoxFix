package local.voxfix.debug

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.ViewConfiguration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object ScreenshotSmartToolsChecks {
    fun run(instrumentation: Instrumentation): String {
        val image = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(Color.rgb(245, 245, 245))
            Canvas(image).drawRect(400f, 200f, 402f, 233f, Paint().apply { color = Color.BLACK })
            // The same caret geometry as the fixed upstream contracts.rs fixture.
            val detected = ScreenshotAutoFilter.detect(image, 10, 20)
            check(detected.any { it.bounds.left in 409..411 && it.bounds.top in 218..222 &&
                it.bounds.right in 411..413 && it.bounds.bottom in 251..255 }) { "JNI caret and crop origin: $detected" }
            check(image.getPixel(400, 200) == Color.BLACK)

            instrumentation.runOnMainSync {
                val window = ScreenshotDocRect(0, 0, 800, 400)
                val first = ScreenshotDocRect(20, 20, 90, 60)
                val second = ScreenshotDocRect(140, 20, 220, 60)
                var committed: ScreenshotDocRect? = null
                val manual = mutableListOf<Rect>()
                val view = ScreenTextRegionView(instrumentation.targetContext, 800, 400, null, manual::add).apply {
                    layout(0, 0, 800, 400)
                    smartFrame = image
                    smartHit = { x, _ -> listOf(if (x < 100) first else second, window) }
                    onSmartCommit = { path, index ->
                        val hit = path[index]
                        committed = hit
                        selectRegion(Rect(hit.left, hit.top, hit.right, hit.bottom))
                    }
                }
                fun touch(action: Int, x: Float, y: Float, time: Long) {
                    val event = MotionEvent.obtain(1, time, action, x, y, 0)
                    try { check(view.onTouchEvent(event)) } finally { event.recycle() }
                }
                touch(MotionEvent.ACTION_DOWN, 35f, 35f, 1)
                touch(MotionEvent.ACTION_UP, 35f, 35f, 2)
                check(committed == first)
                touch(MotionEvent.ACTION_DOWN, 170f, 35f, 1)
                touch(MotionEvent.ACTION_UP, 170f, 35f, 2)
                check(committed == second) { "A second tap must switch an existing smart selection" }
                touch(MotionEvent.ACTION_DOWN, 35f, 35f, 1)
                touch(MotionEvent.ACTION_UP, 35f, 35f, 2)
                check(committed == first)
                touch(MotionEvent.ACTION_DOWN, 35f, 35f, 1)
                touch(MotionEvent.ACTION_MOVE, 170f, 35f, ViewConfiguration.getLongPressTimeout().toLong() + 1)
                touch(MotionEvent.ACTION_UP, 170f, 35f, ViewConfiguration.getLongPressTimeout().toLong() + 2)
                check(committed == second)
                touch(MotionEvent.ACTION_DOWN, 300f, 100f, 1)
                touch(MotionEvent.ACTION_MOVE, 430f, 190f, 2)
                touch(MotionEvent.ACTION_UP, 430f, 190f, 3)
                check(manual.single() == Rect(300, 100, 430, 190))
                check(committed == second)
                view.selectRegion(Rect(300, 100, 700, 350))
                touch(MotionEvent.ACTION_DOWN, 300f, 220f, 1)
                touch(MotionEvent.ACTION_MOVE, 255f, 220f, 2)
                touch(MotionEvent.ACTION_UP, 255f, 220f, 3)
                check(manual.last() == Rect(255, 100, 700, 350)) { "An immediate edge drag must resize" }
                view.smartWindowMode = true
                touch(MotionEvent.ACTION_DOWN, 35f, 35f, 1)
                touch(MotionEvent.ACTION_UP, 35f, 35f, 2)
                check(committed == window)
                touch(MotionEvent.ACTION_DOWN, 170f, 35f, 1)
                touch(MotionEvent.ACTION_UP, 170f, 35f, 2)
                check(committed == second) { "A tap inside a selected window must refine to the tapped child" }
                check(!view.smartWindowMode)
                touch(MotionEvent.ACTION_DOWN, 35f, 35f, 1)
                touch(MotionEvent.ACTION_UP, 35f, 35f, 2)
                check(committed == first) { "After refinement, a sibling tap must stay in element selection" }
                var textReady = false
                view.smartHit = { _, _ -> if (textReady) listOf(first, window) else listOf(window) }
                view.selectRegion(Rect(second.left, second.top, second.right, second.bottom))
                touch(MotionEvent.ACTION_DOWN, 30f, 35f, 1)
                touch(MotionEvent.ACTION_UP, 30f, 35f, 2)
                check(committed == window)
                textReady = true
                view.refineLastSmartTap()
                check(committed == first) { "Late text geometry must refine the most recent tap" }
            }
            checkFilterGesture(instrumentation, image)
            image.eraseColor(Color.WHITE)
            val canvas = Canvas(image)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 34f }
            canvas.drawText("第一段文字比这一行的空白短", 30f, 72f, paint)
            canvas.drawText("短行。", 30f, 120f, paint)
            canvas.drawText("这是另一个单独段落。", 30f, 230f, paint)
            val parent = ScreenshotDocRect(0, 0, 800, 400)
            val text = ScreenshotSmartText.detect(image)
            val short = text.refine(60, 106, listOf(parent)).first()
            check(short.right < 160 && short.top in 80..115) { "Short line includes blank space: $short" }
            val separate = text.refine(80, 217, listOf(parent)).first()
            check(separate.top >= 185 && separate.bottom < 250) { "Separate paragraph unavailable: $separate" }
        } finally { image.recycle() }
        return "SCREENSHOT_SMART_TOOLS_PASS"
    }

    private fun checkFilterGesture(instrumentation: Instrumentation, image: Bitmap) {
        val ready = CountDownLatch(1)
        lateinit var drawing: ScreenshotDrawingLayer
        instrumentation.runOnMainSync {
            drawing = ScreenshotDrawingLayer(instrumentation.targetContext, image) {
                if (drawing.autoFilterRegions != null || drawing.autoFilterFailed) ready.countDown()
            }
            drawing.layout(0, 0, image.width, image.height)
            drawing.region = Rect(0, 0, image.width, image.height)
            drawing.tool = ScreenshotDrawingTool.FILTER
            drawing.chooseAutoFilter()
        }
        try {
            check(ready.await(15, TimeUnit.SECONDS)) { "Auto Filter result not delivered" }
            instrumentation.runOnMainSync {
                check(!drawing.autoFilterFailed)
                fun tap() {
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(1, 2, action, 401f, 212f, 0)
                        try { check(drawing.onTouchEvent(event)) } finally { event.recycle() }
                    }
                }
                tap()
                val objectBefore = drawing.history.items.single()
                check(objectBefore.autoFilterCategory != null)
                val doc = ScreenshotDocument.full(image.width, image.height).copy(objects = drawing.history.items)
                check(ScreenshotDocumentCodec.decode(ScreenshotDocumentCodec.encode(doc)).objects.single() == objectBefore)
                tap()
                check(drawing.history.items.isEmpty())
                drawing.undo()
                check(drawing.history.items.single() == objectBefore)
            }
        } finally { instrumentation.runOnMainSync { drawing.close() } }
    }
}
