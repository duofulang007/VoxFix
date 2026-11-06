package local.voxfix.debug

import android.graphics.Path
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Free-draw point pipeline and path geometry, ported from Snow Shot 1.1.8
 * (`snow_draw_engine_qt/crates/snow-draw-engine-core/src/stroke.rs`, `path.rs` and
 * `crates/snow-draw-engine-editor/src/free_draw_workflow.rs`).
 *
 * The port keeps two numbers that matter for parity: the stabilizer runs on spatially resampled
 * samples (so the result does not depend on pointer event frequency), and every vertex survives as
 * a curve anchor with centripetal Catmull-Rom controls. Internal math is Double; only stored vertices
 * are Float, matching the rest of the drawing model.
 */
internal object ScreenshotStrokeGeometry {
    const val MAX_PENDING = 128

    /** Round caps need a real segment for click-only dots; the original uses the same 1e-3 length. */
    const val DOT_SEGMENT = 1e-3

    data class GeoPoint(val x: Double, val y: Double)

    /** Screen-space parameters, taken from the original builder with camera zoom folded out. */
    class Tuning(widthPx: Float) {
        private val width = widthPx.toDouble().coerceAtLeast(0.0)
        val sampleSpacing = (width * 0.03).coerceIn(0.75, 1.5)
        val responseDistance = max(width * 0.35, 6.0)
        val tolerance = (width * 0.06).coerceIn(0.35, 1.25)
        val closureRadius = max(10.0, width * 0.5)
    }

    /**
     * Adaptive spatial stabilizer: samples are emitted by distance, then pulled toward the pointer
     * with a response that rises as the error grows, followed by a fixed second pole.
     */
    class Filter(start: GeoPoint, private val spacing: Double, private val response: Double) {
        private var lastRaw = start
        private var filtered = start
        private var smoothed = start
        private var untilSample = spacing

        fun reset(point: GeoPoint) {
            lastRaw = point; filtered = point; smoothed = point; untilSample = spacing
        }

        fun lastRaw(): GeoPoint = lastRaw

        fun sampleSpacing(): Double = spacing
        fun responseDistance(): Double = response

        fun ingest(point: GeoPoint, out: MutableList<GeoPoint>) {
            var segmentStart = lastRaw
            var segmentLength = distance(segmentStart, point)
            if (segmentLength <= 1e-12) { lastRaw = point; return }
            while (segmentLength + 1e-12 >= untilSample) {
                val ratio = untilSample / segmentLength
                val sample = lerp(segmentStart, point, ratio)
                out.add(stabilize(sample))
                segmentStart = sample
                segmentLength = distance(segmentStart, point)
                untilSample = spacing
            }
            untilSample = max(untilSample - segmentLength, 1e-12)
            lastRaw = point
        }

        private fun stabilize(sample: GeoPoint): GeoPoint {
            val normalized = (distance(filtered, sample) / response).coerceIn(0.0, 1.0)
            val adaptive = normalized * normalized * (3.0 - 2.0 * normalized)
            val rate = MIN_RESPONSE + (MAX_RESPONSE - MIN_RESPONSE) * adaptive
            filtered = lerp(filtered, sample, rate)
            smoothed = lerp(smoothed, filtered, 0.5)
            return smoothed
        }

        /** Pulls the last sample onto the exact pointer position so a stroke ends where it was lifted. */
        fun settleEndpoint(): GeoPoint? {
            if (distance(smoothed, lastRaw) <= 1e-12) return null
            filtered = lastRaw; smoothed = lastRaw; untilSample = spacing
            return filtered
        }

        private companion object {
            const val MIN_RESPONSE = 0.18
            const val MAX_RESPONSE = 0.82
        }
    }

    /** Ramer-Douglas-Peucker, same stack walk and strict `>` comparison as the original. */
    fun simplify(points: List<GeoPoint>, tolerance: Double): List<GeoPoint> {
        if (points.size <= 2) return points.toList()
        val keep = BooleanArray(points.size)
        keep[0] = true; keep[points.size - 1] = true
        val stack = ArrayDeque<Int>()
        stack.addLast(0); stack.addLast(points.size - 1)
        while (stack.isNotEmpty()) {
            val end = stack.removeLast(); val start = stack.removeLast()
            var maximum = tolerance
            var furthest = -1
            for (index in start + 1 until end) {
                val value = distanceToSegment(points[index], points[start], points[end])
                if (value > maximum) { maximum = value; furthest = index }
            }
            if (furthest >= 0) {
                keep[furthest] = true
                stack.addLast(start); stack.addLast(furthest)
                stack.addLast(furthest); stack.addLast(end)
            }
        }
        return points.filterIndexed { index, _ -> keep[index] }
    }

