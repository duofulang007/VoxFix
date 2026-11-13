package local.voxfix.debug.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import local.voxfix.debug.Module

/**
 * 外壳组件构造器。全部代码构建 View，不引 AndroidX——
 * 本工程从零到 155 个源文件都没引依赖，壳层不打破这条线。
 *
 * 几何与字号依据 docs/App外壳设计-2026-11-13.md §2/§3。
 */
object VoxFixUi {
    private const val MEDIUM = "sans-serif-medium"

    private fun dp(context: Context, value: Int) = VoxFixTheme.dp(context, value)

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

    fun page(context: Context, palette: Palette): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 28))
            setBackgroundColor(palette.bg)
        }

    fun card(context: Context, palette: Palette): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = VoxFixTheme.round(
                palette.surface, dp(context, 10).toFloat(), dp(context, 1), palette.border
            )
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
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 8))
            addView(text(context, title, 15f, palette.textPrimary, medium = true))
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
        setPadding(dp(context, 2), dp(context, 14), dp(context, 2), dp(context, 8))
        addView(text(context, title, 13.5f, palette.textSecondary, medium = true))
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

    /**
     * 设置行：标题 + 可选说明 + 右侧值/箭头。
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
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (divider) wrap.addView(divider(context, palette, 16 + indentDp))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16 + indentDp), dp(context, 10), dp(context, 16), dp(context, 10))
            minimumHeight = dp(context, 56)
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
        }

        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(text(context, title, 15f, if (dimmed) palette.textTertiary else palette.textPrimary))
        if (!desc.isNullOrEmpty()) {
            body.addView(text(context, desc, 12.5f, palette.textTertiary).apply {
                setPadding(0, dp(context, 2), 0, 0)
            })
        }
        row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (!value.isNullOrEmpty()) {
            row.addView(text(context, value, 14f, valueColor).apply {
                setPadding(dp(context, 8), 0, 0, 0)
            })
        }
        if (chevron) {
            row.addView(icon(context, local.voxfix.debug.R.drawable.ic_vx_chevron, 18, palette.textDisabled).apply {
                setPadding(dp(context, 6), 0, 0, 0)
            })
        }
        wrap.addView(row)
        if (dimmed) wrap.alpha = 0.45f
        return wrap
    }

    /** 首页动作卡：图标 + 标题 + 一句说明；模块关闭时整体转灰并提示去开启。 */
    fun tile(
        context: Context,
        palette: Palette,
        iconRes: Int,
        title: String,
        desc: String,
        locked: Boolean,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        background = VoxFixTheme.round(
            if (locked) palette.surfaceSunken else palette.surface,
            dp(context, 10).toFloat(), dp(context, 1), palette.border
        )
        setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 13))
        minimumHeight = dp(context, 92)
        isClickable = true
        setOnClickListener { onClick() }
        addView(icon(context, iconRes, 22, if (locked) palette.textDisabled else palette.primary))
        addView(spacer(context, 0, 10))
        addView(text(context, title, 15f, if (locked) palette.textTertiary else palette.textPrimary, medium = true))
        addView(text(
            context,
            if (locked) "已关闭 · 去开启" else desc,
            12f,
            if (locked) palette.textDisabled else palette.textTertiary
        ).apply { setPadding(0, dp(context, 2), 0, 0) })
    }

    fun toggle(
        context: Context,
        palette: Palette,
        checked: Boolean,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit
    ): Switch = Switch(context).apply {
        isChecked = checked
        isEnabled = enabled
        showText = false
        thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(palette.primary, palette.textDisabled)
        )
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    /**
     * 模块行：图标 + 名称 + 用途 + 开关。
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
        if (divider) wrap.addView(divider(context, palette, 16 + indent))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16 + indent), dp(context, 10), dp(context, 16), dp(context, 10))
            minimumHeight = dp(context, 56)
        }

        val box = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            background = VoxFixTheme.round(
                if (enabled) palette.primarySoft else palette.textDisabled,
                dp(context, 6).toFloat()
            )
            addView(icon(
                context, module.iconRes, 18,
                if (enabled) palette.primary else palette.textTertiary
            ))
        }
        row.addView(box, LinearLayout.LayoutParams(dp(context, 28), dp(context, 28)))

        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(text(
            context, module.label, 15f,
            if (enabled) palette.textPrimary else palette.textTertiary
        ))
        body.addView(text(context, module.summary, 12.5f, palette.textTertiary).apply {
            setPadding(0, dp(context, 2), 0, 0)
        })
        row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(context, 12) })

        val switch = toggle(context, palette, selfOn, enabled, onToggle)
        row.addView(switch)
        wrap.addView(row)
        if (!enabled) wrap.alpha = 0.55f
        // 页面拿到行之后要能回写开关状态，故把 Switch 挂在 tag 上。
        wrap.tag = switch
        return wrap
    }

    fun note(context: Context, palette: Palette, value: String): TextView =
        text(context, value, 12.5f, palette.textTertiary).apply {
            setLineSpacing(dp(context, 4).toFloat(), 1f)
            setPadding(dp(context, 2), dp(context, 2), dp(context, 2), 0)
        }
}
