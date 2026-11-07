package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 上游公式的定点断言（`snow_canvas_filter_render.cpp` / `snow_canvas_filter_avx2.h`）。
 * 全部用不透明像素算，避免混入预乘 alpha 的换算歧义；alpha 相关的两处另有专项。
 */
class ScreenshotFilterMathTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun normalizeMapsSliderRangeOntoUnitInterval() {
        assertEquals(0f, ScreenshotFilterMath.normalize(4), 1e-6f)
        assertEquals(1f, ScreenshotFilterMath.normalize(64), 1e-6f)
        assertEquals(0.5f, ScreenshotFilterMath.normalize(34), 1e-6f)
        // 越界值夹住，不产生负强度或超强。
        assertEquals(0f, ScreenshotFilterMath.normalize(0), 1e-6f)
        assertEquals(1f, ScreenshotFilterMath.normalize(999), 1e-6f)
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
