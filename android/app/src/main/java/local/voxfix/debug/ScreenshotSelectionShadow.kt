package local.voxfix.debug

/**
 * 选区阴影资产，逐行移植上游 `screenshotselectionshadowrenderer.cpp::buildShadowAsset`
 * （固定提交）：
 *
 * - 资产是边长 `max(3, (radius + shadow) * 2 + 1)` 的正方形，按九宫格拉伸铺到结果四周；
 * - 每个像素算到「内缩 shadow 的圆角矩形」的距离，距离在 `[0, shadow)` 内才有颜色；
 * - alpha = 0.36 × 颜色 alpha × smoothstep(1 − 距离/shadow)；默认颜色 `#333333`（不透明）。
 *
 * 纯函数（只吐像素数组），JVM 可测；绘制在 `ScreenshotSelectionShadowPainter`。
 */
object ScreenshotSelectionShadow {
    const val PEAK_ALPHA = 0.36f
    const val DEFAULT_COLOR = 0x333333

    fun assetSize(radius: Int, shadow: Int): Int {
        val r = radius.coerceAtLeast(0)
        val s = shadow.coerceAtLeast(1)
        return maxOf(3, (r + s) * 2 + 1)
    }

    /** 圆角矩形的有符号距离（上游 `roundedRectangleDistance`）。 */
    fun distance(x: Double, y: Double, halfWidth: Double, halfHeight: Double, radius: Double): Double {
        val qx = kotlin.math.abs(x) - (halfWidth - radius)
        val qy = kotlin.math.abs(y) - (halfHeight - radius)
        val outsideX = maxOf(qx, 0.0)
        val outsideY = maxOf(qy, 0.0)
        return Math.hypot(outsideX, outsideY) + minOf(maxOf(qx, qy), 0.0) - radius
    }

    /** 返回 size×size 的 ARGB 像素；完全透明的像素是 0。 */
    fun asset(radius: Int, shadow: Int, color: Int = DEFAULT_COLOR): IntArray {
        val r = radius.coerceAtLeast(0)
        val s = shadow.coerceAtLeast(1)
        val size = assetSize(r, s)
        val pixels = IntArray(size * size)
        val center = size / 2.0
        val half = center - s
        val rgb = color and 0xFFFFFF
        for (y in 0 until size) {
            for (x in 0 until size) {
                val d = distance(x + 0.5 - center, y + 0.5 - center, half, half, r.toDouble())
                if (d < 0.0 || d >= s) continue
                val progress = (1.0 - d / s).coerceIn(0.0, 1.0)
                val smooth = progress * progress * (3.0 - 2.0 * progress)
                val alpha = Math.round(255f * PEAK_ALPHA * smooth.toFloat()).coerceIn(0, 255)
                pixels[y * size + x] = (alpha shl 24) or rgb
            }
        }
        return pixels
    }

    /**
     * 九宫格目标矩形（上游 `paintNineSlice`）：四角固定为资产角格大小，边与中心拉伸；
     * 圆角不超过选区短边的一半。
     */
    fun nineSliceRects(contentWidth: Float, contentHeight: Float, effect: Float,
                       cornerRadius: Float): List<ScreenshotMarqueeRect> {
        val radius = cornerRadius.coerceIn(0f, minOf(contentWidth, contentHeight) / 2f)
        val shadow = effect.coerceAtLeast(0f)
        val outer = ScreenshotMarqueeRect(-shadow, -shadow, contentWidth + shadow, contentHeight + shadow)
        val x = floatArrayOf(outer.left, radius, contentWidth - radius, outer.right)
        val y = floatArrayOf(outer.top, radius, contentHeight - radius, outer.bottom)
        val rects = ArrayList<ScreenshotMarqueeRect>(9)
        for (row in 0 until 3) {
            for (column in 0 until 3) {
                rects.add(ScreenshotMarqueeRect(x[column], y[row], x[column + 1], y[row + 1]))
            }
        }
        return rects
    }
}
