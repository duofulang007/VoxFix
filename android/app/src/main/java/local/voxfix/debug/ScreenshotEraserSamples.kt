package local.voxfix.debug

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * 橡皮擦线段采样，逐行移植上游 `snow_draw_engine_qt/crates/snow-draw-engine-editor/src/interaction/eraser_workflow.rs`
 * 的 `eraser_segment_samples`。
 *
 * 上游规则（固定提交 `afdc340b14e7f92a294a95c374d13ada0d8c7a82`）：
 * - `tolerance` 非有限或非正时按 0 处理；
 * - `tolerance == 0` 或首尾重合（`distance <= f64::EPSILON`）时不插值，仅在 `includeStart` 时给出起点、
 *   在两点确实不同时给出终点；
 * - 否则 `count = ceil(distance / (tolerance * 0.5))`，最少 1 段，从 `includeStart ? 0 : 1` 起逐点线性插值。
 *
 * 调用方传的是**视野半径**（上游 `ERASER_RADIUS_VIEW = 8.0` 除以缩放），所以实际采样步长是半径的一半，
 * 与 T12 已验收的「半半径采样」一致。纯几何，JVM 可测；不触碰位图与历史。
 */
internal object ScreenshotEraserSamples {
    /** 判等用的最小距离：Float 域的 `f64::EPSILON` 等价物。 */
    private const val EPSILON = 1.1920929E-7f

    fun segment(
        start: ScreenshotDrawingPoint,
        end: ScreenshotDrawingPoint,
        tolerance: Float,
        includeStart: Boolean = false,
    ): List<ScreenshotDrawingPoint> {
        val radius = if (tolerance.isFinite() && tolerance > 0f) tolerance else 0f
        val dx = end.x - start.x
        val dy = end.y - start.y
        val distance = hypot(dx, dy)
        if (!distance.isFinite()) {
            return if (includeStart) listOf(start) else emptyList()
        }
        if (radius == 0f || distance <= EPSILON) {
            val points = ArrayList<ScreenshotDrawingPoint>(2)
            if (includeStart) points.add(start)
            if (distance > EPSILON) points.add(end)
            return points
        }
        val count = max(ceil(distance / (radius * 0.5f)), 1f).toInt()
        val first = if (includeStart) 0 else 1
        return (first..count).map { index ->
            val t = index.toFloat() / count.toFloat()
            ScreenshotDrawingPoint(start.x + dx * t, start.y + dy * t)
        }
    }
}
