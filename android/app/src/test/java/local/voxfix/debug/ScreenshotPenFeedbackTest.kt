package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class ScreenshotPenFeedbackTest {
    @Test fun lensStaysAboveTheFingerExceptAtTheTopEdge() {
        val screen=SelBox(0f,0f,360f,720f)
        val middle=ScreenshotPenFeedback.lensBounds(ScreenshotDrawingPoint(180f,400f),screen,1f)!!
        assertTrue(middle.bottom<400f-30f)
        val top=ScreenshotPenFeedback.lensBounds(ScreenshotDrawingPoint(180f,8f),screen,1f)!!
        assertTrue(top.top>8f+30f)
    }
    @Test fun lensRemainsVisibleAtEveryCornerAndInASmallPinEditor() {
        for(screen in listOf(SelBox(0f,0f,360f,720f),SelBox(20f,40f,120f,160f))) {
            for(point in ScreenshotSelection.corners(screen)) {
                val box=ScreenshotPenFeedback.lensBounds(point,screen,1f)!!
                assertTrue(box.left>=screen.left && box.top>=screen.top && box.right<=screen.right && box.bottom<=screen.bottom)
            }
        }
        assertNull(ScreenshotPenFeedback.lensBounds(ScreenshotDrawingPoint(0f,0f),SelBox(0f,0f,8f,8f),1f))
    }
    @Test fun smallPinCanvasCanPlaceLensOutsideItWithinTheWindow() {
        val windowRelativeToPin=SelBox(-100f,-300f,260f,420f)
        val box=ScreenshotPenFeedback.lensBounds(ScreenshotDrawingPoint(20f,20f),windowRelativeToPin,1f)!!
        assertTrue(box.bottom<0f)
        assertTrue(box.left>=windowRelativeToPin.left && box.right<=windowRelativeToPin.right)
    }
}
