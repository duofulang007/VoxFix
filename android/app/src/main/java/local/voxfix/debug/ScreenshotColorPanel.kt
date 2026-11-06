package local.voxfix.debug

import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import kotlin.math.roundToInt

/**
 * Snow Shot 1.1.8 AdColorPicker (solid): stroke-style row, SV panel, hue rail, transparency rail,
 * colour preview swatch that doubles as the canvas sampler, then format + value + percent inputs.
 * Metrics follow the 1.1.8 popup: 6px panel padding, 222x162 SV area, 8px rails with a 28px
 * swatch column, 28px format row, 4px radius on fields.
 */
internal class ScreenshotColorPanel(context: Context, initial: Int, private val preview: (Int) -> Unit,
    pattern: ScreenshotStrokePattern? = null, private val patternPreview: ((ScreenshotStrokePattern) -> Unit)? = null) : LinearLayout(context) {
    private companion object {
        const val PANEL = 0xFF1F1F1F.toInt()
        const val FIELD = 0xFF111111.toInt()
        const val FIELD_BORDER = 0xFF3A3A3A.toInt()
        const val TEXT = 0xFFD9D9D9.toInt()
        const val TEXT_DIM = 0xFF8C8C8C.toInt()
        const val PRIMARY = 0xFF1668DC.toInt()
        const val SV_RATIO = 162f / 222f
    }
    private val density = resources.displayMetrics.density
    private fun dp(n: Int) = (n * density).toInt()
    private val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
    private var alpha = Color.alpha(initial)
    val color: Int get() = Color.HSVToColor(alpha, hsv)
    var onCancel: () -> Unit = {}
    var onSample: () -> Unit = {}
    /** No sampler host (e.g. annotating a live recording) → hide the eyedropper, never fake a colour. */
    var sampleAvailable = true
        set(value) {
            field = value
            sampler.visibility = if (value) VISIBLE else GONE
        }
    private var syncing = false
    private var dirtyAlpha = false
    private var dirtyChannels = false
    private var mode = "HEX"
    private var menu: PopupWindow? = null
    private var measuredWidthContent = 0
    private val inputs = LinearLayout(context)
    private val channels = mutableListOf<EditText>()
    private val modeButton = TextView(context)
    private val alphaInput = field("透明度百分比")
    private val square = Plane(0)
    private val hue = Plane(1)
    private val transparency = Plane(2)
    private val sampler = ImageView(context)

    private fun surface(): GradientDrawable = GradientDrawable().apply {
        setColor(FIELD); cornerRadius = dp(4).toFloat(); setStroke(dp(1).coerceAtLeast(1), FIELD_BORDER)
    }
    private fun field(label: String) = EditText(context).apply {
        contentDescription = label; textSize = 13f; setTextColor(TEXT); setSingleLine()
        setPadding(dp(6), 0, dp(6), 0); background = surface(); gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false; minHeight = 0; minimumHeight = 0
        imeOptions = EditorInfo.IME_ACTION_DONE; setSelectAllOnFocus(true)
        setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { commitInputs(); clearFocus(); true } else false }
        setOnFocusChangeListener { _, focused -> if (!focused && !syncing) commitInputs() }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!syncing) { if (label == "透明度百分比") dirtyAlpha = true else dirtyChannels = true; readInputs(false) }
            }
        })
    }
    init {
        orientation = VERTICAL; isFocusableInTouchMode = true; contentDescription = "颜色调色板"
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = GradientDrawable().apply { setColor(PANEL); cornerRadius = dp(8).toFloat() }
        if (pattern != null) {
            val row = LinearLayout(context); var selected = pattern; val buttons = mutableListOf<View>()
            ScreenshotStrokePattern.values().forEachIndexed { index, value ->
                val button = object : View(context) {
                    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    override fun onDraw(canvas: Canvas) {
                        paint.color = if (selected == value) Color.WHITE else 0xFFD9D9D9.toInt()
                        paint.strokeWidth = dp(2).coerceAtLeast(2).toFloat(); paint.strokeCap = Paint.Cap.BUTT
                        paint.pathEffect = ScreenshotPenRendering.effect(value, paint.strokeWidth)
                        canvas.drawLine(dp(7).toFloat(), height / 2f, width - dp(7).toFloat(), height / 2f, paint)
                        paint.pathEffect = null
                    }
                }.apply {
                    contentDescription = listOf("实线", "虚线", "点线")[index]; tooltipText = contentDescription
                    isClickable = true; isFocusable = true
                    background = android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply { setColor(0xFF112A45.toInt()); cornerRadius = dp(4).toFloat() })
                        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                    }
                    isSelected = value == selected
                    setOnClickListener {
                        selected = value; patternPreview?.invoke(value)
                        buttons.forEach { it.isSelected = it === this; it.invalidate() }
                    }
                }
                buttons.add(button)
                row.addView(button, LayoutParams(dp(28), dp(28)).apply { if (index > 0) marginStart = dp(4) })
            }
            addView(row, LayoutParams(-1, dp(28)).apply { bottomMargin = dp(8) })
        }
        addView(square, LayoutParams(-1, dp(162)))
        val bars = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val sliders = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        sliders.addView(hue, LayoutParams(-1, dp(8)))
        sliders.addView(transparency, LayoutParams(-1, dp(8)).apply { topMargin = dp(12) })
        bars.addView(sliders, LayoutParams(0, dp(28), 1f))
        sampler.apply {
            contentDescription = "从截图取色"; tooltipText = contentDescription; isFocusable = true; isClickable = true
            setImageResource(R.drawable.snow_color_picker); setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { commitInputs(); onSample() }
        }
        bars.addView(sampler, LayoutParams(dp(28), dp(28)).apply { marginStart = dp(12) })
        addView(bars, LayoutParams(-1, dp(28)).apply { topMargin = dp(10); bottomMargin = dp(10) })
        modeButton.apply {
            text = "HEX"; textSize = 12f; setTextColor(TEXT); gravity = Gravity.CENTER_VERTICAL
            contentDescription = "颜色格式"; isFocusable = true; isClickable = true; setOnClickListener { showFormats() }
            // The desktop selector is a combo box; the chevron is drawn, not typed as a glyph.
            val chevron = context.getDrawable(R.drawable.snow_chevron_down)?.mutate()
            chevron?.setBounds(0, 0, dp(10), dp(10))
            setCompoundDrawables(null, null, chevron, null)
            compoundDrawablePadding = dp(2)
        }
        inputs.gravity = Gravity.CENTER_VERTICAL
        inputs.addView(modeButton, LayoutParams(dp(46), dp(28)))
        inputs.addView(alphaInput, LayoutParams(dp(44), dp(28)).apply { marginStart = dp(8) })
        addView(inputs, LayoutParams(-1, dp(28)))
        buildChannels(); sync()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val content = measuredWidth - paddingLeft - paddingRight
        if (content <= 0 || content == measuredWidthContent) return
        val height = (content * SV_RATIO).toInt()
        val params = square.layoutParams
        if (params.height == height) { measuredWidthContent = content; return }
        params.height = height; square.layoutParams = params
        measuredWidthContent = content
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    fun setColor(value: Int) {
        val next = FloatArray(3); Color.colorToHSV(value, next)
        // A grey target carries no hue; keep the rail where the user left it, as the desktop panel does.
        if (next[1] > 0) hsv[0] = next[0]
        hsv[1] = next[1]; hsv[2] = next[2]; alpha = Color.alpha(value)
        sync(true); preview(color)
    }
    fun setMode(value: String) {
        require(value in listOf("HEX", "RGB", "HSB"))
        commitInputs(); mode = value; buildChannels(); sync(true)
    }
    /** Exposed for the device suite: what the format row currently shows. */
    val formatMode: String get() = mode
    private fun buildChannels() {
        syncing = true; channels.forEach { inputs.removeView(it) }; channels.clear()
        val labels = when (mode) { "RGB" -> listOf("红色通道", "绿色通道", "蓝色通道"); "HSB" -> listOf("色相数值", "饱和度数值", "亮度数值"); else -> listOf("十六进制颜色") }
        for (label in labels) {
            val input = field(label); channels.add(input)
            inputs.addView(input, inputs.childCount - 1, LayoutParams(0, dp(28), 1f).apply { marginStart = dp(8) })
        }
        modeButton.text = mode; syncing = false
    }
    private fun sync(force: Boolean = false) {
        syncing = true
        val values = when (mode) {
            "RGB" -> listOf(Color.red(color), Color.green(color), Color.blue(color)).map(Int::toString)
            "HSB" -> listOf(hsv[0].roundToInt(), (hsv[1] * 100).roundToInt(), (hsv[2] * 100).roundToInt()).map(Int::toString)
            else -> listOf(String.format("#%06X", color and 0xFFFFFF))
        }
        channels.forEachIndexed { index, input -> if (force || !input.hasFocus()) input.setText(values[index]) }
        if (force || !alphaInput.hasFocus()) alphaInput.setText("${(alpha * 100f / 255).roundToInt()}%")
        // The swatch shows the live colour; the pipette glyph switches ink so it stays visible.
        sampler.background = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(4).toFloat(); setStroke(dp(1).coerceAtLeast(1), FIELD_BORDER)
        }
        sampler.setColorFilter(if (Color.alpha(color) < 128 || Color.luminance(color) > .4f) Color.BLACK else Color.WHITE)
        square.invalidate(); hue.invalidate(); transparency.invalidate(); syncing = false
        if (force) { dirtyAlpha = false; dirtyChannels = false }
    }
    fun commitInputs(): Boolean = readInputs(true)
    private fun readInputs(commit: Boolean): Boolean {
        if (syncing || channels.isEmpty()) return false
        val percent = alphaInput.text.toString().trim().removeSuffix("%").toIntOrNull()?.takeIf { it in 0..100 }
        var nextAlpha = percent?.let { if (dirtyAlpha) (it * 255f / 100).roundToInt() else alpha }
        val texts = channels.map { it.text.toString().trim() }
        val next = if (!dirtyChannels) color else when (mode) {
            "HEX" -> {
                val hex = texts[0].removePrefix("#")
                if (hex.matches(Regex("(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})"))) {
                    if (hex.length == 8) nextAlpha = hex.takeLast(2).toInt(16)
                    Color.rgb(hex.take(2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
                } else null
            }
            "RGB" -> texts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }.let { if (it.all { n -> n != null }) Color.rgb(it[0]!!, it[1]!!, it[2]!!) else null }
            else -> texts.mapIndexed { index, s -> s.removeSuffix("%").toFloatOrNull()?.takeIf { n -> n.isFinite() && n in 0f..(if (index == 0) 360f else 100f) } }.let {
                if (it.all { n -> n != null }) Color.HSVToColor(floatArrayOf(it[0]!! % 360, it[1]!! / 100, it[2]!! / 100)) else null
            }
        }
        if (next == null || nextAlpha == null) { if (commit) sync(true); return false }
        val old = color; val converted = FloatArray(3); Color.colorToHSV(next, converted)
        if (mode == "HSB" && dirtyChannels) hsv[0] = texts[0].toFloat() % 360 else if (converted[1] > 0) hsv[0] = converted[0]
        hsv[1] = converted[1]; hsv[2] = converted[2]; alpha = nextAlpha
        sync(commit); if (old != color) preview(color)
        return true
    }
    private fun showFormats() {
        commitInputs(); menu?.dismiss()
        val body = LinearLayout(context).apply { orientation = VERTICAL; background = surface() }
        val popup = PopupWindow(body, dp(72), -2, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); isOutsideTouchable = true; elevation = dp(6).toFloat()
        }
        for (format in listOf("HEX", "RGB", "HSB")) body.addView(TextView(context).apply {
            text = format; contentDescription = "颜色格式 $format"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(if (mode == format) PRIMARY else TEXT)
            setOnClickListener { popup.dismiss(); setMode(format) }
        }, LayoutParams(-1, dp(32)))
        menu = popup; popup.showAsDropDown(modeButton)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) onCancel()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onDetachedFromWindow() { menu?.dismiss(); super.onDetachedFromWindow() }
    private inner class Plane(private val kind: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var cancelled = false
        init { contentDescription = listOf("饱和度与亮度", "色相", "颜色透明度")[kind]; isClickable = true; isFocusable = true }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            if (kind == 0) {
                paint.shader = LinearGradient(0f, 0f, w, 0f, Color.WHITE, Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, paint)
                paint.shader = LinearGradient(0f, 0f, 0f, h, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, paint)
                paint.shader = null
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = Color.WHITE
                val r = dp(6).toFloat()
                val x = (hsv[1] * w).coerceIn(r, w - r); val y = ((1 - hsv[2]) * h).coerceIn(r, h - r)
                canvas.drawCircle(x, y, r, paint)
                paint.strokeWidth = 1f; paint.color = 0x66000000
                canvas.drawCircle(x, y, dp(6).toFloat() + 1.5f, paint)
                paint.style = Paint.Style.FILL
                return
            }
            // Hue and transparency are full-width pills, 8dp tall, with a ring handle.
            val rail = RectF(0f, (h - dp(8)) / 2f, w, (h + dp(8)) / 2f)
            val radius = rail.height() / 2f
            if (kind == 2) {
                val step = dp(4).coerceAtLeast(2)
                canvas.save(); canvas.clipRect(rail)
                paint.shader = null
                for (x in 0..width step step) for (y in 0..height step step) {
                    paint.color = if ((x / step + y / step) % 2 == 0) 0xFF3A3A3A.toInt() else 0xFF2A2A2A.toInt()
                    canvas.drawRect(x.toFloat(), y.toFloat(), (x + step).toFloat(), (y + step).toFloat(), paint)
                }
                canvas.restore()
            }
            paint.shader = if (kind == 1) LinearGradient(0f, 0f, w, 0f,
                intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null, Shader.TileMode.CLAMP)
            else LinearGradient(0f, 0f, w, 0f, color and 0x00FFFFFF, color or 0xFF000000.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rail, radius, radius, paint)
            paint.shader = null
            val progress = if (kind == 1) hsv[0] / 360f else this@ScreenshotColorPanel.alpha / 255f
            // Thin white ring with a hollow centre, kept fully inside the rail at both ends.
            val r = dp(6).toFloat()
            val x = (progress * w).coerceIn(r, w - r)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = Color.WHITE
            canvas.drawCircle(x, h / 2f, r, paint)
            paint.strokeWidth = 1f; paint.color = 0x66000000
            canvas.drawCircle(x, h / 2f, r + 1.5f, paint)
            paint.style = Paint.Style.FILL
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { cancelled = false; commitInputs(); parent.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { cancelled = true; parent.requestDisallowInterceptTouchEvent(false); return true }
                MotionEvent.ACTION_UP -> parent.requestDisallowInterceptTouchEvent(false)
            }
            if (!cancelled && event.actionMasked in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
                if (kind == 0) {
                    hsv[1] = (event.x / width).coerceIn(0f, 1f); hsv[2] = 1 - (event.y / height).coerceIn(0f, 1f)
                } else {
                    val progress = (event.x / width).coerceIn(0f, 1f)
                    if (kind == 1) hsv[0] = progress * 360 else this@ScreenshotColorPanel.alpha = (progress * 255).roundToInt()
                }
                sync(true); preview(color)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
