package local.voxfix.debug

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin

/**
 * Binding geometry for T22 (arrow endpoint binding / outline snapping), ported from
 * Snow Shot 1.1.8 at `afdc340b14e7f92a294a95c374d13ada0d8c7a82` (Apache-2.0):
 *
 * - `snow-draw-engine-document/src/arrow/geom.rs`
 * - `snow-draw-engine-document/src/arrow/binding_geometry.rs`
 * - `snow-draw-engine-document/src/arrow/binding_geometry_direction.rs`
 * - `snow-draw-engine-document/src/arrow/hit_test.rs` 与四份叶模块
 *
 * Only the pure geometry lives here: no view, no state. Callers pass candidates in paint
 * order and get back hit results or a normalised fixed point, so the same code serves both
 * the drag preview and the committed document.
 *
 * Differences from the original are marked [手机侧差异]; the original constants are kept
 * verbatim (both binding gaps happen to be 5.0, they are separate constants upstream).
 */
internal object ScreenshotBindingGeometry {

    const val BASE_BINDING_GAP = 5.0
    const val BASE_BINDING_GAP_ELBOW = 5.0
    const val BASE_ARROW_MIN_LENGTH = 10.0
    const val EPSILON = 1e-4

    private const val SEARCH_CONE_MULTIPLIER = 2.0
    private const val DIAMOND_SHRINK = 0.95

    enum class Shape { RECTANGLE, ELLIPSE, DIAMOND }

    enum class Heading(val dx: Int, val dy: Int) {
        UP(0, -1), RIGHT(1, 0), DOWN(0, 1), LEFT(-1, 0);
    }

    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * A bindable element as the arrow sees it. Defaults mirror the original's
     * `Option<bool>` tri-state: null means "engine default", which is enabled/opaque.
     */
    data class Bindable(
        val id: Int,
        val shape: Shape,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val angle: Float = 0f,
        val strokeWidth: Float = 0f,
        val zIndex: Double? = null,
        val backgroundOpaque: Boolean? = null,
        val bindingEnabled: Boolean? = null,
        val interiorHitEnabled: Boolean? = null,
        val visibilityBounds: Bounds? = null
    ) {
        val centerX: Double get() = x + width / 2.0
        val centerY: Double get() = y + height / 2.0
    }

    // ---- geom.rs ----------------------------------------------------------------

    fun clamp(value: Double, min: Double, max: Double): Double = when {
        value < min -> min
        value > max -> max
        else -> value
    }

    fun center(bindable: Bindable): DoubleArray =
        doubleArrayOf(bindable.x + bindable.width / 2.0, bindable.y + bindable.height / 2.0)

    fun rotate(point: DoubleArray, around: DoubleArray, angle: Double): DoubleArray {
        if (angle == 0.0) return point
        val dx = point[0] - around[0]
        val dy = point[1] - around[1]
        val c = cos(angle)
        val s = sin(angle)
        return doubleArrayOf(around[0] + dx * c - dy * s, around[1] + dx * s + dy * c)
    }

    fun unrotate(point: DoubleArray, around: DoubleArray, angle: Double): DoubleArray =
        rotate(point, around, -angle)

    fun distance(a: DoubleArray, b: DoubleArray): Double =
        hypot(a[0] - b[0], a[1] - b[1])

    fun pointsEqual(a: DoubleArray, b: DoubleArray, tolerance: Double): Boolean =
        abs(a[0] - b[0]) <= tolerance && abs(a[1] - b[1]) <= tolerance

    /** Unrotated diamond vertices; the `floor(width)/2 + 1` offsets are the original's. */
    fun diamondVertices(bindable: Bindable): List<DoubleArray> {
        val topX = floor(bindable.width.toDouble()) / 2.0 + 1.0
        val rightY = floor(bindable.height.toDouble()) / 2.0 + 1.0
        return listOf(
            doubleArrayOf(bindable.x + topX, bindable.y.toDouble()),
            doubleArrayOf(bindable.x + bindable.width.toDouble(), bindable.y + rightY),
            doubleArrayOf(bindable.x + topX, bindable.y + bindable.height.toDouble()),
            doubleArrayOf(bindable.x.toDouble(), bindable.y + rightY)
        )
    }

