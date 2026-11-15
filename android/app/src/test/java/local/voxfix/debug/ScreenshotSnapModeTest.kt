package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/** 吸附模式互斥：开网格关对象、开对象关网格、两个都关就是不吸附。 */
class ScreenshotSnapModeTest {
    @Test fun gridWins() {
        assertEquals(false to true, ScreenshotSnapConfig.applyMode(grid = true, objects = true))
    }

    @Test fun objectsOnly() {
        assertEquals(true to false, ScreenshotSnapConfig.applyMode(grid = false, objects = true))
    }

    @Test fun bothOff() {
        assertEquals(false to false, ScreenshotSnapConfig.applyMode(grid = false, objects = false))
    }

    @Test fun defaultsMatchUpstream() {
        assertEquals(20.0, ScreenshotSnapConfig.DEFAULT_GRID_SIZE, 0.0)
        assertEquals(8.0, ScreenshotSnapConfig.DEFAULT_SNAP_DISTANCE, 0.0)
    }
}
