package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotStyleTokensTest {
    @Test fun styleRowIsExactlyOneCellPlusPadding() {
        assertEquals(40,ScreenshotToolbarMetrics.STYLE_ROW)
        assertEquals(
            ScreenshotToolbarMetrics.STYLE_CELL+ScreenshotToolbarMetrics.STYLE_PAD_V*2,
            ScreenshotToolbarMetrics.STYLE_ROW)
    }

    @Test fun mainToolbarKeepsTheUpstreamGeometry() {
        assertEquals(32,ScreenshotToolbarMetrics.MAIN_CELL)
        assertEquals(24,ScreenshotToolbarMetrics.MAIN_ICON)
        assertEquals(8,ScreenshotToolbarMetrics.MAIN_GAP)
        assertEquals(8,ScreenshotToolbarMetrics.PANEL_RADIUS)
        assertEquals(1,ScreenshotToolbarMetrics.SEPARATOR_WIDTH)
        assertEquals(16,ScreenshotToolbarMetrics.SEPARATOR_HEIGHT)
    }

    @Test fun touchTargetReachesTheAndroidMinimum() {
        assertTrue("热区必须 ≥48dp",ScreenshotToolbarMetrics.TOUCH_MIN>=48)
        assertTrue("热区必须大于可视尺寸",ScreenshotToolbarMetrics.TOUCH_MIN>ScreenshotToolbarMetrics.MAIN_CELL)
        assertTrue("热区必须大于二级格",ScreenshotToolbarMetrics.TOUCH_MIN>ScreenshotToolbarMetrics.STYLE_CELL)
    }

    @Test fun numericBoundsMatchTheDocumentedRange() {
        assertEquals(0,ScreenshotStyleBounds.CORNER_MIN)
        assertEquals(83,ScreenshotStyleBounds.CORNER_MAX)
        assertEquals(0,ScreenshotStyleBounds.OUTLINE_WIDTH_MIN)
        assertEquals(12,ScreenshotStyleBounds.OUTLINE_WIDTH_MAX)
        assertEquals(10,ScreenshotStyleBounds.ARROW_RATIO_MIN)
        assertEquals(30,ScreenshotStyleBounds.ARROW_RATIO_MAX)
        assertEquals(-90,ScreenshotStyleBounds.WATERMARK_ANGLE_MIN)
        assertEquals(90,ScreenshotStyleBounds.WATERMARK_ANGLE_MAX)
        assertEquals(10,ScreenshotStyleBounds.WATERMARK_GAP_MIN)
        assertEquals(200,ScreenshotStyleBounds.WATERMARK_GAP_MAX)
        // 字号只认 ScreenshotFontSize，不要在这里开第二套口径。
        assertEquals(6f,ScreenshotFontSize.MIN)
        assertEquals(256f,ScreenshotFontSize.MAX_TEXT)
        assertEquals(512f,ScreenshotFontSize.MAX_SERIAL)
        assertEquals(512f,ScreenshotFontSize.MAX_WATERMARK)
    }

    @Test fun detailPanelIsShorterThanThreeRows() {
        assertTrue("单行化后二级栏不该再要 280dp",
            ScreenshotToolbarMetrics.DETAIL_MAX<=ScreenshotToolbarMetrics.STYLE_ROW*3)
        assertTrue("至少放得下一行",ScreenshotToolbarMetrics.DETAIL_MAX>=ScreenshotToolbarMetrics.STYLE_ROW)
    }
}
