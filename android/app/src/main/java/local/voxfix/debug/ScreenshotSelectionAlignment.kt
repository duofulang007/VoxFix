package local.voxfix.debug

/** SelectionAlignment and alignment_deltas from document_ops.rs. */
internal enum class ScreenshotSelectionAlignment(private val vertical: Boolean, private val anchor: Anchor) {
    LEFT(false, Anchor.START), CENTER_HORIZONTAL(false, Anchor.CENTER), RIGHT(false, Anchor.END),
    DISTRIBUTE_HORIZONTAL(false, Anchor.DISTRIBUTE),
    TOP(true, Anchor.START), CENTER_VERTICAL(true, Anchor.CENTER), BOTTOM(true, Anchor.END),
    DISTRIBUTE_VERTICAL(true, Anchor.DISTRIBUTE);

    private enum class Anchor { START, CENTER, END, DISTRIBUTE }
    private data class Span(val start: Double, val end: Double) {
        val center get() = (start + end) / 2
        val width get() = end - start
    }
    val minimumCount get() = if (anchor == Anchor.DISTRIBUTE) 3 else 2

    fun deltas(bounds: Map<Int, SelBox>): Map<Int, ScreenshotDrawingPoint> {
        if (bounds.size < minimumCount) return emptyMap()
        val spans = bounds.mapValues { (_, box) ->
            if (vertical) Span(box.top.toDouble(), box.bottom.toDouble())
            else Span(box.left.toDouble(), box.right.toDouble())
        }
        val reference = Span(spans.values.minOf { it.start }, spans.values.maxOf { it.end })
        val offsets = when (anchor) {
            Anchor.START -> spans.mapValues { reference.start - it.value.start }
            Anchor.CENTER -> spans.mapValues { reference.center - it.value.center }
            Anchor.END -> spans.mapValues { reference.end - it.value.end }
            Anchor.DISTRIBUTE -> distribute(spans, reference)
        }
        return offsets.mapValues { (_, delta) ->
            if (vertical) ScreenshotDrawingPoint(0f, delta.toFloat())
            else ScreenshotDrawingPoint(delta.toFloat(), 0f)
        }
    }

    private fun distribute(spans: Map<Int, Span>, reference: Span): Map<Int, Double> {
        val ordered = spans.entries.sortedBy { it.value.center }
        val gap = (reference.width - ordered.sumOf { it.value.width }) / (ordered.size - 1)
        if (gap < 0) {
            // Overlap: keep the units owning the outer edges pinned and distribute centers.
            val first = ordered.first { it.value.start == reference.start }
            val last = ordered.first { it.value.end == reference.end }
            val step = (last.value.center - first.value.center) / (ordered.size - 1)
            var center = first.value.center
            return buildMap {
                for ((index, span) in ordered) {
                    if (index == first.key || index == last.key) continue
                    center += step
                    put(index, center - span.center)
                }
            }
        }
        var position = reference.start
        return ordered.associate { (index, span) ->
            val offset = position - span.start
            position += span.width + gap
            index to offset
        }
    }
}
