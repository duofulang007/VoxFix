package local.voxfix.debug

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Outline projection and midpoint snapping for T22, ported from Snow Shot 1.1.8 at
 * `afdc340b14e7f92a294a95c374d13ada0d8c7a82` (Apache-2.0):
 *
 * - `arrow/binding_geometry_outline.rs`  (outline intersection, ray helpers)
 * - `arrow/hit_test_side_midpoint.rs`    (`get_binding_side_mid_point`, sector table)
 * - `arrow/binding_geometry_snap_points.rs` (`snap_to_mid`, mid-point suggestions, diagonal)
 * - `arrow/binding_geometry_projection.rs`  (fixed point, `bind_point_to_outline`, `update_bound_point`)
 *
 * Kept separate from [ScreenshotBindingGeometry] so the hit-test half stays readable.
 */

internal object ScreenshotBindingProjection {

    private const val OFFSET = 0.01
    private const val OFFSET_DIAGONAL = OFFSET * 0.707
    private const val CORNER_RADIUS = 0.01
    private const val DIAMOND_SAMPLE_STEPS = 50.0

    // ---- side table (hit_test_side_midpoint.rs) ---------------------------------

    enum class Side { TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT, TOP_LEFT }

    private data class Sector(val centerAngle: Double, val width: Double, val side: Side)

    // The diagonal sectors are narrow on rectangles (15 degrees) and wide on
    // diamond/ellipse (75 degrees); the two tables are the original's own split.
    private val RECTANGLE_SECTORS = listOf(
        Sector(0.0, 75.0, Side.RIGHT), Sector(45.0, 15.0, Side.BOTTOM_RIGHT),
        Sector(90.0, 75.0, Side.BOTTOM), Sector(135.0, 15.0, Side.BOTTOM_LEFT),
        Sector(180.0, 75.0, Side.LEFT), Sector(225.0, 15.0, Side.TOP_LEFT),
        Sector(270.0, 75.0, Side.TOP), Sector(315.0, 15.0, Side.TOP_RIGHT)
    )

    private val DIAMOND_SECTORS = listOf(
        Sector(0.0, 15.0, Side.RIGHT), Sector(45.0, 75.0, Side.BOTTOM_RIGHT),
        Sector(90.0, 15.0, Side.BOTTOM), Sector(135.0, 75.0, Side.BOTTOM_LEFT),
        Sector(180.0, 15.0, Side.LEFT), Sector(225.0, 75.0, Side.TOP_LEFT),
        Sector(270.0, 15.0, Side.TOP), Sector(315.0, 75.0, Side.TOP_RIGHT)
    )

    private fun normalizeDegrees(degrees: Double) = ((degrees % 360.0) + 360.0) % 360.0

    /** Which side of the element a ratio hits; ties fall back to the nearest sector centre. */
    fun shapeSide(fixedX: Double, fixedY: Double, shape: ScreenshotBindingGeometry.Shape): Side {
        val config = if (shape == ScreenshotBindingGeometry.Shape.RECTANGLE) RECTANGLE_SECTORS else DIAMOND_SECTORS
        val centeredX = fixedX - 0.5
        val centeredY = fixedY - 0.5
        val degrees = normalizeDegrees(Math.toDegrees(atan2(centeredY, centeredX)))
        for (sector in config) {
            val half = sector.width / 2.0
            val start = normalizeDegrees(sector.centerAngle - half)
            val end = normalizeDegrees(sector.centerAngle + half)
            val inRange = if (start <= end) degrees in start..end else degrees >= start || degrees <= end
            if (inRange) return sector.side
        }
        var nearest = config[0]
        var nearestDistance = Double.POSITIVE_INFINITY
        for (sector in config) {
            var distanceToSector = abs(degrees - sector.centerAngle)
            if (distanceToSector > 180.0) distanceToSector = 360.0 - distanceToSector
            if (distanceToSector < nearestDistance) {
                nearestDistance = distanceToSector
                nearest = sector
            }
        }
        return nearest.side
    }

    private fun mid(a: DoubleArray, b: DoubleArray) = doubleArrayOf((a[0] + b[0]) / 2.0, (a[1] + b[1]) / 2.0)