    fun inflate(bounds: Bounds, top: Float = 0f, right: Float = 0f, bottom: Float = 0f, left: Float = 0f) =
        Bounds(bounds.left - left, bounds.top - top, bounds.right + right, bounds.bottom + bottom)

    fun rotatedBounds(bindable: Bindable): Bounds {
        val around = center(bindable)
        val corners = listOf(
            doubleArrayOf(bindable.x.toDouble(), bindable.y.toDouble()),
            doubleArrayOf(bindable.x + bindable.width.toDouble(), bindable.y.toDouble()),
            doubleArrayOf(bindable.x + bindable.width.toDouble(), bindable.y + bindable.height.toDouble()),
            doubleArrayOf(bindable.x.toDouble(), bindable.y + bindable.height.toDouble())
        ).map { rotate(it, around, bindable.angle.toDouble()) }
        return Bounds(
            corners.minOf { it[0] }.toFloat(), corners.minOf { it[1] }.toFloat(),
            corners.maxOf { it[0] }.toFloat(), corners.maxOf { it[1] }.toFloat()
        )
    }

    /** `normalize_fixed_point`: non-finite and near-0.5 ratios collapse to 0.5001. */
    fun normalizeFixedPoint(ratioX: Double, ratioY: Double): DoubleArray {
        val x = if (ratioX.isFinite()) ratioX else 0.5001
        val y = if (ratioY.isFinite()) ratioY else 0.5001
        return doubleArrayOf(
            if (abs(x - 0.5) < EPSILON) 0.5001 else x,
            if (abs(y - 0.5) < EPSILON) 0.5001 else y
        )
    }

    /** `get_global_fixed_point`: ratio -> element-local point -> rotated global point. */
    fun fixedPointToGlobal(ratioX: Double, ratioY: Double, bindable: Bindable): DoubleArray {
        val fixed = normalizeFixedPoint(ratioX, ratioY)
        val point = doubleArrayOf(
            bindable.x + bindable.width * fixed[0],
            bindable.y + bindable.height * fixed[1]
        )
        return rotate(point, center(bindable), bindable.angle.toDouble())
    }

    // ---- hit_test_shape_geometry.rs ---------------------------------------------

    private fun localPoint(point: DoubleArray, bindable: Bindable): DoubleArray =
        unrotate(point, center(bindable), bindable.angle.toDouble())

    private fun pointSegmentDistance(point: DoubleArray, a: DoubleArray, b: DoubleArray): Double {
        val abx = b[0] - a[0]
        val aby = b[1] - a[1]
        val apx = point[0] - a[0]
        val apy = point[1] - a[1]
        val lengthSq = abx * abx + aby * aby
        if (lengthSq <= 1e-9) return distance(point, a)
        val t = clamp((apx * abx + apy * aby) / lengthSq, 0.0, 1.0)
        return distance(point, doubleArrayOf(a[0] + abx * t, a[1] + aby * t))
    }

    private fun inConvexPolygon(point: DoubleArray, vertices: List<DoubleArray>): Boolean {
        var reference = 0.0
        for (index in vertices.indices) {
            val from = vertices[index]
            val to = vertices[(index + 1) % vertices.size]
            val cross = (to[0] - from[0]) * (point[1] - from[1]) - (to[1] - from[1]) * (point[0] - from[0])
            if (abs(cross) <= 1e-9) continue
            val s = sign(cross)
            if (reference == 0.0) {
                reference = s
                continue
            }
            if (s != reference) return false
        }
        return true
    }

    fun isPointInBindable(point: DoubleArray, bindable: Bindable): Boolean {
        val local = localPoint(point, bindable)
        return when (bindable.shape) {
            Shape.RECTANGLE -> local[0] >= bindable.x && local[1] >= bindable.y &&
                local[0] <= bindable.x + bindable.width && local[1] <= bindable.y + bindable.height
            Shape.ELLIPSE -> {
                val rx = (bindable.width / 2.0).coerceAtLeast(1e-6)
                val ry = (bindable.height / 2.0).coerceAtLeast(1e-6)
                val nx = (local[0] - bindable.centerX) / rx
                val ny = (local[1] - bindable.centerY) / ry
                nx * nx + ny * ny <= 1.0
            }
            Shape.DIAMOND -> inConvexPolygon(local, diamondVertices(bindable))
        }
    }

