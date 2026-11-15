package local.voxfix.debug.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * 自绘小块。全部用 Canvas 画，不引第三方库、不加动画：
 * 这些图元只承担「看得懂产品是什么」，不承担交互，所以尺寸固定、状态由入参决定。
 *
 * 命名红线：View 子类里不允许出现名为 `draw` 的成员（会撞 View.draw 造成栈溢出），
 * 绘制入口一律用 [onDraw]。
 */

/** 就绪度环：轨道 + 主色进度弧 + 中心「已完成/总数」。 */
class StatusRing(
    context: Context,
    private val palette: Palette,
    private var done: Int,
    private var total: Int
) : View(context) {

    private val stroke = VoxFixTheme.dp(context, 5.5f).toFloat()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = palette.borderStrong
    }
    private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = palette.primary
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.textPrimary
        textSize = VoxFixTheme.dp(context, 17).toFloat()
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    /** 0..1，进场时从 0 扫到目标比例；不是装饰，改用数值动画表达「刚检查完」。 */
    private var sweep = 0f
    private var animator: ValueAnimator? = null

    fun update(done: Int, total: Int) {
        this.done = done
        this.total = total
        animateTo(if (total > 0) done.toFloat() / total else 0f)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(sweep, target.coerceIn(0f, 1f)).apply {
            duration = 520
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                sweep = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animateTo(if (total > 0) done.toFloat() / total else 0f)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= stroke) return
        val r = (size - stroke) / 2f
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, r, track)
        if (sweep > 0f) {
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * sweep, false, progress)
        }
        val text = if (total > 0) "$done/$total" else "—"
        val baseline = cy - (label.descent() + label.ascent()) / 2f
        canvas.drawText(text, cx, baseline, label)
    }
}

/** 细进度条：轨道 + 主色填充，圆头。数字变化时从当前位置补到新比例。 */
class MeterBar(
    context: Context,
    private val palette: Palette,
    private var fraction: Float
) : View(context) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.surfaceSunken }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.primary }
    private var shown = 0f
    private var animator: ValueAnimator? = null

    fun update(value: Float) {
        fraction = value.coerceIn(0f, 1f)
        animateTo(fraction)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 480
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animateTo(fraction.coerceIn(0f, 1f))
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        if (h <= 0f) return
        val r = h / 2f
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), r, r, track)
        val w = width * shown
        if (w > 0f) canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, fill)
    }
}

/**
 * 键盘缩略图：三行字母键 + 一行空格，空格里点一个麦克风圆点，
 * 用来在首页/输入页一眼说明「这是输入法，长按空格能说话」。
 * 纯示意，不可点击。
 */
class KeyboardPreview(context: Context, private val palette: Palette) : View(context) {

    private val key = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.surfaceSunken }
    private val space = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.primarySoft }
    private val mic = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = VoxFixTheme.dp(context, 1.4f).toFloat()
        strokeCap = Paint.Cap.ROUND
        color = palette.primary
    }

    private val gap = VoxFixTheme.dp(context, 4).toFloat()
    private val radius = VoxFixTheme.dp(context, 4).toFloat()

    override fun onDraw(canvas: Canvas) {
        val rows = intArrayOf(10, 9, 7)
        val rowH = (height - gap * 4) / 4.4f
        var top = 0f
        for (count in rows) {
            val usable = width - gap * (count - 1)
            val keyW = usable / count
            var left = (width - (keyW * count + gap * (count - 1))) / 2f
            for (i in 0 until count) {
                canvas.drawRoundRect(RectF(left, top, left + keyW, top + rowH), radius, radius, key)
                left += keyW + gap
            }
            top += rowH + gap
        }
        // 最后一行：功能键 / 空格 / 回车
        val side = width * 0.14f
        val spaceTop = top
        val spaceBottom = spaceTop + rowH * 1.4f
        canvas.drawRoundRect(RectF(0f, spaceTop, side, spaceBottom), radius, radius, key)
        canvas.drawRoundRect(RectF(width - side, spaceTop, width.toFloat(), spaceBottom), radius, radius, key)
        val spaceLeft = side + gap
        val spaceRight = width - side - gap
        canvas.drawRoundRect(RectF(spaceLeft, spaceTop, spaceRight, spaceBottom), radius, radius, space)

        val cx = (spaceLeft + spaceRight) / 2f
        val cy = (spaceTop + spaceBottom) / 2f
        val bodyW = VoxFixTheme.dp(context, 5).toFloat()
        val bodyH = VoxFixTheme.dp(context, 8).toFloat()
        canvas.drawRoundRect(
            RectF(cx - bodyW / 2, cy - bodyH, cx + bodyW / 2, cy + bodyH / 2),
            bodyW / 2, bodyW / 2, mic
        )
        val cradle = RectF(cx - VoxFixTheme.dp(context, 6).toFloat(), cy - bodyH * 0.55f,
            cx + VoxFixTheme.dp(context, 6).toFloat(), cy + bodyH * 0.9f)
        canvas.drawArc(cradle, 0f, 180f, false, mic)
        canvas.drawLine(cx, cy + bodyH * 0.9f, cx, cy + bodyH * 1.35f, mic)
    }
}

