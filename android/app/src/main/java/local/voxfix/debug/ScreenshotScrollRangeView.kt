package local.voxfix.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 长截图预览：按原始像素 1:1 显示，不做任何整体缩放。
 *
 * 旧实现把整张长图压进一张 ≤4096px 的位图里再拉伸显示，长图必然糊。这里改为窗口渲染：
 * View 的逻辑尺寸就是成图的真实像素尺寸，onDraw 只向宿主请求当前可见段（带上下余量），
 * 宿主在工作线程调 `ScreenshotStitch.render(start, end)` 出一张 1:1 的段位图，回调 [updateBand]。
 * 滚动时可见段变化超出余量才重渲染，内存与 CPU 都不随总长度增长。
 *
 * 裁边把手、遮罩与无障碍操作沿用原交互；坐标全部基于完整画布范围（trim.extent）。
 */
internal class ScreenshotScrollRangeView(
    context: Context, private val horizontal: Boolean,
    private val trim: ScreenshotScrollTrim, private val crossSize: Int,
    /** 已落盘段落的总行数：view 坐标系是整幅长图，trim 只作用于当前段，位置需加 base。 */
    private val base: Long,
    private val requestBand: (Int, Int) -> Unit, private val changed: () -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint()
    private val image = RectF()
    private var band: Bitmap? = null
    private var bandStart = 0
    private var disposed = false
    private var requestedStart = -1
    private var requestedEnd = -1
    private var draggingHead: Boolean? = null
    private var dragOffset = 0f
    private val gutter = 12 * density
    private val totalExtent get() = base + trim.extent
    private val axisStart get() = if (horizontal) image.left else image.top
    private val axisLength get() = if (horizontal) image.width() else image.height()
    private fun position(source: Int) = axisStart + (base + source).toFloat() * axisLength / totalExtent

    init {
        isFocusable = true
        contentDescription = "长截图裁边，拖动蓝色边线调整范围"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The logical size is the real pixel size of the whole long image; the ScrollView scrolls it.
        val width = if (horizontal) totalExtent.toInt() else crossSize
        val height = if (horizontal) crossSize else totalExtent.toInt()
        setMeasuredDimension(
            resolveSize(width + (2 * gutter).roundToInt(), widthMeasureSpec),
            resolveSize(height + (2 * gutter).roundToInt(), heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (horizontal) image.set(gutter, 0f, gutter + totalExtent, h.toFloat())
        else image.set(0f, gutter, w.toFloat(), gutter + totalExtent)
    }

    fun updateBand(bitmap: Bitmap, start: Int) {
        if (disposed) { bitmap.recycle(); return }
        band?.recycle()
        band = bitmap
        bandStart = start
        invalidate()
    }

    fun dispose() {
        disposed = true
        band?.recycle()
        band = null
    }

    override fun onDraw(canvas: Canvas) {
        fill.color = Color.WHITE
        canvas.drawRect(image, fill)
        val clip = canvas.clipBounds
        val visibleStart = (if (horizontal) clip.left - image.left else clip.top - image.top)
            .toInt().coerceIn(0, totalExtent.toInt())
        val visibleEnd = (if (horizontal) clip.right - image.left else clip.bottom - image.top)
            .toInt().coerceIn(visibleStart, totalExtent.toInt())
        val start = (visibleStart - BAND_PAD).coerceAtLeast(0)
        val end = (visibleEnd + BAND_PAD).coerceAtMost(totalExtent.toInt())
        val band = band
        val covered = band != null && start >= bandStart && end <= bandStart + band.height
        if (!covered && (start != requestedStart || end != requestedEnd)) {
            requestedStart = start; requestedEnd = end
            requestBand(start, end)
        }
        band?.let { cached ->
            val offset = gutter + bandStart
            if (horizontal) {
                canvas.drawBitmap(cached, null,
                    RectF(image.left + bandStart, image.top, image.left + bandStart + cached.width, image.bottom), paint)
            } else {
                canvas.drawBitmap(cached, null,
                    RectF(image.left, offset, image.right, offset + cached.height), paint)
            }
        }
        val head = position(trim.start)
        val tail = position(trim.end)
        paint.color = 0xb3000000.toInt()
        if (horizontal) {
            canvas.drawRect(image.left, image.top, head, image.bottom, paint)
            canvas.drawRect(tail, image.top, image.right, image.bottom, paint)
        } else {
            canvas.drawRect(image.left, image.top, image.right, head, paint)
            canvas.drawRect(image.left, tail, image.right, image.bottom, paint)
        }
        paint.color = 0xff4096ff.toInt()
        paint.strokeWidth = 2 * density
        for (edge in listOf(head, tail)) {
            val half = 18 * density
            val thickness = 4 * density
            if (horizontal) {
                canvas.drawLine(edge, image.top, edge, image.bottom, paint)
                canvas.drawRoundRect(edge-thickness, image.centerY()-half, edge+thickness, image.centerY()+half, thickness, thickness, paint)
            } else {
                canvas.drawLine(image.left, edge, image.right, edge, paint)
                canvas.drawRoundRect(image.centerX()-half, edge-thickness, image.centerX()+half, edge+thickness, thickness, thickness, paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || axisLength <= 0f) return false
        val pointer = if (horizontal) event.x else event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val headDistance = abs(pointer - position(trim.start))
                val tailDistance = abs(pointer - position(trim.end))
                if (minOf(headDistance, tailDistance) > 24 * density) return false
                draggingHead = headDistance <= tailDistance
                dragOffset = pointer - position(if (draggingHead == true) trim.start else trim.end)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val head = draggingHead ?: return false
                val source = ((pointer - dragOffset - axisStart) * trim.extent / axisLength).roundToInt()
                adjust(head, source)
                if (event.actionMasked == MotionEvent.ACTION_UP) draggingHead = null
            }
            MotionEvent.ACTION_CANCEL -> draggingHead = null
        }
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_HEAD_IN, "收进起始边"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_HEAD_OUT, "移回起始边"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_TAIL_IN, "收进结束边"))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_TAIL_OUT, "移回结束边"))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (!isEnabled) return super.performAccessibilityAction(action, arguments)
        val step = (trim.extent / 50).coerceAtLeast(1)
        when (action) {
            ACTION_HEAD_IN -> adjust(true, trim.start + step)
            ACTION_HEAD_OUT -> adjust(true, trim.start - step)
            ACTION_TAIL_IN -> adjust(false, trim.end - step)
            ACTION_TAIL_OUT -> adjust(false, trim.end + step)
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return true
    }

    private fun adjust(head: Boolean, source: Int) {
        if (head) trim.trimHead(source) else trim.trimTail(trim.extent - source)
        invalidate()
        changed()
    }

    private companion object {
        const val ACTION_HEAD_IN = 0x00100001
        const val ACTION_HEAD_OUT = 0x00100002
        const val ACTION_TAIL_IN = 0x00100003
        const val ACTION_TAIL_OUT = 0x00100004
        /** 渲染窗口在可见区两侧各多出这么多源像素，滚动小段不重渲染。 */
        const val BAND_PAD = 1200
    }
}