    fun distanceToOutline(point: DoubleArray, bindable: Bindable): Double {
        val local = localPoint(point, bindable)
        return when (bindable.shape) {
            Shape.RECTANGLE -> {
                val minX = bindable.x.toDouble()
                val minY = bindable.y.toDouble()
                val maxX = bindable.x + bindable.width.toDouble()
                val maxY = bindable.y + bindable.height.toDouble()
                val inside = local[0] >= minX && local[0] <= maxX && local[1] >= minY && local[1] <= maxY
                if (inside) {
                    minOf(
                        abs(local[0] - minX), abs(maxX - local[0]),
                        abs(local[1] - minY), abs(maxY - local[1])
                    )
                } else {
                    distance(
                        local,
                        doubleArrayOf(clamp(local[0], minX, maxX), clamp(local[1], minY, maxY))
                    )
                }
            }
            Shape.ELLIPSE -> {
                val rx = (bindable.width / 2.0).coerceAtLeast(1e-6)
                val ry = (bindable.height / 2.0).coerceAtLeast(1e-6)
                val dx = local[0] - bindable.centerX
                val dy = local[1] - bindable.centerY
                if (abs(dx) < 1e-9 && abs(dy) < 1e-9) {
                    minOf(rx, ry)
                } else {
                    val scale = 1.0 / hypot(dx / rx, dy / ry)
                    distance(local, doubleArrayOf(bindable.centerX + dx * scale, bindable.centerY + dy * scale))
                }
            }
            Shape.DIAMOND -> {
                val v = diamondVertices(bindable)
                minOf(
                    pointSegmentDistance(local, v[0], v[1]),
                    pointSegmentDistance(local, v[1], v[2]),
                    pointSegmentDistance(local, v[2], v[3]),
                    pointSegmentDistance(local, v[3], v[0])
                )
            }
        }
    }

    // ---- hit_test_visibility.rs --------------------------------------------------

    fun isBackgroundOpaque(bindable: Bindable) = bindable.backgroundOpaque != false

    fun isBindingEnabled(bindable: Bindable) = bindable.bindingEnabled != false

    fun isInteriorHitEnabled(bindable: Bindable) = bindable.interiorHitEnabled != false

    fun isVisibleAtPoint(point: DoubleArray, bindable: Bindable): Boolean {
        val bounds = bindable.visibilityBounds ?: return true
        return point[0] >= bounds.left && point[1] >= bounds.top &&
            point[0] <= bounds.right && point[1] <= bounds.bottom
    }

    /** Stable sort by explicit z when at least one candidate carries a finite z. */
    fun sortByZIndex(bindables: List<Bindable>): List<Bindable> {
        if (bindables.none { it.zIndex?.isFinite() == true }) return bindables
        return bindables.withIndex()
            .sortedWith(
                compareBy({ it.value.zIndex?.takeIf { z -> z.isFinite() } ?: it.index.toDouble() }, { it.index })
            )
            .map { it.value }
    }

    // ---- hit_test_binding_hit.rs -------------------------------------------------

    private fun hitThreshold(bindable: Bindable, tolerance: Double) =
        if (isInteriorHitEnabled(bindable)) tolerance else maxOf(1.0, tolerance)

    fun isPointNearForBindingHit(point: DoubleArray, bindable: Bindable, tolerance: Double): Boolean {
        if (!isBindingEnabled(bindable)) return false
        if (!isVisibleAtPoint(point, bindable)) return false
        val threshold = hitThreshold(bindable, tolerance)
        if (!isInteriorHitEnabled(bindable)) return distanceToOutline(point, bindable) <= threshold
        return isPointInBindable(point, bindable) || distanceToOutline(point, bindable) <= threshold
    }

