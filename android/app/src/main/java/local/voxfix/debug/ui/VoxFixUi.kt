package local.voxfix.debug.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import local.voxfix.debug.Module

/**
 * 外壳组件构造器。全部代码构建 View，不引 AndroidX——
 * 本工程从零到 155 个源文件都没引依赖，壳层不打破这条线。
 *
 * v2（2026-11-15）：顶栏 44/24sp、卡片 18 圆角无描边、行带图标砖、
 * 语义状态走胶囊，页面留白由 16 提到 20。几何依据 docs/App外壳设计-2026-11-15.md。
 */
object VoxFixUi {
    private const val MEDIUM = "sans-serif-medium"

    /** 语义色只在这里定义一次，页面不再自己挑 primary/success 混着用。 */
    enum class Tone { NEUTRAL, PRIMARY, SUCCESS, WARNING, DANGER }

    private fun dp(context: Context, value: Int) = VoxFixTheme.dp(context, value)

    private fun toneInk(palette: Palette, tone: Tone) = when (tone) {
        Tone.NEUTRAL -> palette.textSecondary
        Tone.PRIMARY -> palette.primary
        Tone.SUCCESS -> palette.success
        Tone.WARNING -> palette.warning
        Tone.DANGER -> palette.danger
    }

    private fun toneSoft(palette: Palette, tone: Tone) = when (tone) {
        Tone.NEUTRAL -> palette.surfaceSunken
        Tone.PRIMARY -> palette.primarySoft
        Tone.SUCCESS -> palette.successSoft
        Tone.WARNING -> palette.warningSoft
        Tone.DANGER -> palette.dangerSoft
    }

    /** 圆角水波纹：没有 AndroidX，就用平台 RippleDrawable 自带形态。 */
    private fun ripple(context: Context, radiusDp: Float, mask: Boolean = true): Drawable {
        val maskDrawable = if (mask) VoxFixTheme.round(0xFFFFFFFF.toInt(), VoxFixTheme.dp(context, radiusDp).toFloat()) else null
        return RippleDrawable(
            ColorStateList.valueOf(0x1F2F6EA8), ColorDrawable(0x00000000), maskDrawable
        )
    }

