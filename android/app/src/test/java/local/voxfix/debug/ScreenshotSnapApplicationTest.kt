package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snap application tests, ported semantics from Snow Shot 1.1.8
 * `snow-draw-engine-editor/src/snapping.rs` (`resolve_move_snap`, `resolve_resize_snap`)
 * at `afdc340b14e7f92a294a95c374d13ada0d8c7a82` (Apache-2.0).
 */
class ScreenshotSnapApplicationTest {


    private fun rect(minX: Double, minY: Double, w: Double, h: Double) =
        ScreenshotSnapping.DrawRect(minX, minY, minX + w, minY + h)

    private val objectOn = ScreenshotSnapConfig(snapEnabled = true)
    private val gridOn = ScreenshotSnapConfig(gridEnabled = true)

    // ---------------------------------------------------------------- move

    @Test
    fun moveWithNullConfigKeepsBaseDelta() {
        val r = ScreenshotSnapApplication.moveSnap(null, false, rect(0.0, 0.0, 10.0, 10.0), 3.0, 4.0, emptyList())
        assertEquals(3.0, r.dx, 1e-9)
        assertEquals(4.0, r.dy, 1e-9)
        assertTrue(r.guides.isEmpty())
    }

    @Test
    fun moveGridSnapsTranslatedMinCorner() {
        // original at (3,3), base delta (6,4) → target min (9,7); grid 20 → (0,0)
        val r = ScreenshotSnapApplication.moveSnap(gridOn, false, rect(3.0, 3.0, 10.0, 10.0), 6.0, 4.0, emptyList())
        // base 6 + (0-9) = -3, base 4 + (0-7) = -3
        assertEquals(-3.0, r.dx, 1e-9)
        assertEquals(-3.0, r.dy, 1e-9)
        assertTrue(r.guides.isEmpty())
    }

    @Test
    fun gridSnapCannotPullAnObjectPastAndroidCanvasEdge() {
        val result = ScreenshotSnapApplication.moveSnap(gridOn, false, rect(20.0, 20.0, 30.0, 30.0),
            43.0, 20.0, emptyList())
        assertEquals(40.0, result.dx, 1e-9)
        val snappedPastRight = ScreenshotSnapApplication.moveSnap(gridOn, false, rect(20.0, 20.0, 30.0, 30.0),
            57.0, 20.0, emptyList())
        assertEquals(60.0, snappedPastRight.dx, 1e-9)
        val constrained = ScreenshotSnapApplication.constrainMove(snappedPastRight,
            SelBox(20f, 20f, 50f, 50f), SelBox(0f, 0f, 107f, 107f))
        assertEquals(57.0, constrained.dx, 1e-9)
        assertEquals(20.0, constrained.dy, 1e-9)
    }

    @Test
    fun moveObjectSnapsToReferenceEdges() {
        // reference left edge at 100; selection 30..80 moved by base dx 24 → 54..104;
        // edge snap pulls back to 50 → total dx = 20.
        val r = ScreenshotSnapApplication.moveSnap(
            objectOn, false, rect(30.0, 0.0, 50.0, 50.0), 24.0, 0.0,
            listOf(rect(100.0, 0.0, 50.0, 50.0))
        )
        assertEquals(20.0, r.dx, 1e-9)
        assertEquals(0.0, r.dy, 1e-9)
        assertTrue(r.guides.isNotEmpty())
    }

    @Test
    fun moveObjectWithoutPointOrGapSnapsStaysPut() {
        val cfg = ScreenshotSnapConfig(snapEnabled = true, enablePointSnaps = false, enableGapSnaps = false)
        val r = ScreenshotSnapApplication.moveSnap(
            cfg, false, rect(30.0, 0.0, 50.0, 50.0), 24.0, 0.0,
            listOf(rect(100.0, 0.0, 50.0, 50.0))
        )
        assertEquals(24.0, r.dx, 1e-9)
        assertTrue(r.guides.isEmpty())
    }

    @Test
    fun moveShowGuidesOffSuppressesGuides() {
        val cfg = ScreenshotSnapConfig(snapEnabled = true, showGuides = false)
        val r = ScreenshotSnapApplication.moveSnap(
            cfg, false, rect(30.0, 0.0, 50.0, 50.0), 24.0, 0.0,
            listOf(rect(100.0, 0.0, 50.0, 50.0))
        )
        assertEquals(20.0, r.dx, 1e-9)
        assertTrue(r.guides.isEmpty())
    }

