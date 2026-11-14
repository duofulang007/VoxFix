package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「点一下就回到最前面」修复的纯函数层回归。
 *
 * 完整链路（refresh 后位置恢复）由设备套件 ScreenshotDrawingRefinementChecks 覆盖，
 * 这里锁定恢复算法本身的语义：布局未就绪必须返回 null 等下一帧，而不是把目标夹成 0。
 */
class ScreenshotScrollMemoryTest {
    @Test fun layoutNotReadyYieldsNullInsteadOfZero() {
        assertNull(ScreenshotScrollMemory.clamp(5,0,100))
        assertNull(ScreenshotScrollMemory.clamp(5,100,0))
        assertNull(ScreenshotScrollMemory.clamp(5,-100,100))
    }

    @Test fun clampsIntoScrollableRange() {
        assertEquals(0,ScreenshotScrollMemory.clamp(-3,100,300))
        assertEquals(200,ScreenshotScrollMemory.clamp(500,100,300))
        assertEquals(120,ScreenshotScrollMemory.clamp(120,100,300))
    }

    @Test fun childNarrowerThanViewportCollapsesToStart() {
        assertEquals(0,ScreenshotScrollMemory.clamp(50,200,100))
    }

    @Test fun zeroExtentChildStillCountsAsNotReady() {
        assertNull(ScreenshotScrollMemory.clamp(50,100,0))
    }

    @Test fun needsApplySkipsInvalidAndEqualTargets() {
        assertFalse(ScreenshotScrollMemory.needsApply(0,null))
        assertFalse(ScreenshotScrollMemory.needsApply(50,50))
        assertTrue(ScreenshotScrollMemory.needsApply(0,50))
    }

    @Test fun restoredPositionSurvivesLargeScrollValues() {
        // 用户横滑到行尾（内容 900、视口 320），refresh 重建后应恢复到 580 而不是 0。
        assertEquals(580,ScreenshotScrollMemory.clamp(580,320,900))
    }
}
