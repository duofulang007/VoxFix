package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenshotScrollControlsTest {
    @Test fun swipeStartsInsideTheFarSideAndMovesTowardTheHead() {
        val swipe = ScreenshotScrollCapture.Companion::swipe
        assertEquals(ScreenshotScrollCapture.Companion.Swipe(50f,80f,50f,50f), swipe(20,20,80,110,false,1f/3f))
        assertEquals(ScreenshotScrollCapture.Companion.Swipe(60f,65f,40f,65f), swipe(20,20,80,110,true,1f/3f))
        // Adaptive auto step is clamped to 0.15–0.60 of the region.
        assertEquals(ScreenshotScrollCapture.Companion.Swipe(50f,92f,50f,38f), swipe(20,20,80,110,false,2f/3f))
    }

    @Test fun trimLimitsKeepAtLeastOnePixelAndCanBeRestored() {
        val trim = ScreenshotScrollTrim(100)
        trim.trimHead(35)
        trim.trimTail(90)
        assertEquals(35,trim.start)
        assertEquals(36,trim.end)
        assertEquals(1,trim.size)
        trim.trimTail(0)
        trim.trimHead(0)
        assertEquals(100,trim.size)
    }
    @Test fun controlsStayOutsideCapturedPixels() {
        assertEquals(30, ScreenshotScrollCapture.controlTop(120, 970, 30, 1000, 56))
        assertEquals(944, ScreenshotScrollCapture.controlTop(45, 900, 30, 1000, 56))
        assertNull(ScreenshotScrollCapture.controlTop(45, 980, 30, 1000, 56))
    }

    @Test fun captureGrowthResetsTheGrowingEdgeAndKeepsTheOppositeCrop() {
        val trim = ScreenshotScrollTrim(100)
        trim.trimHead(12)
        trim.trimTail(7)
        trim.grow(140, prepend = false)
        assertEquals(12, trim.start)
        assertEquals(140, trim.end)
        trim.trimTail(9)
        trim.grow(155, prepend = true)
        assertEquals(0, trim.start)
        assertEquals(146, trim.end)
    }
}
