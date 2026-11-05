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
 * Floating recording bar. Snow Shot keeps its drawing palette on screen while recording; the phone
 * first pass only exposes the transport controls, placed outside the recorded region so it cannot
 * appear in the file.
 */
@SuppressLint("ViewConstructor")
internal class ScreenRecordingControl(context: Context) : LinearLayout(context) {
    var onStop: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onTogglePause: ((Boolean) -> Unit)? = null
    var onToggleMicrophone: (() -> Unit)? = null
    var onOpenSaved: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val duration = TextView(context)
    private val pause = button("暂停")
    private val stop = button("完成", accent = true)
    private val cancel = button("取消")
    private val microphone = button("麦克")
    private val dot = View(context)

    private var startedAt = 0L
    private var pausedAt = 0L
    private var pausedTotal = 0L
    private var paused = false
    private var finished = false

    private val tick = object : Runnable {
        override fun run() {
            if (finished) return
            duration.text = ScreenRecordingGeometry.formatDuration(elapsed())
            handler.postDelayed(this, 250)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(0xF0202020.toInt())
            setStroke(dp(1), 0x40FFFFFF)
        }
        dot.layoutParams = LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) }
        dot.background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(0xFFE53935.toInt()) }
        addView(dot)
        duration.setTextColor(Color.WHITE)
        duration.textSize = 15f
        duration.typeface = android.graphics.Typeface.MONOSPACE
        duration.text = "00:00:00"
        duration.gravity = Gravity.CENTER
        duration.setPadding(dp(2), 0, dp(10), 0)
        duration.contentDescription = "录制时长"
        addView(duration)
        addView(pause)
        addView(stop)
        addView(cancel)
        addView(microphone)
        pause.contentDescription = "暂停录制"
        stop.contentDescription = "停止并保存录屏"
        cancel.contentDescription = "取消录屏"
        microphone.contentDescription = "麦克风开关"
        pause.setOnClickListener { togglePause() }
        stop.setOnClickListener { if (!finished) onStop?.invoke() }
        cancel.setOnClickListener { if (!finished) onCancel?.invoke() }
        microphone.setOnClickListener { onToggleMicrophone?.invoke() }
    }

    fun start(microphoneOn: Boolean, microphoneAvailable: Boolean) {
        startedAt = SystemClock.elapsedRealtime()
        paused = false
        pausedTotal = 0
        finished = false
        setMicrophone(microphoneOn, microphoneAvailable)
        handler.post(tick)
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
        handler.removeCallbacks(tick)
        duration.text = "已保存"
        duration.contentDescription = "已保存 $path"
        pause.visibility = GONE
        cancel.visibility = GONE
        microphone.visibility = GONE
        stop.text = "查看"
        stop.contentDescription = "查看录屏"
        stop.setOnClickListener { onOpenSaved?.invoke() }
        dot.background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(0xFF66BB6A.toInt()) }
        postDelayed({ if (isAttachedToWindow) release() }, 10_000)
    }

    fun showFailure(message: String) {
        finished = true
        handler.removeCallbacks(tick)
        duration.text = message
        duration.contentDescription = message
        pause.visibility = GONE
        microphone.visibility = GONE
        stop.visibility = GONE
        cancel.text = "关闭"
        cancel.contentDescription = "关闭录屏提示"
        cancel.setOnClickListener { release() }
        dot.background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(0xFFE53935.toInt()) }
        postDelayed({ if (isAttachedToWindow) release() }, 8_000)
    }

    var onReleased: (() -> Unit)? = null

    fun release() {
        if (!isAttachedToWindow) { onReleased?.invoke(); return }
        handler.removeCallbacks(tick)
        onReleased?.invoke()
    }

    private fun togglePause() {
        if (finished) return
        setPaused(!paused)
        onTogglePause?.invoke(paused)
    }

    private fun elapsed(): Long {
        val now = SystemClock.elapsedRealtime()
        return if (paused) pausedAt - startedAt - pausedTotal else now - startedAt - pausedTotal
    }

    private fun button(label: String, accent: Boolean = false): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isSingleLine = true
            maxLines = 1
            minWidth = dp(44)
            minHeight = dp(36)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = buttonBackground(false, accent)
            isClickable = true
            isFocusable = true
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
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
