package local.voxfix.debug.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import local.voxfix.debug.R

/**
 * 设计令牌。取值见 docs/App外壳设计-2026-11-13.md §2：
 * Snow Shot 1.1.8 的 Ant Design Qt 令牌，深色由 values-night 提供，切换跟随系统。
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
    val danger: Int,
    val success: Int,
    val warning: Int
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
        danger = context.getColor(R.color.vox_danger),
        success = context.getColor(R.color.vox_success),
        warning = context.getColor(R.color.vox_warning)
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
}
