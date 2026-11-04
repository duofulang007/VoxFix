package local.voxfix.debug

import android.graphics.Rect

/** Integer rectangle that survives a JSON round trip without touching android.graphics. */
data class ScreenshotDocRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun toRect() = Rect(left, top, right, bottom)
    companion object {
        fun of(rect: Rect) = ScreenshotDocRect(rect.left, rect.top, rect.right, rect.bottom)
    }
}

enum class ScreenshotTextMode { ORIGINAL, TRANSLATION }

/**
 * Recognised text as part of the project: the user's edits, the geometry of every block and which
 * of the two views was on screen. Text blocks stay in reading order, which is what ties them back
 * to the OCR paragraphs.
 */
data class ScreenshotDocumentText(
    val blocks: List<ScreenshotTextBlock>,
    val translation: List<ScreenshotTextBlock> = emptyList(),
    val mode: ScreenshotTextMode = ScreenshotTextMode.ORIGINAL,
    val translated: Boolean = false
)

/**
 * Everything needed to reopen a screenshot and keep editing the same marks.
 *
 * Coordinates are bitmap pixels of the canvas; the viewport is the part of that canvas the record
 * shows. Drawn objects keep their own id, so a move or a restyle never looks like a new object.
 */
data class ScreenshotDocument(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val viewport: ScreenshotDocRect,
    val objects: List<ScreenshotDrawingStroke> = emptyList(),
    val text: ScreenshotDocumentText? = null,
    /** Empty means the rectangular viewport; otherwise these canvas rectangles form the mask. */
    val regions: List<ScreenshotDocRect> = emptyList(),
    val drawingUnit: Float = 0f,
    val fontUnit: Float = 0f
) {
    fun isEmpty() = objects.isEmpty() && text == null
    companion object {
        fun full(width: Int, height: Int) = ScreenshotDocument(width, height, ScreenshotDocRect(0, 0, width, height))
    }
}