    fun text(
        context: Context,
        value: String,
        sizeSp: Float,
        color: Int,
        medium: Boolean = false
    ): TextView = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (medium) typeface = Typeface.create(MEDIUM, Typeface.NORMAL)
        includeFontPadding = false
    }

    fun icon(context: Context, resId: Int, sizeDp: Int, tint: Int): ImageView =
        ImageView(context).apply {
            setImageResource(resId)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(tint, PorterDuff.Mode.SRC_IN)
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }

    fun spacer(context: Context, widthDp: Int = 0, heightDp: Int = 0): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, widthDp), dp(context, heightDp))
        }

    /**
     * 二级页统一顶栏：返回圆钮 + 标题 +（可选）副标题与右侧文字动作。
     * 首页用 [large] 放大品牌字号；其余页面固定 24sp。
     */
    fun topBar(
        context: Context,
        palette: Palette,
        title: String,
        onBack: (() -> Unit)?,
        action: String? = null,
        onAction: (() -> Unit)? = null,
        subtitle: String? = null,
        large: Boolean = false
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, if (onBack == null) 20 else 8), dp(context, 8), dp(context, 16), dp(context, 10))
        if (onBack != null) {
            val back = icon(context, local.voxfix.debug.R.drawable.ic_vx_back, 20, palette.textSecondary).apply {
                setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
                isClickable = true
                contentDescription = "返回"
                background = ripple(context, 22f)
                setOnClickListener { onBack() }
                pressScale(this)
            }
            addView(back, LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)))
        }
        val titleBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(text(context, title, if (large) 29f else 24f, palette.textPrimary, medium = true).apply {
            if (large) letterSpacing = -0.012f
        })
        if (!subtitle.isNullOrEmpty()) {
            titleBox.addView(text(context, subtitle, 12.5f, palette.textTertiary).apply {
                setPadding(0, dp(context, 3), 0, 0)
            })
        }
        addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(context, if (onBack == null) 0 else 6) })
        if (!action.isNullOrEmpty()) {
            val actionView = text(context, action, 13.5f, palette.primary, medium = true).apply {
                setPadding(dp(context, 12), dp(context, 8), dp(context, 4), dp(context, 8))
                isClickable = onAction != null
                background = ripple(context, 10f)
                setOnClickListener { onAction?.invoke() }
            }
            addView(actionView)
            tag = actionView
        }
    }

    /** 可滚动二级页：顶栏 + ScrollView + 20dp 页列。 */
    fun screen(
        context: Context,
        palette: Palette,
        title: String,
        onBack: (() -> Unit)?,
        action: String? = null,
        onAction: (() -> Unit)? = null,
        subtitle: String? = null,
        content: (LinearLayout) -> Unit
    ): LinearLayout {
        val root = shell(context, palette, title, onBack, action, onAction, subtitle)
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val column = page(context, palette)
        content(column)
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        return root
    }

    /** 不可整页滚动的二级页：顶栏 + 占满剩余高度的 FrameLayout。 */
    fun screenBody(
        context: Context,
        palette: Palette,
        title: String,
        onBack: (() -> Unit)?,
        action: String? = null,
        onAction: (() -> Unit)? = null,
        subtitle: String? = null,
        content: (FrameLayout) -> Unit
    ): LinearLayout {
        val root = shell(context, palette, title, onBack, action, onAction, subtitle)
        val body = FrameLayout(context)
        content(body)
        root.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        return root
    }

    /** 取 [screen]/[screenBody] 顶栏右侧的动作按钮（无动作时为 null）。 */
    fun actionView(root: LinearLayout): TextView? =
        (root.getChildAt(0).tag as? TextView)

    private fun shell(
        context: Context,
        palette: Palette,
        title: String,
        onBack: (() -> Unit)?,
        action: String? = null,
        onAction: (() -> Unit)? = null,
        subtitle: String? = null
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.bg)
            setOnApplyWindowInsetsListener { view, insets ->
                // 刘海/挖孔也要让位，否则横屏或居中挖孔机型标题会被切掉。
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
            addView(topBar(context, palette, title, onBack, action, onAction, subtitle), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

    fun page(context: Context, palette: Palette): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 2), dp(context, 20), dp(context, 32))
            setBackgroundColor(palette.bg)
        }

    /** 卡片：18 圆角、无描边、1dp 抬升；行自带内距，故卡片本身只留 4dp 呼吸。 */
    fun card(context: Context, palette: Palette): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = VoxFixTheme.round(palette.surface, dp(context, 18).toFloat())
            elevation = dp(context, 1).toFloat()
            // 行水波纹是矩形，裁到圆角卡内才不会在卡片四角露出直角。
            clipToOutline = true
            setPadding(0, dp(context, 4), 0, dp(context, 4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 12) }
        }

    /** 卡片标题行：左标题 + 右灰字说明。 */
    fun head(context: Context, palette: Palette, title: String, sub: String? = null): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 8))
            addView(text(context, title, 15.5f, palette.textPrimary, medium = true))
            if (!sub.isNullOrEmpty()) {
                addView(spacer(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(text(context, sub, 12.5f, palette.textTertiary))
            }
        }

    /** 分组标题，可带右侧文字动作。 */
    fun section(
        context: Context,
        palette: Palette,
        title: String,
        action: String? = null,
        onAction: (() -> Unit)? = null
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 4), dp(context, 18), dp(context, 4), dp(context, 10))
        addView(text(context, title, 13f, palette.textTertiary, medium = true).apply {
            letterSpacing = 0.04f
        })
        if (!action.isNullOrEmpty()) {
            addView(spacer(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(text(context, action, 13f, palette.primary).apply {
                setPadding(dp(context, 8), dp(context, 4), 0, dp(context, 4))
                setOnClickListener { onAction?.invoke() }
                isClickable = onAction != null
            })
        }
    }

    private fun divider(context: Context, palette: Palette, insetDp: Int): View =
        View(context).apply {
            setBackgroundColor(palette.border)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { leftMargin = dp(context, insetDp) }
        }

    /** 图标砖：34dp 圆角软底 + 19dp 线性图标，行的视觉锚点。 */
    fun iconTile(
        context: Context,
        palette: Palette,
        resId: Int,
        tone: Tone = Tone.PRIMARY,
        sizeDp: Int = 34,
        iconDp: Int = 19
    ): FrameLayout = FrameLayout(context).apply {
        background = VoxFixTheme.round(toneSoft(palette, tone), dp(context, 11).toFloat())
        addView(icon(context, resId, iconDp, toneInk(palette, tone)), FrameLayout.LayoutParams(
            dp(context, iconDp), dp(context, iconDp), Gravity.CENTER
        ))
        layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
    }

    /** 状态胶囊：软底 + 同色文字，替代「绿字写在行尾」的旧做法。 */
    fun pill(
        context: Context,
        palette: Palette,
        label: String,
        tone: Tone = Tone.NEUTRAL
    ): TextView = text(context, label, 12f, toneInk(palette, tone), medium = true).apply {
        setPadding(dp(context, 9), dp(context, 4), dp(context, 9), dp(context, 4))
        background = VoxFixTheme.round(toneSoft(palette, tone), dp(context, 20).toFloat())
    }

    /**
     * 设置行：图标砖 + 标题 + 可选说明 + 右侧值/胶囊/箭头。
     * [indentDp] 用于父子层级；子项按 16 递增，与父项图标后的文字对齐。
     */
    fun row(
        context: Context,
        palette: Palette,
        title: String,
        desc: String? = null,
        value: String? = null,
        valueColor: Int = palette.textSecondary,
        chevron: Boolean = false,
        indentDp: Int = 0,
        dimmed: Boolean = false,
        divider: Boolean = true,
        iconRes: Int? = null,
        iconTone: Tone = Tone.PRIMARY,
        trailing: View? = null,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (divider) wrap.addView(divider(context, palette, 18 + indentDp))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 18 + indentDp), dp(context, 11), dp(context, 18), dp(context, 11))
            minimumHeight = dp(context, 58)
            if (onClick != null) {
                isClickable = true
                background = ripple(context, 0f, mask = false)
                setOnClickListener { onClick() }
            }
        }

        if (iconRes != null) {
            row.addView(iconTile(context, palette, iconRes, iconTone), LinearLayout.LayoutParams(
                dp(context, 34), dp(context, 34)
            ).apply { rightMargin = dp(context, 13) })
        }

        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(text(context, title, 15.5f, if (dimmed) palette.textTertiary else palette.textPrimary))
        if (!desc.isNullOrEmpty()) {
            body.addView(text(context, desc, 12.5f, palette.textTertiary).apply {
                setPadding(0, dp(context, 3), 0, 0)
            })
        }
        row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (trailing != null) {
            row.addView(trailing, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(context, 10) })
        }
        if (!value.isNullOrEmpty()) {
            row.addView(text(context, value, 13.5f, valueColor).apply {
                setPadding(dp(context, 10), 0, 0, 0)
            })
        }
        if (chevron) {
            // 箭头单独放进 24dp 盒子居中：直接给 ImageView 加左 padding 会把图形压扁并偏到右边。
            val box = FrameLayout(context).apply {
                addView(
                    icon(context, local.voxfix.debug.R.drawable.ic_vx_chevron, 17, palette.textDisabled),
                    FrameLayout.LayoutParams(dp(context, 17), dp(context, 17), Gravity.CENTER)
                )
            }
            row.addView(box, LinearLayout.LayoutParams(dp(context, 24), dp(context, 24))
                .apply { leftMargin = dp(context, 4) })
        }
        wrap.addView(row)
        if (dimmed) wrap.alpha = 0.5f
        return wrap
    }

    /**
     * 设置行 + 右侧开关。[tag] 挂 Switch，供页面在外部状态变化时回写。
     * 禁止页面再反射最后一层子 View 硬塞控件。
     */
    fun switchRow(
        context: Context,
        palette: Palette,
        title: String,
        desc: String? = null,
        checked: Boolean,
        enabled: Boolean = true,
        divider: Boolean = true,
        iconRes: Int? = null,
        iconTone: Tone = Tone.PRIMARY,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        val switch = toggle(context, palette, checked, enabled, onChange).apply {
            contentDescription = title
        }
        val rowView = row(context, palette, title, desc = desc, dimmed = !enabled,
            divider = divider, iconRes = iconRes, iconTone = iconTone, trailing = switch)
        rowView.tag = switch
        return rowView
    }

    /**
     * 就绪步骤行：左侧状态徽标（序号或对勾）+ 文案 + 右侧动作胶囊。
     * 首页三步向导专用，替代「1 · xxx」纯文字编号。
     */
    fun stepRow(
        context: Context,
        palette: Palette,
        index: Int,
        done: Boolean,
        title: String,
        desc: String? = null,
        action: String? = null,
        divider: Boolean = true,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (divider) wrap.addView(divider(context, palette, 18))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 12))
            minimumHeight = dp(context, 60)
            if (onClick != null) {
                isClickable = true
                background = ripple(context, 0f, mask = false)
                setOnClickListener { onClick() }
            }
        }
        val badge = FrameLayout(context).apply {
            background = VoxFixTheme.round(
                if (done) palette.successSoft else palette.surfaceSunken,
                dp(context, 14).toFloat()
            )
            if (done) {
                addView(icon(context, local.voxfix.debug.R.drawable.ic_vx_check, 15, palette.success),
                    FrameLayout.LayoutParams(dp(context, 15), dp(context, 15), Gravity.CENTER))
            } else {
                addView(text(context, "$index", 13f, palette.textTertiary, medium = true),
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
                    ))
            }
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(context, 28), dp(context, 28))
            .apply { rightMargin = dp(context, 19) })

        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(text(context, title, 15.5f, if (done) palette.textSecondary else palette.textPrimary))
        if (!desc.isNullOrEmpty()) {
            body.addView(text(context, desc, 12.5f, palette.textTertiary).apply {
                setPadding(0, dp(context, 3), 0, 0)
            })
        }
        row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!action.isNullOrEmpty()) {
            row.addView(pill(context, palette, action, if (done) Tone.SUCCESS else Tone.PRIMARY),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(context, 10) })
        }
        wrap.addView(row)
        return wrap
    }

    /** 首页/屏幕页的方形快捷入口：图标砖 + 名称，等宽排布由调用方给权重。 */
    fun actionTile(
        context: Context,
        palette: Palette,
        iconRes: Int,
        label: String,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, dp(context, 14), 0, dp(context, 12))
        background = VoxFixTheme.round(palette.surface, dp(context, 16).toFloat())
        elevation = dp(context, 1).toFloat()
        clipToOutline = true
        isClickable = true
        contentDescription = label
        setOnClickListener { onClick() }
        pressScale(this)
        addView(iconTile(context, palette, iconRes, Tone.PRIMARY, 38, 21))
        addView(text(context, label, 12.5f, palette.textSecondary, medium = true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 9) })
    }

    /** 主按钮：48dp、主色填充、白字。整页只允许一个。 */
    fun primaryButton(
        context: Context,
        palette: Palette,
        label: String,
        onClick: () -> Unit
    ): TextView = text(context, label, 15f, palette.onPrimary, medium = true).apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(context, 48)
        background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            VoxFixTheme.round(palette.primary, dp(context, 14).toFloat()),
            VoxFixTheme.round(0xFFFFFFFF.toInt(), dp(context, 14).toFloat())
        )
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** 一次性软底按钮：卡片内的次级动作（检查更新、忽略版本等）。 */
    fun softButton(
        context: Context,
        palette: Palette,
        label: String,
        onClick: () -> Unit
    ): TextView = text(context, label, 13.5f, palette.primary, medium = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(context, 14), dp(context, 9), dp(context, 14), dp(context, 9))
        background = RippleDrawable(
            ColorStateList.valueOf(0x1F2F6EA8),
            VoxFixTheme.round(palette.primarySoft, dp(context, 12).toFloat()),
            VoxFixTheme.round(0xFFFFFFFF.toInt(), dp(context, 12).toFloat())
        )
        isClickable = true
        setOnClickListener { onClick() }
    }

    /**
     * 分段选择：38dp 高，容器 surfaceSunken，选中项白底 + 主色文字。
     */
    class Segmented(
        context: Context,
        private val palette: Palette,
        labels: List<String>,
        selected: Int,
        private val onSelect: (Int) -> Unit
    ) : LinearLayout(context) {
        private val cells = ArrayList<TextView>()
        private val ctx = context
        private var current = selected

        var selected: Int
            get() = current
            set(value) {
                current = value.coerceIn(0, cells.size - 1)
                paint()
            }

        init {
            orientation = HORIZONTAL
            setPadding(p(context, 3), p(context, 3), p(context, 3), p(context, 3))
            background = VoxFixTheme.round(palette.surfaceSunken, p(context, 12).toFloat())
            labels.forEachIndexed { index, label ->
                val cell = VoxFixUi.text(context, label, 13.5f, palette.textSecondary, medium = true).apply {
                    gravity = Gravity.CENTER
                    minimumHeight = p(context, 34)
                    isClickable = true
                    setOnClickListener {
                        if (current != index) {
                            current = index
                            paint()
                            onSelect(index)
                        }
                    }
                }
                addView(cell, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                cells.add(cell)
            }
            paint()
        }

        private fun paint() {
            cells.forEachIndexed { index, cell ->
                val on = index == current
                cell.setTextColor(if (on) palette.primary else palette.textSecondary)
                cell.background = if (on) {
                    VoxFixTheme.round(palette.surface, p(ctx, 9).toFloat(), p(ctx, 1), palette.border)
                } else null
            }
        }

        private fun p(context: Context, value: Int) = VoxFixTheme.dp(context, value)
    }

    /** 文本按钮：13.5sp，最小 40dp 热区；用于顶栏动作与页内低频操作。 */
    fun textButton(
        context: Context,
        palette: Palette,
        label: String,
        color: Int = palette.primary,
        onClick: () -> Unit
    ): TextView = text(context, label, 13.5f, color).apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(context, 40)
        setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
        isClickable = true
        setOnClickListener { onClick() }
    }

    /**
     * 输入框：surfaceSunken 底 + 12dp 圆角 + 1dp 描边，去系统下划线。
     * 深色模式必须同时给出文字与 hint 颜色，否则会继承主题的深色文字。
     */
    fun field(
        context: Context,
        palette: Palette,
        hint: String,
        multiline: Boolean = false
    ): EditText = EditText(context).apply {
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(palette.textPrimary)
        setHintTextColor(palette.textTertiary)
        background = VoxFixTheme.round(
            palette.surfaceSunken, dp(context, 12).toFloat(), dp(context, 1), palette.border
        )
        setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
        includeFontPadding = false
        inputType = if (multiline) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        if (multiline) {
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            setSingleLine(false)
        }
    }

    fun toggle(
        context: Context,
        palette: Palette,
        checked: Boolean,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit
    ): VxSwitch = VxSwitch(context, palette, checked, enabled, onChange)

    /**
     * 模块行：图标砖 + 名称 + 用途 + 开关。
     * [enabled] 为 false 表示祖先模块已关闭——整行转灰、开关禁用。
     */
    fun moduleRow(
        context: Context,
        palette: Palette,
        module: Module,
        selfOn: Boolean,
        enabled: Boolean,
        divider: Boolean = true,
        onToggle: (Boolean) -> Unit
    ): LinearLayout {
        val indent = module.depth * 16
        val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (divider) wrap.addView(divider(context, palette, 18 + indent))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 18 + indent), dp(context, 11), dp(context, 18), dp(context, 11))
            minimumHeight = dp(context, 58)
        }

        row.addView(iconTile(
            context, palette, module.iconRes,
            if (enabled) Tone.PRIMARY else Tone.NEUTRAL
        ), LinearLayout.LayoutParams(dp(context, 34), dp(context, 34))
            .apply { rightMargin = dp(context, 13) })

        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(text(
            context, module.label, 15.5f,
            if (enabled) palette.textPrimary else palette.textTertiary
        ))
        body.addView(text(context, module.summary, 12.5f, palette.textTertiary).apply {
            setPadding(0, dp(context, 3), 0, 0)
        })
        row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = 0 })

        val switch = toggle(context, palette, selfOn, enabled, onToggle).apply {
            contentDescription = module.label
        }
        row.addView(switch)
        wrap.addView(row)
        if (!enabled) wrap.alpha = 0.55f
        // 页面拿到行之后要能回写开关状态，故把 Switch 挂在 tag 上。
        wrap.tag = switch
        return wrap
    }

    fun note(context: Context, palette: Palette, value: String): TextView =
        text(context, value, 12f, palette.textTertiary).apply {
            setLineSpacing(dp(context, 4).toFloat(), 1f)
            setPadding(dp(context, 4), dp(context, 6), dp(context, 4), 0)
        }

    /** 页脚说明行：小图标 + 一句话，替代页尾整段灰字。 */
    fun hintRow(context: Context, palette: Palette, iconRes: Int, value: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(context, 4), dp(context, 10), dp(context, 8), 0)
            addView(icon(context, iconRes, 15, palette.textDisabled), LinearLayout.LayoutParams(
                dp(context, 15), dp(context, 15)
            ).apply { topMargin = dp(context, 2); rightMargin = dp(context, 8) })
            addView(text(context, value, 12f, palette.textTertiary).apply {
                setLineSpacing(dp(context, 3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    /**
     * 按压回弹：按下缩到 0.97，抬手回到 1。只对「整块可点」的小元件用
     * （返回钮、快捷入口砖），长列表行仍交给水波纹，避免整行抖动。
     */
    fun pressScale(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            false
        }
    }

    /** 换页入场：10dp 上浮 + 淡入。页面缓存复用时也会重播，读起来像「切到了新页」。 */
    fun contentEnter(view: View, distanceDp: Int = 10) {
        val context = view.context
        view.alpha = 0f
        view.translationY = dp(context, distanceDp).toFloat()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }
}
