package local.voxfix.debug

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating recording bar plus its export settings, mirroring Snow Shot's recording palette.
 *
 * The upstream window docks next to the selection and carries the drawing tools; the phone build
 * keeps transport controls and an expanding settings row, placed outside the recorded region so it
 * cannot appear in the file. Upstream edits are wheel driven — on touch every value cycles on tap,
 * and the gap is recorded in `docs/SnowShot-T28-录屏-实现与验收-2026-11-06.md`.
 */
@SuppressLint("ViewConstructor")
internal class ScreenRecordingControl(context: Context) : LinearLayout(context) {
    var onStop: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    /** Idle stage only: user pressed start; the service still has to obtain projection consent. */
    var onStart: (() -> Unit)? = null
    /** Idle stage only: user closed the setup without recording. */
    var onClose: (() -> Unit)? = null
    var onTogglePause: ((Boolean) -> Unit)? = null
    var onOpenSaved: (() -> Unit)? = null
    var onOpenFolder: (() -> Unit)? = null
    var onOptionsChanged: ((ScreenRecordingOptions) -> Unit)? = null
    var onLayoutChanged: (() -> Unit)? = null
    var onReleased: (() -> Unit)? = null
    /** Screen-space drag deltas from the grip, so the bar can be moved off the recorded content. */
    var onDrag: ((Float, Float) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val bar = LinearLayout(context)
    /**
     * Upstream keeps the export settings on one horizontal row
     * (`createRecordingExportSettingsToolbar` builds a `QHBoxLayout`); the phone used to stack six
     * labelled rows, which covered almost half the screen. Chips scroll horizontally instead.
     */
    private val panel = android.widget.HorizontalScrollView(context)
    private val panelRow = LinearLayout(context)
    private val duration = TextView(context)
    private val start = button("开始", accent = true)
    private val close = button("关闭")
    private val pause = button("暂停")
    private val stop = button("完成", accent = true)
    private val cancel = button("取消")
    private val microphone = button("麦克")
    private val settings = android.widget.ImageView(context).apply {
        setImageResource(R.drawable.snow_setting)
        setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
        contentDescription = "录制设置"
        tooltipText = contentDescription
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(7), dp(7), dp(7), dp(7))
        background = buttonBackground(false, false)
        isClickable = true
        isFocusable = true
        layoutParams = LayoutParams(dp(36), dp(32)).apply { marginStart = dp(4) }
    }
    private val dot = View(context)
    private val grip = android.widget.ImageView(context).apply {
        setImageResource(R.drawable.snow_grip)
        setColorFilter(0x99FFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
        contentDescription = "按住拖动录屏控制条"
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(1), dp(10), dp(1), dp(10))
        layoutParams = LayoutParams(dp(14), LayoutParams.MATCH_PARENT).apply { marginEnd = dp(4) }
    }
    private val optionRows = mutableListOf<TextView>()
    private val optionLabels = mutableListOf<String>()
    private val optionValues = mutableListOf<() -> String>()

    private var startedAt = 0L
    private var pausedAt = 0L
    private var pausedTotal = 0L
    private var paused = false
    private var finished = false
    private var counting = false
    private var options = ScreenRecordingOptions()

