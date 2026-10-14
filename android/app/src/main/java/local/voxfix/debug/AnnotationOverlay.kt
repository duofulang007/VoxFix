package local.voxfix.debug

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow

/**
 * 标注里的一个矩形（屏幕坐标）。用自己的类型而不是 RectF，几何逻辑才能进 JVM 单测。
 */
internal data class MarkBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX get() = (left + right) / 2f
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * 标注的几何计算。全部是纯函数、不碰 Android 类型，由 AnnotationGeometryTest 覆盖。
 *
 * 比例来自两张参考图逐像素量出来的结果（都按"字框高"归一，字号变了等比缩放）：
 *   图一：竖线 2px 宽、高＝整行；水滴 21 宽 × 28 高（1.25 倍字框高），上端钝头、下端圆底。
 *   图二：实心米色高亮盖住整行；两端手柄是直径约 41（≈1 倍字框高）的半圆，内缘平齐高亮边、
 *         圆心落在高亮下缘之下 0.58 个直径处。
 */
internal object AnnotationGeometry {
    const val DROP_WIDTH = 0.95f
    const val DROP_HEIGHT = 1.25f
    const val HANDLE_CENTER_DROP = 0.58f
    const val HIGHLIGHT_PAD = 0.15f

    /** 单位长度＝字框高：参考图里竖线等于整行、手柄直径也约等于整行。 */
    fun unit(height: Float, min: Float, max: Float) = height.coerceIn(min, max)

    /**
     * 把选区里每个字的边界框按行合并成高亮块：同一行上下重叠的并成一个，
     * 行内左右相接（窗口 ≤ gap）的也并起来，避免一字一个方块。
     * glyphs 只包含宿主观测窗口内的字符，取不到的字自然被跳过。
     */
    fun runs(start: Int, end: Int, glyphs: Map<Int, MarkBox>, gap: Float = 2f): List<MarkBox> {
        if (start >= end) return emptyList()
        val rows = mutableListOf<MutableList<MarkBox>>()
        for (index in start until end) {
            val box = glyphs[index] ?: continue
            val row = rows.lastOrNull { it.first().let { first -> box.top < first.bottom && box.bottom > first.top } }
            if (row == null) rows.add(mutableListOf(box)) else row.add(box)
        }
        return rows.flatMap { row ->
            val merged = mutableListOf<MarkBox>()
            for (box in row.sortedBy { it.left }) {
                val last = merged.lastOrNull()
                if (last != null && box.left - last.right <= gap) {
                    merged[merged.lastIndex] = MarkBox(last.left, minOf(last.top, box.top), maxOf(last.right, box.right), maxOf(last.bottom, box.bottom))
                } else merged.add(box)
            }
            merged
        }
    }

    /**
     * 拖动水滴时的反查：把手指坐标换成字符偏移。
     * 先要落在某一行（纵向容差 slack），再按水平距离挑最近的字；落在字的右半边取它下一个偏移，
     * 与系统手柄一致。
     */
    fun offsetAt(x: Float, y: Float, glyphs: Map<Int, MarkBox>, slack: Float = 8f): Int? {
        var best: Pair<Int, MarkBox>? = null
        var bestScore = Float.MAX_VALUE
        for ((index, box) in glyphs) {
            val rowDistance = when {
                y < box.top -> box.top - y
                y > box.bottom -> y - box.bottom
                else -> 0f
            }
            if (rowDistance > slack) continue
            val horizontal = when {
                x < box.left -> box.left - x
                x > box.right -> x - box.right
                else -> 0f
            }
            val score = rowDistance * 3f + horizontal
            if (score < bestScore) { bestScore = score; best = index to box }
        }
        val (index, box) = best ?: return null
        return if (x > box.centerX) index + 1 else index
    }
}

/**
 * 「标注」浮层：进入编辑（起点锁定）后宿主自带的选区手柄不再出现，由它补上视觉参照。
 *   未选中 -> 琥珀竖线（1.06s 一亮一暗）+ 下方一枚水滴，标出起点；
 *   已选中 -> 逐行实心米色高亮 + 两端半圆水滴。
 * 数据来自 CursorAnchorInfo 的字符级边界框；两枚水滴各自是一个 48dp 的可触摸小窗，可左右拖动。
 *
 * 窗口都挂在 IME 窗口上（与「来自剪贴板」标签同一套 popup 做法），不占键盘高度：
 *   视觉层铺满键盘上方整片区域、NOT_TOUCHABLE，只负责画；手势层只盖住水滴周围。
 */