    /**
     * Topmost candidate under the pointer; among overlapping hits the smallest area wins
     * (original sorts descending by area then pops the last), and an opaque background
     * stops the walk from collecting anything further down.
     */
    fun hoveredBindable(point: DoubleArray, bindables: List<Bindable>, tolerance: Double): Bindable? {
        val ordered = sortByZIndex(bindables)
        val candidates = mutableListOf<Bindable>()
        for (bindable in ordered.asReversed()) {
            if (!isPointNearForBindingHit(point, bindable, tolerance)) continue
            candidates += bindable
            if (isBackgroundOpaque(bindable)) break
        }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        fun area(bindable: Bindable) =
            bindable.width.toDouble() * bindable.width + bindable.height.toDouble() * bindable.height
        return candidates.sortedWith(compareByDescending { area(it) }).last()
    }

    fun bindablesOverPoint(point: DoubleArray, bindables: List<Bindable>, tolerance: Double): List<Bindable> =
        sortByZIndex(bindables).filter { isPointNearForBindingHit(point, it, tolerance) }

    private fun sampleOutlinePoints(bindable: Bindable, offset: Double): List<DoubleArray> {
        val around = center(bindable)
        val angle = bindable.angle.toDouble()
        if (bindable.shape == Shape.DIAMOND) {
            val v = diamondVertices(bindable)
            return listOf(
                rotate(doubleArrayOf(v[0][0], v[0][1] - offset), around, angle),
                rotate(doubleArrayOf(v[1][0] + offset, v[1][1]), around, angle),
                rotate(doubleArrayOf(v[2][0], v[2][1] + offset), around, angle),
                rotate(doubleArrayOf(v[3][0] - offset, v[3][1]), around, angle)
            )
        }
        if (bindable.shape == Shape.ELLIPSE) {
            val cx = bindable.centerX
            val cy = bindable.centerY
            val rx = bindable.width / 2.0
            val ry = bindable.height / 2.0
            return listOf(
                rotate(doubleArrayOf(cx, cy - ry - offset), around, angle),
                rotate(doubleArrayOf(cx + rx + offset, cy), around, angle),
                rotate(doubleArrayOf(cx, cy + ry + offset), around, angle),
                rotate(doubleArrayOf(cx - rx - offset, cy), around, angle)
            )
        }
        val left = bindable.x - offset
        val top = bindable.y - offset
        val right = bindable.x + bindable.width + offset
        val bottom = bindable.y + bindable.height + offset
        return listOf(
            rotate(doubleArrayOf(left, top), around, angle),
            rotate(doubleArrayOf(right, top), around, angle),
            rotate(doubleArrayOf(right, bottom), around, angle),
            rotate(doubleArrayOf(left, bottom), around, angle)
        )
    }

    /** True when the inner element's shrunken outline sits entirely inside the outer one. */
    fun isInsideOther(inner: Bindable, outer: Bindable): Boolean {
        val offset = -maxOf(inner.width, inner.height) / 20.0
        return sampleOutlinePoints(inner, offset).all { isPointInBindable(it, outer) }
    }

    // ---- binding_geometry.rs ------------------------------------------------------

    fun bindingGap(bindable: Bindable, elbowed: Boolean): Double =
        (if (elbowed) BASE_BINDING_GAP_ELBOW else BASE_BINDING_GAP) + bindable.strokeWidth / 2.0

    /**
     * Search radius grows as the canvas zooms out; clamped to `[15, 30]` of logical pixels
     * because `max(BASE_BINDING_GAP, 15)` is 15.
     */
    fun maxBindingDistance(zoom: Double): Double {
        val base = maxOf(BASE_BINDING_GAP, 15.0)
        val safeZoom = if (zoom < 1.0) zoom else 1.0
        return clamp(base / (safeZoom * 1.5), base, base * 2.0)
    }

    // ---- geometry/direction -------------------------------------------------------

    fun vectorToHeading(from: DoubleArray, to: DoubleArray): Heading {
        val x = to[0] - from[0]
        val y = to[1] - from[1]
        val absX = abs(x)
        val absY = abs(y)
        return when {
            x > absY -> Heading.RIGHT
            x <= -absY -> Heading.LEFT
            y > absX -> Heading.DOWN
            else -> Heading.UP
        }
    }