    /** `get_binding_side_mid_point`: a point just outside a side's midpoint of the element. */
    fun sideMidPoint(fixed: DoubleArray, bindable: ScreenshotBindingGeometry.Bindable): DoubleArray {
        val normalized = ScreenshotBindingGeometry.normalizeFixedPoint(fixed[0], fixed[1])
        val side = shapeSide(normalized[0], normalized[1], bindable.shape)
        val around = ScreenshotBindingGeometry.center(bindable)
        val angle = bindable.angle.toDouble()

        if (bindable.shape == ScreenshotBindingGeometry.Shape.DIAMOND) {
            val topX = Math.floor(bindable.width.toDouble()) / 2.0 + 1.0
            val rightY = Math.floor(bindable.height.toDouble()) / 2.0 + 1.0
            val verticalRadius = (topX - 0.0) * 0.01
            val horizontalRadius = (rightY - 0.0) * 0.01

            val top = doubleArrayOf(bindable.x + topX, bindable.y.toDouble())
            val right = doubleArrayOf(bindable.x + bindable.width.toDouble(), bindable.y + rightY)
            val bottom = doubleArrayOf(bindable.x + topX, bindable.y + bindable.height.toDouble())
            val left = doubleArrayOf(bindable.x.toDouble(), bindable.y + rightY)

            val rightCorner = listOf(
                doubleArrayOf(right[0] - verticalRadius, right[1] - horizontalRadius), right, right,
                doubleArrayOf(right[0] - verticalRadius, right[1] + horizontalRadius)
            )
            fun bezier(curve: List<DoubleArray>, t: Double): DoubleArray {
                val oneMinusT = 1.0 - t
                val b0 = oneMinusT * oneMinusT * oneMinusT
                val b1 = 3.0 * oneMinusT * oneMinusT * t
                val b2 = 3.0 * oneMinusT * t * t
                val b3 = t * t * t
                return doubleArrayOf(
                    b0 * curve[0][0] + b1 * curve[1][0] + b2 * curve[2][0] + b3 * curve[3][0],
                    b0 * curve[0][1] + b1 * curve[1][1] + b2 * curve[2][1] + b3 * curve[3][1]
                )
            }
            // The original samples the right-hand bezier for every side; kept as-is so the
            // phone matches the desktop point-for-point.
            fun cornerSample(flatCornerIndex: Int): DoubleArray {
                val i = flatCornerIndex.toDouble()
                val t0 = if (i - 1.0 < 0.0) 0.0 else (i - 1.0) / DIAMOND_SAMPLE_STEPS
                val t1 = i / DIAMOND_SAMPLE_STEPS
                val t2 = (i + 1.0) / DIAMOND_SAMPLE_STEPS
                val p0 = bezier(rightCorner, t0)
                val p1 = bezier(rightCorner, t1)
                val p2 = bezier(rightCorner, t2)
                return doubleArrayOf(
                    p1[0] + ((p2[0] - p0[0]) * 0.5) / 3.0,
                    p1[1] + ((p2[1] - p0[1]) * 0.5) / 3.0
                )
            }

            val result = when (side) {
                Side.LEFT -> cornerSample(2).let { doubleArrayOf(it[0] - OFFSET, it[1]) }
                Side.RIGHT -> cornerSample(0).let { doubleArrayOf(it[0] + OFFSET, it[1]) }
                Side.TOP -> cornerSample(3).let { doubleArrayOf(it[0], it[1] - OFFSET) }
                Side.BOTTOM -> cornerSample(1).let { doubleArrayOf(it[0], it[1] + OFFSET) }
                Side.TOP_RIGHT -> mid(top, right).let { doubleArrayOf(it[0] + OFFSET_DIAGONAL, it[1] - OFFSET_DIAGONAL) }
                Side.BOTTOM_RIGHT -> mid(right, bottom).let { doubleArrayOf(it[0] + OFFSET_DIAGONAL, it[1] + OFFSET_DIAGONAL) }
                Side.BOTTOM_LEFT -> mid(bottom, left).let { doubleArrayOf(it[0] - OFFSET_DIAGONAL, it[1] + OFFSET_DIAGONAL) }
                Side.TOP_LEFT -> mid(left, top).let { doubleArrayOf(it[0] - OFFSET_DIAGONAL, it[1] - OFFSET_DIAGONAL) }
            }
            return ScreenshotBindingGeometry.rotate(result, around, angle)
        }

        if (bindable.shape == ScreenshotBindingGeometry.Shape.ELLIPSE) {
            val centerX = bindable.centerX
            val centerY = bindable.centerY
            val radiusX = bindable.width / 2.0
            val radiusY = bindable.height / 2.0
            val result = when (side) {
                Side.TOP -> doubleArrayOf(centerX, centerY - radiusY - OFFSET)
                Side.RIGHT -> doubleArrayOf(centerX + radiusX + OFFSET, centerY)
                Side.BOTTOM -> doubleArrayOf(centerX, centerY + radiusY + OFFSET)
                Side.LEFT -> doubleArrayOf(centerX - radiusX - OFFSET, centerY)
                Side.TOP_RIGHT -> {
                    val a = -Math.PI / 4.0
                    doubleArrayOf(centerX + radiusX * cos(a) + OFFSET_DIAGONAL, centerY + radiusY * sin(a) - OFFSET_DIAGONAL)
                }
                Side.BOTTOM_RIGHT -> {
                    val a = Math.PI / 4.0
                    doubleArrayOf(centerX + radiusX * cos(a) + OFFSET_DIAGONAL, centerY + radiusY * sin(a) + OFFSET_DIAGONAL)
                }
                Side.BOTTOM_LEFT -> {
                    val a = 3.0 * Math.PI / 4.0
                    doubleArrayOf(centerX + radiusX * cos(a) - OFFSET_DIAGONAL, centerY + radiusY * sin(a) + OFFSET_DIAGONAL)
                }
                Side.TOP_LEFT -> {
                    val a = -3.0 * Math.PI / 4.0
                    doubleArrayOf(centerX + radiusX * cos(a) - OFFSET_DIAGONAL, centerY + radiusY * sin(a) - OFFSET_DIAGONAL)
                }
            }
            return ScreenshotBindingGeometry.rotate(result, around, angle)
        }

        val top = listOf(
            doubleArrayOf(bindable.x + CORNER_RADIUS, bindable.y.toDouble()),
            doubleArrayOf(bindable.x + bindable.width - CORNER_RADIUS, bindable.y.toDouble())
        )
        val right = listOf(
            doubleArrayOf(bindable.x + bindable.width.toDouble(), bindable.y + CORNER_RADIUS),
            doubleArrayOf(bindable.x + bindable.width.toDouble(), bindable.y + bindable.height - CORNER_RADIUS)
        )
        val bottom = listOf(
            doubleArrayOf(bindable.x + CORNER_RADIUS, bindable.y + bindable.height.toDouble()),
            doubleArrayOf(bindable.x + bindable.width - CORNER_RADIUS, bindable.y + bindable.height.toDouble())
        )
        val left = listOf(
            doubleArrayOf(bindable.x.toDouble(), bindable.y + bindable.height - CORNER_RADIUS),
            doubleArrayOf(bindable.x.toDouble(), bindable.y + CORNER_RADIUS)
        )

        val result = when (side) {
            Side.TOP -> mid(top[0], top[1]).let { doubleArrayOf(it[0], it[1] - OFFSET) }
            Side.RIGHT -> mid(right[0], right[1]).let { doubleArrayOf(it[0] + OFFSET, it[1]) }
            Side.BOTTOM -> mid(bottom[0], bottom[1]).let { doubleArrayOf(it[0], it[1] + OFFSET) }
            Side.LEFT -> mid(left[0], left[1]).let { doubleArrayOf(it[0] - OFFSET, it[1]) }
            Side.TOP_LEFT -> mid(left[1], top[0]).let { doubleArrayOf(it[0] - OFFSET_DIAGONAL, it[1] - OFFSET_DIAGONAL) }
            Side.TOP_RIGHT -> mid(top[1], right[0]).let { doubleArrayOf(it[0] + OFFSET_DIAGONAL, it[1] - OFFSET_DIAGONAL) }
            Side.BOTTOM_RIGHT -> mid(right[1], bottom[1]).let { doubleArrayOf(it[0] + OFFSET_DIAGONAL, it[1] + OFFSET_DIAGONAL) }
            Side.BOTTOM_LEFT -> mid(bottom[0], left[0]).let { doubleArrayOf(it[0] - OFFSET_DIAGONAL, it[1] + OFFSET_DIAGONAL) }
        }
        return ScreenshotBindingGeometry.rotate(result, around, angle)
    }

