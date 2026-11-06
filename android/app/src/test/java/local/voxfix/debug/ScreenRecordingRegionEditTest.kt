package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Port checks for Snow Shot `resizegeometry.h` and `screenrecordingareawindow.cpp::resizeEdgesAt`,
 * which the recording setup stage now uses to let the frame be adjusted before recording starts.
 */
class ScreenRecordingRegionEditTest {
    private val bounds = ScreenRecordingRegion(0, 0, 1080, 2400)
    private val frame = ScreenRecordingRegion(100, 200, 400, 700)

    @Test fun cornersGrabBothEdges() {
        assertEquals(
            ScreenRecordingRegionEdit.LEFT or ScreenRecordingRegionEdit.TOP,
            ScreenRecordingRegionEdit.hit(frame, 105, 205, 40)
        )
        assertEquals(
            ScreenRecordingRegionEdit.RIGHT or ScreenRecordingRegionEdit.BOTTOM,
            ScreenRecordingRegionEdit.hit(frame, 395, 695, 40)
        )
    }

    @Test fun edgesGrabOneAxisAndCentreMoves() {
        assertEquals(ScreenRecordingRegionEdit.TOP, ScreenRecordingRegionEdit.hit(frame, 250, 205, 40))
        assertEquals(ScreenRecordingRegionEdit.LEFT, ScreenRecordingRegionEdit.hit(frame, 105, 450, 40))
        assertEquals(ScreenRecordingRegionEdit.MOVE, ScreenRecordingRegionEdit.hit(frame, 250, 450, 40))
        assertEquals(ScreenRecordingRegionEdit.NONE, ScreenRecordingRegionEdit.hit(frame, 900, 1200, 40))
    }

    /** The drawn handles straddle the border, so a press just outside the frame still grabs it. */
    @Test fun bandOutsideTheFrameStillGrabsTheEdge() {
        assertEquals(ScreenRecordingRegionEdit.RIGHT, ScreenRecordingRegionEdit.hit(frame, 415, 450, 40))
        assertEquals(ScreenRecordingRegionEdit.BOTTOM, ScreenRecordingRegionEdit.hit(frame, 250, 715, 40))
    }

    /** Upstream caps the band at a quarter of the extent so small frames keep a movable middle. */
    @Test fun smallFrameReservesItsCentreForMoving() {
        val small = ScreenRecordingRegion(0, 0, 80, 80)
        assertEquals(ScreenRecordingRegionEdit.MOVE, ScreenRecordingRegionEdit.hit(small, 40, 40, 40))
        assertEquals(ScreenRecordingRegionEdit.LEFT, ScreenRecordingRegionEdit.hit(small, 10, 40, 40))
    }

    @Test fun pressedEdgeKeepsTheOppositeSideAnchored() {
        val edit = ScreenRecordingRegionEdit.edit(
            frame, ScreenRecordingRegionEdit.RIGHT, 40, 0, 0, 32
        )
        assertEquals(ScreenRecordingRegion(100, 200, 440, 700), edit.region)
        assertEquals(ScreenRecordingRegionEdit.RIGHT, edit.edges)
    }

    /** Pressing an edge and dragging past its anchor swaps the effective edge instead of stalling. */
    @Test fun crossingTheAnchorFlipsTheEffectiveEdge() {
        val edit = ScreenRecordingRegionEdit.edit(
            frame, ScreenRecordingRegionEdit.RIGHT, -320, 0, 0, 32
        )
        assertEquals(ScreenRecordingRegionEdit.LEFT, edit.edges)
        // 100 (the old left edge) stays put as the anchor; the minimum then grows back to 32.
        assertEquals(ScreenRecordingRegion(68, 200, 100, 700), edit.region)
    }

    @Test fun minimumExtentIsAppliedAwayFromTheAnchor() {
        val edit = ScreenRecordingRegionEdit.edit(
            frame, ScreenRecordingRegionEdit.LEFT, 295, 0, 0, 32
        )
        assertEquals(32, edit.region.width)
        assertEquals(400, edit.region.right)
        assertEquals(400 - 32, edit.region.left)
    }

    @Test fun cornerDragResizesBothAxes() {
        val edit = ScreenRecordingRegionEdit.edit(
            frame, ScreenRecordingRegionEdit.LEFT or ScreenRecordingRegionEdit.TOP, 20, 30, 0, 32
        )
        assertEquals(ScreenRecordingRegion(120, 230, 400, 700), edit.region)
    }

    @Test fun moveKeepsTheSizeAndClampsIntoTheDisplay() {
        val moved = ScreenRecordingRegionEdit.move(frame, 40, -30)
        assertEquals(ScreenRecordingRegion(140, 170, 440, 670), moved)
        val clamped = ScreenRecordingRegionEdit.clamp(
            ScreenRecordingRegionEdit.move(frame, -500, 2000), bounds, 32
        )
        assertEquals(ScreenRecordingRegion(0, 2400 - 500, 300, 2400), clamped)
    }

    @Test fun clampPromotesTinyRegionsToTheMinimumExtent() {
        val tiny = ScreenRecordingRegion(500, 500, 510, 505)
        val clamped = ScreenRecordingRegionEdit.clamp(tiny, bounds, 32)
        assertEquals(32, clamped.width)
        assertEquals(32, clamped.height)
        assertEquals(500, clamped.left)
        assertEquals(500, clamped.top)
    }
}
