package local.voxfix.debug.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import local.voxfix.debug.R

/**
 * 设计令牌 v2（2026-11-15）：与键盘前端同源的中性墨色 + 钢蓝 accent。
 *
 * 旧版本直接用 Snow Shot 的 Ant Design Qt 取值（#1677FF / #F5F5F5 / 1dp 描边白卡），
 * 那是桌面后台管理面板的语言，落在手机上像调试工具；v2 换成
 * 「暖灰画布 + 无描边白卡 + 单一 accent + 低饱和语义色」，并补齐 soft 底供胶囊/图标砖使用。
 */
class Palette(
    val bg: Int,
    val surface: Int,
    val surfaceHigh: Int,
    val surfaceSunken: Int,
    val border: Int,
    val borderStrong: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textTertiary: Int,
    val textDisabled: Int,
    val primary: Int,
    val primaryHover: Int,
    val primarySoft: Int,
    val onPrimary: Int,
    val danger: Int,
    val dangerSoft: Int,
    val success: Int,
    val successSoft: Int,
    val warning: Int,
    val warningSoft: Int
)

object VoxFixTheme {
    fun palette(context: Context) = Palette(
        bg = context.getColor(R.color.vox_bg),
        surface = context.getColor(R.color.vox_surface),
        surfaceHigh = context.getColor(R.color.vox_surface_high),
        surfaceSunken = context.getColor(R.color.vox_surface_sunken),
        border = context.getColor(R.color.vox_border),
        borderStrong = context.getColor(R.color.vox_border_strong),
        textPrimary = context.getColor(R.color.vox_text_primary),
        textSecondary = context.getColor(R.color.vox_text_secondary),
        textTertiary = context.getColor(R.color.vox_text_tertiary),
        textDisabled = context.getColor(R.color.vox_text_disabled),
        primary = context.getColor(R.color.vox_primary),
        primaryHover = context.getColor(R.color.vox_primary_hover),
        primarySoft = context.getColor(R.color.vox_primary_soft),
        onPrimary = context.getColor(R.color.vox_on_primary),
        danger = context.getColor(R.color.vox_danger),
        dangerSoft = context.getColor(R.color.vox_danger_soft),
        success = context.getColor(R.color.vox_success),
        successSoft = context.getColor(R.color.vox_success_soft),
        warning = context.getColor(R.color.vox_warning),
        warningSoft = context.getColor(R.color.vox_warning_soft)
    )

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** 卡片/控件统一走它，保证圆角与描边一致。 */
    fun round(color: Int, radiusPx: Float, strokePx: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusPx
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    /** 渐变底：只给首页 hero 用，起止都落在同名色系里，暗色下同样成立。 */
    fun gradient(start: Int, end: Int, radiusPx: Float) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)
    ).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
    }
}