    // ---- outline intersection (binding_geometry_outline.rs) ----------------------

    fun normalizeVector(from: DoubleArray, to: DoubleArray): DoubleArray? {
        val dx = to[0] - from[0]
        val dy = to[1] - from[1]
        val length = hypot(dx, dy)
        return if (length <= 1e-6) null else doubleArrayOf(dx / length, dy / length)
    }

    private fun pointFromVector(origin: DoubleArray, direction: DoubleArray, magnitude: Double): DoubleArray =
        doubleArrayOf(origin[0] + direction[0] * magnitude, origin[1] + direction[1] * magnitude)

    private fun lineIntersection(a1: DoubleArray, a2: DoubleArray, b1: DoubleArray, b2: DoubleArray): DoubleArray? {
        val dxa = a2[0] - a1[0]
        val dya = a2[1] - a1[1]
        val dxb = b2[0] - b1[0]
        val dyb = b2[1] - b1[1]
        val denominator = dxa * dyb - dya * dxb
        if (abs(denominator) < 1e-9) return null
        val dx = b1[0] - a1[0]
        val dy = b1[1] - a1[1]
        val t = (dx * dyb - dy * dxb) / denominator
        val u = (dx * dya - dy * dxa) / denominator
        if (t < 0.0 || t > 1.0 || u < 0.0 || u > 1.0) return null
        return doubleArrayOf(a1[0] + t * dxa, a1[1] + t * dya)
    }

    private fun dedupe(points: List<DoubleArray>): List<DoubleArray> {
        val unique = mutableListOf<DoubleArray>()
        for (point in points) {
            if (unique.any { ScreenshotBindingGeometry.pointsEqual(it, point, 1e-6) }) continue
            unique += point
        }
        return unique
    }

    private fun edges(corners: List<DoubleArray>): List<Pair<DoubleArray, DoubleArray>> =
        corners.indices.map { corners[it] to corners[(it + 1) % corners.size] }

    private fun rectangleIntersections(from: DoubleArray, to: DoubleArray, bindable: ScreenshotBindingGeometry.Bindable, gap: Double) =
        dedupe(
            edges(
                listOf(
                    doubleArrayOf(bindable.x - gap, bindable.y - gap),
                    doubleArrayOf(bindable.x + bindable.width + gap, bindable.y - gap),
                    doubleArrayOf(bindable.x + bindable.width + gap, bindable.y + bindable.height + gap),
                    doubleArrayOf(bindable.x - gap, bindable.y + bindable.height + gap)
                )
            ).mapNotNull { (a, b) -> lineIntersection(from, to, a, b) }
        )

