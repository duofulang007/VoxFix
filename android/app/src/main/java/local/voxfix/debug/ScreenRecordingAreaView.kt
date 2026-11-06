package local.voxfix.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/**
 * Border around the recorded region, mirroring Snow Shot's `ScreenRecordingAreaWindow` frame.
 *
 * Upstream draws a 2 physical-pixel border with a 1-pixel transparent gap outside the selection
 * (`screenrecordinggeometry.cpp`, `kPhysicalBorderWidth` / `kPhysicalBorderPadding`) and switches
 * the colour by state: `#F5222D` recording, `#FAAD14` paused and counting down. The phone keeps the
 * same colours and geometry in dp, and the border sits entirely outside the selection so the GL
 * crop never captures it.
 *
 * The window is touch-transparent: taps must keep reaching the app underneath while recording.
 */
internal class ScreenRecordingAreaView(context: Context, private val region: Rect) : View(context) {
    enum class State { COUNTDOWN, RECORDING, PAUSED }

    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var state = State.COUNTDOWN

    fun setState(value: State) {
        if (state == value) return
        state = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        border.strokeWidth = 2f * density
        border.color = when (state) {
            State.RECORDING -> 0xFFF5222D.toInt()
            State.PAUSED, State.COUNTDOWN -> 0xFFFAAD14.toInt()
        }
        val inset = 2f * density
        canvas.drawRect(
            region.left - inset, region.top - inset,
            region.right + inset, region.bottom + inset,
            border
        )
    }
}
