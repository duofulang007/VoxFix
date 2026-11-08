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
 * 忙碌转圈 Drawable，逐条对齐原版 `AdButton::drawSpinner`（ant_design_qt button.cpp:1943-1952）：
 * - 270° 圆弧，起始角 = 90 − 共享相位；相位 = (nowMs % spinnerCycleMs) × 360 / 1000（默认周期 1000ms）；
 * - side = max(8, min(w,h) − 2)（逻辑像素），线宽 = clamp(min(w,h) × 0.08, 1, 2)，圆帽圆角；
 * - 颜色 = 按钮文字色 × 0.72 alpha；原版工具栏文字即主题主色（用户观察到的"蓝色转圈"）。
 * 作为按钮图标使用（原版语义：busy 时图标被转圈替代），scheduleSelf 循环重绘。
 */
internal class ScreenshotBusySpinnerDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = COLOR
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
        val side = maxOf(8f * density, min - 2f * density)
        val stroke = (min / density * 0.08f).coerceIn(1f, 2f) * density
        paint.strokeWidth = stroke
        val cx = w / 2f; val cy = h / 2f
        val angle = ((SystemClock.uptimeMillis() % CYCLE_MS) * 360 / CYCLE_MS).toInt()
        canvas.drawArc(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f,
            (90 - angle).toFloat(), -270f, false, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val CYCLE_MS = 1000L
        /** 颜色 = 主色 #1677FF × 0.72 alpha（原版 busyIndicatorColor = 文字色 × 0.72）。 */
        val COLOR = Color.argb(Math.round(255 * 0.72f), 0x16, 0x77, 0xFF)
    }
}
