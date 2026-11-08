package local.voxfix.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock

/**
 * 忙碌转圈（用户对照原版确认的样式）：**蓝色圆底 + 白色圆弧旋转**。
 * 只是交互动画，不反映真实进度；配合各按钮的"最短显示时长"使用（见 Action.setBusy）。
 * 弧：270°，起始角 = 90 − 相位；相位 = (nowMs % 1000) × 360 / 1000（原版 spinnerCycleMs 默认 1000）。
 *
 * 动画自驱（原版 AdButton 是逐帧订阅驱动，这里等价实现）：`draw()` 末尾按需排下一次失效。
 * 两个坑（2026-11-08 冻结一帧的根因）：
 * - `start()` 常在 `setImageDrawable` **之前**调用，此时 callback 为空，`scheduleSelf` 是 no-op，
 *   循环永远排不上 → 只画一帧就冻结；所以从 `draw()`（attach 后必经）里接续循环。
 * - `scheduleSelf` 的 when 是**绝对 uptimeMillis**（postAtTime 语义），不能当延时传 16，
 *   否则自我抢跑成紧循环；必须传 `uptimeMillis() + FRAME_MS`。
 */
internal class ScreenshotBusySpinnerDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = DISC
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private var running = false
    private var scheduled = false
    private val ticker = Runnable {
        scheduled = false
        if (!running) return@Runnable
        invalidateSelf()
    }

    fun start() {
        running = true
        pump()
    }

    fun stop() { running = false }

    /** 已挂到 View（callback 非空）且未排队时，排下一次帧；未挂上则等首个 draw() 再接续。 */
    private fun pump() {
        if (!running || scheduled || callback == null) return
        scheduled = true
        scheduleSelf(ticker, SystemClock.uptimeMillis() + FRAME_MS)
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        val min = minOf(w, h)
        if (min <= 0f) return
        val cx = w / 2f; val cy = h / 2f
        val radius = min / 2f - density
        canvas.drawCircle(cx, cy, radius, discPaint)
        arcPaint.strokeWidth = 2f * density
        val arcRadius = radius * 0.62f
        val angle = ((SystemClock.uptimeMillis() % CYCLE_MS) * 360 / CYCLE_MS).toInt()
        canvas.drawArc(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius,
            (90 - angle).toFloat(), -270f, false, arcPaint)
        pump()
    }

    override fun setAlpha(alpha: Int) {
        discPaint.alpha = alpha; arcPaint.alpha = alpha
    }
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val CYCLE_MS = 1000L
        /** 16ms 一帧，与原版逐帧订阅等价；scheduleSelf 用绝对时间，须加在 uptimeMillis 上。 */
        const val FRAME_MS = 16L
        /** 蓝色圆底 = 主题主色 #1677FF；弧为白色。 */
        val DISC = Color.rgb(0x16, 0x77, 0xFF)
    }
}