    private fun ellipseIntersections(from: DoubleArray, to: DoubleArray, bindable: ScreenshotBindingGeometry.Bindable, gap: Double): List<DoubleArray> {
        val cx = bindable.centerX
        val cy = bindable.centerY
        val rx = max(bindable.width / 2.0 + gap, 1e-6)
        val ry = max(bindable.height / 2.0 + gap, 1e-6)
        val dx = to[0] - from[0]
        val dy = to[1] - from[1]
        val px = from[0] - cx
        val py = from[1] - cy
        val a = (dx * dx) / (rx * rx) + (dy * dy) / (ry * ry)
        val b = 2.0 * ((px * dx) / (rx * rx) + (py * dy) / (ry * ry))
        val c = (px * px) / (rx * rx) + (py * py) / (ry * ry) - 1.0
        val discriminant = b * b - 4.0 * a * c
        if (discriminant < -1e-9 || abs(a) < 1e-9) return emptyList()
        val sqrtDiscriminant = max(discriminant, 0.0).let { Math.sqrt(it) }
        return dedupe(
            listOf((-b - sqrtDiscriminant) / (2.0 * a), (-b + sqrtDiscriminant) / (2.0 * a))
                .filter { it in 0.0..1.0 }
                .map { doubleArrayOf(from[0] + dx * it, from[1] + dy * it) }
        )
    }

    private fun diamondIntersections(from: DoubleArray, to: DoubleArray, bindable: ScreenshotBindingGeometry.Bindable, gap: Double): List<DoubleArray> {
        val cx = bindable.centerX
        val cy = bindable.centerY
        val vertices = listOf(
            doubleArrayOf(cx, bindable.y - gap),
            doubleArrayOf(bindable.x + bindable.width + gap, cy),
            doubleArrayOf(cx, bindable.y + bindable.height + gap),
            doubleArrayOf(bindable.x - gap, cy)
        )
        return dedupe(edges(vertices).mapNotNull { (a, b) -> lineIntersection(from, to, a, b) })
    }

    /** `intersect_outline`: intersect the ray with the element outline inflated by [gap]. */
    fun intersectOutline(from: DoubleArray, to: DoubleArray, bindable: ScreenshotBindingGeometry.Bindable, gap: Double): List<DoubleArray> {
        val around = ScreenshotBindingGeometry.center(bindable)
        val angle = bindable.angle.toDouble()
        val localFrom = ScreenshotBindingGeometry.unrotate(from, around, angle)
        val localTo = ScreenshotBindingGeometry.unrotate(to, around, angle)
        val local = bindable.copy(angle = 0f)
        val points = when (local.shape) {
            ScreenshotBindingGeometry.Shape.RECTANGLE -> rectangleIntersections(localFrom, localTo, local, gap)
            ScreenshotBindingGeometry.Shape.ELLIPSE -> ellipseIntersections(localFrom, localTo, local, gap)
            ScreenshotBindingGeometry.Shape.DIAMOND -> diamondIntersections(localFrom, localTo, local, gap)
        }
        return points.map { ScreenshotBindingGeometry.rotate(it, around, angle) }
    }

    // ---- corner avoidance and side snapping (binding_geometry_snap_points.rs) ----

    /** Push a pointer that sits past a rectangle corner back onto the closer side. */
    fun avoidRectangularCorner(elbowed: Boolean, bindable: ScreenshotBindingGeometry.Bindable, point: DoubleArray): DoubleArray {
        val around = ScreenshotBindingGeometry.center(bindable)
        val angle = bindable.angle.toDouble()
        val local = ScreenshotBindingGeometry.unrotate(point, around, angle)
        val gap = ScreenshotBindingGeometry.bindingGap(bindable, elbowed)
        val x = bindable.x.toDouble()
        val y = bindable.y.toDouble()
        val right = bindable.x + bindable.width.toDouble()
        val bottom = bindable.y + bindable.height.toDouble()

        if (local[0] < x && local[1] < y) {
            return if (local[1] - y > -gap) {
                ScreenshotBindingGeometry.rotate(doubleArrayOf(x - gap, y), around, angle)
            } else {
                ScreenshotBindingGeometry.rotate(doubleArrayOf(x, y - gap), around, angle)
            }
        }
        if (local[0] < x && local[1] > bottom) {
            return if (local[0] - x > -gap) {
                ScreenshotBindingGeometry.rotate(doubleArrayOf(x, bottom + gap), around, angle)
            } else {
                ScreenshotBindingGeometry.rotate(doubleArrayOf(x - gap, bottom), around, angle)
            }
        }
        if (local[0] > right && local[1] > bottom) {
            return if (local[0] - x < bindable.width + gap) {
                ScreenshotBindingGeometry.rotate(doubleArrayOf(right, bottom + gap), around, angle)
            } else {
                ScreenshotBindingGeometry.rotate(doubleArrayOf(right + gap, bottom), around, angle)
            }
        }
        if (local[0] > right && local[1] < y) {
            return if (local[0] - x < bindable.width + gap) {
                ScreenshotBindingGeometry.rotate(doubleArrayOf(right, y - gap), around, angle)
            } else {
                ScreenshotBindingGeometry.rotate(doubleArrayOf(right + gap, y), around, angle)
            }
        }
        return point
    }

