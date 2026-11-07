package local.voxfix.debug

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * 高斯模糊，逐条对齐上游 `snow_canvas_filter_render.cpp` 的 `blur()` / `blurMasked()`。
 *
 * 四步与上游一致：
 * 1. 目标区域外扩 `support` 取源（`requestedSourcePixels`），再按降采样倍率把矩形对齐到画布网格
 *    （`alignUp` 左/上、`alignDown` 右/下，原点即图像原点）；
 * 2. 四抽样降采样：每格取 1/4、3/4 处的 2×2 平均（`downsample`）；
 * 3. 三次水平＋垂直盒滤波，半径由 `gaussianBoxRadii` 给出，越界按边缘 clamp（`horizontalBoxBlur`）；
 * 4. 双线性重建回目标区域（`upsampleBilinearComposited`，这里不需要遮罩混合）。
 *
 * 上游对遮罩区只做局部窗口，所以这里也只在 `crop ± support` 上工作，输出就是 crop 大小的位图。
 * 全部整数运算，且定点除数与上游 `BoxAverage` 相同（`(1<<24)/count` 加半的倒数），
 * 否则整像素断言会因取整差一分而失败。
 */
internal object ScreenshotGaussianBlur {
    /** [sigma] 是设备像素（逻辑 sigma × 密度）。返回 crop 大小的新位图。 */
    fun apply(source: Bitmap, crop: Rect, sigma: Double): Bitmap {
        if (crop.width() < 1 || crop.height() < 1) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val factor = ScreenshotFilterMath.blurReductionFactor(sigma)
        val radii = ScreenshotFilterMath.gaussianBoxRadii(sigma / factor)
        val support = ScreenshotFilterMath.blurSupportRadius(radii, factor)
        val requested = Rect(
            maxOf(0, crop.left - support), maxOf(0, crop.top - support),
            minOf(source.width, crop.right + support), minOf(source.height, crop.bottom + support))
        if (requested.width() < 1 || requested.height() < 1) return copy(source, crop)
        // 网格对齐：上游在 factor>1 时把取样矩形按倍率对齐，保证重建相位稳定。
        val aligned = Rect(
            alignUp(requested.left, factor), alignUp(requested.top, factor),
            alignDown(requested.right, factor), alignDown(requested.bottom, factor))
        val sourceRect = if (aligned.width() < 1 || aligned.height() < 1) requested else aligned
        val sourceWidth = sourceRect.width()
        val sourceHeight = sourceRect.height()
        val pixels = IntArray(sourceWidth * sourceHeight)
        source.getPixels(pixels, 0, sourceWidth, sourceRect.left, sourceRect.top, sourceWidth, sourceHeight)

        val reducedWidth = (sourceWidth + factor - 1) / factor
        val reducedHeight = (sourceHeight + factor - 1) / factor
        var plane = downsample(pixels, sourceWidth, sourceHeight, factor, reducedWidth, reducedHeight)
        for (radius in radii) {
            if (radius <= 0) continue
            plane = boxBlur(plane, reducedWidth, reducedHeight, radius, horizontal = true)
            plane = boxBlur(plane, reducedWidth, reducedHeight, radius, horizontal = false)
        }
        return reconstruct(plane, reducedWidth, reducedHeight, factor, crop, sourceRect)
    }