    private fun pointInTriangle(p: DoubleArray, a: DoubleArray, b: DoubleArray, c: DoubleArray): Boolean {
        fun signOf(p1: DoubleArray, p2: DoubleArray, p3: DoubleArray): Double =
            (p1[0] - p3[0]) * (p2[1] - p3[1]) - (p2[0] - p3[0]) * (p1[1] - p3[1])

        val d1 = signOf(p, a, b)
        val d2 = signOf(p, b, c)
        val d3 = signOf(p, c, a)
        val hasNegative = d1 < 0.0 || d2 < 0.0 || d3 < 0.0
        val hasPositive = d1 > 0.0 || d2 > 0.0 || d3 > 0.0
        return !(hasNegative && hasPositive)
    }

    private fun scaleFromOrigin(point: DoubleArray, origin: DoubleArray, factor: Double): DoubleArray =
        doubleArrayOf(
            origin[0] + (point[0] - origin[0]) * factor,
            origin[1] + (point[1] - origin[1]) * factor
        )

    /**
     * `heading_from_bindable`: diamonds get a shrunken four-cone test against their own
     * vertices, everything else falls back to the AABB cone.
     */
    fun headingFromBindable(point: DoubleArray, bindable: Bindable): Heading {
        val around = center(bindable)
        val angle = bindable.angle.toDouble()
        val corners = listOf(
            doubleArrayOf(bindable.x.toDouble(), bindable.y.toDouble()),
            doubleArrayOf(bindable.x + bindable.width.toDouble(), bindable.y.toDouble()),
            doubleArrayOf(bindable.x + bindable.width.toDouble(), bindable.y + bindable.height.toDouble()),
            doubleArrayOf(bindable.x.toDouble(), bindable.y + bindable.height.toDouble())
        ).map { rotate(it, around, angle) }
        val aabb = doubleArrayOf(
            corners.minOf { it[0] }, corners.minOf { it[1] },
            corners.maxOf { it[0] }, corners.maxOf { it[1] }
        )
        val midPoint = doubleArrayOf((aabb[0] + aabb[2]) / 2.0, (aabb[1] + aabb[3]) / 2.0)

        if (bindable.shape == Shape.DIAMOND) {
            val top = scaleFromOrigin(
                rotate(doubleArrayOf(bindable.x + bindable.width / 2.0, bindable.y.toDouble()), around, angle),
                midPoint, DIAMOND_SHRINK
            )
            val right = scaleFromOrigin(
                rotate(doubleArrayOf(bindable.x + bindable.width.toDouble(), bindable.y + bindable.height / 2.0), around, angle),
                midPoint, DIAMOND_SHRINK
            )
            val bottom = scaleFromOrigin(
                rotate(doubleArrayOf(bindable.x + bindable.width / 2.0, bindable.y + bindable.height.toDouble()), around, angle),
                midPoint, DIAMOND_SHRINK
            )
            val left = scaleFromOrigin(
                rotate(doubleArrayOf(bindable.x.toDouble(), bindable.y + bindable.height / 2.0), around, angle),
                midPoint, DIAMOND_SHRINK
            )

            fun vec(from: DoubleArray, to: DoubleArray) = doubleArrayOf(to[0] - from[0], to[1] - from[1])
            fun crossOf(l: DoubleArray, r: DoubleArray) = l[0] * r[1] - l[1] * r[0]

            if (crossOf(vec(point, top), vec(top, right)) <= 0.0 && crossOf(vec(point, top), vec(top, left)) > 0.0) {
                return vectorToHeading(top, midPoint)
            }
            if (crossOf(vec(point, right), vec(right, bottom)) <= 0.0 && crossOf(vec(point, right), vec(right, top)) > 0.0) {
                return vectorToHeading(right, midPoint)
            }
            if (crossOf(vec(point, bottom), vec(bottom, left)) <= 0.0 && crossOf(vec(point, bottom), vec(bottom, right)) > 0.0) {
                return vectorToHeading(bottom, midPoint)
            }
            if (crossOf(vec(point, left), vec(left, top)) <= 0.0 && crossOf(vec(point, left), vec(left, bottom)) > 0.0) {
                return vectorToHeading(left, midPoint)
            }
            if (crossOf(vec(point, midPoint), vec(top, midPoint)) <= 0.0 &&
                crossOf(vec(point, midPoint), vec(right, midPoint)) > 0.0
            ) {
                return vectorToHeading(if (bindable.width > bindable.height) top else right, midPoint)
            }
            if (crossOf(vec(point, midPoint), vec(right, midPoint)) <= 0.0 &&
                crossOf(vec(point, midPoint), vec(bottom, midPoint)) > 0.0
            ) {
                return vectorToHeading(if (bindable.width > bindable.height) bottom else right, midPoint)
            }
            if (crossOf(vec(point, midPoint), vec(bottom, midPoint)) <= 0.0 &&
                crossOf(vec(point, midPoint), vec(left, midPoint)) > 0.0
            ) {
                return vectorToHeading(if (bindable.width > bindable.height) bottom else left, midPoint)
            }
            return vectorToHeading(if (bindable.width > bindable.height) top else left, midPoint)
        }

        val topLeft = scaleFromOrigin(doubleArrayOf(aabb[0], aabb[1]), midPoint, SEARCH_CONE_MULTIPLIER)
        val topRight = scaleFromOrigin(doubleArrayOf(aabb[2], aabb[1]), midPoint, SEARCH_CONE_MULTIPLIER)
        val bottomLeft = scaleFromOrigin(doubleArrayOf(aabb[0], aabb[3]), midPoint, SEARCH_CONE_MULTIPLIER)
        val bottomRight = scaleFromOrigin(doubleArrayOf(aabb[2], aabb[3]), midPoint, SEARCH_CONE_MULTIPLIER)

        return when {
            pointInTriangle(point, topLeft, topRight, midPoint) -> Heading.UP
            pointInTriangle(point, topRight, bottomRight, midPoint) -> Heading.RIGHT
            pointInTriangle(point, bottomRight, bottomLeft, midPoint) -> Heading.DOWN
            else -> Heading.LEFT
        }
    }