    private val tick = object : Runnable {
        override fun run() {
            if (finished || counting) return
            duration.text = ScreenRecordingGeometry.formatDuration(elapsed())
            handler.postDelayed(this, 250)
        }
    }

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(0xF0202020.toInt())
            setStroke(dp(1), 0x40FFFFFF)
        }
        bar.orientation = HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        bar.setPadding(dp(10), dp(6), dp(10), dp(6))
        dot.layoutParams = LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) }
        dot.background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(0xFFE53935.toInt()) }
        bar.addView(grip)
        bar.addView(dot)
        start.contentDescription = "开始录制"
        bar.addView(start)
        duration.setTextColor(Color.WHITE)
        duration.textSize = 14f
        duration.typeface = android.graphics.Typeface.MONOSPACE
        duration.text = "00:00:00"
        duration.gravity = Gravity.CENTER
        duration.setPadding(dp(2), 0, dp(6), 0)
        duration.contentDescription = "录制时长"
        bar.addView(duration)
        bar.addView(pause)
        bar.addView(stop)
        bar.addView(cancel)
        close.contentDescription = "关闭录屏设置"
        bar.addView(close)
        bar.addView(microphone)
        bar.addView(settings)
        start.setOnClickListener { if (!counting) onStart?.invoke() }
        close.setOnClickListener { if (!counting) onClose?.invoke() }
        pause.contentDescription = "暂停录制"
        stop.contentDescription = "停止并保存录屏"
        cancel.contentDescription = "取消录屏"
        microphone.contentDescription = "麦克风开关"
        settings.contentDescription = "录制设置"
        pause.setOnClickListener { togglePause() }
        stop.setOnClickListener { if (!finished) onStop?.invoke() }
        cancel.setOnClickListener { if (!finished) onCancel?.invoke() }
        microphone.setOnClickListener { toggleMicrophone() }
        settings.setOnClickListener { toggleSettings() }
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        panel.visibility = GONE
        addView(panel)
        buildSettings()
        showSetupVisibility()
    }

    /**
     * Idle stage: the frame is still adjustable and the export settings are editable, matching
     * upstream `updateRecordingExportSettingsControls()` (`editable = state == Idle && !busy`).
     * Without this stage the settings button could never be reached, because the phone used to
     * start capturing the moment the toolbar record action was confirmed.
     */
    fun showSetup(microphoneOn: Boolean, microphoneAvailable: Boolean) {
        startedAt = 0
        paused = false
        pausedTotal = 0
        finished = false
        counting = false
        pausedAt = 0
        dot.background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(ScreenRecordingSetupView.IDLE_COLOR)
        }
        duration.text = "00:00:00"
        duration.contentDescription = "录制时长"
        start.text = "开始"
        start.contentDescription = "开始录制"
        pause.visibility = GONE
        stop.visibility = GONE
        cancel.visibility = GONE
        start.visibility = VISIBLE
        close.visibility = VISIBLE
        microphone.visibility = VISIBLE
        settings.visibility = VISIBLE
        settings.isEnabled = true
        settings.alpha = 1f
        setMicrophone(microphoneOn, microphoneAvailable)
        handler.removeCallbacks(tick)
    }

    private fun showSetupVisibility() {
        // Built hidden: the first frame the bar shows is decided by showSetup() or start().
        start.visibility = GONE
        close.visibility = GONE
    }

    fun setOptions(value: ScreenRecordingOptions) {
        options = value
        rebuildSettings()
    }

    fun start(microphoneOn: Boolean, microphoneAvailable: Boolean, elapsedMs: Long = 0) {
        startedAt = SystemClock.elapsedRealtime() - elapsedMs
        paused = false
        pausedTotal = 0
        finished = false
        counting = false
        start.visibility = GONE
        close.visibility = GONE
        pause.visibility = VISIBLE
        stop.visibility = VISIBLE
        cancel.visibility = VISIBLE
        duration.text = "00:00:00"
        dot.background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(0xFFE53935.toInt())
        }
        dot.alpha = 1f
        setMicrophone(microphoneOn, microphoneAvailable)
        settings.isEnabled = false
        settings.alpha = 0.4f
        closeSettings()
        handler.post(tick)
    }

    /** Upstream counts down before the encoder starts; cancel keeps the projection request clean. */
    fun startCountdown(seconds: Int, onDone: () -> Unit, onCancelled: () -> Unit) {
        if (seconds <= 0) { onDone(); return }
        counting = true
        finished = false
        var remaining = seconds
        start.visibility = GONE
        close.visibility = GONE
        pause.visibility = GONE
        stop.visibility = GONE
        microphone.visibility = GONE
        settings.visibility = GONE
        cancel.visibility = VISIBLE
        closeSettings()
        cancel.text = "取消"
        duration.text = "${seconds}s"
        duration.contentDescription = "倒计时 $seconds 秒"
        val step = object : Runnable {
            override fun run() {
                if (!counting) return
                remaining--
                if (remaining <= 0) {
                    counting = false
                    duration.text = "00:00:00"
                    duration.contentDescription = "录制时长"
                    pause.visibility = VISIBLE
                    stop.visibility = VISIBLE
                    microphone.visibility = VISIBLE
                    settings.visibility = VISIBLE
                    cancel.setOnClickListener { if (!finished) onCancel?.invoke() }
                    onDone()
                } else {
                    duration.text = "${remaining}s"
                    duration.contentDescription = "倒计时 $remaining 秒"
                    handler.postDelayed(this, 1_000)
                }
            }
        }
        cancel.setOnClickListener {
            if (!counting) return@setOnClickListener
            counting = false
            handler.removeCallbacks(step)
            onCancelled()
        }
        handler.postDelayed(step, 1_000)
    }

    fun stopCountdown() {
        counting = false
        handler.removeCallbacksAndMessages(null)
    }

    fun setMicrophone(enabled: Boolean, available: Boolean) {
        microphone.isEnabled = available && !finished
        microphone.alpha = if (available) 1f else 0.4f
        microphone.setTextColor(if (enabled && available) 0xFF66BB6A.toInt() else Color.WHITE)
        microphone.background = buttonBackground(enabled && available, accent = false)
    }

    fun setPaused(value: Boolean) {
        if (finished || paused == value) return
        paused = value
        if (value) {
            pausedAt = SystemClock.elapsedRealtime()
            pause.text = "继续"
            pause.contentDescription = "继续录制"
            dot.alpha = 0.35f
        } else {
            pausedTotal += SystemClock.elapsedRealtime() - pausedAt
            pause.text = "暂停"
            pause.contentDescription = "暂停录制"
            dot.alpha = 1f
        }
    }

    fun showSaved(path: String, uri: android.net.Uri?) {
        finished = true
        counting = false
        handler.removeCallbacks(tick)
        duration.text = "已保存"
        duration.contentDescription = "已保存 $path"
        start.visibility = GONE
        close.visibility = GONE
        pause.visibility = GONE
        cancel.visibility = GONE
        microphone.visibility = GONE
        settings.visibility = GONE
        closeSettings()
        stop.text = "查看"
        stop.contentDescription = "查看录屏"
        stop.setOnClickListener { onOpenSaved?.invoke() }
        dot.background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(0xFF66BB6A.toInt()) }
        postDelayed({ if (isAttachedToWindow) release() }, 10_000)
    }

    fun showFailure(message: String) {
        finished = true
        counting = false
        handler.removeCallbacks(tick)
        duration.text = message
        duration.contentDescription = message
        start.visibility = GONE
        close.visibility = GONE
        pause.visibility = GONE
        microphone.visibility = GONE
        stop.visibility = GONE
        settings.visibility = GONE
        closeSettings()
        cancel.text = "关闭"
        cancel.contentDescription = "关闭录屏提示"
        cancel.setOnClickListener { release() }
        dot.background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(0xFFE53935.toInt()) }
        postDelayed({ if (isAttachedToWindow) release() }, 8_000)
    }

    fun release() {
        if (!isAttachedToWindow) { onReleased?.invoke(); return }
        handler.removeCallbacksAndMessages(null)
        onReleased?.invoke()
    }

    private fun toggleMicrophone() {
        if (finished || counting || !options.format.hasAudio) return
        options = options.copy(microphone = !options.microphone, systemAudio = options.microphone)
        setMicrophone(options.microphone, true)
        refreshSettings()
        onOptionsChanged?.invoke(options)
    }

    private var lastRawX = 0f
    private var lastRawY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false

    /**
     * Whole-bar dragging.
     *
     * The bar is the window root; non-interactive children (the duration label, padding) return
     * false on touch-down, and an unconsumed DOWN drops the whole gesture, so moves never arrive.
     * Consuming DOWN here and deciding on MOVE keeps taps on buttons intact while letting any other
     * spot drag the window out of the recorded region.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    dragging = kotlin.math.abs(event.rawX - downRawX) > slop ||
                        kotlin.math.abs(event.rawY - downRawY) > slop
                    // Haptic tick the moment the bar starts following the finger; without it the
                    // window seems to jump on its own.
                    if (dragging) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
                if (dragging) {
                    onDrag?.invoke(event.rawX - lastRawX, event.rawY - lastRawY)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun togglePause() {        if (finished || counting) return
        setPaused(!paused)
        onTogglePause?.invoke(paused)
    }

    private fun elapsed(): Long {
        val now = SystemClock.elapsedRealtime()
        return if (paused) pausedAt - startedAt - pausedTotal else now - startedAt - pausedTotal
    }

    // ---- export settings row (upstream: format, clarity, frame rate, quality, audio, delay) ----

    private fun buildSettings() {
        panel.setPadding(dp(12), 0, dp(12), dp(6))
        panel.isHorizontalScrollBarEnabled = false
        panel.contentDescription = "录制设置，可左右滚动"
        // The window is wrap-content: an unconstrained scroll view would make it wider than the
        // display and put the trailing chips out of reach.
        val maxWidth = (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(160))
        panel.layoutParams = LayoutParams(maxWidth, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        }
        panelRow.orientation = HORIZONTAL
        panelRow.gravity = Gravity.CENTER_VERTICAL
        panelRow.removeAllViews()
        optionRows.clear()
        optionLabels.clear()
        optionValues.clear()
        val rows = ArrayList<Triple<String, () -> String, () -> Unit>>()
        rows.add(Triple("格式", { options.format.label }, { cycleFormat() }))
        rows.add(Triple("清晰度", { options.effectiveClarity.label }, { cycleClarity() }))
        rows.add(Triple("帧率", { "${options.effectiveFrameRate} fps" }, { cycleFrameRate() }))
        // GIF and APNG have no quality knob upstream; only MP4 (bitrate) and WebP (VP8 quality) use it.
        if (!options.format.animated || options.format == ScreenRecordingOptions.Format.WEBP) {
            rows.add(Triple("质量", { "${options.quality}" }, { cycleQuality() }))
        }
        if (options.format.hasAudio) {
            rows.add(Triple("声音", {
                if (options.microphone) "麦克风" else if (options.systemAudio) "系统声音" else "静音"
            }, { cycleAudio() }))
        }
        if (options.format.animated) {
            rows.add(Triple("循环", { if (options.loopAnimated) "开" else "关" }, { cycleLoop() }))
        }
        rows.add(Triple("延迟", {
            if (options.startDelaySeconds == 0) "关闭" else "${options.startDelaySeconds} 秒"
        }, { cycleDelay() }))
        for ((label, value, action) in rows) {
            val current = chip(label).apply {
                // The label is only announced once; the visible chip carries label + value so a
                // single row reads without separate column headers.
                setOnClickListener { action(); rebuildSettings(); onOptionsChanged?.invoke(options) }
            }
            panelRow.addView(current)
            optionRows.add(current)
            optionLabels.add(label)
            optionValues.add(value)
        }
        panelRow.addView(chip("文件夹").apply {
            setTextColor(0xCCFFFFFF.toInt())
            setOnClickListener { onOpenFolder?.invoke() }
        })
        panel.removeAllViews()
        panel.addView(panelRow, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        refreshSettings()
    }

    /** Animated formats drop the audio track, so the microphone button has to follow the format. */
    private fun rebuildSettings() {
        setMicrophone(options.microphone && options.format.hasAudio, options.format.hasAudio)
        buildSettings()
    }

    private fun chip(label: String): TextView = TextView(context).apply {
        text = label
        textSize = 12f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        isSingleLine = true
        minHeight = dp(30)
        setPadding(dp(9), dp(4), dp(9), dp(4))
        background = GradientDrawable().apply {
            cornerRadius = dp(7).toFloat()
            setColor(0x26FFFFFF)
        }
        isClickable = true
        isFocusable = true
        contentDescription = label
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(6)
        }
    }

    private fun refreshSettings() {
        optionRows.forEachIndexed { index, view ->
            val value = optionValues.getOrNull(index)?.invoke() ?: return@forEachIndexed
            val label = optionLabels.getOrNull(index) ?: ""
            view.text = "$label $value"
            view.contentDescription = "$label $value"
        }
    }

    private fun toggleSettings() {
        if (counting || finished) return
        if (panel.visibility == VISIBLE) closeSettings() else {
            panel.visibility = VISIBLE
            onLayoutChanged?.invoke()
        }
    }

    private fun closeSettings() {
        if (panel.visibility != VISIBLE) return
        panel.visibility = GONE
        onLayoutChanged?.invoke()
    }

    private fun cycleFormat() {
        val values = ScreenRecordingOptions.Format.values()
        options = options.copy(format = values[(options.format.ordinal + 1) % values.size])
        if (!options.format.hasAudio) options = options.copy(microphone = false, systemAudio = false)
    }

    private fun cycleClarity() {
        val values = options.clarityOptions
        val current = if (options.format.animated) options.animatedClarity else options.clarity
        val next = values[(values.indexOf(current).coerceAtLeast(0) + 1) % values.size]
        options = if (options.format.animated) options.copy(animatedClarity = next) else options.copy(clarity = next)
    }

    private fun cycleFrameRate() {
        val values = options.frameRateOptions
        val current = options.effectiveFrameRate
        val index = values.indexOf(current).takeIf { it >= 0 } ?: 0
        val next = values[(index + 1) % values.size]
        options = if (options.format.animated) options.copy(animatedFrameRate = next) else options.copy(frameRate = next)
    }

    private fun cycleQuality() {
        val values = listOf(40, 60, 80, 100)
        val index = values.indexOf(options.quality).takeIf { it >= 0 } ?: 2
        options = options.copy(quality = values[(index + 1) % values.size])
    }

    private fun cycleLoop() {
        options = options.copy(loopAnimated = !options.loopAnimated)
    }

    private fun cycleAudio() {
        options = when {
            options.microphone -> options.copy(microphone = false, systemAudio = true)
            options.systemAudio -> options.copy(microphone = false, systemAudio = false)
            else -> options.copy(microphone = true, systemAudio = false)
        }
        setMicrophone(options.microphone, true)
    }

    private fun cycleDelay() {
        val values = listOf(0, 3, 5, 10)
        val index = values.indexOf(options.startDelaySeconds).takeIf { it >= 0 } ?: 0
        options = options.copy(startDelaySeconds = values[(index + 1) % values.size])
    }

    private fun button(label: String, accent: Boolean = false): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isSingleLine = true
            maxLines = 1
            minWidth = dp(40)
            minHeight = dp(34)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = buttonBackground(false, accent)
            isClickable = true
            isFocusable = true
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(4)
            }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.alpha = 0.7f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.alpha = 1f
                }
                false
            }
        }

    private fun buttonBackground(active: Boolean, accent: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            when {
                accent -> setColor(0xFFD84315.toInt())
                active -> setColor(0xFF2E7D32.toInt())
                else -> setColor(0x33FFFFFF)
            }
            setStroke(dp(1), if (active) 0xFF66BB6A.toInt() else 0x33FFFFFF)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val BAR_DP = 48
    }
}
