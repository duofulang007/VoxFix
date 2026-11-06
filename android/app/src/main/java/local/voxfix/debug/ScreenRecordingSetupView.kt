package local.voxfix.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Recording setup frame: the idle-stage counterpart of Snow Shot's `ScreenRecordingAreaWindow`.
 *
 * Upstream opens the recording area in `Idle`, keeps the export settings panel expanded by default
 * and lets the mouse drag the frame (move) or its edges (resize) before the user presses start
 * (`screenrecordingareawindow.cpp`, `resizeEdgesAt` / `beginRegionDrag`). The phone had no such
 * stage: pressing record went straight to the projection consent and started capturing.
 *
 * Differences, all forced by the platform and recorded in the T28 document:
 * - Upstream shows no handles because the mouse cursor names the edge; touch needs eight visible
 *   handles and a wider grab band.
 * - Upstream hit-tests inside the frame only; the handles straddle the border here, so the band is
 *   tested on both sides.
 * - Upstream lets the window hang off screen; this frame is clamped to the captured display.
 */
internal class ScreenRecordingSetupView(
    context: Context,
    initial: ScreenRecordingRegion,
    private val bounds: ScreenRecordingRegion
) : View(context) {
    var onRegionChanged: ((ScreenRecordingRegion) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val handle = RectF()

    private var region = initial
    private var originX = 0
    private var originY = 0

    private val edgeHitWidth = dp(20)
    private val minimumExtent = dp(MINIMUM_DP)
    private val handleSize = dp(10)

    private var pointerId = -1
    private var pressed = ScreenRecordingRegionEdit.NONE
    private var previousEdges = 0
    private var startRegion = initial
    private var downRawX = 0f
    private var downRawY = 0f

    fun currentRegion() = region

    /** Window origin in screen pixels; the view may be clamped against a display edge. */
    fun place(value: ScreenRecordingRegion, x: Int, y: Int) {
        region = value
        originX = x
        originY = y
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Frame geometry matches ScreenRecordingAreaView: a 2dp stroke centred 4dp outside the
        // captured region, so neither the border nor its antialiasing can enter the crop.
        val inset = 4f * density
        border.strokeWidth = 2f * density
        border.color = IDLE_COLOR
        val frame = RectF(
            region.left - originX - inset, region.top - originY - inset,
            region.right - originX + inset, region.bottom - originY + inset
        )
        canvas.drawRect(frame, border)

        handleFill.color = 0xFFFFFFFF.toInt()
        handleStroke.strokeWidth = 1.5f * density
        handleStroke.color = IDLE_COLOR
        val half = handleSize / 2f
        for ((cx, cy) in handleCentres(frame)) {
            handle.set(cx - half, cy - half, cx + half, cy + half)
            canvas.drawRoundRect(handle, dp(2).toFloat(), dp(2).toFloat(), handleFill)
            canvas.drawRoundRect(handle, dp(2).toFloat(), dp(2).toFloat(), handleStroke)
        }
    }

    private fun handleCentres(frame: RectF): List<Pair<Float, Float>> {
        val cx = frame.centerX()
        val cy = frame.centerY()
        return listOf(
            frame.left to frame.top, cx to frame.top, frame.right to frame.top,
            frame.right to cy, frame.right to frame.bottom,
            cx to frame.bottom, frame.left to frame.bottom, frame.left to cy
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                downRawX = event.rawX
                downRawY = event.rawY
                startRegion = region
                previousEdges = 0
                pressed = ScreenRecordingRegionEdit.hit(
                    region, event.rawX.toInt(), event.rawY.toInt(), edgeHitWidth
                )
                // A press in the window margin that misses the frame is swallowed: the window is
                // deliberately small, and dropping the gesture would send it to the app below.
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.findPointerIndex(pointerId) < 0) return true
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                val next = when {
                    pressed == ScreenRecordingRegionEdit.NONE -> region
                    pressed == ScreenRecordingRegionEdit.MOVE ->
                        ScreenRecordingRegionEdit.move(startRegion, dx, dy)
                    else -> {
                        val edit = ScreenRecordingRegionEdit.edit(
                            startRegion, pressed, dx, dy, previousEdges, minimumExtent
                        )
                        previousEdges = edit.edges
                        edit.region
                    }
                }
                val clamped = ScreenRecordingRegionEdit.clamp(next, bounds, minimumExtent)
                if (clamped != region) {
                    region = clamped
                    invalidate()
                    onRegionChanged?.invoke(clamped)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointerId = -1
                pressed = ScreenRecordingRegionEdit.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    companion object {
        /** Idle border colour from `screenrecordingareawindow.cpp` (`kIdleColor`). */
        const val IDLE_COLOR = 0xFF4096FF.toInt()
        /** Screen-space margin kept around the frame for the handles and their grab band. */
        const val MARGIN_DP = 24
        /**
         * Upstream floors a region at 10 physical pixels because a mouse can aim exactly; a finger
         * cannot, so the touch build keeps a 32dp square as the smallest capturable frame.
         */
        const val MINIMUM_DP = 32
    }
}
