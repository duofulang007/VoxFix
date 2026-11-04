package local.voxfix.debug

import android.graphics.Rect
import android.graphics.RectF

/** Plain float box: the selection rules must stay runnable in a JVM test, without a device. */
internal data class SelBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
    companion object {
        fun of(rect: RectF) = SelBox(rect.left, rect.top, rect.right, rect.bottom)
        fun of(rect: Rect) = SelBox(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
    }
}

/**
 * Selection geometry for already-drawn objects.
 *
 * It stays outside the View so the phone's touch path and the JVM regression execute the same rules
 * instead of two copies that drift apart.
 */
internal object ScreenshotSelection {
    const val NO_HANDLE = -1
    const val MIN_SIZE = 12f
    /** A touch this close to a corner grabs the handle instead of moving the object. */
    const val HANDLE_TOLERANCE = 10f

    fun corners(box: SelBox): List<ScreenshotDrawingPoint> = listOf(
        ScreenshotDrawingPoint(box.left, box.top), ScreenshotDrawingPoint(box.right, box.top),
        ScreenshotDrawingPoint(box.right, box.bottom), ScreenshotDrawingPoint(box.left, box.bottom)
    )

    fun handle(box: SelBox, x: Float, y: Float, tolerance: Float): Int =
        corners(box).indexOfFirst { kotlin.math.hypot(it.x - x, it.y - y) <= tolerance }

    fun inside(box: SelBox, x: Float, y: Float, slack: Float): Boolean =
        x >= box.left - slack && x <= box.right + slack && y >= box.top - slack && y <= box.bottom + slack

    /**
     * Movement on one axis. A box that already fits stays fully inside the capture box; a box that is
     * larger than the capture box may be dragged freely. The previous clamp returned an empty range
     * (`minOf(0,start)`..`maxOf(0,end)`) once the object stuck out on both sides, which froze the axis.
     */
    fun axisDelta(start: Float, size: Float, cropStart: Float, cropEnd: Float, delta: Float): Float {
        val innerMin = cropStart - start
        val innerMax = cropEnd - (start + size)
        return if (innerMin <= innerMax) delta.coerceIn(innerMin, innerMax) else delta
    }

    fun moveX(box: SelBox, crop: SelBox, delta: Float) = axisDelta(box.left, box.width, crop.left, crop.right, delta)
    fun moveY(box: SelBox, crop: SelBox, delta: Float) = axisDelta(box.top, box.height, crop.top, crop.bottom, delta)

    /** The dragged corner follows the finger; the opposite corner stays anchored. */
    fun resize(box: SelBox, handle: Int, x: Float, y: Float, minSize: Float): SelBox = SelBox(
        left = if (handle == 0 || handle == 3) x.coerceAtMost(box.right - minSize) else box.left,
        top = if (handle == 0 || handle == 1) y.coerceAtMost(box.bottom - minSize) else box.top,
        right = if (handle == 0 || handle == 3) box.right else x.coerceAtLeast(box.left + minSize),
        bottom = if (handle == 0 || handle == 1) box.bottom else y.coerceAtLeast(box.top + minSize)
    )
}
