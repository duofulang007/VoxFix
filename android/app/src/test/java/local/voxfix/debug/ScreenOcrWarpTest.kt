package local.voxfix.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOcrWarpTest {
    @Test fun axisAlignedQuadNeedsNoWarp() {
        assertFalse(ScreenOcr.needsWarp(
            floatArrayOf(0f, 100f, 100f, 0f), floatArrayOf(0f, 0f, 20f, 20f), 0f))
    }

    @Test fun horizontalQuadWithUnequalOppositeEdgesNeedsWarp() {
        // ML Kit reports angle 0 here, but the bottom edge is 20% shorter: that is perspective.
        assertTrue(ScreenOcr.needsWarp(
            floatArrayOf(10f, 110f, 100f, 20f), floatArrayOf(0f, 0f, 20f, 20f), 0f))
    }

    @Test fun rotatedQuadNeedsWarp() {
        assertTrue(ScreenOcr.needsWarp(
            floatArrayOf(0f, 100f, 100f, 0f), floatArrayOf(0f, 3.5f, 23.5f, 20f), 2f))
    }

    @Test fun degenerateInputIsIgnored() {
        assertFalse(ScreenOcr.needsWarp(floatArrayOf(0f), floatArrayOf(0f), 0f))
    }
}