    /** `snap_to_mid`: pull the pointer onto a side midpoint when it is close enough. */
    fun snapToMid(
        bindable: ScreenshotBindingGeometry.Bindable,
        point: DoubleArray,
        tolerance: Double = 0.05,
        bindingGap: Double = 0.0
    ): DoubleArray? {
        val rawCenter = ScreenshotBindingGeometry.center(bindable)
        val center = doubleArrayOf(rawCenter[0] - 0.1, rawCenter[1] - 0.1)
        val local = ScreenshotBindingGeometry.unrotate(point, center, bindable.angle.toDouble())
        val verticalThreshold = ScreenshotBindingGeometry.clamp(tolerance * bindable.height, 5.0, 80.0)
        val horizontalThreshold = ScreenshotBindingGeometry.clamp(tolerance * bindable.width, 5.0, 80.0)
        if (ScreenshotBindingGeometry.distance(center, local) < bindingGap) return null
        val angle = bindable.angle.toDouble()

        if (local[0] <= bindable.x + bindable.width / 2.0 &&
            local[1] > center[1] - verticalThreshold && local[1] < center[1] + verticalThreshold
        ) {
            return ScreenshotBindingGeometry.rotate(doubleArrayOf(bindable.x - bindingGap, center[1]), center, angle)
        }
        if (local[1] <= bindable.y + bindable.height / 2.0 &&
            local[0] > center[0] - horizontalThreshold && local[0] < center[0] + horizontalThreshold
        ) {
            return ScreenshotBindingGeometry.rotate(doubleArrayOf(center[0], bindable.y - bindingGap), center, angle)
        }
        if (local[0] >= bindable.x + bindable.width / 2.0 &&
            local[1] > center[1] - verticalThreshold && local[1] < center[1] + verticalThreshold
        ) {
            return ScreenshotBindingGeometry.rotate(
                doubleArrayOf(bindable.x + bindable.width + bindingGap, center[1]), center, angle
            )
        }
        if (local[1] >= bindable.y + bindable.height / 2.0 &&
            local[0] > center[0] - horizontalThreshold && local[0] < center[0] + horizontalThreshold
        ) {
            return ScreenshotBindingGeometry.rotate(
                doubleArrayOf(center[0], bindable.y + bindable.height + bindingGap), center, angle
            )
        }

        if (bindable.shape == ScreenshotBindingGeometry.Shape.DIAMOND) {
            val offsetThreshold = max(horizontalThreshold, verticalThreshold)
            val qx = bindable.width / 4.0
            val qy = bindable.height / 4.0
            val corners = listOf(
                doubleArrayOf(bindable.x + qx - bindingGap, bindable.y + qy - bindingGap),
                doubleArrayOf(bindable.x + 3.0 * qx + bindingGap, bindable.y + qy - bindingGap),
                doubleArrayOf(bindable.x + qx - bindingGap, bindable.y + 3.0 * qy + bindingGap),
                doubleArrayOf(bindable.x + 3.0 * qx + bindingGap, bindable.y + 3.0 * qy + bindingGap)
            )
            for (corner in corners) {
                if (ScreenshotBindingGeometry.distance(corner, local) < offsetThreshold) {
                    return ScreenshotBindingGeometry.rotate(corner, center, angle)
                }
            }
        }
        return null
    }

    fun snapOutlineMidPointCandidates(bindable: ScreenshotBindingGeometry.Bindable): List<DoubleArray> = listOf(
        sideMidPoint(doubleArrayOf(1.0, 0.5), bindable),
        sideMidPoint(doubleArrayOf(0.5, 1.0), bindable),
        sideMidPoint(doubleArrayOf(0.0, 0.5), bindable),
        sideMidPoint(doubleArrayOf(0.5, 0.0), bindable)
    )

    /** Nearest side midpoint within the binding radius, ignoring pointers inside the element. */
    fun snapOutlineMidPoint(point: DoubleArray, bindable: ScreenshotBindingGeometry.Bindable, zoom: Double): DoubleArray? {
        val threshold = ScreenshotBindingGeometry.maxBindingDistance(zoom) + bindable.strokeWidth / 2.0
        if (ScreenshotBindingGeometry.isPointInBindable(point, bindable)) return null
        return snapOutlineMidPointCandidates(bindable).firstOrNull {
            ScreenshotBindingGeometry.distance(point, it) <= threshold
        }
    }

    /** How midpoint indicators behave; keeps the two booleans from being transposed. */
    enum class OutlineMidPointMode {
        DISABLED, LINEAR, ELBOW;

        companion object {
            fun forArrow(elbowed: Boolean, midpointSnappingEnabled: Boolean) = when {
                !midpointSnappingEnabled -> DISABLED
                elbowed -> ELBOW
                else -> LINEAR
            }
        }
    }

    data class OutlineMidPointSuggestion(val highlighted: DoubleArray?, val near: List<DoubleArray>)