    private fun copy(source: Bitmap, crop: Rect): Bitmap =
        Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())

    private fun alignDown(value: Int, factor: Int): Int {
        var remainder = value % factor
        if (remainder < 0) remainder += factor
        return value - remainder
    }

    private fun alignUp(value: Int, factor: Int): Int {
        val aligned = alignDown(value, factor)
        return if (aligned == value) value else aligned + factor
    }

    /** `downsample`：2×2 分层抽样后按 2/4 收缩，`count` 只可能是 1／2／4。 */
    private fun downsample(pixels: IntArray, width: Int, height: Int, factor: Int,
                           outWidth: Int, outHeight: Int): IntArray {
        if (factor <= 1) return pixels
        val out = IntArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            val top = y * factor
            val bottom = minOf(height, top + factor)
            val sampleY0 = top + (bottom - top) / 4
            val sampleY1 = top + ((bottom - top) * 3) / 4
            val yCount = if (sampleY0 == sampleY1) 1 else 2
            for (x in 0 until outWidth) {
                val left = x * factor
                val right = minOf(width, left + factor)
                val sampleX0 = left + (right - left) / 4
                val sampleX1 = left + ((right - left) * 3) / 4
                val xCount = if (sampleX0 == sampleX1) 1 else 2
                var alpha = 0; var red = 0; var green = 0; var blue = 0
                for (sy in 0 until yCount) {
                    val row = (if (sy == 0) sampleY0 else sampleY1) * width
                    for (sx in 0 until xCount) {
                        val pixel = pixels[row + if (sx == 0) sampleX0 else sampleX1]
                        alpha += pixel ushr 24 and 0xFF
                        red += pixel ushr 16 and 0xFF
                        green += pixel ushr 8 and 0xFF
                        blue += pixel and 0xFF
                    }
                }
                val count = xCount * yCount
                out[y * outWidth + x] = when (count) {
                    4 -> ((alpha shr 2) shl 24) or ((red shr 2) shl 16) or ((green shr 2) shl 8) or (blue shr 2)
                    2 -> ((alpha shr 1) shl 24) or ((red shr 1) shl 16) or ((green shr 1) shl 8) or (blue shr 1)
                    else -> (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
        }
        return out
    }

    /**
     * `horizontalBoxBlur` / `verticalBoxBlur`：滑动窗口 + 上游定点倒数。
     * 越界样本 clamp 到边缘（不是跳过），所以图边会自然延伸。
     */
    private fun boxBlur(input: IntArray, width: Int, height: Int, radius: Int,
                        horizontal: Boolean): IntArray {
        val output = IntArray(input.size)
        val count = radius * 2 + 1
        val reciprocal = if (count <= 1) (1L shl 24) else ((1L shl 24) + count / 2) / count
        fun average(sum: Int): Int {
            val value = (sum.toLong() * reciprocal + (1L shl 23)) shr 24
            return if (value > 255) 255 else value.toInt()
        }
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        fun index(line: Int, position: Int) = if (horizontal) line * width + position else position * width + line
        for (line in 0 until lines) {
            var alpha = 0; var red = 0; var green = 0; var blue = 0
            fun add(position: Int, sign: Int) {
                val pixel = input[index(line, position.coerceIn(0, length - 1))]
                alpha += (pixel ushr 24 and 0xFF) * sign
                red += (pixel ushr 16 and 0xFF) * sign
                green += (pixel ushr 8 and 0xFF) * sign
                blue += (pixel and 0xFF) * sign
            }
            for (position in -radius..radius) add(position, 1)
            for (position in 0 until length) {
                output[index(line, position)] =
                    (average(alpha) shl 24) or (average(red) shl 16) or (average(green) shl 8) or average(blue)
                add(position - radius, -1)
                add(position + radius + 1, 1)
            }
        }
        return output
    }

    /** 双线性重建：源是降采样平面（相对 [sourceRect]），目标坐标是绝对图像坐标。 */
    private fun reconstruct(plane: IntArray, reducedWidth: Int, reducedHeight: Int, factor: Int,
                            crop: Rect, sourceRect: Rect): Bitmap {
        val outWidth = crop.width()
        val outHeight = crop.height()
        val xSamples = IntArray(outWidth * 3)
        for (x in 0 until outWidth) {
            ScreenshotFilterMath.axisSample(crop.left + x - sourceRect.left, reducedWidth, factor, xSamples, x * 3)
        }
        val ySamples = IntArray(outHeight * 3)
        for (y in 0 until outHeight) {
            ScreenshotFilterMath.axisSample(crop.top + y - sourceRect.top, reducedHeight, factor, ySamples, y * 3)
        }
        val output = IntArray(outWidth * outHeight)
        var firstRow = -1
        var secondRow = -1
        var expandedFirst = IntArray(outWidth)
        var expandedSecond = IntArray(outWidth)
        for (localY in 0 until outHeight) {
            val first = ySamples[localY * 3]
            val second = ySamples[localY * 3 + 1]
            val weight = ySamples[localY * 3 + 2]
            if (first == secondRow) {
                val swap = expandedFirst; expandedFirst = expandedSecond; expandedSecond = swap
                firstRow = secondRow; secondRow = -1
            }
            if (first != firstRow) {
                expandRow(plane, reducedWidth, first, xSamples, expandedFirst)
                firstRow = first
            }
            if (second != firstRow && second != secondRow) {
                expandRow(plane, reducedWidth, second, xSamples, expandedSecond)
                secondRow = second
            }
            val line0 = expandedFirst
            val line1 = if (second == firstRow) expandedFirst else expandedSecond
            val base = localY * outWidth
            for (x in 0 until outWidth) {
                output[base + x] = interpolate(line0[x], line1[x], weight)
            }
        }
        return Bitmap.createBitmap(output, outWidth, outHeight, Bitmap.Config.ARGB_8888)
    }

    private fun expandRow(plane: IntArray, reducedWidth: Int, row: Int, xSamples: IntArray, out: IntArray) {
        val base = row * reducedWidth
        for (x in out.indices) {
            out[x] = interpolate(plane[base + xSamples[x * 3]], plane[base + xSamples[x * 3 + 1]], xSamples[x * 3 + 2])
        }
    }

    /** `interpolatePixel`：按 0..256 权重插值，RG/BA 两路打包算。 */
    private fun interpolate(first: Int, second: Int, weight: Int): Int {
        if (weight <= 0 || first == second) return first
        if (weight >= 256) return second
        val inverse = 256 - weight
        fun mix(a: Int, b: Int): Int {
            val lane = (((a and 0x00FF00FF) * inverse) + ((b and 0x00FF00FF) * weight) + 0x00800080) shr 8
            return lane and 0x00FF00FF
        }
        val redBlue = mix(first, second)
        val alphaGreen = mix(first shr 8, second shr 8)
        return redBlue or (alphaGreen shl 8)
    }
}