internal class AnnotationOverlay(private val host: android.inputmethodservice.InputMethodService) {
    private val main = Handler(Looper.getMainLooper())
    private val density = host.resources.displayMetrics.density
    private val mark = 0xFFFFBB12.toInt()
    private val soft = 0xFFFDF7E1.toInt()

    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mark }
    private val softPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = soft }
    private val path = Path()
    private val temp = RectF()

    private var start = -1
    private var end = -1
    private var caret: MarkBox? = null
    private var caretVisible = false
    private var runs: List<MarkBox> = emptyList()
    private var glyphs: Map<Int, MarkBox> = emptyMap()
    private var boxHeight = 0f
    private var anchorView: View? = null
    private var keyboardHeight = 0
    private var wanted = false
    private var caretOn = true
    private var broken = false

    /** 拖动水滴：(端点 0=较小侧 1=较大侧, 屏幕 x, 屏幕 y, 阶段 0=按下 1=移动 2=抬手)。 */
    var onHandleDrag: ((Int, Float, Float, Int) -> Unit)? = null

    // 注意函数名不能叫 draw：onDraw 里的 draw(canvas) 会解析到 View.draw() 自己，直接无限递归
    // 把输入法进程栈溢出杀掉（2026-10-14 真机事故）。
    private val canvasView = object : View(host) {
        override fun onDraw(canvas: Canvas) = guard("onDraw") { render(canvas) }
    }
    private val visual = PopupWindow(host).apply {
        contentView = canvasView
        width = ViewGroup.LayoutParams.MATCH_PARENT
        height = ViewGroup.LayoutParams.WRAP_CONTENT
        isFocusable = false
        isTouchable = false
        isOutsideTouchable = false
        setBackgroundDrawable(null)
        elevation = 0f
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
    }

    /**
     * 水滴的触摸层：**只负责接触摸，不画东西**——形状由视觉层画，两边都画会叠成两个标记。
     */
    private inner class HandleView(private val which: Int) : View(host) {
        override fun onDraw(canvas: Canvas) = Unit
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { guard("handle-down") { onHandleDrag?.invoke(which, event.rawX, event.rawY, 0) }; return true }
                MotionEvent.ACTION_MOVE -> { guard("handle-move") { onHandleDrag?.invoke(which, event.rawX, event.rawY, 1) }; return true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    guard("handle-up") { onHandleDrag?.invoke(which, event.rawX, event.rawY, 2) }
                    performClick()
                    return true
                }
            }
            return false
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }

    private val handleViews = arrayOf(HandleView(0), HandleView(1))
    private val handleWindows = Array(2) { index ->
        PopupWindow(host).apply {
            contentView = handleViews[index]
            width = (48 * density).toInt()
            height = (48 * density).toInt()
            isFocusable = false
            isTouchable = true
            isOutsideTouchable = false
            setBackgroundDrawable(null)
            elevation = 0f
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
    }

    private val blink = object : Runnable {
        override fun run() {
            caretOn = !caretOn
            canvasView.invalidate()
            if (wanted) main.postDelayed(this, 530)
        }
    }

    /** 浮层出任何异常都只把自己关掉：宁可没有标注，也不能让输入法用不了。 */
    private fun guard(what: String, block: () -> Unit) {
        if (broken) return
        try { block() } catch (error: Throwable) {
            broken = true
            Log.i(TAG, "overlay disabled after $what: $error")
            runCatching { visual.dismiss(); handleWindows.forEach { it.dismiss() } }
        }
    }

    /** 宿主给了新一帧字符几何。 */
    fun update(start: Int, end: Int, caret: MarkBox?, caretVisible: Boolean, glyphs: Map<Int, MarkBox>) = guard("update") {
        this.start = start; this.end = end; this.caret = caret; this.caretVisible = caretVisible
        this.glyphs = glyphs
        this.runs = AnnotationGeometry.runs(start, end, glyphs)
        this.boxHeight = caret?.height
            ?: runs.maxOfOrNull { it.height }
            ?: glyphs.values.maxOfOrNull { it.height }
            ?: 0f
        if (wanted) { ensureShown(); positionHandles() }
        canvasView.invalidate()
    }

    fun show(anchor: View, keyboardHeightPx: Int) = guard("show") {
        anchorView = anchor
        keyboardHeight = keyboardHeightPx
        wanted = true
        if (start >= 0) { ensureShown(); positionHandles() }
    }

    fun hide() = guard("hide") {
        wanted = false
        main.removeCallbacks(blink)
        visual.dismiss()
        handleWindows.forEach { it.dismiss() }
    }

    fun reposition(anchor: View, keyboardHeightPx: Int) = guard("reposition") {
        anchorView = anchor
        keyboardHeight = keyboardHeightPx
        if (wanted && start >= 0) { ensureShown(); positionHandles() }
    }

    fun offsetAt(x: Float, y: Float): Int? = AnnotationGeometry.offsetAt(x, y, glyphs, 10f * density)

    /** 字号变了也等比：单位长度＝字框高，参考图里竖线与手柄直径都等于整行。 */
    private fun unit(): Float = AnnotationGeometry.unit(boxHeight.takeIf { it > 0f } ?: 0f, 18f * density, 34f * density)

    private fun anchorBottom(anchor: View): Float {
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        return (location[1] + anchor.height).toFloat()
    }

    private fun ensureShown() {
        val anchor = anchorView ?: return
        val bottom = anchorBottom(anchor)
        val top = bottom - keyboardHeight
        val height = top.toInt().coerceAtLeast(1)
        if (!visual.isShowing) {
            visual.height = height
            visual.showAsDropDown(anchor, 0, -(keyboardHeight + height))
            main.removeCallbacks(blink)
            caretOn = true
            main.postDelayed(blink, 530)
            Log.i(TAG, "overlay show top=$top height=$height kb=$keyboardHeight boxes=${runs.size}")
        } else if (visual.height != height) {
            visual.update(anchor, 0, -(keyboardHeight + height), ViewGroup.LayoutParams.MATCH_PARENT, height)
        }
    }

    /** 两枚水滴的触摸窗位置：中心对齐画出来的那枚。 */
    private fun positionHandles() {
        val anchor = anchorView ?: return
        val bottom = anchorBottom(anchor)
        val positions = markerCenters()
        for (index in handleViews.indices) {
            val window = handleWindows[index]
            val target = positions.getOrNull(index)
            if (target == null || !wanted) { window.dismiss(); continue }
            val half = 24f * density
            val x = (target.first - half).toInt()
            val y = (target.second - half - bottom).toInt()
            if (!window.isShowing) window.showAsDropDown(anchor, x, y)
            else window.update(anchor, x, y, window.width, window.height)
        }
    }

    /** 画出来的水滴/手柄的中心点（屏幕坐标）。 */
    private fun markerCenters(): List<Pair<Float, Float>> {
        if (start < 0 || end < 0 || start > end) return emptyList()
        val size = unit()
        if (start == end) {
            val box = caret ?: return emptyList()
            val dropHeight = size * AnnotationGeometry.DROP_HEIGHT
            return listOf(box.centerX to (box.bottom + dropHeight * 0.55f))
        }
        val first = runs.firstOrNull() ?: return emptyList()
        val last = runs.lastOrNull() ?: first
        val centerY = first.bottom + size * AnnotationGeometry.HANDLE_CENTER_DROP
        return listOf((first.left - size / 2f) to centerY, (last.right + size / 2f) to centerY)
    }

    private fun render(canvas: Canvas) {
        if (!wanted || start < 0) return
        val size = unit()
        val pad = (size * AnnotationGeometry.HIGHLIGHT_PAD).coerceIn(2f * density, 6f * density)
        for (run in runs) {
            temp.set(run.left, run.top - pad, run.right, run.bottom + pad)
            canvas.drawRoundRect(temp, 2f * density, 2f * density, softPaint)
        }
        if (start == end) {
            val box = caret ?: return
            if (caretVisible && caretOn) {
                temp.set(box.centerX - density, box.top, box.centerX + density, box.bottom)
                canvas.drawRoundRect(temp, density, density, markPaint)
            }
            drawDrop(canvas, box.centerX, box.bottom, size * AnnotationGeometry.DROP_WIDTH / 2f, size * AnnotationGeometry.DROP_HEIGHT)
            return
        }
        markerCenters().forEachIndexed { index, (x, y) ->
            drawHandle(canvas, x, y, size / 2f, index == 0)
        }
    }

    /**
     * 参考图一那枚水滴：上端钝头（约 1/3 宽），往下鼓到最宽、再收成圆底。
     * top 是水滴顶端 y（贴在光标底边），size 是高度。
     */
    private fun drawDrop(canvas: Canvas, cx: Float, top: Float, halfWidth: Float, size: Float) {
        if (size <= 0f || halfWidth <= 0f) return
        val radius = halfWidth.coerceAtLeast(size * 0.2f)
        val centerY = top + size - radius
        path.reset()
        path.moveTo(cx, top)
        path.cubicTo(cx - radius * 0.32f, top + size * 0.30f, cx - radius, centerY - radius * 0.70f, cx - radius, centerY)
        temp.set(cx - radius, centerY - radius, cx + radius, centerY + radius)
        path.arcTo(temp, 180f, 180f, false)
        path.cubicTo(cx + radius, centerY - radius * 0.70f, cx + radius * 0.32f, top + size * 0.30f, cx, top)
        path.close()
        canvas.drawPath(path, markPaint)
    }

    /** 参考图二两端的手柄：外弧朝外、内缘平齐高亮边。 */
    private fun drawHandle(canvas: Canvas, cx: Float, cy: Float, radius: Float, left: Boolean) {
        if (radius <= 0f) return
        path.reset()
        temp.set(cx - radius, cy - radius, cx + radius, cy + radius)
        path.addArc(temp, if (left) 90f else 270f, 180f)
        path.close()
        canvas.drawPath(path, markPaint)
    }

    private companion object { const val TAG = "VoxFixSel" }
}