    fun distance(left: GeoPoint, right: GeoPoint): Double = hypot(right.x - left.x, right.y - left.y)

    fun distanceToSegment(point: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
        val dx = end.x - start.x; val dy = end.y - start.y
        val lengthSq = dx * dx + dy * dy
        if (lengthSq <= 1e-12) return distance(point, start)
        val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSq).coerceIn(0.0, 1.0)
        return distance(point, GeoPoint(start.x + dx * t, start.y + dy * t))
    }

    private fun lerp(from: GeoPoint, to: GeoPoint, ratio: Double) =
        GeoPoint(from.x + (to.x - from.x) * ratio, from.y + (to.y - from.y) * ratio)

    /**
     * Centripetal Catmull-Rom as cubic Bezier segments. Endpoints without a neighbour are mirrored
     * (`reflect`), which is what keeps the first and last segment from flattening.
     */
    fun buildPath(vertices: List<GeoPoint>, closed: Boolean, path: Path) {
        if (vertices.isEmpty()) return
        path.moveTo(vertices[0].x.toFloat(), vertices[0].y.toFloat())
        val count = vertices.size
        val edges = if (closed) count else count - 1
        for (edge in 0 until edges) {
            val start = vertices[edge]
            val end = vertices[(edge + 1) % count]
            val previous = neighborBefore(vertices, edge, closed)
            val next = neighborAfter(vertices, edge, closed)
            val controls = centripetalControls(previous, start, end, next)
            if (controls == null) path.lineTo(end.x.toFloat(), end.y.toFloat())
            else path.cubicTo(controls.first.x.toFloat(), controls.first.y.toFloat(),
                controls.second.x.toFloat(), controls.second.y.toFloat(), end.x.toFloat(), end.y.toFloat())
        }
        if (closed) path.close()
    }

    private fun neighborBefore(vertices: List<GeoPoint>, edge: Int, closed: Boolean): GeoPoint =
        when {
            edge > 0 -> vertices[edge - 1]
            closed -> vertices[vertices.size - 1]
            else -> reflect(vertices[0], vertices.getOrElse(1) { vertices[0] })
        }

    private fun neighborAfter(vertices: List<GeoPoint>, edge: Int, closed: Boolean): GeoPoint {
        val endIndex = (edge + 1) % vertices.size
        return when {
            edge + 2 < vertices.size -> vertices[edge + 2]
            closed -> vertices[(endIndex + 1) % vertices.size]
            else -> reflect(vertices[endIndex], vertices[edge])
        }
    }

    private fun reflect(anchor: GeoPoint, neighbor: GeoPoint) =
        GeoPoint(anchor.x * 2.0 - neighbor.x, anchor.y * 2.0 - neighbor.y)

    internal fun centripetalControls(p0: GeoPoint, p1: GeoPoint, p2: GeoPoint, p3: GeoPoint): Pair<GeoPoint, GeoPoint>? {
        val t0 = 0.0
        val t1 = t0 + chordParameter(p0, p1)
        val t2 = t1 + chordParameter(p1, p2)
        val t3 = t2 + chordParameter(p2, p3)
        if (t1 <= t0 || t2 <= t1 || t3 <= t2) return null
        val segment = t2 - t1
        val tangent1 = weightedTangent(p0, p1, p2, t0, t1, t2) ?: return null
        val tangent2 = weightedTangent(p1, p2, p3, t1, t2, t3) ?: return null
        return GeoPoint(p1.x + tangent1.x * segment / 3.0, p1.y + tangent1.y * segment / 3.0) to
            GeoPoint(p2.x - tangent2.x * segment / 3.0, p2.y - tangent2.y * segment / 3.0)
    }

    private fun chordParameter(a: GeoPoint, b: GeoPoint) = sqrt(hypot(b.x - a.x, b.y - a.y))

    private fun weightedTangent(p0: GeoPoint, p1: GeoPoint, p2: GeoPoint,
                                t0: Double, t1: Double, t2: Double): GeoPoint? {
        val d10 = t1 - t0; val d21 = t2 - t1; val d20 = t2 - t0
        if (d10 <= 0.0 || d21 <= 0.0 || d20 <= 0.0) return null
        return GeoPoint(
            (p1.x - p0.x) / d10 - (p2.x - p0.x) / d20 + (p2.x - p1.x) / d21,
            (p1.y - p0.y) / d10 - (p2.y - p0.y) / d20 + (p2.y - p1.y) / d21)
    }

    internal fun toGeo(points: List<ScreenshotDrawingPoint>) = points.map { GeoPoint(it.x.toDouble(), it.y.toDouble()) }

    internal fun fromGeo(points: List<GeoPoint>) = points.map { ScreenshotDrawingPoint(it.x.toFloat(), it.y.toFloat()) }

    /**
     * Upstream `free_draw_bounds`: the canvas bounds come from the laid-out path commands, not the
     * stored vertices, so a curve's overshoot outside the vertex hull widens the object box.
     */
    fun smoothBounds(points: List<ScreenshotDrawingPoint>, closed: Boolean): FloatArray {
        if(points.isEmpty()) return floatArrayOf(0f,0f,0f,0f)
        val vertices=points.map { GeoPoint(it.x.toDouble(), it.y.toDouble()) }
        var left=vertices[0].x; var top=vertices[0].y; var right=left; var bottom=top
        fun take(x: Double,y: Double) {
            if(x<left) left=x; if(x>right) right=x; if(y<top) top=y; if(y>bottom) bottom=y
        }
        val count=vertices.size
        val edges=if(closed) count else count-1
        for(edge in 0 until edges) {
            val start=vertices[edge]
            val end=vertices[(edge+1)%count]
            take(end.x,end.y)
            val controls=if(count>=2)
                centripetalControls(neighborBefore(vertices,edge,closed),start,end,neighborAfter(vertices,edge,closed)) else null
            if(controls!=null) for(step in 1..15) {
                val t=step/16.0; val mt=1.0-t
                take(mt*mt*mt*start.x+3.0*mt*mt*t*controls.first.x+3.0*mt*t*t*controls.second.x+t*t*t*end.x,
                    mt*mt*mt*start.y+3.0*mt*mt*t*controls.first.y+3.0*mt*t*t*controls.second.y+t*t*t*end.y)
            }
        }
        return floatArrayOf(left.toFloat(),top.toFloat(),right.toFloat(),bottom.toFloat())
    }

    /**
     * Upstream `free_draw_hit_test`: walk the laid-out path commands — every Catmull-Rom cubic
     * sampled at 16 steps, straight segments as-is — and answer on `tolerance + stroke_width * 0.5`.
     * Mirrors [buildPath] edge for edge, so hit follows the rendered curve instead of the stored
     * polyline chords.
     */
    fun hitTestSmooth(points: List<ScreenshotDrawingPoint>, closed: Boolean,
        p: ScreenshotDrawingPoint, tolerance: Float, strokeWidth: Float): Boolean {
        if(points.isEmpty()) return false
        val vertices=points.map { GeoPoint(it.x.toDouble(), it.y.toDouble()) }
        val point=GeoPoint(p.x.toDouble(),p.y.toDouble())
        val limit=tolerance.toDouble()+strokeWidth*0.5
        val count=vertices.size
        val edges=if(closed) count else count-1
        for(edge in 0 until edges) {
            val start=vertices[edge]
            val end=vertices[(edge+1)%count]
            val controls=if(count>=2)
                centripetalControls(neighborBefore(vertices,edge,closed),start,end,neighborAfter(vertices,edge,closed)) else null
            if(controls==null) {
                if(distanceToSegment(point,start,end)<=limit) return true
            } else {
                var previous=start
                for(step in 1..16) {
                    val t=step/16.0; val mt=1.0-t
                    val sample=GeoPoint(
                        mt*mt*mt*start.x+3.0*mt*mt*t*controls.first.x+3.0*mt*t*t*controls.second.x+t*t*t*end.x,
                        mt*mt*mt*start.y+3.0*mt*mt*t*controls.first.y+3.0*mt*t*t*controls.second.y+t*t*t*end.y)
                    if(distanceToSegment(point,previous,sample)<=limit) return true
                    previous=sample
                }
            }
        }
        return false
    }
}

