package local.voxfix.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors

internal object ScreenshotAutoFilter {
    val labels = listOf("文字行", "框内文字", "图片", "头像", "图标", "气泡", "文字块")
    data class Region(val bounds: ScreenshotDocRect, val category: Int)

    fun hit(regions: List<Region>, x: Float, y: Float): Region? = regions
        .filter { x >= it.bounds.left && x < it.bounds.right && y >= it.bounds.top && y < it.bounds.bottom }
        .minByOrNull { it.bounds.width.toLong() * it.bounds.height }

    fun intersecting(regions: List<Region>, from: ScreenshotDrawingPoint, to: ScreenshotDrawingPoint) =
        regions.filter {
            it.bounds.left < maxOf(from.x, to.x) && it.bounds.right > minOf(from.x, to.x) &&
                it.bounds.top < maxOf(from.y, to.y) && it.bounds.bottom > minOf(from.y, to.y)
        }

    private val loaded by lazy { runCatching { System.loadLibrary("voxfix_snow_region") }.isSuccess }
    private external fun detectNative(pixels: IntArray, width: Int, height: Int): IntArray?

    fun detect(image: Bitmap, left: Int, top: Int): List<Region> {
        check(loaded) { "Snow Shot detector not packaged" }
        val width = image.width
        val height = image.height
        check(width.toLong() * height <= 16_000_000) { "Image exceeds detector memory budget" }
        val pixels = IntArray(width * height)
        image.getPixels(pixels, 0, width, 0, 0, width, height)
        val rows = checkNotNull(detectNative(pixels, width, height))
        check(rows.size % 5 == 0)
        return (rows.indices step 5).mapNotNull { i ->
            val x = rows[i].coerceIn(0, width)
            val y = rows[i + 1].coerceIn(0, height)
            val right = (rows[i].toLong() + rows[i + 2]).coerceIn(0, width.toLong()).toInt()
            val bottom = (rows[i + 1].toLong() + rows[i + 3]).coerceIn(0, height.toLong()).toInt()
            val category = rows[i + 4]
            if (right <= x || bottom <= y || category !in labels.indices) null
            else Region(ScreenshotDocRect(left + x, top + y, left + right, top + bottom), category)
        }
    }
}

internal class ScreenshotAutoFilterSession(
    private val image: Bitmap,
    private val redraw: () -> Unit,
    private val changed: () -> Unit
) {
    private companion object {
        val worker = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bounds: Rect? = null
    @Volatile private var closed = false
    private var generation = 0
    private var detecting = false
    private var started = 0L
    private var flashUntil = 0L
    var failed = false
        private set
    var result: List<ScreenshotAutoFilter.Region>? = null
        private set
    var hits: List<ScreenshotAutoFilter.Region> = emptyList()

    fun request(requested: Rect?) {
        if (closed) return
        if (requested == null || requested.isEmpty) {
            generation++
            bounds = null
            result = null
            hits = emptyList()
            detecting = false
            failed = false
            redraw()
            return
        }
        if (requested == bounds && (detecting || result != null)) return
        val source = Rect(requested)
        val token = ++generation
        bounds = source
        detecting = true
        failed = false
        result = null
        hits = emptyList()
        flashUntil = 0L
        started = SystemClock.uptimeMillis()
        val crop = runCatching {
            val sub = Bitmap.createBitmap(image, source.left, source.top, source.width(), source.height())
            if (sub === image) checkNotNull(sub.copy(Bitmap.Config.ARGB_8888, false)) else sub
        }.getOrNull()
        if (crop == null) {
            detecting = false
            failed = true
            return
        }
        worker.execute {
            val detected = try {
                if (closed) null else runCatching { ScreenshotAutoFilter.detect(crop, source.left, source.top) }.getOrNull()
            } finally { crop.recycle() }
            main.post {
                if (!closed && token == generation) {
                    detecting = false
                    result = detected
                    failed = detected == null
                    flashUntil = SystemClock.uptimeMillis() + 300
                    redraw()
                    changed()
                }
            }
        }
        redraw()
    }

    fun draw(canvas: Canvas, density: Float, needsFrame: () -> Unit) {
        val source = bounds ?: return
        val now = SystemClock.uptimeMillis()
        canvas.save()
        canvas.clipRect(source)
        if (detecting) {
            val y = source.top + (now - started) % 1400 / 1400f * (source.height() + 60 * density) - 30 * density
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f, y - 30 * density, 0f, y + 12 * density,
                intArrayOf(0x004096FF, 0x2D4096FF, 0x9160DCFF.toInt(), 0x004096FF),
                floatArrayOf(0f, .70f, .76f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(source.left.toFloat(), y - 30 * density, source.right.toFloat(), y + 12 * density, paint)
            paint.shader = null
            needsFrame()
        } else {
            val flashing = now < flashUntil
            val outline = if (flashing) result.orEmpty() else hits
            for ((box) in outline) {
                paint.style = Paint.Style.FILL
                paint.color = 0x33FF4D4F
                canvas.drawRect(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density
                paint.color = 0xFFFF4D4F.toInt()
                canvas.drawRect(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), paint)
            }
            if (flashing) needsFrame()
        }
        canvas.restore()
    }

    fun close() { closed = true }
}
