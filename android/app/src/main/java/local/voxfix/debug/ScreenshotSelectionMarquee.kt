package local.voxfix.debug

/** 一个浮点矩形，供纯函数使用（JVM 可测，不依赖 android.graphics）。 */
data class ScreenshotMarqueeRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * 选区比例锁定 = 1:1（上游 `kNewMarqueeAspectRatio` 与 `kEqualWidthHeightAspectRatio` 都是 1.0）。
 *
 * 两个纯函数逐行移植 `screenshotselectiongeometry.cpp`：
 * - [marquee]：`marqueeScreenshotSelectionRect`——按下点与当前点的**像素格**含首尾（span +1），
 *   同一格按下不算选区（空矩形）。
 * - [lockedSquare]：`aspectRatioLockedMarqueeRect`——按较大跨度撑成正方形，从按下的像素格边缘向外生长，
 *   贴到画布边界时按可用宽高等比收缩。
 */
object ScreenshotSelectionMarquee {
    fun marquee(originX: Float, originY: Float, endX: Float, endY: Float): ScreenshotMarqueeRect {
        val originCellX = Math.round(originX).toFloat()
        val originCellY = Math.round(originY).toFloat()
        val endCellX = Math.round(endX).toFloat()
        val endCellY = Math.round(endY).toFloat()
        val left = minOf(originCellX, endCellX)
        val top = minOf(originCellY, endCellY)
        val width = kotlin.math.abs(endCellX - originCellX) + 1f
        val height = kotlin.math.abs(endCellY - originCellY) + 1f
        if (width == 1f && height == 1f) return ScreenshotMarqueeRect(left, top, left, top)
        return ScreenshotMarqueeRect(left, top, left + width, top + height)
    }

    fun lockedSquare(originX: Float, originY: Float, endX: Float, endY: Float,
                     boundsWidth: Float, boundsHeight: Float): ScreenshotMarqueeRect {
        val inclusive = marquee(originX, originY, endX, endY)
        val originCellX = Math.round(originX).toFloat()
        val originCellY = Math.round(originY).toFloat()
        val endCellX = Math.round(endX).toFloat()
        val endCellY = Math.round(endY).toFloat()
        val horizontalDirection = if (endCellX < originCellX) -1 else 1
        val verticalDirection = if (endCellY < originCellY) -1 else 1
        // ratio = 1：宽取两轴较大者，高与宽相等。
        var width = maxOf(inclusive.width, inclusive.height)
        var height = width
        var horizontalAnchor = if (horizontalDirection < 0) inclusive.right else inclusive.left
        var verticalAnchor = if (verticalDirection < 0) inclusive.bottom else inclusive.top
        horizontalAnchor = horizontalAnchor.coerceIn(0f, boundsWidth)
        verticalAnchor = verticalAnchor.coerceIn(0f, boundsHeight)
        val availableWidth = if (horizontalDirection < 0) horizontalAnchor else boundsWidth - horizontalAnchor
        val availableHeight = if (verticalDirection < 0) verticalAnchor else boundsHeight - verticalAnchor
        val maximumWidth = maxOf(0f, minOf(availableWidth, availableHeight))
        width = minOf(width, maximumWidth)
        height = width
        val left = if (horizontalDirection < 0) horizontalAnchor - width else horizontalAnchor
        val top = if (verticalDirection < 0) verticalAnchor - height else verticalAnchor
        return ScreenshotMarqueeRect(minOf(left, left + width), minOf(top, top + height),
            maxOf(left, left + width), maxOf(top, top + height))
    }
}
