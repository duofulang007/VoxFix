package local.voxfix.debug

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视口数学的回归网。
 *
 * 这里的断言全部来自「用户能看到什么」：
 * - 捏合时焦点下的像素不能滑（这是"重影/画面跑偏"的数学根因）；
 * - 跨过"内容恰好占满视图"的临界点时位置不能跳（这是"缩放抽搐"的根因）；
 * - 屏幕 ↔ 图片坐标必须严格互逆（放大后画笔要落在正确像素上）。
 */
class ScreenshotViewportTest {

    /** 真实长图：916×10192 在 1080×2400 的窗口里，整幅比例约 0.2355。 */
    private val longImage = 916 to 10192
    private val view = 1080f to 2400f
    private val longFit get() = ScreenshotCanvasFit.of(longImage.first, longImage.second, 1080, 2400)

    private fun viewport(): ScreenshotViewport =
        ScreenshotViewport(longImage.first, longImage.second).apply {
            fit(view.first, view.second, longFit)
        }

    @Test
    fun fitCentersImageAtContainScale() {
        val vp = viewport()
        assertEquals(longFit.containScale, vp.scale, 1e-4f)
        val expectedX = (view.first - longImage.first * longFit.containScale) / 2f
        assertEquals(expectedX, vp.offsetX, 1e-3f)
        assertEquals(0f, vp.offsetY, 1e-3f)
    }

    /**
     * 焦点不动的验证必须在「内容两个方向都超出视口」的状态下做。
     *
     * 内容比视口窄时它必须贴着视口边缘（不能露出空白），这时边界夹取会主动改变 offset，
     * 焦点自然会跟着移——那是产品要求，不是数学错误。
     */
    private val squareFit get() = ScreenshotCanvasFit.of(1080, 1080, 1080, 1080)
    private fun squareViewport(zoom: Float): ScreenshotViewport =
        ScreenshotViewport(1080, 1080).apply {
            fit(1080f, 1080f, squareFit)
            zoomBy(zoom, 540f, 540f, squareFit)
        }

    @Test
    fun zoomKeepsFocusPixelUnderFinger() {
        val vp = squareViewport(2f)
        assertTrue("前置条件：内容必须两向都超出视口", vp.displayWidth > 1080f && vp.displayHeight > 1080f)
        repeat(12) { step ->
            val focusX = 300f + step * 40f
            val focusY = 350f + step * 45f
            val imageX = vp.toImageX(focusX)
            val imageY = vp.toImageY(focusY)
            vp.zoomBy(1.05f, focusX, focusY, squareFit)
            val driftX = abs(vp.toScreenX(imageX) - focusX)
            val driftY = abs(vp.toScreenY(imageY) - focusY)
            assertTrue("第 $step 档焦点水平漂移 $driftX px", driftX < 0.5f)
            assertTrue("第 $step 档焦点垂直漂移 $driftY px", driftY < 0.5f)
        }
    }

    @Test
    fun zoomOutKeepsFocusPixelUnderFinger() {
        val vp = squareViewport(3.5f)
        val focusX = 780f
        val focusY = 620f
        val imageX = vp.toImageX(focusX)
        val imageY = vp.toImageY(focusY)
        vp.zoomBy(1f / 1.3f, focusX, focusY, squareFit)
        assertTrue(abs(vp.toScreenX(imageX) - focusX) < 0.5f)
        assertTrue(abs(vp.toScreenY(imageY) - focusY) < 0.5f)
    }

    /** 缩放再原路放大回去，比例必须回到起点（浮点容差内）。 */
    @Test
    fun zoomIsReversible() {
        val vp = viewport()
        val start = vp.scale
        vp.zoomBy(2.4f, 540f, 1200f, longFit)
        vp.zoomBy(1f / 2.4f, 540f, 1200f, longFit)
        assertEquals(start, vp.scale, 1e-3f)
    }

    /** 跨过「内容宽 == 视图宽」临界点时不能跳回居中，那正是缩放抽搐的来源。 */
    @Test
    fun offsetDoesNotJumpWhenContentCrossesViewport() {
        val vp = viewport()
        var previous = vp.offsetX
        var maxJump = 0f
        repeat(160) {
            vp.zoomBy(1.02f, 540f, 1200f, longFit)
            maxJump = maxOf(maxJump, abs(vp.offsetX - previous))
            previous = vp.offsetX
        }
        assertTrue("单档 offset 跳变 ${maxJump}px，边界夹取不连续", maxJump < 120f)
        // 内容宽度从 216 一路放大到超过视图，偏移必须始终在合法区间内
        val contentWidth = longImage.first * vp.scale
        assertTrue(vp.offsetX <= maxOf(0f, view.first - contentWidth) + 0.5f)
        assertTrue(vp.offsetX >= minOf(0f, view.first - contentWidth) - 0.5f)
    }

