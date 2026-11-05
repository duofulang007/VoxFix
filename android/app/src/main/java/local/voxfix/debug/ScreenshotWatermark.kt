package local.voxfix.debug

import android.graphics.*
import kotlin.math.hypot
import kotlin.math.max

/**
 * Watermark configuration, matching Snow Shot 1.1.8 `document.rs:WatermarkConfig`:
 * black text at 16px, 30° rotation, 56px gap and 0.16 opacity by default, clamped to
 * 10–200 gap, -90…90 angle, 0…1 opacity and 6–512 font size (the watermark bound, not the
 * text tool's 256). Effective alpha is colour alpha × opacity; nothing is painted below
 * 0.004 alpha or with empty text.
 *
 * The tile layout ports `snow_canvas_watermark_renderer.cpp`: steps are the shaped text's
 * **ink bounds** plus the gap (`horizontalStep = inkWidth + gap`, `verticalStep = inkHeight + gap`),
 * the grid is anchored at the anchor-area centre in the rotated frame (row index × verticalStep,
 * odd rows shifted half a horizontal step, parity taken on the signed index like C++ `row & 1`),
 * and every unit's ink top-left sits on its grid point pulled back one raster pixel.
 */
data class ScreenshotWatermark(
    val text: String = "",
    val fontSize: Float = 16f,
    val fontFamily: String = "sans-serif",
    val color: Int = 0xFF000000.toInt(),
    val angle: Float = 30f,
    val gap: Float = 56f,
    val opacity: Float = .16f,
    /** Template with `{text}` / timestamp placeholders; empty renders [text] literally. */
    val template: String = "",
    /** When the template was applied; 0 keeps timestamp placeholders literal. */
    val appliedAt: Long = 0L
) {
    fun normalized() = copy(
        text = text.trim(),
        gap = if (gap.isFinite()) gap.coerceIn(10f, 200f) else 56f,
        opacity = if (opacity.isFinite()) opacity.coerceIn(0f, 1f) else 0f,
        angle = if (angle.isFinite()) angle.coerceIn(-90f, 90f) else 30f,
        template = template.trim(),
        appliedAt = if (appliedAt > 0) appliedAt else 0L,
        fontSize = if (fontSize.isFinite()) fontSize.coerceIn(ScreenshotFontSize.MIN, ScreenshotFontSize.MAX_WATERMARK) else 16f)
    fun effectiveAlpha() = (Color.alpha(color) / 255f) * opacity.coerceIn(0f, 1f)
    /** `document.rs:resolved_text` — the template wins when one is set. */
    fun resolvedText() = ScreenshotWatermarkTemplates.resolve(template, text, appliedAt)
    fun isVisible() = resolvedText().trim().isNotEmpty() && effectiveAlpha() >= .004f

    /**
     * Paint the repeating text over [crop]; the caller has already drawn the screenshot image.
     * Port of `snow_canvas_watermark_renderer.cpp` (zoom collapsed to 1: our canvas units already
     * are view pixels).
     */
    fun draw(canvas: Canvas, crop: Rect, unit: Float) {
        val value = normalized()
        if (!value.isVisible() || unit <= 0f) return
        val text = value.resolvedText()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = value.color
            alpha = (value.effectiveAlpha() * 255f).toInt().coerceIn(0, 255)
            textSize = value.fontSize * unit
            typeface = Typeface.create(value.fontFamily, Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
        }
        // `entry->shape->inkBounds`: the tight glyph box, not the advance width or line height.
        val ink = Rect()
        paint.getTextBounds(text, 0, text.length, ink)
        val inkWidth = ink.width().coerceAtLeast(1).toFloat()
        val inkHeight = ink.height().coerceAtLeast(1).toFloat()
        val gap = value.gap * unit
        val horizontalStep = max(1f, inkWidth + gap)
        val verticalStep = max(1f, inkHeight + gap)
        val centerX = crop.exactCenterX(); val centerY = crop.exactCenterY()
        val radius = hypot(crop.width().toFloat(), crop.height().toFloat()) / 2f + horizontalStep + verticalStep
        canvas.save()
        canvas.clipRect(crop)
        // Grid indices are measured from the rotation centre; `and 1` on negative indices keeps
        // the original's two's-complement parity, so odd rows shift right in both directions.
        val firstRow = (kotlin.math.floor(-radius / verticalStep).toLong()) - 1
        val lastRow = (kotlin.math.ceil(radius / verticalStep).toLong()) + 1
        canvas.rotate(value.angle, centerX, centerY)
        for (row in firstRow..lastRow) {
            val offset = if (row and 1L != 0L) horizontalStep / 2f else 0f
            val firstColumn = (kotlin.math.floor((-radius - offset) / horizontalStep).toLong()) - 1
            val lastColumn = (kotlin.math.ceil((radius - offset) / horizontalStep).toLong()) + 1
            for (column in firstColumn..lastColumn) {
                val inkLeft = column * horizontalStep + offset
                val inkTop = row * verticalStep
                // Ink top-left on the grid point, pulled back the original's one raster pixel.
                canvas.drawText(text, centerX + inkLeft - ink.left - 1f, centerY + inkTop - ink.top - 1f, paint)
            }
        }
        canvas.restore()
    }
}
