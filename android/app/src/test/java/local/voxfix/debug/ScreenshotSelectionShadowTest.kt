package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选区阴影资产与九宫格，对照上游 `buildShadowAsset` / `paintNineSlice`（固定提交）。
 * 峰值 alpha = 0.36 × 颜色 alpha，默认颜色 #333333。
 */
class ScreenshotSelectionShadowTest {
    @Test fun assetSizeFollowsRadiusAndShadow() {
        assertEquals(21, ScreenshotSelectionShadow.assetSize(0, 10))
        assertEquals(51, ScreenshotSelectionShadow.assetSize(20, 5))
        assertEquals(3, ScreenshotSelectionShadow.assetSize(0, 0))
    }

    /** 资产中心落在内缩后的实心区域内，必须完全透明（九宫格中心会被拉伸）。 */
    @Test fun centerStaysTransparent() {
        val size = ScreenshotSelectionShadow.assetSize(0, 10)
        val pixels = ScreenshotSelectionShadow.asset(0, 10)
        assertEquals(0, pixels[(size / 2) * size + size / 2])
    }

    /** 贴着边缘的一圈最浓，峰值不超过 0.36×255≈92，越往外越淡；中心保持透明。 */
    @Test fun alphaPeaksAtTheEdgeAndFadesOut() {
        val shadow = 10
        val size = ScreenshotSelectionShadow.assetSize(0, shadow)
        val pixels = ScreenshotSelectionShadow.asset(0, shadow)
        val center = size / 2
        val column = (0 until size).map { y -> (pixels[y * size + center] ushr 24) }
        assertEquals("中心必须透明", 0, column[center])
        val peak = column.max()
        assertTrue("峰值 alpha=$peak", peak in 60..92)
        // 从外侧向边缘单调变浓，边缘之后回到 0。
        for (y in 1 until center) assertTrue("第 $y 行比上一行更淡", column[y] >= column[y - 1])
        assertEquals(0, column[center])
    }

    /** 九宫格目标：四角等于资产角格，边与中心拉伸；圆角不超过短边一半。 */
    @Test fun nineSliceRectsMatchTheOriginalLayout() {
        val rects = ScreenshotSelectionShadow.nineSliceRects(100f, 50f, 10f, 8f)
        assertEquals(9, rects.size)
        assertEquals(-10f, rects[0].left, 1e-3f)
        assertEquals(-10f, rects[0].top, 1e-3f)
        assertEquals(8f, rects[1].left, 1e-3f)
        assertEquals(110f, rects[2].right, 1e-3f)
        assertEquals(60f, rects[8].bottom, 1e-3f)
        val clamped = ScreenshotSelectionShadow.nineSliceRects(20f, 10f, 4f, 200f)
        // 圆角被夹到短边一半（5），所以第二列从 5 开始。
        assertEquals(5f, clamped[1].left, 1e-3f)
    }
}
