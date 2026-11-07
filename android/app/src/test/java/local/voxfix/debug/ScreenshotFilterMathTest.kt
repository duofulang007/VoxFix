package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 上游公式的定点断言（`snow_canvas_filter_render.cpp` / `snow_canvas_filter_avx2.h`）。
 * 全部用不透明像素算，避免混入预乘 alpha 的换算歧义；alpha 相关的两处另有专项。
 */
class ScreenshotFilterMathTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** 强度就是上游的 0..1；NaN 按上游当 1.0。 */
    @Test fun clampKeepsUpstreamUnitInterval() {
        assertEquals(0f, ScreenshotFilterMath.clamp(-1f), 1e-6f)
        assertEquals(1f, ScreenshotFilterMath.clamp(2f), 1e-6f)
        assertEquals(0.5f, ScreenshotFilterMath.clamp(0.5f), 1e-6f)
        assertEquals(1f, ScreenshotFilterMath.clamp(Float.NaN), 1e-6f)
    }

    /** `FilterRenderSpec::resolve`：块边长 `2+10s`、模糊 sigma `0.5+18s`（逻辑像素）。 */
    @Test fun strengthMapsOntoUpstreamFilterScales() {
        assertEquals(2f, ScreenshotFilterMath.mosaicBlockSize(0f), 1e-6f)
        assertEquals(7f, ScreenshotFilterMath.mosaicBlockSize(0.5f), 1e-6f)
        assertEquals(12f, ScreenshotFilterMath.mosaicBlockSize(1f), 1e-6f)
        assertEquals(0.5f, ScreenshotFilterMath.blurSigma(0f), 1e-6f)
        assertEquals(9.5f, ScreenshotFilterMath.blurSigma(0.5f), 1e-6f)
        assertEquals(18.5f, ScreenshotFilterMath.blurSigma(1f), 1e-6f)
    }

    /** 降采样倍率分档：`sigma < 2/4/8/16/32/128` 依次取 1/2/4/8/16/32，其余 64。 */
    @Test fun reductionFactorFollowsUpstreamBands() {
        assertEquals(1, ScreenshotFilterMath.blurReductionFactor(1.9))
        assertEquals(2, ScreenshotFilterMath.blurReductionFactor(2.0))
        assertEquals(2, ScreenshotFilterMath.blurReductionFactor(3.9))
        assertEquals(4, ScreenshotFilterMath.blurReductionFactor(4.0))
        assertEquals(8, ScreenshotFilterMath.blurReductionFactor(8.0))
        assertEquals(16, ScreenshotFilterMath.blurReductionFactor(16.0))
        assertEquals(32, ScreenshotFilterMath.blurReductionFactor(32.0))
        assertEquals(64, ScreenshotFilterMath.blurReductionFactor(128.0))
        assertEquals(64, ScreenshotFilterMath.blurReductionFactor(400.0))
    }

    /** `gaussianBoxRadii`：宽度取奇数，余数决定前几遍用 lowerWidth。 */
    @Test fun gaussianBoxRadiiMatchUpstream() {
        assertEquals(listOf(0, 0, 0), ScreenshotFilterMath.gaussianBoxRadii(0.0).toList())
        // sigma 0.5 时三遍半径都落到 0，即不模糊——上游同样 skip 掉 radius<=0 的遍数。
        assertEquals(listOf(0, 0, 0), ScreenshotFilterMath.gaussianBoxRadii(0.5).toList())
        // qRound(1.5) = 2 → 前两遍 lowerWidth=1，第三遍 upperWidth=3，半径各为 0/0/1。
        assertEquals(listOf(0, 0, 1), ScreenshotFilterMath.gaussianBoxRadii(1.0).toList())
        assertEquals(listOf(1, 1, 2), ScreenshotFilterMath.gaussianBoxRadii(2.0).toList())
        // 支撑 = 半径和 × 倍率，降采样再加两个采样余量。
        assertEquals(56, ScreenshotFilterMath.blurSupportRadius(intArrayOf(1, 2, 2), 8))
        assertEquals(48, ScreenshotFilterMath.blurSupportRadius(ScreenshotFilterMath.gaussianBoxRadii(2.0), 8))
        assertEquals(5, ScreenshotFilterMath.blurSupportRadius(intArrayOf(1, 2, 2), 1))
    }

    /** `axisSample<Factor>`：坐标 → 源像素对 + 0..256 权重。 */
    @Test fun axisSampleProducesUpstreamWeights() {
        val out = IntArray(3)
        ScreenshotFilterMath.axisSample(1, 10, 2, out, 0)
        assertEquals(0, out[0]); assertEquals(1, out[1]); assertEquals(64, out[2])
        ScreenshotFilterMath.axisSample(2, 10, 2, out, 0)
        assertEquals(0, out[0]); assertEquals(1, out[1]); assertEquals(192, out[2])
        ScreenshotFilterMath.axisSample(3, 10, 2, out, 0)
        assertEquals(1, out[0]); assertEquals(2, out[1]); assertEquals(64, out[2])
        // 越界坐标夹到两端，不产生越界下标。
        ScreenshotFilterMath.axisSample(-3, 4, 8, out, 0)
        assertEquals(0, out[0]); assertEquals(0, out[1])
    }

    /** Qt `qRound` 与 Kotlin `Math.round` 在 .5 的负侧不同：-2.5 取 -3。 */
    @Test fun qRoundMatchesQtAtNegativeHalves() {
        assertEquals(2, ScreenshotFilterMath.qRound(1.5))
        assertEquals(-3, ScreenshotFilterMath.qRound(-2.5))
        assertEquals(3, ScreenshotFilterMath.qRound(2.5))
        assertEquals(0, ScreenshotFilterMath.qRound(0.49))
    }

    /** `transformedColor` type 2：luminance = (r*54 + g*183 + b*19 + 128) >> 8，夹到 alpha。 */
    @Test fun grayscaleUsesUpstreamLumaCoefficients() {
        assertEquals(argb(255, 54, 54, 54), ScreenshotFilterMath.grayscale(argb(255, 255, 0, 0)))
        // (255*183 + 128) >> 8 = 182，上游用的是位移而不是除 255。
        assertEquals(argb(255, 182, 182, 182), ScreenshotFilterMath.grayscale(argb(255, 0, 255, 0)))
        assertEquals(argb(255, 19, 19, 19), ScreenshotFilterMath.grayscale(argb(255, 0, 0, 255)))
        assertEquals(argb(255, 255, 255, 255), ScreenshotFilterMath.grayscale(argb(255, 255, 255, 255)))
        assertEquals(argb(255, 0, 0, 0), ScreenshotFilterMath.grayscale(argb(255, 0, 0, 0)))
    }

    /** 预乘数据上，半透明像素的亮度不会超过 alpha。 */
    @Test fun grayscaleClampsLuminanceToAlpha() {
        val premultiplied = argb(64, 64, 64, 64)
        val out = ScreenshotFilterMath.grayscale(premultiplied)
        assertEquals(64, out ushr 24 and 0xFF)
        assertEquals(64, out ushr 16 and 0xFF)
    }

    /** `transformedColor` type 3：alpha − 通道（预乘反色）。 */
    @Test fun invertSubtractsChannelsFromAlpha() {
        assertEquals(argb(255, 0, 255, 255), ScreenshotFilterMath.invert(argb(255, 255, 0, 0)))
        assertEquals(argb(255, 255, 255, 255), ScreenshotFilterMath.invert(argb(255, 0, 0, 0)))
        assertEquals(argb(128, 0, 128, 128), ScreenshotFilterMath.invert(argb(128, 128, 0, 0)))
    }

    /** `scale = round(strength * 131072)`；65536 是原点，意味着强度 0.5 不改变画面。 */
    @Test fun brightnessScaleIsNeutralAtHalfStrength() {
        assertEquals(65_536, ScreenshotFilterMath.brightnessScale(0.5f))
        assertEquals(0, ScreenshotFilterMath.brightnessScale(0f))
        assertEquals(131_072, ScreenshotFilterMath.brightnessScale(1f))
        val pixel = argb(255, 40, 90, 200)
        assertEquals(pixel, ScreenshotFilterMath.brightness(pixel, 65_536))
    }

    @Test fun brightnessPushesTowardsWhiteAndBlack() {
        val pixel = argb(255, 40, 90, 200)
        val brighter = ScreenshotFilterMath.brightness(pixel, 131_072)
        assertEquals(argb(255, 255, 255, 255), brighter)
        val darker = ScreenshotFilterMath.brightness(pixel, 0)
        assertEquals(argb(255, 0, 0, 0), darker)
        // 中间值落在两者之间，且单调。
        val mid = ScreenshotFilterMath.brightness(pixel, 98_304)
        assertEquals(true, (mid ushr 16 and 0xFF) in 40..255)
        assertEquals(true, (mid ushr 8 and 0xFF) in 90..255)
    }

    /** `EmbossResponse`：delta 为 0 时灰阶恒为 `alpha/2`。 */
    @Test fun embossAtZeroDeltaIsHalfAlpha() {
        val flat = argb(255, 10, 20, 30)
        assertEquals(argb(255, 128, 128, 128), ScreenshotFilterMath.emboss(flat, flat, 255, 0.5f))
        assertEquals(argb(64, 32, 32, 32), ScreenshotFilterMath.emboss(flat, flat, 64, 0.5f))
    }

    /** 正样本更亮时变白、更暗时变黑，强度越大越靠近端点。 */
    @Test fun embossRespondsToNeighbourDifference() {
        val dark = argb(255, 0, 0, 0)
        val bright = argb(255, 255, 255, 255)
        val up = ScreenshotFilterMath.emboss(dark, bright, 255, 1f)
        assertEquals(argb(255, 255, 255, 255), up)
        val down = ScreenshotFilterMath.emboss(bright, dark, 255, 1f)
        assertEquals(argb(255, 0, 0, 0), down)
        // 强度 0 时始终是中性灰。
        val neutral = ScreenshotFilterMath.emboss(dark, bright, 255, 0f)
        assertEquals(argb(255, 128, 128, 128), neutral)
    }

    @Test fun embossRadiusFollowsDeviceDensity() {
        assertEquals(1, ScreenshotFilterMath.embossRadius(0f))
        assertEquals(1, ScreenshotFilterMath.embossRadius(1f))
        assertEquals(2, ScreenshotFilterMath.embossRadius(1.5f))
        // K60 密度 2.75 → 1 逻辑像素 = 3 设备像素。
        assertEquals(3, ScreenshotFilterMath.embossRadius(2.75f))
    }
}