    /** Midpoint dots shown next to a binding highlight (Excalidraw's interactive dots). */
    fun outlineMidPointSuggestion(
        pointer: DoubleArray,
        bindable: ScreenshotBindingGeometry.Bindable,
        zoom: Double,
        mode: OutlineMidPointMode
    ): OutlineMidPointSuggestion {
        val empty = OutlineMidPointSuggestion(null, emptyList())
        if (mode == OutlineMidPointMode.DISABLED) return empty
        val elbowed = mode == OutlineMidPointMode.ELBOW
        if (!elbowed && ScreenshotBindingGeometry.isPointInBindable(pointer, bindable)) return empty

        val threshold = ScreenshotBindingGeometry.maxBindingDistance(zoom) + bindable.strokeWidth / 2.0
        val candidates = snapOutlineMidPointCandidates(bindable)
        val distances = candidates.map { ScreenshotBindingGeometry.distance(pointer, it) }
        val closestIndex = distances.indices.minByOrNull { distances[it] } ?: 0
        val closestDistance = distances[closestIndex]
        val highlighted = if (closestDistance <= threshold) candidates[closestIndex] else null
        val near = when {
            elbowed -> candidates.filterIndexed { index, _ -> highlighted == null || index != closestIndex }
            highlighted == null && closestDistance <= threshold * 2.0 -> listOf(candidates[closestIndex])
            else -> emptyList()
        }
        return OutlineMidPointSuggestion(highlighted, near)
    }

    private fun diagonalGuideSegments(bindable: ScreenshotBindingGeometry.Bindable): List<Pair<DoubleArray, DoubleArray>> {
        val around = ScreenshotBindingGeometry.center(bindable)
        val angle = bindable.angle.toDouble()
        if (bindable.shape == ScreenshotBindingGeometry.Shape.RECTANGLE) {
            val topLeft = ScreenshotBindingGeometry.rotate(doubleArrayOf(bindable.x.toDouble(), bindable.y.toDouble()), around, angle)
            val topRight = ScreenshotBindingGeometry.rotate(doubleArrayOf(bindable.x + bindable.width.toDouble(), bindable.y.toDouble()), around, angle)
            val bottomRight = ScreenshotBindingGeometry.rotate(doubleArrayOf(bindable.x + bindable.width.toDouble(), bindable.y + bindable.height.toDouble()), around, angle)
            val bottomLeft = ScreenshotBindingGeometry.rotate(doubleArrayOf(bindable.x.toDouble(), bindable.y + bindable.height.toDouble()), around, angle)
            return listOf(topLeft to bottomRight, topRight to bottomLeft)
        }
        val topCenter = ScreenshotBindingGeometry.rotate(doubleArrayOf(bindable.x + bindable.width / 2.0, bindable.y.toDouble()), around, angle)
        val bottomCenter = ScreenshotBindingGeometry.rotate(doubleArrayOf(bindable.x + bindable.width / 2.0, bindable.y + bindable.height.toDouble()), around, angle)
        val leftCenter = ScreenshotBindingGeometry.rotate(doubleArrayOf(bindable.x.toDouble(), bindable.y + bindable.height / 2.0), around, angle)
        val rightCenter = ScreenshotBindingGeometry.rotate(doubleArrayOf(bindable.x + bindable.width.toDouble(), bindable.y + bindable.height / 2.0), around, angle)
        return listOf(topCenter to bottomCenter, leftCenter to rightCenter)
    }

    /**
     * Project the pointer onto the element's diagonal so a straight arrow aims at the body
     * rather than at whatever pixel the finger covered.
     */
    fun projectFixedPointOntoDiagonal(
        arrow: ScreenshotBindingArrow,
        point: DoubleArray,
        bindable: ScreenshotBindingGeometry.Bindable,
        edge: ScreenshotEndpointEdge,
        bindables: List<ScreenshotBindingGeometry.Bindable>,
        zoom: Double,
        midpointSnappingEnabled: Boolean
    ): DoubleArray? {
        if (arrow.points.size < 2 || (arrow.spanWidth < 3.0 && arrow.spanHeight < 3.0)) return null

        if (midpointSnappingEnabled) {
            snapOutlineMidPoint(point, bindable, zoom)?.let { return it }
        }

        val guides = diagonalGuideSegments(bindable)
        val byId = bindables.associateBy { it.id }
        var anchor = arrow.pointAtGlobal(if (edge == ScreenshotEndpointEdge.START) 1 else -2)
        if (arrow.points.size == 2) {
            val other = arrow.otherBindingFor(edge)?.let { binding -> byId[binding.elementId]?.let { binding to it } }
            if (other != null) {
                anchor = ScreenshotBindingGeometry.fixedPointToGlobal(other.first.fixedX, other.first.fixedY, other.second)
            }
        }

        val direction = normalizeVector(anchor, point) ?: return null
        val extent = max(
            ScreenshotBindingGeometry.distance(guides[0].first, guides[0].second),
            ScreenshotBindingGeometry.distance(guides[1].first, guides[1].second)
        )
        val rayLength = 2.0 * ScreenshotBindingGeometry.distance(anchor, point) + extent
        val rayPoint = doubleArrayOf(anchor[0] + direction[0] * rayLength, anchor[1] + direction[1] * rayLength)

        val first = lineIntersection(guides[0].first, guides[0].second, rayPoint, anchor)
        val second = lineIntersection(guides[1].first, guides[1].second, rayPoint, anchor)
        val projection = when {
            first != null && second != null ->
                if (ScreenshotBindingGeometry.distance(anchor, first) <= ScreenshotBindingGeometry.distance(anchor, second)) first else second
            first != null -> first
            second != null -> second
            else -> null
        } ?: return null

        return if (ScreenshotBindingGeometry.isPointInBindable(projection, bindable)) projection else null
    }

