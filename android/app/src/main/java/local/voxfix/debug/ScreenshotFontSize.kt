package local.voxfix.debug

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Snow Shot 1.1.8 font-size contract.
 *
 * `snow_canvas_style_edit.h` clamps text to 6–256 and the serial badge to 6–512, and both are
 * `double` values (`screenshotdefaultstyles.h` starts text at 30 and the badge at 24). The palette
 * readout prints no decimals (`IconNumericValuePreviewButton` keeps `decimalPlaces` at 0), so a size
 * that survived object scaling shows its whole number while a fraction the user typed stays visible.
 */
internal object ScreenshotFontSize {
    const val MIN = 6f
    const val MAX_TEXT = 256f
    const val MAX_SERIAL = 512f
    /** `maximumWatermarkFontSize` in `snow_canvas_style_edit.h`. */
    const val MAX_WATERMARK = 512f
    fun max(tool: ScreenshotDrawingTool?): Float = if (tool == ScreenshotDrawingTool.SERIAL) MAX_SERIAL else MAX_TEXT
    fun clamp(tool: ScreenshotDrawingTool?, value: Float, fallback: Float = 30f): Float {
        val safe = if (value.isFinite()) value else fallback
        return safe.coerceIn(MIN, max(tool))
    }
    /** The editor field rejects instead of silently shrinking, so the hint can name the bound. */
    fun parse(tool: ScreenshotDrawingTool?, text: String): Float? =
        text.trim().toFloatOrNull()?.takeIf { it.isFinite() && it >= MIN && it <= max(tool) }
    fun format(value: Float): String {
        val rounded = value.roundToInt()
        return if (abs(value - rounded) < .05f) rounded.toString() else String.format(Locale.US, "%.1f", value)
    }
}
