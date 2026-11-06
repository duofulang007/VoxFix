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
    private val windowLocation = IntArray(2)
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
        // The region is in screen pixels. An accessibility overlay window can start below the status
        // bar, so convert through the window's real screen position instead of assuming (0, 0).
        getLocationOnScreen(windowLocation)
        val dx = windowLocation[0].toFloat()
        val dy = windowLocation[1].toFloat()
        // 4dp frame offset with a 2dp stroke leaves the inner edge 3dp (about 8px on a 2.75x screen)
        // outside the selection, so neither the encoder's odd-size expansion nor bilinear sampling
        // can pick the border up.
        val inset = 4f * density
        canvas.drawRect(
            region.left - inset - dx, region.top - inset - dy,
            region.right + inset - dx, region.bottom + inset - dy,
            border
        )
    }
}
