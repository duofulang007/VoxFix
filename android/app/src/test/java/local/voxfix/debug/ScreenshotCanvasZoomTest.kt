package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画布缩放纯函数回归：捏合 clamp、双击档位、上限推导。
 * 对应修复：捏合与双击动画打架导致的重影 / 缩不回去（zoom 一律夹回 [1f, maxZoom]）。
 */
class ScreenshotCanvasZoomTest {
    private val wide = ScreenshotCanvasFit.Fit(containScale = 0.5f, widthScale = 1f, canFitWidth = true)
    private val plain = ScreenshotCanvasFit.Fit(containScale = 1f, widthScale = 1f, canFitWidth = false)

    @Test fun clampKeepsZoomInsideOneToMax() {
        assertEquals(1f, ScreenshotCanvasZoom.clamp(0.3f, 4f), 1e-6f)
        assertEquals(1f, ScreenshotCanvasZoom.clamp(1f, 4f), 1e-6f)
        assertEquals(4f, ScreenshotCanvasZoom.clamp(9f, 4f), 1e-6f)
        // max 自身越小时 clamp 也不越界（窗口缩小后旧 zoom 被拉回合法区间）。
        assertEquals(1.5f, ScreenshotCanvasZoom.clamp(6f, 1.5f), 1e-6f)
    }

    @Test fun maxZoomFollowsWidthRatioWithFloorAndCeiling() {
        assertEquals(4f, ScreenshotCanvasZoom.maxZoom(null), 1e-6f)
        // 满宽/整幅 = 2 → 上限 2.4，但地板 4f 更大。
        assertEquals(4f, ScreenshotCanvasZoom.maxZoom(wide), 1e-3f)
        // 比例极大时被天花板 12f 夹住。
        val extreme = ScreenshotCanvasFit.Fit(containScale = 0.05f, widthScale = 1f, canFitWidth = true)
        assertEquals(12f, ScreenshotCanvasZoom.maxZoom(extreme), 1e-3f)
    }

    @Test fun pinchTargetClampsBothDirections() {
        val max = 8f
        assertEquals(1f, ScreenshotCanvasZoom.pinchTarget(1.2f, 0.5f, max), 1e-6f)
        assertEquals(8f, ScreenshotCanvasZoom.pinchTarget(6f, 4f, max), 1e-6f)
        assertEquals(3f, ScreenshotCanvasZoom.pinchTarget(4f, 0.75f, max), 1e-6f)
    }

    @Test fun doubleTapTogglesWholeAndWidth() {
        // 整幅（1f）< 满宽*0.85 → 去满宽；满宽附近 → 回整幅。
        val toWidth = ScreenshotCanvasZoom.doubleTapTarget(1f, wide)!!
        assertEquals(2f, toWidth, 1e-3f)
        assertEquals(1f, ScreenshotCanvasZoom.doubleTapTarget(2f, wide)!!, 1e-3f)
        // 差距过小不做无意义动画：整幅==满宽（普通截图）时双击没有意义。
        assertNull(ScreenshotCanvasZoom.doubleTapTarget(1f, plain))
        assertNull(ScreenshotCanvasZoom.doubleTapTarget(1.005f, plain))
        // 目标档位也吃 maxZoom 上限。
        val capped = ScreenshotCanvasZoom.doubleTapTarget(1f, ScreenshotCanvasFit.Fit(0.1f, 0.2f, true))!!
        assertTrue(capped <= ScreenshotCanvasZoom.maxZoom(ScreenshotCanvasFit.Fit(0.1f, 0.2f, true)) + 1e-4f)
        assertFalse(capped < 1f)
    }
}