    @Test
    fun screenAndImageCoordinatesAreInverse() {
        val vp = viewport()
        vp.zoomBy(2.7f, 400f, 900f, longFit)
        for (x in listOf(0f, 123.5f, 540f, 1079f)) {
            for (y in listOf(0f, 88f, 1200f, 2399f)) {
                assertEquals(x, vp.toScreenX(vp.toImageX(x)), 1e-3f)
                assertEquals(y, vp.toScreenY(vp.toImageY(y)), 1e-3f)
            }
        }
    }

    @Test
    fun scaleIsClampedBetweenContainAndMax() {
        val vp = viewport()
        val min = vp.minScale(longFit)
        val max = vp.maxScale(longFit)
        assertTrue("上限必须大于下限", max > min)
        repeat(40) { vp.zoomBy(1.3f, 540f, 1200f, longFit) }
        assertEquals(max, vp.scale, 1e-3f)
        repeat(80) { vp.zoomBy(0.7f, 540f, 1200f, longFit) }
        assertEquals(min, vp.scale, 1e-3f)
        // 整幅就是下限：长图最大倍率应让 1:1 可达
        assertTrue("长图应能放到 1:1 以上", max >= 1f)
    }

    @Test
    fun dpToImageShrinksAsScaleGrows() {
        val vp = viewport()
        val atContain = vp.dpToImage(8f, 3f)
        vp.zoomBy(4f, 540f, 1200f, longFit)
        val atFourTimes = vp.dpToImage(8f, 3f)
        assertEquals(atContain / 4f, atFourTimes, 1e-2f)
    }

    /** 可见图像区域必须夹进图片范围，否则 drawBitmap 的 srcRect 会越界。 */
    @Test
    fun visibleRegionStaysInsideImage() {
        val vp = viewport()
        vp.zoomBy(5f, 540f, 1200f, longFit)
        vp.panBy(0f, -20000f)
        assertTrue(vp.imageTop >= 0f)
        assertTrue(vp.imageBottom <= longImage.second.toFloat() + 0.5f)
        assertTrue(vp.imageLeft >= 0f)
        assertTrue(vp.imageRight <= longImage.first.toFloat() + 0.5f)
    }

    /**
     * 编辑已保存的截图时，画布只显示当初框选的那一块。
     *
     * 真实记录：整屏底图 1080×2400，保存的 viewport 是 (203,602)-(938,1614)。
     * 如果按整图铺满，用户会看到一张陌生的全屏截图、找不到自己截的内容。
     */
    @Test
    fun contentRectLimitsCanvasToSelection() {
        val vp = ScreenshotViewport(1080, 2400)
        vp.setContentRect(203f, 602f, 938f, 1614f)
        val fit = ScreenshotCanvasFit.of(735, 1012, 1080, 2400)
        vp.fit(1080f, 2400f, fit)
        assertEquals(735f, vp.displayWidth, 0.5f)
        assertEquals(1012f, vp.displayHeight, 0.5f)
        // 可见区域绝不越过选区
        assertTrue("可见左边界 ${vp.imageLeft} 越过选区", vp.imageLeft >= 203f - 0.5f)
        assertTrue("可见右边界 ${vp.imageRight} 越过选区", vp.imageRight <= 938f + 0.5f)
        assertTrue("可见上边界 ${vp.imageTop} 越过选区", vp.imageTop >= 602f - 0.5f)
        assertTrue("可见下边界 ${vp.imageBottom} 越过选区", vp.imageBottom <= 1614f + 0.5f)
        // 选区左上角就落在画布左上角
        assertEquals(vp.offsetX, vp.toScreenX(203f), 0.5f)
        assertEquals(vp.offsetY, vp.toScreenY(602f), 0.5f)
        // 选区内部坐标往返仍自洽
        assertEquals(500f, vp.toScreenX(vp.toImageX(500f)), 0.5f)
        assertEquals(900f, vp.toScreenY(vp.toImageY(900f)), 0.5f)
    }

    /** 旋转/分屏后保留倍率，而不是把用户丢回整幅。 */
    @Test
    fun resizeKeepsZoomRatio() {
        val vp = viewport()
        vp.zoomBy(3f, 540f, 1200f, longFit)
        val ratioBefore = vp.scale / vp.minScale(longFit)
        val portrait = ScreenshotCanvasFit.of(longImage.first, longImage.second, 2400, 1080)
        vp.resize(2400f, 1080f, portrait)
        val ratioAfter = vp.scale / vp.minScale(portrait)
        assertEquals(ratioBefore, ratioAfter, 1e-2f)
    }
}
