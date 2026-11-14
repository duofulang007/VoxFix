package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotStepperMathTest {
    @Test fun holdAcceleratesByTick() {
        assertEquals(1,ScreenshotStepperMath.stepForTick(0))
        assertEquals(1,ScreenshotStepperMath.stepForTick(11))
        assertEquals(2,ScreenshotStepperMath.stepForTick(12))
        assertEquals(2,ScreenshotStepperMath.stepForTick(29))
        assertEquals(4,ScreenshotStepperMath.stepForTick(30))
        assertEquals(4,ScreenshotStepperMath.stepForTick(400))
    }

    @Test fun clampsToBoundsOnBothEnds() {
        assertEquals(6f,ScreenshotStepperMath.clamp(1f,6f,256f),0f)
        assertEquals(256f,ScreenshotStepperMath.clamp(999f,6f,256f),0f)
        assertEquals(10f,ScreenshotStepperMath.clamp(10f,10f,30f),0f)
        assertEquals(0,ScreenshotStepperMath.clamp(-5,0,83))
        assertEquals(83,ScreenshotStepperMath.clamp(400,0,83))
    }

    @Test fun invertedBoundsCollapseToMinimum() {
        assertEquals(5f,ScreenshotStepperMath.clamp(9f,5f,1f),0f)
        assertEquals(5,ScreenshotStepperMath.clamp(9,5,1))
    }

    @Test fun singleStepsCountTheWholeFontRange() {
        // 6→256 逐格是 250 步；加速之后（tick30 起每次 4 格）约 5 秒走完，够用且不过冲。
        assertEquals(250,ScreenshotStepperMath.singleSteps(ScreenshotFontSize.MIN,ScreenshotFontSize.MAX_TEXT,1f))
        assertEquals(0,ScreenshotStepperMath.singleSteps(6f,6f,1f))
        assertEquals(0,ScreenshotStepperMath.singleSteps(6f,256f,0f))
    }
}