/**
 * Streaming builder for one pen stroke. Mirrors `StreamingFreeDrawBuilder`: committed vertices are
 * already simplified, the raw tail is bounded at [ScreenshotStrokeGeometry.MAX_PENDING] samples, and
 * the preview is committed + simplified tail so dragging shows exactly what lifting will store.
 */
internal class PenStrokeBuilder private constructor(start: ScreenshotDrawingPoint, widthPx: Float,
    vertices: List<ScreenshotDrawingPoint>,private val reverseOutput: Boolean) {
    constructor(start: ScreenshotDrawingPoint,widthPx: Float): this(start,widthPx,emptyList(),false)
    companion object {
        /** free_draw_workflow.rs::continue_from: seed the original prefix, reversing at the start. */
        fun continueFrom(points: List<ScreenshotDrawingPoint>,widthPx: Float,atStart: Boolean): PenStrokeBuilder {
            val vertices=if(atStart) points.reversed() else points
            return PenStrokeBuilder(vertices.last(),widthPx,vertices,atStart)
        }
    }
    private val tuning = ScreenshotStrokeGeometry.Tuning(widthPx)
    private val filter = ScreenshotStrokeGeometry.Filter(
        ScreenshotStrokeGeometry.GeoPoint(start.x.toDouble(), start.y.toDouble()),
        tuning.sampleSpacing, tuning.responseDistance)
    private val committed = ArrayList(ScreenshotStrokeGeometry.toGeo(vertices))
    private val pending = ArrayList<ScreenshotStrokeGeometry.GeoPoint>().apply {
        add(ScreenshotStrokeGeometry.GeoPoint(start.x.toDouble(), start.y.toDouble()))
    }
    private val emitted = ArrayList<ScreenshotStrokeGeometry.GeoPoint>()

    /** Returns true when the visible preview changed. */
    fun append(point: ScreenshotDrawingPoint): Boolean {
        if (!point.x.isFinite() || !point.y.isFinite()) return false
        emitted.clear()
        filter.ingest(ScreenshotStrokeGeometry.GeoPoint(point.x.toDouble(), point.y.toDouble()), emitted)
        if (emitted.isEmpty()) return false
        emitted.forEach(::push)
        return true
    }

    private fun push(sample: ScreenshotStrokeGeometry.GeoPoint) {
        if (pending.lastOrNull()?.let { ScreenshotStrokeGeometry.distance(it, sample) <= 1e-12 } == true) return
        pending.add(sample)
        if (pending.size == ScreenshotStrokeGeometry.MAX_PENDING) compact()
    }

    /**
     * Simplifies the frozen part of the window and keeps [ScreenshotStrokeGeometry.MAX_PENDING] -
     * overlap samples so the next window still sees enough context for its corners.
     */
    private fun compact() {
        val boundary = ScreenshotStrokeGeometry.MAX_PENDING - 4
        commit(ScreenshotStrokeGeometry.simplify(pending.subList(0, boundary + 1), tuning.tolerance))
        repeat(boundary) { pending.removeAt(0) }
    }

    private fun commit(vertices: List<ScreenshotStrokeGeometry.GeoPoint>) {
        for (vertex in vertices) {
            if (committed.lastOrNull()?.let { ScreenshotStrokeGeometry.distance(it, vertex) <= 1e-12 } == true) continue
            committed.add(vertex)
        }
    }

    private fun flush() {
        if (pending.isEmpty()) return
        commit(ScreenshotStrokeGeometry.simplify(pending, tuning.tolerance))
        pending.clear()
    }

    /** Preview vertices: frozen prefix plus the simplified live tail including the raw pointer. */
    fun preview(): List<ScreenshotDrawingPoint> {
        val tail = ArrayList(pending)
        val raw = filter.lastRaw()
        val alreadyThere = tail.lastOrNull()?.let { ScreenshotStrokeGeometry.distance(it, raw) <= 1e-12 } ?: false
        if (!alreadyThere) tail.add(raw)
        val vertices = ArrayList(committed)
        for (point in ScreenshotStrokeGeometry.simplify(tail, tuning.tolerance)) {
            if (vertices.lastOrNull()?.let { ScreenshotStrokeGeometry.distance(it, point) <= 1e-12 } == true) continue
            vertices.add(point)
        }
        return ScreenshotStrokeGeometry.fromGeo(if(reverseOutput) vertices.reversed() else vertices)
    }

    fun finish(): Result {
        filter.settleEndpoint()?.let(::push)
        flush()
        if (committed.size == 1) {
            val start = committed[0]
            committed.add(ScreenshotStrokeGeometry.GeoPoint(start.x + ScreenshotStrokeGeometry.DOT_SEGMENT, start.y))
        }
        var closed = false
        if (committed.size >= 4 &&
            ScreenshotStrokeGeometry.distance(committed[0], committed.last()) <= tuning.closureRadius) {
            committed.removeAt(committed.lastIndex)
            closed = committed.size >= 3
        }
        return Result(ScreenshotStrokeGeometry.fromGeo(if(reverseOutput) committed.reversed() else committed), closed)
    }

    data class Result(val points: List<ScreenshotDrawingPoint>, val closed: Boolean)
}
