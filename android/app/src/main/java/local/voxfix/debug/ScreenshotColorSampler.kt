package local.voxfix.debug

import android.graphics.*
import android.view.*

/**
 * Snow Shot 1.1.8 magnifier (screenshotcolorpickerwindow.cpp): 11x11 source pixels at 12x,
 * panel padding 4, radius 6, content gap 4, 20px position line, 24px colour bar, 16px cursor gap.
 * Touch adaptation: there is no hover on a phone, so press-drag-release replaces it; the panel
 * stays 16dp away from the finger so the magnified area is never covered by the panel itself.
 *
 * The pixels come from an owned snapshot, so toolbars, this magnifier and its own readout can
 * never enter the sample.
 */
internal class ScreenshotColorSampler(private val target: View, private val image: Bitmap,
    private val done: (Int?) -> Unit) : View(target.context) {
    private companion object {
        const val ZOOM = 12
        const val GRID = 11
        const val PAD = 4
        const val RADIUS = 6
        const val CONTENT_GAP = 4
        const val TEXT_H = 20
        const val TEXT_SIZE = 13f
        const val COLOR_H = 24
        const val CURSOR_GAP = 16
        const val PANEL = 0xFF1F1F1F.toInt()
        const val BORDER = 0xFF3A3A3A.toInt()
        const val TEXT = 0xFFD9D9D9.toInt()
    }
    private val host: ViewGroup? = target.rootView as? ViewGroup
    private val density = resources.displayMetrics.density
    private fun dip(value: Int) = (value * density).toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TEXT_SIZE * resources.displayMetrics.scaledDensity; textAlign = Paint.Align.CENTER
    }
    private var source: Point? = null
    private var touchX = 0f
    private var touchY = 0f
    private var finished = false
    private val previousFocus = host?.findFocus()
    private val panelWidth: Float get() = dip(PAD * 2 + GRID * ZOOM).toFloat()
    private val panelHeight: Float get() = dip(PAD * 2 + GRID * ZOOM + CONTENT_GAP * 2 + TEXT_H + COLOR_H).toFloat()

    init { contentDescription = "截图滴管取色，拖动后松手选色，返回取消"; isFocusableInTouchMode = true }

    fun show() {
        val parent = host
        if (parent == null) { finish(null); return }
        parent.addView(this, ViewGroup.LayoutParams(-1, -1)); requestFocus()
    }

    /** Target rect inside its own window root; works before and after the window is attached. */
    private fun targetRect(): Rect {
        val parent = host ?: return Rect(0, 0, target.width, target.height)
        if (parent === target) return Rect(0, 0, target.width, target.height)
        val rect = Rect(0, 0, target.width, target.height)
        return runCatching { parent.offsetDescendantRectToMyCoords(target, rect); rect }
            .getOrElse { Rect(0, 0, target.width, target.height) }
    }

    private fun visibleRect(): Rect? {
        val local = Rect()
        if (!target.getLocalVisibleRect(local)) return null
        val bounds = targetRect()
        local.offset(bounds.left, bounds.top)
        return local
    }

    private fun finish(color: Int?) {
        if (finished) return
        finished = true; host?.removeView(this)
        if (!image.isRecycled) image.recycle()
        previousFocus?.takeIf { it.isAttachedToWindow }?.requestFocus()
        done(color)
    }

    override fun onDetachedFromWindow() {
        if (!finished) { finished = true; if (!image.isRecycled) image.recycle(); done(null) }
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val rect = targetRect()
        val visible = visibleRect()
        if (visible != null && rect.width() > 0 && rect.height() > 0 && !image.isRecycled) {
            canvas.save(); canvas.clipRect(visible)
            paint.isFilterBitmap = false
            canvas.drawBitmap(image, null, rect, paint)
            canvas.restore()
        }
        val point = source ?: return
        val w = panelWidth; val h = panelHeight
        val left = (touchX - w / 2f).coerceIn(0f, (width - w).coerceAtLeast(0f).toFloat())
        val above = touchY - dip(CURSOR_GAP) - h
        val top = (if (above >= 0f) above else touchY + dip(CURSOR_GAP))
            .coerceIn(0f, (height - h).coerceAtLeast(0f).toFloat())

        // Panel: padding 4, radius 6.
        paint.style = Paint.Style.FILL; paint.color = PANEL
        canvas.drawRoundRect(RectF(left, top, left + w, top + h), dip(RADIUS).toFloat(), dip(RADIUS).toFloat(), paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = BORDER
        canvas.drawRoundRect(RectF(left + .5f, top + .5f, left + w - .5f, top + h - .5f),
            dip(RADIUS).toFloat(), dip(RADIUS).toFloat(), paint)
        paint.style = Paint.Style.FILL

        // 11x11 source pixels at 12x, no smoothing, centre pixel outlined.
        val pad = dip(PAD).toFloat()
        val canvasSide = dip(GRID * ZOOM).toFloat()
        val preview = RectF(left + pad, top + pad, left + pad + canvasSide, top + pad + canvasSide)
        val cell = preview.width() / GRID
        val half = GRID / 2
        for (dx in 0 until GRID) for (dy in 0 until GRID) {
            paint.color = image.getPixel((point.x + dx - half).coerceIn(0, image.width - 1),
                (point.y + dy - half).coerceIn(0, image.height - 1))
            canvas.drawRect(preview.left + dx * cell, preview.top + dy * cell,
                preview.left + (dx + 1) * cell, preview.top + (dy + 1) * cell, paint)
        }
        val center = RectF(preview.left + half * cell, preview.top + half * cell,
            preview.left + (half + 1) * cell, preview.top + (half + 1) * cell)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
        paint.color = if (Color.luminance(image.getPixel(point.x, point.y)) > .5f) Color.BLACK else Color.WHITE
        canvas.drawRect(center, paint)
        paint.style = Paint.Style.FILL

        // Position line: whole source pixels, same wording as the desktop readout.
        val colorText = String.format("#%06X", image.getPixel(point.x, point.y) and 0xFFFFFF)
        val gap = dip(CONTENT_GAP).toFloat()
        val textTop = preview.bottom + gap
        paint.color = TEXT
        canvas.drawText("X: ${point.x} Y: ${point.y}", left + w / 2f, textTop + dip(TEXT_H) - dip(6), text)

        // Colour bar: the sampled colour, labelled in a readable ink.
        val barTop = textTop + dip(TEXT_H) + gap
        val bar = RectF(left + pad, barTop, left + w - pad, barTop + dip(COLOR_H).toFloat())
        paint.color = image.getPixel(point.x, point.y)
        canvas.drawRoundRect(bar, dip(RADIUS).toFloat(), dip(RADIUS).toFloat(), paint)
        paint.color = if (Color.luminance(image.getPixel(point.x, point.y)) > .5f) Color.BLACK else Color.WHITE
        canvas.drawText(colorText, bar.centerX(), bar.centerY() + text.textSize * .36f, text)

        // Touch marker at the sampling point.
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dip(2).coerceAtLeast(2).toFloat(); paint.color = Color.WHITE
        canvas.drawCircle(touchX, touchY, dip(6).toFloat(), paint)
        paint.strokeWidth = 1f; paint.color = 0x99000000.toInt()
        canvas.drawCircle(touchX, touchY, dip(6).toFloat() + 2f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun sampleAt(x: Float, y: Float): Point? {
        val rect = targetRect()
        val visible = visibleRect() ?: return null
        if (rect.width() <= 0f || rect.height() <= 0f) return null
        if (!visible.contains(x.toInt(), y.toInt())) return null
        return Point(
            ((x - rect.left) * image.width / rect.width()).toInt().coerceIn(0, image.width - 1),
            ((y - rect.top) * image.height / rect.height()).toInt().coerceIn(0, image.height - 1))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            finish(null); return true
        }
        if (event.actionMasked in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
            touchX = event.x; touchY = event.y
            source = sampleAt(event.x, event.y)
            invalidate()
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val picked = source
                finish(picked?.let { image.getPixel(it.x, it.y) })
                performClick()
            }
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) finish(null)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