    // ---- projection (binding_geometry_projection.rs) ----------------------------

    /** Ratio of a global point inside the element, normalised away from the exact centre. */
    fun calculateFixedPointForBinding(bindable: ScreenshotBindingGeometry.Bindable, globalPoint: DoubleArray): DoubleArray {
        val around = ScreenshotBindingGeometry.center(bindable)
        val local = ScreenshotBindingGeometry.unrotate(globalPoint, around, bindable.angle.toDouble())
        val fixedX = (local[0] - bindable.x) / max(bindable.width.toDouble(), 1e-6)
        val fixedY = (local[1] - bindable.y) / max(bindable.height.toDouble(), 1e-6)
        return ScreenshotBindingGeometry.normalizeFixedPoint(fixedX, fixedY)
    }

    /** `bind_point_to_outline`: where the arrow should actually touch the element. */
    fun bindPointToOutline(
        arrow: ScreenshotBindingArrow,
        bindable: ScreenshotBindingGeometry.Bindable,
        edge: ScreenshotEndpointEdge,
        customIntersector: Pair<DoubleArray, DoubleArray>? = null,
        midpointSnappingEnabled: Boolean
    ): DoubleArray {
        val point = arrow.pointAtGlobal(if (edge == ScreenshotEndpointEdge.START) 0 else -1)
        if (arrow.points.size < 2) return point

        val edgePoint = if (bindable.shape == ScreenshotBindingGeometry.Shape.RECTANGLE && arrow.elbowed) {
            avoidRectangularCorner(arrow.elbowed, bindable, point)
        } else {
            point
        }
        val adjacentIndex = if (edge == ScreenshotEndpointEdge.START) 1 else -2
        val adjacentPoint = if (customIntersector != null && !arrow.elbowed) {
            customIntersector.second
        } else {
            arrow.pointAtGlobal(adjacentIndex)
        }
        val gap = ScreenshotBindingGeometry.bindingGap(bindable, arrow.elbowed)
        val center = ScreenshotBindingGeometry.center(bindable)

        val intersection: DoubleArray? = if (arrow.elbowed) {
            val heading = ScreenshotBindingGeometry.headingForPointFromBindable(
                point, bindable, ScreenshotBindingGeometry.rotatedBounds(bindable)
            )
            val isHorizontal = heading == ScreenshotBindingGeometry.Heading.LEFT ||
                heading == ScreenshotBindingGeometry.Heading.RIGHT
            val resolved = if (midpointSnappingEnabled) {
                snapToMid(bindable, edgePoint, 0.05, gap) ?: point
            } else {
                point
            }
            val otherPoint = if (isHorizontal) {
                doubleArrayOf(center[0], resolved[1])
            } else {
                doubleArrayOf(resolved[0], center[1])
            }
            val intersector = customIntersector ?: run {
                val direction = normalizeVector(otherPoint, resolved) ?: doubleArrayOf(0.0, 0.0)
                otherPoint to pointFromVector(otherPoint, direction, max(bindable.width, bindable.height) * 2.0)
            }
            intersectOutline(intersector.first, intersector.second, bindable, gap)
                .sortedBy { ScreenshotBindingGeometry.distance(point, it) }
                .firstOrNull()
                ?: run {
                    val anotherPoint = if (isHorizontal) {
                        doubleArrayOf(resolved[0], center[1])
                    } else {
                        doubleArrayOf(center[0], resolved[1])
                    }
                    val direction = normalizeVector(anotherPoint, resolved) ?: doubleArrayOf(0.0, 0.0)
                    val rayEnd = pointFromVector(anotherPoint, direction, max(bindable.width, bindable.height) * 2.0)
                    intersectOutline(anotherPoint, rayEnd, bindable, ScreenshotBindingGeometry.BASE_BINDING_GAP_ELBOW)
                        .sortedBy { ScreenshotBindingGeometry.distance(point, it) }
                        .firstOrNull()
                }
        } else {
            var intersector = customIntersector
            if (intersector == null) {
                val direction = normalizeVector(edgePoint, adjacentPoint)
                if (direction != null) {
                    val halfVector = pointFromVector(
                        doubleArrayOf(0.0, 0.0), direction,
                        ScreenshotBindingGeometry.distance(edgePoint, adjacentPoint) +
                            max(bindable.width, bindable.height) + gap * 2.0
                    )
                    intersector = pointFromVector(adjacentPoint, halfVector, 1.0) to
                        pointFromVector(adjacentPoint, doubleArrayOf(-halfVector[0], -halfVector[1]), 1.0)
                }
            }
            when {
                ScreenshotBindingGeometry.distance(edgePoint, adjacentPoint) < 1.0 -> edgePoint
                intersector != null -> intersectOutline(intersector.first, intersector.second, bindable, gap)
                    .sortedBy { ScreenshotBindingGeometry.distance(it, adjacentPoint) }
                    .firstOrNull()
                else -> null
            }
        }

        return if (intersection != null &&
            ScreenshotBindingGeometry.distance(edgePoint, intersection) * ScreenshotBindingGeometry.distance(edgePoint, intersection) >= 1e-4
        ) {
            intersection
        } else {
            edgePoint
        }
    }

