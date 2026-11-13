package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 与 `preview/image-fit.test.mjs` 跑同一张矩阵：两边显示规则必须逐格一致。 */
class ScreenshotCanvasFitTest {
    private fun assertFit(imageW: Int, imageH: Int, viewW: Int, viewH: Int,
                          contain: Float, width: Float, canFit: Boolean) {
        val fit = ScreenshotCanvasFit.of(imageW, imageH, viewW, viewH)
        assertEquals("contain $imageW x $imageH in $viewW x $viewH", contain, fit.containScale, 1e-4f)
        assertEquals("width $imageW x $imageH in $viewW x $viewH", width, fit.widthScale, 1e-4f)
        assertEquals("canFitWidth $imageW x $imageH in $viewW x $viewH", canFit, fit.canFitWidth)
    }

    @Test fun screenSizedCaptureNeedsNoAdaptation() {
        // 普通全屏截图：整幅比例与屏幕一致，两个模式都是 1，不该出现切换入口。
        assertFit(1080, 2400, 1080, 2400, 1f, 1f, false)
    }

    @Test fun tallCaptureCanFillWidth() {
        // 长截图：整幅只有 0.235，按宽度是 1，切换入口必须出现。
        assertFit(916, 10192, 1080, 2400, 0.2355f, 1f, true)
    }

    @Test fun smallImageIsNeverUpscaled() {
        assertFit(200, 100, 1080, 2400, 1f, 1f, false)
    }

    @Test fun wideImageFitsByWidthAlready() {
        // 横图：整幅与按宽度同比例，切换没有意义。
        assertFit(1600, 900, 1080, 2400, 0.675f, 0.675f, false)
    }

    @Test fun moderatelyTallCaptureOffersSwitch() {
        // 高宽比 3.7：整幅 0.6，按宽度 1，差距足够大。
        assertFit(1080, 4000, 1080, 2400, 0.6f, 1f, true)
    }

    @Test fun switchIsHiddenWhenGainIsMarginal() {
        // 只高一点点时切换收益太小，避免工具条上出现一个几乎无变化的按钮。
        val fit = ScreenshotCanvasFit.of(1080, 2450, 1080, 2400)
        assertFalse(fit.canFitWidth)
        assertTrue(fit.containScale > 0.97f)
    }

    @Test fun invalidInputsFallBackToIdentity() {
        assertFit(0, 0, 1080, 2400, 1f, 1f, false)
        assertFit(1080, 2400, 0, 0, 1f, 1f, false)
    }
}
