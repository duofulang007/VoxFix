package local.voxfix.debug

/**
 * Reading order without a full layout engine. A block that sits entirely above every other block
 * opens its own band — that is what a cross-column headline looks like, and filing it into a
 * column by its left edge reads it in the middle of the article. Everything else is split on its
 * widest column gap. The previous version chose one cut direction for the whole page, which read a
 * two-column article correctly only when nothing spanned both columns.
 */
internal object ScreenshotParagraphs {
    private const val COLUMN_GAP_HEIGHT = 1.2f
    /** Slack so a headline and the columns below it still count as stacked, not side by side. */
    private const val BAND_SLACK = 0.25f

    fun readingOrder(blocks: List<ScreenshotTextBlock>): List<ScreenshotTextBlock> {
        if (blocks.size < 2) return blocks
        val height = medianHeight(blocks)
        val ordered = blocks.sortedWith(compareBy({ it.top }, { it.left }))
        val head = ordered.first()
        val rest = ordered.drop(1)
        // Peeling the topmost isolated block is what makes a centred headline read first instead of
        // landing in whichever column happens to contain its centre.
        if (head.bottom - height * BAND_SLACK <= rest.minOf { it.top }) {
            return listOf(head) + readingOrder(rest)
        }
        return columns(blocks, height)
    }

    private fun columns(blocks: List<ScreenshotTextBlock>, height: Int): List<ScreenshotTextBlock> {
        if (blocks.size < 2) return blocks
        val sorted = blocks.sortedBy { it.left }
        var end = sorted.first().right
        var split = 0
        var widest = 0
        for (block in sorted.drop(1)) {
            val gap = block.left - end
            if (gap > height * COLUMN_GAP_HEIGHT && gap > widest) {
                widest = gap
                split = end + gap / 2
            }
            end = maxOf(end, block.right)
        }
        if (widest == 0) return blocks.sortedWith(compareBy<ScreenshotTextBlock> { it.top }.thenBy { it.left })
        // Split on centres so a block straddling the gap is not dropped from either side.
        return columns(blocks.filter { it.centerX() < split }, height) +
            columns(blocks.filter { it.centerX() >= split }, height)
    }

    private fun medianHeight(blocks: List<ScreenshotTextBlock>): Int =
        blocks.map { it.bottom - it.top }.sorted()[blocks.size / 2].coerceAtLeast(1)

    fun merge(blocks: List<ScreenshotTextBlock>): List<ScreenshotTextBlock> {
        val result = mutableListOf<ScreenshotTextBlock>()
        var previous: ScreenshotTextBlock? = null
        for (line in readingOrder(blocks)) {
            val last = result.lastOrNull()
            val before = previous
            val h = line.bottom-line.top
            val ph = before?.let { it.bottom-it.top } ?: 0
            val sameParagraph = last != null && before != null && h > 0 && ph > 0 &&
                h.toDouble()/ph in .67..1.5 && line.top-before.bottom in -h/4..maxOf(h,ph) &&
                kotlin.math.abs(line.left-before.left) <= maxOf(h,ph)*2 &&
                minOf(line.right,before.right)-maxOf(line.left,before.left) > minOf(line.right-line.left,before.right-before.left)*.6 &&
                // Short headings and separate list entries should keep their own context.
                !(before.text.trimEnd().lastOrNull() in listOf('。','！','？','.', '!', '?') && before.right-before.left < (line.right-line.left)*.8) &&
                !Regex("^\\s*(?:[•●▪]|\\d+[.)、])").containsMatchIn(line.text)
            if (sameParagraph) {
                val separator = if (last!!.text.lastOrNull()?.code in 33..126 && line.text.firstOrNull()?.code in 33..126) " " else ""
                result[result.lastIndex] = ScreenshotTextBlock(minOf(last.left,line.left),minOf(last.top,line.top),
                    maxOf(last.right,line.right),maxOf(last.bottom,line.bottom),last.text.trimEnd()+separator+line.text.trimStart())
            } else result.add(line)
            previous = line
        }
        return result
    }
}
