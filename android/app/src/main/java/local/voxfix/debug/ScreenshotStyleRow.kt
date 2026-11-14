package local.voxfix.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 二级工具栏的唯一形态：**一条横向属性行**。
 *
 * 对齐 Snow Shot 1.1.8 `screenshottoolpalette.cpp:196-211`：28dp 格 / 4dp 格间 / 组间 8dp /
 * 行高 40dp。原版把参数细节放在弹层里（ColorEditor / StrokeEditor / FillEditor /
 * IconOptionEditor），手机端改造前把那些弹层内容全平铺成整宽 44dp 行，TEXT 一项就有 11 行
 * ≈484dp，必须纵向滚动才够得到圆角 —— 这是「面板又大又复杂」的根因。
 *
 * 手机侧扩展：行尾有渐隐 + 箭头，提示「还能往右滑」。没有这个提示，横向滚动会把
 * 「纵向找不到」换成「横向找不到」。
 */
internal open class ScreenshotStyleRow(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    protected fun dp(n: Int) = (n * density).toInt()

    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
            dp(ScreenshotToolbarMetrics.STYLE_PAD_H), dp(ScreenshotToolbarMetrics.STYLE_PAD_V),
            dp(ScreenshotToolbarMetrics.STYLE_PAD_H), dp(ScreenshotToolbarMetrics.STYLE_PAD_V))
    }
    private val scroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(row)
    }
    private val fade = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
            Color.TRANSPARENT, ScreenshotUiTokens.PANEL)).apply { cornerRadius = 0f }
        isClickable = false
        visibility = View.GONE
        // 只在「还能往右」时出现，滚到头自动消失：比定时消失更准，也不会误判。
        alpha = .92f
    }
    private val hint = ImageView(context).apply {
        setImageResource(R.drawable.snow_chevron_down)
        rotation = -90f
        setColorFilter(ScreenshotUiTokens.TEXT, PorterDuff.Mode.SRC_IN)
        isClickable = false
        visibility = View.GONE
    }
    private var afterBreak = false

    init {
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, dp(ScreenshotToolbarMetrics.STYLE_ROW)))
        addView(fade, LayoutParams(dp(28), dp(ScreenshotToolbarMetrics.STYLE_ROW), Gravity.RIGHT))
        addView(hint, LayoutParams(dp(14), dp(14), Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(4) })
        scroller.setOnScrollChangeListener { _, _, _, _, _ -> syncHint() }
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncHint() }
        // 28dp 格热区扩到 48dp：改造前每个面板的 group() 都有这行，单行化时一度丢失。
        ScreenshotTouchTargets.install(row)
    }

    private fun syncHint() {
        val more = scroller.canScrollHorizontally(1)
        val show = if (more) View.VISIBLE else View.GONE
        if (fade.visibility != show) { fade.visibility = show; hint.visibility = show }
    }

    /** 重建列表后要恢复的横向位置（`refresh()` 会 removeAllViews，不保存就跳回行首）。 */
    fun currentScroll(): Int = scroller.scrollX
    /** 必须等布局完成再滚：`post{scrollTo}` 会在 extent=0 时把目标夹成 0，位置静默丢失。 */
    fun restoreScroll(x: Int) { ScreenshotScrollMemory.applyAfterLayout(scroller,x,horizontal=true) }

    /**
     * 28dp 自绘格。选中态沿用二级栏既有语义：20% 主色底 + 1dp 主色描边（原版是浅主色底、
     * 无描边，手机补描边以便在深色面板上分辨边界）。
     */
    fun cell(label: String, selected: Boolean = false, mixed: Boolean = false,
             draw: (Canvas, Paint) -> Unit, run: (View) -> Unit): View = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            canvas.save(); canvas.scale(density, density); draw(canvas, paint)
            if (mixed) ScreenshotMixedStyleMark.draw(canvas, paint)
            canvas.restore()
        }
    }.apply {
        contentDescription = label; tooltipText = label
        stateDescription = if (mixed) "Mixed" else null
        isFocusable = true; isClickable = true; isSelected = selected
        background = cellSurface(selected)
        setOnClickListener { run(it) }
    }

    /** 带矢量图标的格（形状种类这类原版用图标表达的选项）。 */
    fun iconCell(icon: Int, label: String, selected: Boolean, run: (View) -> Unit): View =
        object : View(context) {
            private val artwork = context.getDrawable(icon)?.mutate()
            override fun onDraw(canvas: Canvas) {
                val value = artwork ?: return
                value.setTint(if (selected) ScreenshotUiTokens.PRIMARY else Color.WHITE)
                value.setBounds(dp(2), dp(2), width - dp(2), height - dp(2))
                value.draw(canvas)
            }
        }.apply {
            contentDescription = label; tooltipText = label
            isFocusable = true; isClickable = true; isSelected = selected
            background = cellSurface(selected)
            setOnClickListener { run(it) }
        }

    /** 文字格：宽度按内容自适应，高度与 28dp 格一致。 */
    fun textCell(text: String, selected: Boolean = false, description: String = text,
                 run: (() -> Unit)? = null): TextView = TextView(context).apply {
        this.text = text; textSize = 12f; gravity = Gravity.CENTER
        contentDescription = description; tooltipText = description
        setTextColor(if (selected) ScreenshotUiTokens.TEXT_ACCENT else ScreenshotUiTokens.TEXT)
        setPadding(dp(7), 0, dp(7), 0); minWidth = dp(ScreenshotToolbarMetrics.STYLE_CELL)
        background = cellSurface(selected)
        isFocusable = true
        if (run != null) { isClickable = true; setOnClickListener { run() } }
    }

    fun addCell(view: View, width: Int = ScreenshotToolbarMetrics.STYLE_CELL): View {
        val params = LinearLayout.LayoutParams(dp(width), dp(ScreenshotToolbarMetrics.STYLE_CELL))
        if (row.childCount > 0 && !afterBreak) params.marginStart = dp(ScreenshotToolbarMetrics.STYLE_GAP)
        afterBreak = false
        row.addView(view, params)
        return view
    }

    /** 组间分隔：8dp 空白 + 1dp 竖线 + 8dp 空白。 */
    fun groupBreak() {
        row.addView(View(context).apply { setBackgroundColor(ScreenshotUiTokens.SEPARATOR) },
            LinearLayout.LayoutParams(dp(ScreenshotToolbarMetrics.SEPARATOR_WIDTH), dp(ScreenshotToolbarMetrics.SEPARATOR_HEIGHT)).apply {
                marginStart = dp(ScreenshotToolbarMetrics.STYLE_GROUP_GAP)
                marginEnd = dp(ScreenshotToolbarMetrics.STYLE_GROUP_GAP)
            })
        afterBreak = true
    }

    private fun cellSurface(selected: Boolean) = GradientDrawable().apply {
        setColor(if (selected) ScreenshotUiTokens.TONAL_CELL else Color.TRANSPARENT)
        cornerRadius = dp(ScreenshotToolbarMetrics.STYLE_CELL_RADIUS).toFloat()
        setStroke(dp(1).coerceAtLeast(1), if (selected) ScreenshotUiTokens.PRIMARY else ScreenshotUiTokens.OUTLINE_IDLE_CELL)
    }
}
