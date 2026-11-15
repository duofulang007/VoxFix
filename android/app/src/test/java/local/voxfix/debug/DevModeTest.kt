package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 开发者模式只做连点计数决策，不依赖 Android。 */
class DevModeTest {
    @Test fun sevenTapsInWindowEnables() {
        var taps = 0
        var enabled = false
        for (i in 0 until DevMode.REQUIRED_TAPS) {
            val d = DevMode.decision(taps, if (i == 0) 0 else 100L * i, 100L * i + 50, enabled)
            taps = d.taps
            enabled = d.enabled
        }
        assertTrue(enabled)
        assertEquals(0, taps)
    }

    @Test fun gapResetsCounter() {
        val first = DevMode.decision(3, 1_000, 1_000 + DevMode.WINDOW_MS + 1, false)
        assertEquals(1, first.taps)
        assertFalse(first.enabled)
        assertEquals(DevMode.REQUIRED_TAPS - 1, first.remaining)
    }

    @Test fun alreadyEnabledDoesNotCount() {
        val d = DevMode.decision(5, 1_000, 1_100, true)
        assertTrue(d.enabled)
        assertEquals(0, d.taps)
    }

    @Test fun remainingDecreases() {
        val d = DevMode.decision(2, 1_000, 1_100, false)
        assertEquals(3, d.taps)
        assertEquals(DevMode.REQUIRED_TAPS - 3, d.remaining)
    }
}
