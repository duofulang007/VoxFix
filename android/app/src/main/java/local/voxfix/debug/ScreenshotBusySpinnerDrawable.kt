package local.voxfix.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 忙碌转圈（用户对照原版确认的样式）：**蓝色圆底 + 白色圆弧旋转**。
 * 只是交互动画，不反映真实进度；配合各按钮的"最短显示时长"使用（见 Action.setBusy）。
 * 弧：270°，起始角 = 90 − 相位；相位 = (nowMs % 1000) × 360 / 1000（原版 spinnerCycleMs 默认 1000）。
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
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            invalidateSelf()
            scheduleSelf(this, 16)
        }
    }

    fun start() {
        if (running) return
        running = true
        ticker.run()
    }

    fun stop() { running = false }

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
    }

    override fun setAlpha(alpha: Int) {
        discPaint.alpha = alpha; arcPaint.alpha = alpha
    }
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val CYCLE_MS = 1000L
        /** 蓝色圆底 = 主题主色 #1677FF；弧为白色。 */
        val DISC = Color.rgb(0x16, 0x77, 0xFF)
    }
}
