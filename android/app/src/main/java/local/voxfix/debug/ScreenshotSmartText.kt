package local.voxfix.debug

import android.graphics.Bitmap

/** Frame-local geometry. OCR strings are not retained by the selection session. */
internal class ScreenshotSmartText(blocks: List<List<ScreenshotDocRect>>) {
    private val paragraphs = blocks.flatMap { block ->
        val groups = mutableListOf<MutableList<ScreenshotDocRect>>()
        for (line in block.sortedBy { it.top }) {
            val last = groups.lastOrNull()?.last()
            val sameParagraph = last != null && line.top - last.bottom in -last.height / 4..maxOf(line.height, last.height) &&
                minOf(line.right, last.right) > maxOf(line.left, last.left)
            if (sameParagraph) groups.last().add(line) else groups.add(mutableListOf(line))
        }
        groups.map { lines ->
            val bounds = ScreenshotDocRect(lines.minOf { it.left }, lines.minOf { it.top },
                lines.maxOf { it.right }, lines.maxOf { it.bottom })
            lines to bounds
        }
    }

    fun refine(x: Int, y: Int, native: List<ScreenshotDocRect>): List<ScreenshotDocRect> {
        val hit = paragraphs.asSequence().flatMap { (lines, paragraph) ->
            lines.asSequence().filter {
                x >= it.left && x < it.right && y >= it.top - it.height / 5 && y < it.bottom + it.height / 5
            }.map { it to paragraph }
        }.minByOrNull { (line, _) -> kotlin.math.abs(2L * y - line.top - line.bottom) } ?: return native
        val (line, paragraph) = hit
        val result = mutableListOf(line)
        if (paragraph != line) result.add(paragraph)
        for (box in native) {
            // Keep the path nested, even when a detector block straddles two native widgets.
            while (result.size > 1 && !box.contains(result.last()) && box.contains(line)) result.removeAt(result.lastIndex)
            if (box != result.last() && box.contains(result.last())) result.add(box)
        }
        return result
    }

    companion object {
        fun detect(image: Bitmap): ScreenshotSmartText {
            val blocks = ScreenOcr.detect(image).textBlocks.map { block ->
                block.lines.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    val rect = ScreenshotDocRect(maxOf(0, box.left), maxOf(0, box.top),
                        minOf(image.width, box.right), minOf(image.height, box.bottom))
                    rect.takeIf { it.width >= 2 && it.height >= 2 }
                }
            }
            return ScreenshotSmartText(blocks)
        }

        private fun ScreenshotDocRect.contains(inner: ScreenshotDocRect) =
            inner.left >= left && inner.top >= top && inner.right <= right && inner.bottom <= bottom
    }
}
