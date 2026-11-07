package local.voxfix.debug

/**
 * 滤镜像素数学，逐条对齐上游固定提交。
 *
 * 出处：`snow_draw_engine_qt/src/rendering/snow_canvas_filter_render.cpp`
 * - 灰度 / 反色：`transformedColor()`（type 2 / type 3），预乘 alpha 语义；
 * - 亮度：`detail::brightnessPixel()`（`snow_canvas_filter_avx2.h`），`scale = round(strength*131072)`，
 *   65536 为原点（不变）；
 * - 浮雕：`EmbossResponse::pixel()` + `EmbossRow`（PixiJS emboss 的 CPU 移植），
 *   正样本取 `(x+r, y+r)`、负样本取 `(x-r, y-r)`，半径来自 `FilterRenderSpec::resolve` 的
 *   `sampling_radius = 1.0` 逻辑像素。
 *
 * 强度换算：上游 `FilterRenderSpec::resolve` 把强度限制在 0..1；灰度与反色的强度**恒为 1.0**
 * （滑块对它们无效），所以 [grayscale]/[invert] 不接收强度。手机滑块的整数区间 4..64 线性映射到
 * 0..1（[normalize]）。
 *
 * 纯整数运算，JVM 可测；位图读写留在 `ScreenshotDrawingFilters`。
 */
internal object ScreenshotFilterMath {
    /** 手机滑块（4..64）→ 上游 0..1。 */
    fun normalize(strength: Int): Float = ((strength - 4) / 60f).coerceIn(0f, 1f)

    /** `transformedColor` type 2：亮阶系数 54/183/19，结果夹到 alpha（预乘）。 */
    fun grayscale(pixel: Int): Int {
        val alpha = pixel ushr 24 and 0xFF
        val red = pixel ushr 16 and 0xFF
        val green = pixel ushr 8 and 0xFF
        val blue = pixel and 0xFF
        val luminance = minOf(alpha, (red * 54 + green * 183 + blue * 19 + 128) shr 8)
        return (alpha shl 24) or (luminance shl 16) or (luminance shl 8) or luminance
    }

    /** `transformedColor` type 3：预乘数据的反色是 `alpha − 通道`。 */
    fun invert(pixel: Int): Int {
        val alpha = pixel ushr 24 and 0xFF
        val red = alpha - (pixel ushr 16 and 0xFF)
        val green = alpha - (pixel ushr 8 and 0xFF)
        val blue = alpha - (pixel and 0xFF)
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    /** `scale = qRound(normalizedStrength * 131072)`；65536 表示不改变。 */
    fun brightnessScale(normalized: Float): Int =
        Math.round(normalized.coerceIn(0f, 1f).toDouble() * 131_072.0).toInt()

    /** `brightnessPixel`：先算白点再按保留系数缩放，结果按 alpha 夹取。 */
    fun brightness(pixel: Int, scale: Int): Int {
        if (scale == 65_536) return pixel
        val alpha = pixel ushr 24 and 0xFF
        val white = (scale - 65_536).coerceAtLeast(0)
        val retained = scale - 2 * white
        val bias = alpha * white + 32_768
        fun channel(value: Int) = minOf(alpha, (value * retained + bias) shr 16)
        return (alpha shl 24) or
            (channel(pixel ushr 16 and 0xFF) shl 16) or
            (channel(pixel ushr 8 and 0xFF) shl 8) or
            channel(pixel and 0xFF)
    }

    /**
     * `EmbossResponse` 的响应表：delta 只有 −765..765 共 1531 种取值，上游同样在每次派发前一次算完
     * （`m_gray` 数组），逐像素只剩一次乘法与取整——否则全屏一帧要几秒。
     */
    fun embossTable(normalized: Float): DoubleArray {
        val strength = normalized.coerceIn(0f, 1f) * 10.0
        return DoubleArray(1531) { index ->
            val delta = index - 765
            (0.5 + strength * delta / (3.0 * 255.0)).coerceIn(0.0, 1.0)
        }
    }

    /**
     * 不透明像素（截图里绝大多数）的查表结果：`qRound(gray * 255)` 只与 delta 有关，
     * 预生成成 Int 表后逐像素只剩一次索引，避免全屏每像素一次双精度取整。
     */
    fun embossOpaqueTable(table: DoubleArray): IntArray =
        IntArray(table.size) { index -> Math.round(table[index] * 255.0).toInt().coerceIn(0, 255) }

    /** 不透明快速路径：alpha 固定 255。 */
    fun embossOpaque(negative: Int, positive: Int, opaqueTable: IntArray): Int {
        val delta = ((positive ushr 16 and 0xFF) + (positive ushr 8 and 0xFF) + (positive and 0xFF)) -
            ((negative ushr 16 and 0xFF) + (negative ushr 8 and 0xFF) + (negative and 0xFF))
        val gray = opaqueTable[delta + 765]
        return (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
    }

    /** `EmbossResponse::pixel`：按正负样本 RGB 和之差查表，灰阶再乘中心 alpha。 */
    fun emboss(negative: Int, positive: Int, centerAlpha: Int, table: DoubleArray): Int {
        val delta = ((positive ushr 16 and 0xFF) + (positive ushr 8 and 0xFF) + (positive and 0xFF)) -
            ((negative ushr 16 and 0xFF) + (negative ushr 8 and 0xFF) + (negative and 0xFF))
        val gray = Math.round(table[delta + 765] * centerAlpha).toInt()
        return (centerAlpha shl 24) or (gray shl 16) or (gray shl 8) or gray
    }

    /** 单点版本，便于断言与一次性调用。 */
    fun emboss(negative: Int, positive: Int, centerAlpha: Int, normalized: Float): Int =
        emboss(negative, positive, centerAlpha, embossTable(normalized))

    /** 浮雕采样半径：上游 `max(1, ceil(1 逻辑像素 × max(1, dpr)))`。 */
    fun embossRadius(density: Float): Int =
        Math.ceil(1.0 * maxOf(1f, density).toDouble()).toInt().coerceAtLeast(1)
}