    @Test
    fun moveNoneModeKeepsBaseDelta() {
        val r = ScreenshotSnapApplication.moveSnap(
            ScreenshotSnapConfig(), false, rect(30.0, 0.0, 50.0, 50.0), 24.0, 0.0,
            listOf(rect(100.0, 0.0, 50.0, 50.0))
        )
        assertEquals(24.0, r.dx, 1e-9)
        assertTrue(r.guides.isEmpty())
    }

    // ---------------------------------------------------------------- resize

    @Test
    fun resizeGridSnapsOnlyDraggedEdges() {
        // frame 3..17 x 3..17; dragging the right edge (sign +1) leftwards to width 14 →
        // grid 20 snaps max x to 20; min x untouched.
        val r = ScreenshotSnapApplication.resizeSnap(
            gridOn, false, aspectLocked = false,
            unsnappedFrame = rect(3.0, 3.0, 14.0, 14.0),
            draggedXSign = 1.0, draggedYSign = 0.0, referenceRects = emptyList()
        )
        assertEquals(3.0, r.dx, 1e-9) // 20 - 17
        assertEquals(0.0, r.dy, 1e-9)
    }

    @Test
    fun resizeGridSkippedWhileAspectLocked() {
        val r = ScreenshotSnapApplication.resizeSnap(
            gridOn, false, aspectLocked = true,
            unsnappedFrame = rect(3.0, 3.0, 14.0, 14.0),
            draggedXSign = 1.0, draggedYSign = 1.0, referenceRects = emptyList()
        )
        assertEquals(0.0, r.dx, 1e-9)
        assertEquals(0.0, r.dy, 1e-9)
    }

    @Test
    fun resizeObjectUsesSignAnchors() {
        // dragging the right edge (sign +1): END anchor on X. Reference end edge at 100.
        // frame 50..96 → snapped end 100 → dx 4.
        val r = ScreenshotSnapApplication.resizeSnap(
            objectOn, false, aspectLocked = false,
            unsnappedFrame = rect(50.0, 0.0, 46.0, 40.0),
            draggedXSign = 1.0, draggedYSign = 0.0,
            referenceRects = listOf(rect(40.0, 0.0, 60.0, 60.0))
        )
        assertEquals(4.0, r.dx, 1e-9)
        assertEquals(0.0, r.dy, 1e-9)
        assertTrue(r.guides.isNotEmpty())
    }

    @Test
    fun resizeObjectLeftDragUsesStartAnchor() {
        // dragging the left edge (sign -1): START anchor. Reference start edge at 100.
        // frame 106..150 → snapped start 100 → dx -6.
        val r = ScreenshotSnapApplication.resizeSnap(
            objectOn, false, aspectLocked = false,
            unsnappedFrame = rect(106.0, 0.0, 44.0, 40.0),
            draggedXSign = -1.0, draggedYSign = 0.0,
            referenceRects = listOf(rect(100.0, 0.0, 50.0, 60.0))
        )
        assertEquals(-6.0, r.dx, 1e-9)
    }

    @Test
    fun resizeObjectNoDraggedEdgeStaysPut() {
        val r = ScreenshotSnapApplication.resizeSnap(
            objectOn, false, aspectLocked = false,
            unsnappedFrame = rect(50.0, 0.0, 46.0, 40.0),
            draggedXSign = 0.0, draggedYSign = 0.0,
            referenceRects = listOf(rect(40.0, 0.0, 60.0, 60.0))
        )
        assertEquals(0.0, r.dx, 1e-9)
        assertEquals(0.0, r.dy, 1e-9)
        assertTrue(r.guides.isEmpty())
    }

    // ---------------------------------------------------------------- anchors parity

    @Test
    fun anchorsForSignMatchesUpstream() {
        assertEquals(
            listOf(ScreenshotObjectSnapping.Anchor.START),
            ScreenshotSnapApplication.anchorsForSign(-1.0)
        )
        assertEquals(
            listOf(ScreenshotObjectSnapping.Anchor.END),
            ScreenshotSnapApplication.anchorsForSign(1.0)
        )
        assertTrue(ScreenshotSnapApplication.anchorsForSign(0.0).isEmpty())
    }
}
