package local.voxfix.debug

import kotlin.math.abs
import kotlin.math.min

/**
 * Pure region-editing geometry for the recording setup stage, ported from Snow Shot
 * `resizegeometry.h` (`dragGeometry` / `anchoredRect`) and `screenrecordingareawindow.cpp`
 * (`resizeEdgesAt`).
 *
 * Kept free of `android.graphics` types so the JVM suite can exercise the edge flipping that the
 * upstream implementation relies on: dragging a pressed edge past its anchor swaps the effective
 * edge instead of inverting or stalling the rectangle.
 */
internal data class ScreenRecordingRegion(
    val left: Int, val top: Int, val right: Int, val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

    fun toRect(): android.graphics.Rect = android.graphics.Rect(left, top, right, bottom)

    companion object {
        fun of(rect: android.graphics.Rect) =
            ScreenRecordingRegion(rect.left, rect.top, rect.right, rect.bottom)
    }
}

internal object ScreenRecordingRegionEdit {
    const val NONE = -1
    const val MOVE = 0
    const val LEFT = 1
    const val TOP = 2
    const val RIGHT = 4
    const val BOTTOM = 8

    /**
     * Which edge set a press grabs, or [MOVE] inside the frame, or [NONE] outside it.
     *
     * Upstream only hit-tests inside the frame with a 6 px band, because a mouse cursor already
     * shows the edge. A finger has no cursor, so the band is expressed in dp by the caller and the
     * test also accepts the band outside the frame — the drawn handles straddle the border.
     */
    fun hit(region: ScreenRecordingRegion, x: Int, y: Int, edgeHitWidth: Int): Int {
        val horizontal = min(edgeHitWidth, region.width / 4).coerceAtLeast(0)
        val vertical = min(edgeHitWidth, region.height / 4).coerceAtLeast(0)
        var edges = 0
        val dx = x - region.left
        val dr = region.right - x
        val dt = y - region.top
        val db = region.bottom - y
        if (dx in -horizontal..horizontal) edges = edges or LEFT
        else if (dr in -horizontal..horizontal) edges = edges or RIGHT
        if (dt in -vertical..vertical) edges = edges or TOP
        else if (db in -vertical..vertical) edges = edges or BOTTOM
        if (edges != 0) return edges
        return if (region.contains(x, y)) MOVE else NONE
    }

    data class Drag(val width: Int, val height: Int, val edges: Int)

    /**
     * Upstream `dragGeometry`: an axis without a pressed edge keeps its extent; a pressed edge
     * measures the distance from its anchor, and the sign decides which edge stays effective.
     */
    fun drag(origin: ScreenRecordingRegion, pressed: Int, dx: Int, dy: Int, previous: Int): Drag {
        var edges = pressed
        fun axis(low: Int, high: Int, extent: Int, movement: Int): Int {
            if (pressed and (low or high) == 0) return extent
            val distance = if (pressed and low != 0) movement - extent else movement + extent
            edges = edges and (low or high).inv()
            edges = edges or when {
                distance < 0 -> low
                distance > 0 -> high
                previous and low != 0 -> low
                else -> high
            }
            return abs(distance)
        }
        val width = axis(LEFT, RIGHT, origin.width, dx)
        val height = axis(TOP, BOTTOM, origin.height, dy)
        return Drag(width, height, edges)
    }

    /** Upstream `anchoredRect`: the pressed edge's far side is the fixed anchor. */
    fun anchored(
        origin: ScreenRecordingRegion, pressed: Int, effective: Int, width: Int, height: Int
    ): ScreenRecordingRegion {
        var x = origin.left
        var y = origin.top
        if (pressed and LEFT != 0) x += origin.width
        if (pressed and TOP != 0) y += origin.height
        if (effective and LEFT != 0) x -= width
        if (effective and TOP != 0) y -= height
        return ScreenRecordingRegion(x, y, x + width, y + height)
    }

    data class Edit(val region: ScreenRecordingRegion, val edges: Int)

    /**
     * Resize applied to [origin] with [dx]/[dy] screen pixels, keeping at least [minimum].
     * [Edit.edges] is the effective edge set the next move must treat as previous.
     */
    fun edit(
        origin: ScreenRecordingRegion, pressed: Int, dx: Int, dy: Int, previous: Int, minimum: Int
    ): Edit {
        val drag = drag(origin, pressed, dx, dy, previous)
        val region = anchored(origin, pressed, drag.edges,
            drag.width.coerceAtLeast(minimum), drag.height.coerceAtLeast(minimum))
        return Edit(region, drag.edges)
    }

    fun move(origin: ScreenRecordingRegion, dx: Int, dy: Int): ScreenRecordingRegion =
        ScreenRecordingRegion(origin.left + dx, origin.top + dy, origin.right + dx, origin.bottom + dy)

    /**
     * Keeps a region inside the captured display and above the minimum extent.
     *
     * Upstream normalized tiny regions to 10 physical pixels and let a window hang off screen; the
     * phone clamps instead, because `ScreenRecorder` crops inside the captured frame and an
     * off-screen edge would silently shift the recorded content.
     */
    fun clamp(
        region: ScreenRecordingRegion, bounds: ScreenRecordingRegion, minimum: Int
    ): ScreenRecordingRegion {
        val width = region.width.coerceIn(min(minimum, bounds.width), bounds.width)
        val height = region.height.coerceIn(min(minimum, bounds.height), bounds.height)
        val left = region.left.coerceIn(bounds.left, bounds.right - width)
        val top = region.top.coerceIn(bounds.top, bounds.bottom - height)
        return ScreenRecordingRegion(left, top, left + width, top + height)
    }
}
