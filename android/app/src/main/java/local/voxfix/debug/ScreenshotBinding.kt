package local.voxfix.debug

/**
 * Binding model shared by the T22 geometry, decision and patch ports. Mirrors Snow Shot
 * 1.1.8 (`arrow` crate: `BindMode`, `FixedPointBinding`, `ArrowState`) at
 * `afdc340b14e7f92a294a95c374d13ada0d8c7a82` (Apache-2.0).
 *
 * Points stay in the caller's coordinate space; [originX]/[originY] translate them the way
 * the original's `to_global_point` does, so a stroke stored relative to its own origin needs
 * no conversion at the call site.
 */

/** Where the endpoint sits relative to its bindable (original `BindMode`). */
enum class ScreenshotBindMode { INSIDE, ORBIT, SKIP }

/** Which end of the arrow is being resolved. */
internal enum class ScreenshotEndpointEdge { START, END }

/** A pinned endpoint: an element plus the ratio inside it (original `FixedPointBinding`). */
data class ScreenshotFixedPointBinding(
    val elementId: Long,
    val mode: ScreenshotBindMode,
    val fixedX: Double,
    val fixedY: Double
)

/** The arrow as the binding code sees it; only the fields the port reads. */
internal data class ScreenshotBindingArrow(
    val points: List<ScreenshotDrawingPoint>,
    val originX: Float = 0f,
    val originY: Float = 0f,
    val elbowed: Boolean = false,
    val startBinding: ScreenshotFixedPointBinding? = null,
    val endBinding: ScreenshotFixedPointBinding? = null,
    val hasStartArrowhead: Boolean = true,
    val hasEndArrowhead: Boolean = true,
    /** Arrow box size; leave unset (NaN) to derive it from [points]. */
    val width: Float = Float.NaN,
    val height: Float = Float.NaN
) {
    /** 手机侧差异：原版由 ArrowState 携带宽高，这里允许省略，缺省时按点集跨度推算。 */
    val spanWidth: Double get() = if (width.isFinite()) width.toDouble() else spanOf { it.x }
    val spanHeight: Double get() = if (height.isFinite()) height.toDouble() else spanOf { it.y }

    private fun spanOf(select: (ScreenshotDrawingPoint) -> Float): Double {
        if (points.isEmpty()) return 0.0
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        for (point in points) {
            val value = select(point)
            if (value < low) low = value
            if (value > high) high = value
        }
        return (high - low).toDouble()
    }

    /** Local point at [index]; negative counts from the end. Out of range yields the origin. */
    fun pointAt(index: Int): DoubleArray {
        val resolved = if (index < 0) points.size + index else index
        if (resolved < 0) return doubleArrayOf(0.0, 0.0)
        val point = points.getOrNull(resolved) ?: return doubleArrayOf(0.0, 0.0)
        return doubleArrayOf(point.x.toDouble(), point.y.toDouble())
    }

    fun pointAtGlobal(index: Int): DoubleArray {
        val local = pointAt(index)
        return doubleArrayOf(originX + local[0], originY + local[1])
    }

    fun toLocal(global: DoubleArray): ScreenshotDrawingPoint =
        ScreenshotDrawingPoint((global[0] - originX).toFloat(), (global[1] - originY).toFloat())

    fun bindingFor(edge: ScreenshotEndpointEdge): ScreenshotFixedPointBinding? =
        if (edge == ScreenshotEndpointEdge.START) startBinding else endBinding

    fun otherBindingFor(edge: ScreenshotEndpointEdge): ScreenshotFixedPointBinding? =
        if (edge == ScreenshotEndpointEdge.START) endBinding else startBinding
}
