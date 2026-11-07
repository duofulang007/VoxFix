package local.voxfix.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/**
 * 二维码／条码识别标记，按上游 `ScreenshotQrMarker` 的形状：24 逻辑像素的圆形按钮、主色填充、
 * 描边 2、白色图标，中心落在识别框四角的**质心**上；只有中心在选区内、且画布可见时才显示。
 * 命中半径 12（上游 `hitButton` 用 144 = 12²）。
 */
class ScreenshotQrMarkers(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1677FF.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * density; color = Color.WHITE
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * density; color = Color.WHITE
    }
    private var radius = 12f * density
    /** 视口坐标下的标记中心。 */
    private var centers: List<PointF> = emptyList()
    /** 点击回调，参数是标记序号（与识别结果同序）。 */
    var onMarker: ((Int) -> Unit)? = null

    init {
        isClickable = true
        contentDescription = "二维码标记"
    }

    fun setMarkers(values: List<PointF>) {
        centers = values
        invalidate()
    }

    fun markerCount(): Int = centers.size

    fun centerAt(index: Int): PointF? = centers.getOrNull(index)

    /** 命中判定与上游一致：到圆心的距离不超过半径。 */
    fun hitIndex(x: Float, y: Float): Int = centers.indexOfFirst { center ->
        val dx = x - center.x; val dy = y - center.y
        dx * dx + dy * dy <= radius * radius
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        radius = 12f * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        centers.forEach { center ->
            canvas.drawCircle(center.x, center.y, radius, fill)
            canvas.drawCircle(center.x, center.y, radius - border.strokeWidth / 2f, border)
            // 上游放的是 14px 的扫码图标；这里用等价的最小几何画一个取景框。
            val arm = radius * 0.42f
            val corner = radius * 0.2f
            val left = center.x - arm; val right = center.x + arm
            val top = center.y - arm; val bottom = center.y + arm
            canvas.drawLine(left, top + corner, left, top, glyph)
            canvas.drawLine(left, top, left + corner, top, glyph)
            canvas.drawLine(right - corner, top, right, top, glyph)
            canvas.drawLine(right, top, right, top + corner, glyph)
            canvas.drawLine(right, bottom - corner, right, bottom, glyph)
            canvas.drawLine(right, bottom, right - corner, bottom, glyph)
            canvas.drawLine(left + corner, bottom, left, bottom, glyph)
            canvas.drawLine(left, bottom, left, bottom - corner, glyph)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (centers.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 只吃标记上的按下：其它位置放给下层（选框、画布继续可用）。
                if (hitIndex(event.x, event.y) < 0) return false
                isPressed = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                val index = hitIndex(event.x, event.y)
                isPressed = false
                if (index >= 0) onMarker?.invoke(index)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { isPressed = false; return true }
        }
        return true
    }

    companion object {
        /** 把识别框换算成视口坐标：先取四角质心，再按控件尺寸缩放。 */
        fun centersFor(hits: List<ScreenshotBarcodeHit>, crop: Rect, width: Int, height: Int,
                       imageWidth: Int, imageHeight: Int): List<PointF> =
            if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) emptyList()
            else hits.mapNotNull { hit ->
                val centerX = crop.left + (hit.box.left + hit.box.right) / 2f
                val centerY = crop.top + (hit.box.top + hit.box.bottom) / 2f
                if (centerX < crop.left || centerX > crop.right || centerY < crop.top || centerY > crop.bottom) null
                else PointF(centerX * width / imageWidth, centerY * height / imageHeight)
            }
    }
}
