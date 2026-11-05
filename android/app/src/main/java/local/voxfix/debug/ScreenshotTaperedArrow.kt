package local.voxfix.debug

import kotlin.math.*

typealias Pt = FloatArray

/**
 * Tapered arrow shaft, ported from Snow Shot 1.1.8 `arrow_shaft.rs` (`ribbon`, `at_distance`,
 * `clipped_centerline`, `flatten_curve`, `tapered_arrow_geometry`). Pure Kotlin on float pairs so
 * the JVM suite can pin widths and vertices; the renderer turns contours into paths.
 *
 * Rules that matter: the shaft runs from the secondary head's neck (or the path start) to the
 * primary head's neck, its width ramps linearly from the secondary's quarter-span to the primary's,
 * solid strokes build one contour that splices in the primary head polygon, and dashed/dotted
 * strokes build per-segment ribbons (dots are circles) plus separate head polygons. A
 * TriangleOutline primary is hollow: its contour punches through to the screenshot.
 */
internal object ScreenshotTaperedArrow {

    fun pt(x: Float, y: Float): Pt = floatArrayOf(x, y)
    private fun add(a: Pt, b: Pt) = pt(a[0] + b[0], a[1] + b[1])
    private fun sub(a: Pt, b: Pt) = pt(a[0] - b[0], a[1] - b[1])
    private fun mul(a: Pt, s: Float) = pt(a[0] * s, a[1] * s)
    private fun length(a: Pt) = hypot(a[0], a[1])
    private fun mix(a: Pt, b: Pt, t: Float) = pt(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
    private fun unit(a: Pt): Pt { val l = max(length(a), 1e-12f); return pt(a[0] / l, a[1] / l) }
    private fun normal(a: Pt) = pt(-a[1], a[0])

    /** `flatten_curve`: adaptive cubic flattening with a 0.02 chord-error bound, depth 12. */
    fun flattenCurve(out: MutableList<Pt>, a: Pt, b: Pt, c: Pt, d: Pt, depth: Int) {
        val chord = length(sub(d, a))
        if (depth >= 12 || length(sub(b, a)) + length(sub(c, b)) + length(sub(d, c)) - chord < 0.02f) {
            out.add(d); return
        }
        val ab = mix(a, b, .5f); val bc = mix(b, c, .5f); val cd = mix(c, d, .5f)
        val abc = mix(ab, bc, .5f); val bcd = mix(bc, cd, .5f); val mid = mix(abc, bcd, .5f)
        flattenCurve(out, a, ab, abc, mid, depth + 1)
        flattenCurve(out, mid, bcd, cd, d, depth + 1)
    }

    fun atDistance(points: List<Pt>, distances: List<Float>, d: Float): Pt {
        var index = distances.count { it < d }.coerceIn(1, points.size - 1)
        val t = ((d - distances[index - 1]) / (distances[index] - distances[index - 1])).coerceIn(0f, 1f)
        return mix(points[index - 1], points[index], t)
    }

    /** Limited offset joins cannot produce the unbounded spikes of an acute miter. */
    fun ribbon(points: List<Pt>, distances: List<Float>, total: Float,
               startWidth: Float, endWidth: Float, start: Float, end: Float): List<Pt> {
        val samples = ArrayList<Pair<Pt, Float>>()
        samples.add(atDistance(points, distances, start) to start)
        points.zip(distances).filter { (_, d) -> d > start && d < end }.forEach { (p, d) -> samples.add(p to d) }
        samples.add(atDistance(points, distances, end) to end)
        val left = ArrayList<Pt>(); val right = ArrayList<Pt>()
        for ((i, sample) in samples.withIndex()) {
            val (p, d) = sample
            val before = unit(sub(p, samples[maxOf(0, i - 1)].first))
            val after = unit(sub(samples[minOf(samples.size - 1, i + 1)].first, p))
            val n = when {
                i == 0 -> normal(after)
                i + 1 == samples.size -> normal(before)
                else -> {
                    val bisector = unit(add(normal(before), normal(after)))
                    val dot = bisector[0] * normal(after)[0] + bisector[1] * normal(after)[1]
                    mul(bisector, 1f / dot.coerceAtLeast(.5f))
                }
            }
            val radius = startWidth + (endWidth - startWidth) * d / total
            val turn = before[0] * after[1] - before[1] * after[0]
            if (i > 0 && i + 1 < samples.size && abs(turn) > 1e-6f) {
                val from = normal(before)
                val angle = atan2(before[0] * after[0] + before[1] * after[1], turn)
                val steps = ceil(abs(angle) / 0.15f).toInt()
                for (step in 0..steps) {
                    val t = angle * step / max(steps, 1)
                    val arc = pt(from[0] * cos(t) - from[1] * sin(t), from[0] * sin(t) + from[1] * cos(t))
                    // The outside follows a round join; the inside uses a bounded intersection.
                    left.add(add(p, mul(if (turn < 0f) arc else n, radius)))
                    right.add(sub(p, mul(if (turn > 0f) arc else n, radius)))
                }
            } else {
                val offset = mul(n, radius)
                left.add(add(p, offset)); right.add(sub(p, offset))
            }
        }
        return left + right.reversed()
    }

    class HeadGeom(val head: ScreenshotArrowhead, val tip: Pt, val a: Pt, val b: Pt,
                   val back: Pt, val neck: Pt, val direction: Pt, val width: Float) {
        /** `HeadGeometry::contour`. */
        fun contour(): List<Pt> = if (head == ScreenshotArrowhead.INDENTED_TRIANGLE)
            listOf(mix(back, tip, .25f), a, tip, b) else listOf(back, a, tip, b)
    }

    /**
     * `head_geometry`: the two widest rendered vertices are the shoulders; the indented head's neck
     * sits a quarter of the way back, everything else uses the shoulder midpoint.
     */
    fun headGeometry(head: ScreenshotArrowhead, tip: Pt, a: Pt, b: Pt): HeadGeom {
        val back = mix(a, b, .5f)
        val direction = unit(sub(tip, back))
        val ordered = if ((a[0] - back[0]) * normal(direction)[0] + (a[1] - back[1]) * normal(direction)[1] < 0f) b else a
        val other = if (ordered === a) b else a
        val neck = if (head == ScreenshotArrowhead.INDENTED_TRIANGLE) mix(back, tip, .125f) else back
        return HeadGeom(head, tip, ordered, other, back, neck, direction, length(sub(ordered, other)) * .25f)
    }

    private fun clippedCenterline(points: List<Pt>, distances: List<Float>, start: Float, end: Float,
                                  startNeck: Pt?, endNeck: Pt): Triple<List<Pt>, List<Float>, Float>? {
        val total = end - start
        if (total < 1e-6f) return null
        val clippedPoints = mutableListOf(atDistance(points, distances, start))
        val clippedDistances = mutableListOf(0f)
        for ((point, distance) in points.zip(distances)) {
            if (distance > start && distance < end) { clippedPoints.add(point); clippedDistances.add(distance - start) }
        }
        clippedPoints.add(atDistance(points, distances, end)); clippedDistances.add(total)
        startNeck?.let { clippedPoints[0] = it }
        clippedPoints[clippedPoints.lastIndex] = endNeck
        return Triple(clippedPoints, clippedDistances, total)
    }

    class Shaft(val contours: List<List<Pt>>, val hollow: Boolean,
                /** A head that is not integrated into the contours; Background fills punch through. */
                val separateHead: HeadGeom?, val separateIsOutline: Boolean)

    /**
     * `tapered_arrow_geometry` for one supported head pair. [centerline] is the flattened drawn
     * path oriented toward the primary head; [secondary] is the opposite head when present. The
     * shaft starts past the secondary's tip-to-neck length and ramps from its quarter-span width.
     */
    fun shaft(centerline: List<Pt>, primary: HeadGeom, secondary: HeadGeom?,
              strokeWidth: Float, strokePattern: ScreenshotStrokePattern, hollowPrimary: Boolean,
              integrateSecondary: Boolean): Shaft {
        val distances = mutableListOf(0f)
        for (i in 1 until centerline.size) distances.add(distances.last() + length(sub(centerline[i], centerline[i - 1])))
        val full = distances.last()
        // `start` is the secondary tip→neck length capped at 80% of the path; `startWidth` its quarter-span.
        val start = secondary?.let { min(length(sub(it.tip, it.neck)), full * .8f) } ?: 0f
        val startWidth = secondary?.width ?: 0f
        val end = full - min(length(sub(primary.tip, primary.neck)), full * .8f)
        val clipped = clippedCenterline(centerline, distances, start, end,
            secondary?.neck, primary.neck) ?: return Shaft(emptyList(), false, null, false)
        val (points, clippedDistances, total) = clipped
        val contours = mutableListOf<List<Pt>>()
        if (hollowPrimary || strokePattern == ScreenshotStrokePattern.SOLID) {
            val contour = ribbon(points, clippedDistances, total, startWidth, primary.width, 0f, total).toMutableList()
            val half = contour.size / 2
            contour[half - 1] = add(primary.neck, mul(normal(primary.direction), primary.width))
            contour[half] = sub(primary.neck, mul(normal(primary.direction), primary.width))
            contour.addAll(half, listOf(primary.a, primary.tip, primary.b))
            contours.add(contour)
        } else {
            val stroke = strokeWidth
            val dash = if (strokePattern == ScreenshotStrokePattern.DASHED) 4f * stroke else stroke
            val gap = 2f * stroke
            var position = 0f
            var guard = 0
            while (position < total && guard++ < 100_000) {
                val segmentEnd = min(position + dash, total)
                if (strokePattern == ScreenshotStrokePattern.DOTTED) {
                    val d = (position + segmentEnd) * .5f
                    val center = atDistance(points, clippedDistances, d)
                    val radius = startWidth + (primary.width - startWidth) * d / total
                    contours.add((0 until 20).map { i ->
                        // Match the clockwise shaft/head winding so overlapping dots never cut holes.
                        val angle = -i * (PI.toFloat() * 2f) / 20f
                        pt(center[0] + radius * cos(angle), center[1] + radius * sin(angle))
                    })
                } else {
                    contours.add(ribbon(points, clippedDistances, total, startWidth, primary.width, position, segmentEnd))
                }
                position += dash + gap
            }
            contours.add(primary.contour())
            if (integrateSecondary) contours.add(secondary!!.contour())
        }
        return Shaft(contours, hollowPrimary, if (integrateSecondary || secondary == null) null else secondary,
            secondary?.head == ScreenshotArrowhead.TRIANGLE_OUTLINE)
    }
}