    /** `heading_for_point_from_bindable`: diamonds defer, the rest use the supplied AABB cone. */
    fun headingForPointFromBindable(point: DoubleArray, bindable: Bindable, aabb: Bounds): Heading {
        if (bindable.shape == Shape.DIAMOND) return headingFromBindable(point, bindable)
        val midPoint = doubleArrayOf(
            (aabb.left + aabb.right) / 2.0,
            (aabb.top + aabb.bottom) / 2.0
        )
        val topLeft = scaleFromOrigin(doubleArrayOf(aabb.left.toDouble(), aabb.top.toDouble()), midPoint, SEARCH_CONE_MULTIPLIER)
        val topRight = scaleFromOrigin(doubleArrayOf(aabb.right.toDouble(), aabb.top.toDouble()), midPoint, SEARCH_CONE_MULTIPLIER)
        val bottomRight = scaleFromOrigin(doubleArrayOf(aabb.right.toDouble(), aabb.bottom.toDouble()), midPoint, SEARCH_CONE_MULTIPLIER)
        val bottomLeft = scaleFromOrigin(doubleArrayOf(aabb.left.toDouble(), aabb.bottom.toDouble()), midPoint, SEARCH_CONE_MULTIPLIER)
        return when {
            pointInTriangle(point, topLeft, topRight, midPoint) -> Heading.UP
            pointInTriangle(point, topRight, bottomRight, midPoint) -> Heading.RIGHT
            pointInTriangle(point, bottomRight, bottomLeft, midPoint) -> Heading.DOWN
            else -> Heading.LEFT
        }
    }

    /** Elbow snap heading: far from the outline the arrow simply points at the other end. */
    fun headingForElbowSnap(
        point: DoubleArray,
        otherPoint: DoubleArray,
        bindable: Bindable?,
        aabb: Bounds?,
        originPoint: DoubleArray? = null,
        zoom: Double = 1.0
    ): Heading {
        val otherPointHeading = vectorToHeading(point, otherPoint)
        if (bindable == null || aabb == null) return otherPointHeading
        val distance = distanceToOutline(originPoint ?: point, bindable)
        val bindDistance = maxBindingDistance(zoom)
        if (distance > bindDistance || distance == 0.0) {
            return vectorToHeading(center(bindable), point)
        }
        return headingForPointFromBindable(point, bindable, aabb)
    }
}
