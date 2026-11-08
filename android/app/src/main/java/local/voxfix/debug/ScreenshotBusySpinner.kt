package local.voxfix.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View

/**
 * 忙碌转圈，逐条对齐原版 `AdButton::drawSpinner`（ant_design_qt button.cpp:1943-1952）：
 * - 270° 圆弧，起始角 = 90 − 共享相位；相位 = (nowMs % 1000) × 360 / 1000（`spinnerCycleMs` 默认 1000）；
 * - side = max(8, min(w,h) − 2)（逻辑像素），线宽 = clamp(min(w,h) × 0.08, 1, 2)，圆帽圆角；
 * - 颜色 = 按钮文字色 × 0.72 alpha；原版工具栏文字即主题主色（用户观察到的"蓝色转圈"），手机取主色 #1677FF。
 * 动画用 postInvalidateDelayed(16) 自循环，只在 attached 时转。
 */
internal class ScreenshotBusySpinner(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = COLOR
    }
    private var animating = false

    init { visibility = GONE; contentDescription = "操作进行中" }

    override fun onDraw(canvas: Canvas) {
        val min = minOf(width, height).toFloat()
        if (min <= 0f) return
        val density = resources.displayMetrics.density
        val side = maxOf(8f * density, min - 2f * density)
        val cx = width / 2f; val cy = height / 2f
        val stroke = (min / density * 0.08f).coerceIn(1f, 2f) * density
        paint.strokeWidth = stroke
        val angle = ((SystemClock.uptimeMillis() % CYCLE_MS) * 360 / CYCLE_MS).toInt()
        canvas.drawArc(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f,
            (90 - angle).toFloat(), -270f, false, paint)
        if (animating) postInvalidateDelayed(16)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animating = true
        postInvalidateDelayed(16)
    }

    override fun onDetachedFromWindow() {
        animating = false
        super.onDetachedFromWindow()
    }

    companion object {
        /** `spinnerCycleMs` 默认 1000；颜色 = 主色 #1677FF × 0.72 alpha（原版 busyIndicatorColor）。 */
        const val CYCLE_MS = 1000L
        val COLOR = Color.argb(Math.round(255 * 0.72f), 0x16, 0x77, 0xFF)
    }
}