    fun calculateFixedPointForElbowBinding(
        arrow: ScreenshotBindingArrow,
        bindable: ScreenshotBindingGeometry.Bindable,
        edge: ScreenshotEndpointEdge,
        midpointSnappingEnabled: Boolean
    ): DoubleArray = calculateFixedPointForBinding(
        bindable, bindPointToOutline(arrow, bindable, edge, null, midpointSnappingEnabled)
    )

    /**
     * `update_bound_point`: the endpoint position a binding implies, in arrow-local space.
     * Returns null when the caller should keep the free point.
     */
    fun updateBoundPoint(
        arrow: ScreenshotBindingArrow,
        edge: ScreenshotEndpointEdge,
        binding: ScreenshotFixedPointBinding?,
        bindable: ScreenshotBindingGeometry.Bindable,
        bindablesById: Map<Long, ScreenshotBindingGeometry.Bindable>,
        dragging: Boolean
    ): DoubleArray? {
        val active = binding ?: return null
        val last = arrow.points.lastOrNull()?.let { doubleArrayOf(it.x.toDouble(), it.y.toDouble()) }
            ?: doubleArrayOf(0.0, 0.0)
        if ((active.elementId != bindable.id && arrow.points.size > 2) ||
            arrow.points.size < 2 ||
            ScreenshotBindingGeometry.pointsEqual(last, doubleArrayOf(0.0, 0.0), 0.0)
        ) {
            return null
        }

        val focusPoint = ScreenshotBindingGeometry.fixedPointToGlobal(active.fixedX, active.fixedY, bindable)
        if (active.mode == ScreenshotBindMode.INSIDE) return arrow.toLocalArray(focusPoint)

        val otherBinding = arrow.otherBindingFor(edge)
        val otherPoint = arrow.pointAtGlobal(if (edge == ScreenshotEndpointEdge.START) 1 else -2)
        val otherBindable = otherBinding?.let { bindablesById[it.elementId] }
        val otherFocus = if (otherBinding != null && otherBindable != null) {
            ScreenshotBindingGeometry.fixedPointToGlobal(otherBinding.fixedX, otherBinding.fixedY, otherBindable)
        } else {
            null
        }
        val otherFocusOrArrowPoint = if (arrow.points.size == 2) otherFocus ?: otherPoint else otherPoint

        val otherOutline = if (otherBindable != null) {
            intersectOutline(
                focusPoint, otherFocusOrArrowPoint, otherBindable,
                ScreenshotBindingGeometry.bindingGap(otherBindable, arrow.elbowed)
            ).sortedBy { ScreenshotBindingGeometry.distance(it, focusPoint) }.firstOrNull()
        } else {
            null
        }

        val outline = intersectOutline(
            focusPoint, otherFocusOrArrowPoint, bindable,
            ScreenshotBindingGeometry.bindingGap(bindable, arrow.elbowed)
        ).sortedBy { ScreenshotBindingGeometry.distance(it, otherFocusOrArrowPoint) }.firstOrNull()

        val currentHasArrowhead =
            if (edge == ScreenshotEndpointEdge.START) arrow.hasStartArrowhead else arrow.hasEndArrowhead
        val resolvedTarget = if ((!arrow.hasStartArrowhead && !arrow.hasEndArrowhead) || currentHasArrowhead) {
            focusPoint
        } else {
            outline ?: focusPoint
        }

        if (otherBindable != null && outline != null && !dragging &&
            otherBindable.width * otherBindable.height < bindable.width * bindable.height * 2.0 &&
            (ScreenshotBindingGeometry.isPointInBindable(outline, otherBindable) ||
                ScreenshotBindingGeometry.distanceToOutline(outline, otherBindable) <=
                ScreenshotBindingGeometry.bindingGap(otherBindable, arrow.elbowed))
        ) {
            return arrow.toLocalArray(resolvedTarget)
        }

        val otherTargetPoint = if (otherBindable != null) {
            otherOutline ?: otherFocus ?: otherPoint
        } else {
            otherPoint
        }
        val targetPoint = outline ?: focusPoint
        val tooShort = ScreenshotBindingGeometry.distance(otherTargetPoint, targetPoint) <=
            ScreenshotBindingGeometry.BASE_ARROW_MIN_LENGTH

        if (otherBindable == null) {
            return arrow.toLocalArray(if (tooShort) focusPoint else targetPoint)
        }
        if (tooShort) return arrow.toLocalArray(resolvedTarget)
        return arrow.toLocalArray(targetPoint)
    }

    private fun ScreenshotBindingArrow.toLocalArray(global: DoubleArray) =
        doubleArrayOf(global[0] - originX, global[1] - originY)
}
