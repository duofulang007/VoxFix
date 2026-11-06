package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-corner radius math ported from `corner_radius.rs`; the thresholds mirror the Rust tests
 * shipped with Snow Shot 1.1.8 (handle inset mapping is linear in [min_inset, max_inset]).
 */
class ScreenshotCornerRadiiTest {
    @Test fun insetAndRadiusRoundTrip() {
        val width=200f; val height=100f; val unit=1f
        val maxRadius=ScreenshotCornerRadii.maxFor(width,height,null,0,ScreenshotCornerRadii.TOP_LEFT)
        assertEquals(minOf(width,height).toFloat(),maxRadius,1e-4f)
        for(t in listOf(0f,.25f,.5f,.75f,1f)) {
            val radius=maxRadius*t
            val inset=ScreenshotCornerRadii.inset(width,height,unit,radius,maxRadius)
            assertEquals(radius,ScreenshotCornerRadii.radiusForInset(width,height,unit,inset,maxRadius),1e-3f)
        }
        assertEquals(ScreenshotCornerRadii.minInset(width,height,unit),
            ScreenshotCornerRadii.inset(width,height,unit,0f,maxRadius),1e-4f)
        assertEquals(ScreenshotCornerRadii.maxInset(width,height,unit),
            ScreenshotCornerRadii.inset(width,height,unit,maxRadius,maxRadius),1e-4f)
    }

    @Test fun adjacentCornersConsumeWidthAndHeight() {
        val radii=ScreenshotCornerRadii.set(null,ScreenshotCornerRadii.TOP_RIGHT,30f)
        // TL's width headroom is 200-30=170, its height headroom is 100-0=100 -> height-limited.
        assertEquals(100f,ScreenshotCornerRadii.maxFor(200f,100f,radii,0,ScreenshotCornerRadii.TOP_LEFT),1e-4f)
        // BR is height-limited by TR: 100-30=70.
        assertEquals(70f,ScreenshotCornerRadii.maxFor(200f,100f,radii,0,ScreenshotCornerRadii.BOTTOM_RIGHT),1e-4f)
        assertEquals(100f,ScreenshotCornerRadii.maxFor(200f,100f,radii,0,ScreenshotCornerRadii.TOP_RIGHT),1e-4f)
        assertTrue(ScreenshotCornerRadii.maxFor(50f,50f,radii,0,ScreenshotCornerRadii.TOP_LEFT)>=0f)
    }

    @Test fun dragMapsPointerDistanceToRadius() {
        val width=200f; val height=100f; val unit=1f
        // Top-left corner at (-100,-50): inward distance along the diagonal.
        val near=ScreenshotCornerRadii.dragRadius(width,height,unit,ScreenshotCornerRadii.TOP_LEFT,null,0,
            ScreenshotDrawingPoint(-100f,-50f),false)
        assertEquals(0f,near,1e-4f)
        val deep=ScreenshotCornerRadii.dragRadius(width,height,unit,ScreenshotCornerRadii.TOP_LEFT,null,0,
            ScreenshotDrawingPoint(-100f+1000f,-50f+1000f),false)
        assertEquals(ScreenshotCornerRadii.maxFor(width,height,null,0,ScreenshotCornerRadii.TOP_LEFT),deep,1e-4f)
        // Shift drags all four: the limit is min(width,height)/2 regardless of the other corners.
        val uniform=ScreenshotCornerRadii.dragRadius(width,height,unit,ScreenshotCornerRadii.TOP_LEFT,null,0,
            ScreenshotDrawingPoint(1000f,1000f),true)
        assertEquals(50f,uniform,1e-4f)
    }

    @Test fun setKeepsIndependentCorners() {
        var radii=ScreenshotCornerRadii.set(null,ScreenshotCornerRadii.TOP_LEFT,10f)
        radii=ScreenshotCornerRadii.set(radii,ScreenshotCornerRadii.BOTTOM_RIGHT,20f)
        assertEquals(listOf(10f,0f,20f,0f),radii)
        assertEquals(10f,ScreenshotCornerRadii.radius(radii,ScreenshotCornerRadii.TOP_LEFT,83),1e-4f)
    }
}
