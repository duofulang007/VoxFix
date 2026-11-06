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
    private val panel = LinearLayout(context)
    private val duration = TextView(context)
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
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(10), dp(6), dp(10), dp(6))
        dot.layoutParams = LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) }
        dot.background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(0xFFE53935.toInt()) }
        bar.addView(grip)
        bar.addView(dot)
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
        bar.addView(microphone)
        bar.addView(settings)
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
        addView(bar)

        panel.orientation = VERTICAL
        panel.setPadding(dp(12), 0, dp(12), dp(8))
        panel.visibility = GONE
        addView(panel)
        buildSettings()
    }

    fun setOptions(value: ScreenRecordingOptions) {
        options = value
        refreshSettings()
    }

    fun start(microphoneOn: Boolean, microphoneAvailable: Boolean) {
        startedAt = SystemClock.elapsedRealtime()
        paused = false
        pausedTotal = 0
        finished = false
        counting = false
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
        pause.visibility = GONE
        stop.visibility = GONE
        microphone.visibility = GONE
        settings.visibility = GONE
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
        if (finished || counting) return
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
        val rows = listOf(
            Triple("格式", { options.format.label }) { cycleFormat() },
            Triple("清晰度", { options.clarity.label }) { cycleClarity() },
            Triple("帧率", { "${options.frameRate} fps" }) { cycleFrameRate() },
            Triple("质量", { "${options.quality}" }) { cycleQuality() },
            Triple("声音", { if (options.microphone) "麦克风" else "系统声音" }) { cycleAudio() },
            Triple("延迟", { if (options.startDelaySeconds == 0) "关闭" else "${options.startDelaySeconds} 秒" }) { cycleDelay() }
        )
        for ((label, value, action) in rows) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val name = TextView(context).apply {
                text = label
                textSize = 13f
                setTextColor(0x99FFFFFF.toInt())
                width = dp(56)
            }
            val current = TextView(context).apply {
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(28)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = dp(7).toFloat()
                    setColor(0x26FFFFFF)
                }
                isClickable = true
                isFocusable = true
                contentDescription = label
                setOnClickListener { action(); refreshSettings(); onOptionsChanged?.invoke(options) }
            }
            row.addView(name)
            row.addView(current, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            panel.addView(row)
            optionRows.add(current)
        }
        val folder = TextView(context).apply {
            text = "打开录制文件夹"
            textSize = 13f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
            minHeight = dp(32)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(7).toFloat()
                setColor(0x26FFFFFF)
            }
            isClickable = true
            isFocusable = true
            contentDescription = "打开录制文件夹"
            setOnClickListener { onOpenFolder?.invoke() }
        }
        panel.addView(folder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        refreshSettings()
    }

    private fun refreshSettings() {
        val values = listOf(
            options.format.label,
            options.clarity.label,
            "${options.frameRate} fps",
            "${options.quality}",
            if (options.microphone) "麦克风" else if (options.systemAudio) "系统声音" else "静音",
            if (options.startDelaySeconds == 0) "关闭" else "${options.startDelaySeconds} 秒"
        )
        optionRows.forEachIndexed { index, view -> values.getOrNull(index)?.let { view.text = it } }
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
        // Animated formats are still pending on Android; cycling stays inside MP4 until they land.
        options = options.copy(format = ScreenRecordingOptions.Format.MP4)
    }

    private fun cycleClarity() {
        val values = ScreenRecordingOptions.Clarity.values()
        options = options.copy(clarity = values[(options.clarity.ordinal + 1) % values.size])
    }

    private fun cycleFrameRate() {
        val values = listOf(15, 24, 30, 60)
        val index = values.indexOf(options.frameRate).takeIf { it >= 0 } ?: 2
        options = options.copy(frameRate = values[(index + 1) % values.size])
    }

    private fun cycleQuality() {
        val values = listOf(40, 60, 80, 100)
        val index = values.indexOf(options.quality).takeIf { it >= 0 } ?: 2
        options = options.copy(quality = values[(index + 1) % values.size])
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
