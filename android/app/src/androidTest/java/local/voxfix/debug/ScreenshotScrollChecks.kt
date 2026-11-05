package local.voxfix.debug

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import java.nio.ByteBuffer

internal object ScreenshotScrollChecks {
    private const val WIDTH = 224
    private const val HEIGHT = 224
    private const val STRIDE = (WIDTH + 8) * 4
    private const val OFFSET = STRIDE + 8
    private const val SHIFT = 40

    private fun value(x: Int, y: Int): Int {
        var hash = x * 0xc2b2ae35.toInt() xor (y * 0x27d4eb2d)
        hash = (hash xor (hash ushr 16)) * 0x7feb352d
        return (hash xor (hash ushr 15)) ushr 24
    }

    private fun frame(dx: Int, dy: Int): ByteBuffer = ByteBuffer.allocateDirect(STRIDE * (HEIGHT + 2)).apply {
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            val index = OFFSET + y * STRIDE + x * 4
            val component = value(x + dx,y + dy)
            put(index,component.toByte())
            put(index+1,(component xor 17).toByte())
            put(index+2,(component xor 31).toByte())
            put(index+3,255.toByte())
        }
    }

    fun run(test: Instrumentation): String {
        checkRangeView(test)
        val screen = Bitmap.createBitmap(WIDTH + 10, HEIGHT + 10, Bitmap.Config.ARGB_8888)
        try {
            val expected = Color.rgb(19, 111, 225)
            screen.eraseColor(Color.RED)
            screen.setPixel(7, 9, expected)
            val data = ByteBuffer.allocateDirect(screen.byteCount)
            screen.copyPixelsToBuffer(data)
            ScreenshotStitch(WIDTH, HEIGHT, false).use { stitch ->
                check(stitch.push(data, screen.rowBytes, 9 * screen.rowBytes + 7 * 4) == ScreenshotStitch.INITIAL)
                val crop = stitch.render()
                try { check(crop.getPixel(0, 0) == expected && crop.getPixel(1, 0) == Color.RED) }
                finally { crop.recycle() }
            }
        } finally { screen.recycle() }
        for (horizontal in listOf(false,true)) ScreenshotStitch(WIDTH,HEIGHT,horizontal).use { stitch ->
            check(stitch.push(frame(0,0),STRIDE,OFFSET) == ScreenshotStitch.INITIAL)
            check(runCatching { stitch.render(start=9,end=9) }.isFailure)
            stitch.render(start=10,end=50).let { bitmap ->
                try {
                    check(bitmap.width == if (horizontal) 40 else WIDTH)
                    check(bitmap.height == if (horizontal) HEIGHT else 40)
                    val v = value(20 + if(horizontal) 10 else 0,30 + if(horizontal) 0 else 10)
                    check(bitmap.getPixel(20,30) == Color.rgb(v,v xor 17,v xor 31))
                } finally { bitmap.recycle() }
            }
            stitch.render().let { bitmap ->
                try {
                    val v = value(20,30)
                    check(bitmap.getPixel(20,30) == Color.rgb(v,v xor 17,v xor 31))
                } finally { bitmap.recycle() }
            }
            val event = stitch.push(frame(if(horizontal) SHIFT else 0,if(horizontal) 0 else SHIFT),STRIDE,OFFSET)
            check(event == if(horizontal) 7 else 2) { "axis horizontal=$horizontal event=$event" }
            check(stitch.width == WIDTH + if(horizontal) SHIFT else 0)
            check(stitch.height == HEIGHT + if(horizontal) 0 else SHIFT)
            stitch.render(80).let { bitmap ->
                try { check(maxOf(bitmap.width,bitmap.height) == 80) } finally { bitmap.recycle() }
            }
        }
        return "SCREENSHOT_SCROLL_PASS"
    }

    private fun checkRangeView(test: Instrumentation) = test.runOnMainSync {
        val bitmap = Bitmap.createBitmap(200, 500, Bitmap.Config.ARGB_8888)
        try {
            val range = ScreenshotScrollTrim(500)
            val view = ScreenshotScrollRangeView(test.targetContext, bitmap, false, range) {}
            val scroll = ScrollView(test.targetContext).apply { addView(view, FrameLayout.LayoutParams(200, 800)) }
            scroll.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(430, View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, 280, 430)
            check(view.height == 800) { "Range image height in scrolling preview: ${view.height}" }
            val head = 24 * test.targetContext.resources.displayMetrics.density
            for ((action, y) in listOf(MotionEvent.ACTION_DOWN to head, MotionEvent.ACTION_MOVE to head+120f,
                MotionEvent.ACTION_UP to head+120f)) {
                val event = MotionEvent.obtain(1, 2, action, 100f, y, 0)
                try { check(view.onTouchEvent(event)) } finally { event.recycle() }
            }
            check(range.start in 60..130 && range.end == 500) { "Dragging the start edge did not update the output crop" }
        } finally { bitmap.recycle() }
    }
}