/**
 * 贴边弧线示意：右侧屏幕边缘挂一片缺口弧，屏幕里三条内容占位线。
 * [active] 为 false 时整块转灰，说明「弧线已隐藏」。
 */
class ArcPreview(context: Context, private val palette: Palette, private var active: Boolean) : View(context) {

    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = VoxFixTheme.dp(context, 1.4f).toFloat()
        color = palette.borderStrong
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.surfaceSunken }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = VoxFixTheme.dp(context, 5).toFloat()
        strokeCap = Paint.Cap.ROUND
        color = if (active) palette.primary else palette.textDisabled
    }
    private val inset = VoxFixTheme.dp(context, 6).toFloat()

    fun setActive(value: Boolean) {
        active = value
        handle.color = if (value) palette.primary else palette.textDisabled
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // 右侧留出 18dp 给把手，否则弧线会被卡片裁成半个「C」。
        val handleGutter = VoxFixTheme.dp(context, 18).toFloat()
        val frameRect = RectF(inset, inset, width - inset - handleGutter, height - inset)
        val r = VoxFixTheme.dp(context, 10).toFloat()
        canvas.drawRoundRect(frameRect, r, r, frame)

        // 内容占位：三条不同长度的圆角线，垂直居中，模拟屏内正文。
        val lineH = VoxFixTheme.dp(context, 5).toFloat()
        val left = frameRect.left + VoxFixTheme.dp(context, 12).toFloat()
        val lineSpace = lineH + VoxFixTheme.dp(context, 8).toFloat()
        val widths = floatArrayOf(0.52f, 0.72f, 0.42f)
        var top = frameRect.centerY() - (lineSpace * widths.size - VoxFixTheme.dp(context, 8).toFloat()) / 2f
        for (factor in widths) {
            val available = frameRect.width() - VoxFixTheme.dp(context, 12).toFloat() * 2
            val right = left + available * factor
            canvas.drawRoundRect(RectF(left, top, right, top + lineH), lineH / 2, lineH / 2, line)
            top += lineSpace
        }

        // 屏幕右缘的弧线把手：贴在边框上的短弧 + 一圈很淡的光晕，读起来像「可以拉出来」。
        val centerY = height / 2f
        val arcR = VoxFixTheme.dp(context, 11).toFloat()
        val edge = frameRect.right
        val arcRect = RectF(edge - arcR, centerY - arcR, edge + arcR, centerY + arcR)
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = VoxFixTheme.dp(context, 11).toFloat()
            strokeCap = Paint.Cap.ROUND
            color = handle.color
            alpha = if (active) 38 else 0
        }
        canvas.drawArc(arcRect, 118f, 124f, false, halo)
        canvas.drawArc(arcRect, 118f, 124f, false, handle)
    }
}

/**
 * 自绘开关（44×26dp）。
 *
 * 平台 Switch 的轨道底图本身带半透明，把主色染上去会得到一片浅蓝（真机截图实证），
 * 而且拇指大小、内距与卡片语言都不统一。这里自绘：实色轨道 + 白色拇指 + 170ms 缓动，
 * 无障碍上仍声明成 android.widget.Switch，读屏行为与系统开关一致。
 */
class VxSwitch(
    context: Context,
    private val palette: Palette,
    checked: Boolean,
    enabled: Boolean = true,
    private val onChange: (Boolean) -> Unit
) : View(context) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val thumbEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = VoxFixTheme.dp(context, 0.5f).toFloat()
        color = palette.border
    }
    private val blend = android.animation.ArgbEvaluator()

    private val trackW = VoxFixTheme.dp(context, 44).toFloat()
    private val trackH = VoxFixTheme.dp(context, 26).toFloat()
    private val inset = VoxFixTheme.dp(context, 3).toFloat()
    private val thumbR = (trackH - inset * 2) / 2f

    private var checkedState = checked
    private var pos = if (checked) 1f else 0f
    private var animator: ValueAnimator? = null

    var isChecked: Boolean
        get() = checkedState
        set(value) {
            if (value == checkedState) return
            checkedState = value
            animateTo(if (value) 1f else 0f)
        }

    init {
        isClickable = true
        isFocusable = true
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
        setOnClickListener {
            if (!isEnabled) return@setOnClickListener
            checkedState = !checkedState
            animateTo(if (checkedState) 1f else 0f)
            onChange(checkedState)
        }
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(pos, target).apply {
            duration = 170
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pos = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(trackW.toInt(), trackH.toInt())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pos = if (checkedState) 1f else 0f
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        if (h <= 0f) return
        track.color = blend.evaluate(pos, palette.borderStrong, palette.primary) as Int
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), h / 2, h / 2, track)
        val x = inset + thumbR + pos * (width - inset * 2 - thumbR * 2)
        canvas.drawCircle(x, h / 2f, thumbR, thumb)
        canvas.drawCircle(x, h / 2f, thumbR - thumbEdge.strokeWidth / 2f, thumbEdge)
    }

    override fun onInitializeAccessibilityNodeInfo(
        info: android.view.accessibility.AccessibilityNodeInfo
    ) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = checkedState
        info.isClickable = true
    }
}
