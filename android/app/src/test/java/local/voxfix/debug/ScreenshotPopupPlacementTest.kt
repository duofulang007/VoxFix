package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotPopupPlacementTest {
    @Test fun prefersBelowTheTrigger() {
        val point=ScreenshotPopupPlacement.place(400,100,500,140,200,300,1080,2400,4)
        assertEquals(144,point.y)
        assertEquals(350,point.x)          // 居中于 400..500
    }

    @Test fun flipsAboveWhenTheBottomIsTooClose() {
        val point=ScreenshotPopupPlacement.place(400,2100,500,2140,200,300,1080,2400,4)
        assertEquals(2100-4-300,point.y)
    }

    @Test fun clampsHorizontallyOnANarrowScreen() {
        val point=ScreenshotPopupPlacement.place(0,100,40,140,280,200,320,640,4)
        assertEquals(8,point.x)
        assertTrue("窄屏上弹层越出右边界",point.x+280<=320)
    }

    @Test fun staysInsideWhenNeitherSideFits() {
        val point=ScreenshotPopupPlacement.place(400,300,500,340,200,2300,1080,2400,4)
        assertEquals(8,point.y)
    }

    @Test fun neverReturnsOffScreenCoordinates() {
        for(top in listOf(-500,0,100,1200,2380)) {
            val point=ScreenshotPopupPlacement.place(-40,top,10,top+40,300,400,1080,2400,4)
            assertTrue("落点跑到屏幕外：$point",point.x>=8 && point.y>=8)
            assertTrue("水平夹取失效：$point",point.x+300<=1080)
            assertTrue("垂直夹取失效：$point",point.y+400<=2400)
        }
    }
}
