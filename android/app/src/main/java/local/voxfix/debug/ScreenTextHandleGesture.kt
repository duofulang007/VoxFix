package local.voxfix.debug

import kotlin.math.abs

/** A short swipe opens the menu; moving the window requires a stationary long press first. */
internal class ScreenTextHandleGesture(private val slop: Float, private val inwardDistance: Float) {
    enum class Move { NONE, PULL, DRAG }
    private var holding = false
    private var moved = false
    private var dragging = false
    private var pulling = false
    private var inward = 0f
    val shouldOpen get() = holding && pulling && inward >= inwardDistance
    val progress get() = progressFrom(0f)
    fun progressFrom(start: Float) = (start + inward / (inwardDistance * 4)).coerceIn(0f, 1f)
    fun begin() { holding = true; moved = false; dragging = false; pulling = false; inward = 0f }
    fun longPress(): Boolean {
        if (!holding || moved) return false
        dragging = true
        return true
    }
    fun move(dx: Float, dy: Float, right: Boolean): Move {
        if (!holding) return Move.NONE
        if (dragging) return Move.DRAG
        if (abs(dx) > slop || abs(dy) > slop) moved = true
        inward = if (right) -dx else dx
        if (pulling || (inward > slop && inward > abs(dy))) {
            pulling = true
            return Move.PULL
        }
        return Move.NONE
    }
    fun end() { holding = false; dragging = false; pulling = false }
}
