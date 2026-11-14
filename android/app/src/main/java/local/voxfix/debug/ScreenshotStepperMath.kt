package local.voxfix.debug

/**
 * 长按连续步进的节拍表（纯函数，JVM 可测）。
 *
 * 原版靠鼠标滚轮逐格调值，触屏没有滚轮。这里用「按下即一步 + 按住连续」替代：
 * 400ms 后开始重复（早于系统 500ms 长按时长，手上有反馈就不会误以为是长按手势），
 * 每 80ms 一步（与项目已定的删除键连删曲线同一手感量级），并按按住时长加速。
 */
internal object ScreenshotStepperMath {
    const val HOLD_DELAY_MS = 400L
    const val REPEAT_MS = 80L

    /** 第 [tick] 次重复时一次走几格。 */
    fun stepForTick(tick: Int): Int = when {
        tick < 12 -> 1
        tick < 30 -> 2
        else -> 4
    }

    fun clamp(value: Float, min: Float, max: Float): Float =
        if (min > max) min else value.coerceIn(min, max)

    fun clamp(value: Int, min: Int, max: Int): Int =
        if (min > max) min else value.coerceIn(min, max)

    /** 从 [from] 到 [to] 需要多少次单步（用于估算手感）。 */
    fun singleSteps(from: Float, to: Float, step: Float): Int =
        if (step <= 0f) 0 else (kotlin.math.abs(to - from) / step).toInt()
}
