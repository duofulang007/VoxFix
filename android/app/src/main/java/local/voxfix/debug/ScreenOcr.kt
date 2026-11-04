package local.voxfix.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/** Called only on the screenshot worker. Each request owns and closes its native sessions. */
internal object ScreenOcr {
    fun recognize(context: Context, image: Bitmap, current: () -> Boolean): List<ScreenshotTextBlock> {
        val detector = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            // Await completion before recycling the image: ML Kit may still own it after a timeout.
            val detected = Tasks.await(detector.process(InputImage.fromBitmap(image, 0)))
            if (!current()) return emptyList()
            val lines = detected.textBlocks.flatMap { it.lines }
            require(lines.size <= 400) { "文字过多，请缩小选区" }
            if (lines.isEmpty()) return emptyList()
            return ScreenTextPaddleRecognizer(context).use { reader ->
                val blocks = mutableListOf<ScreenshotTextBlock>()
                for (line in lines) {
                    if (!current()) return emptyList()
                    val box = line.boundingBox ?: continue
                    if (box.width() < 2 || box.height() < 2) continue
                    val crop = lineImage(image, line)
                    val text = try { reader.recognize(crop) } finally { if (crop !== image) crop.recycle() }
                    // A failed/empty candidate never silently falls back to the less accurate reader.
                    check(text.isNotBlank()) { "文字行识别失败，请重新框选" }
                    blocks.add(ScreenshotTextBlock(box.left, box.top, box.right, box.bottom, text))
                }
                ScreenshotParagraphs.readingOrder(blocks)
            }
        } finally { detector.close() }
    }

    internal fun lineImage(image: Bitmap, line: Text.Line): Bitmap {
        val points = line.cornerPoints
        if (points != null && points.size == 4) {
            val xs = points.map { it.x.toFloat() }.toFloatArray()
            val ys = points.map { it.y.toFloat() }.toFloatArray()
            if (needsWarp(xs, ys, line.angle)) warp(image, xs, ys)?.let { return it }
        }
        val box = Rect(requireNotNull(line.boundingBox))
        val margin = maxOf(1, (box.height()*.05f).toInt())
        box.inset(-margin,-margin)
        check(box.intersect(0,0,image.width,image.height))
        return Bitmap.createBitmap(image,box.left,box.top,box.width(),box.height())
    }

    /**
     * ML Kit reports a small angle for a horizontal quad that is still perspective-skewed, so the
     * old "angle > 1°" gate cropped those lines with their axis-aligned box — dragging background
     * and the neighbouring column into the recognizer. Compare edge directions and opposite edge
     * lengths instead of trusting the reported angle alone.
     */
    internal fun needsWarp(xs: FloatArray, ys: FloatArray, angleDegrees: Float): Boolean {
        if (xs.size != 4 || ys.size != 4) return false
        if (kotlin.math.abs(angleDegrees) > 1f) return true
        fun length(from: Int, to: Int) =
            kotlin.math.hypot((xs[to] - xs[from]).toDouble(), (ys[to] - ys[from]).toDouble())
        fun direction(from: Int, to: Int) =
            Math.toDegrees(kotlin.math.atan2((ys[to] - ys[from]).toDouble(), (xs[to] - xs[from]).toDouble()))
        // 0° for a horizontal edge, 90° for a vertical one, after normalising to ±90°.
        fun normalized(value: Double) = ((value + 90.0) % 180.0) - 90.0
        fun horizontalDeviation(value: Double) = kotlin.math.abs(normalized(value))
        fun verticalDeviation(value: Double) = kotlin.math.abs(kotlin.math.abs(normalized(value)) - 90.0)
        if (horizontalDeviation(direction(0, 1)) > 2.0 || horizontalDeviation(direction(3, 2)) > 2.0) return true
        if (verticalDeviation(direction(0, 3)) > 2.0 || verticalDeviation(direction(1, 2)) > 2.0) return true
        val widths = listOf(length(0, 1), length(3, 2))
        val heights = listOf(length(0, 3), length(1, 2))
        if (widths.min() <= 1.0 || heights.min() <= 1.0) return false
        return widths.max() / widths.min() > 1.04 || heights.max() / heights.min() > 1.04
    }

    /** Returns null when the quad is degenerate, so the caller can still crop the plain box. */
    private fun warp(image: Bitmap, xs: FloatArray, ys: FloatArray): Bitmap? {
        fun length(from: Int, to: Int) =
            kotlin.math.hypot((xs[to] - xs[from]).toDouble(), (ys[to] - ys[from]).toDouble())
        val w = maxOf(length(0, 1), length(3, 2)).toInt().coerceAtLeast(2)
        val h = maxOf(length(0, 3), length(1, 2)).toInt().coerceAtLeast(2)
        val margin = maxOf(1, (h * .05f).toInt())
        val matrix = Matrix()
        val ok = matrix.setPolyToPoly(
            floatArrayOf(xs[0], ys[0], xs[1], ys[1], xs[2], ys[2], xs[3], ys[3]), 0,
            floatArrayOf(margin.toFloat(), margin.toFloat(), (w + margin).toFloat(), margin.toFloat(),
                (w + margin).toFloat(), (h + margin).toFloat(), margin.toFloat(), (h + margin).toFloat()), 0, 4)
        if (!ok) return null
        return Bitmap.createBitmap(w + 2 * margin, h + 2 * margin, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.drawColor(image.getPixel(xs[0].toInt().coerceIn(0, image.width - 1),
                ys[0].toInt().coerceIn(0, image.height - 1)))
            canvas.drawBitmap(image, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
    }
}
