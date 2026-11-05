package local.voxfix.debug

import local.voxfix.debug.ScreenshotBindingGeometry.Bindable
import local.voxfix.debug.ScreenshotBindingGeometry.Shape
import local.voxfix.debug.ScreenshotBindingProjection.OutlineMidPointMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the outline-projection port. Expected values are recomputed from the
 * Snow Shot 1.1.8 formulas (gaps, offsets, ratio normalisation), not from the phone build.
 */
class ScreenshotBindingProjectionTest {

    private fun rect(id: Long = 1L, x: Float = 0f, y: Float = 0f, w: Float = 100f, h: Float = 50f, stroke: Float = 0f) =
        Bindable(id, Shape.RECTANGLE, x, y, w, h, strokeWidth = stroke)

    private fun arrow(
        vararg points: Pair<Float, Float>,
        elbowed: Boolean = false,
        startBinding: ScreenshotFixedPointBinding? = null,
        endBinding: ScreenshotFixedPointBinding? = null
    ) = ScreenshotBindingArrow(
        points = points.map { ScreenshotDrawingPoint(it.first, it.second) },
        elbowed = elbowed,
        startBinding = startBinding,
        endBinding = endBinding
    )

    // ---- side midpoints -----------------------------------------------------------

    @Test
    fun sideMidpointsSitOneHundredthOutsideEachSide() {
        val element = rect()
        val right = ScreenshotBindingProjection.sideMidPoint(doubleArrayOf(1.0, 0.5), element)
        assertEquals(100.01, right[0], 1e-6)
        assertEquals(25.0, right[1], 1e-6)

        val top = ScreenshotBindingProjection.sideMidPoint(doubleArrayOf(0.5, 0.0), element)
        assertEquals(50.0, top[0], 1e-6)
        assertEquals(-0.01, top[1], 1e-6)

        val left = ScreenshotBindingProjection.sideMidPoint(doubleArrayOf(0.0, 0.5), element)
        assertEquals(-0.01, left[0], 1e-6)
        assertEquals(25.0, left[1], 1e-6)

        val bottom = ScreenshotBindingProjection.sideMidPoint(doubleArrayOf(0.5, 1.0), element)
        assertEquals(50.0, bottom[0], 1e-6)
        assertEquals(50.01, bottom[1], 1e-6)
    }

    @Test
    fun sideSectorsFollowTheAngleOfTheRatio() {
        assertEquals(ScreenshotBindingProjection.Side.RIGHT, ScreenshotBindingProjection.shapeSide(1.0, 0.5, Shape.RECTANGLE))
        assertEquals(ScreenshotBindingProjection.Side.BOTTOM, ScreenshotBindingProjection.shapeSide(0.5, 1.0, Shape.RECTANGLE))
        assertEquals(ScreenshotBindingProjection.Side.LEFT, ScreenshotBindingProjection.shapeSide(0.0, 0.5, Shape.RECTANGLE))
        assertEquals(ScreenshotBindingProjection.Side.TOP, ScreenshotBindingProjection.shapeSide(0.5, 0.0, Shape.RECTANGLE))
        // 45 degrees lands in the narrow diagonal sector for rectangles
        assertEquals(ScreenshotBindingProjection.Side.BOTTOM_RIGHT, ScreenshotBindingProjection.shapeSide(1.0, 1.0, Shape.RECTANGLE))
    }

    // ---- outline intersection ------------------------------------------------------

    @Test
    fun theRayCrossesBothInflatedSides() {
        val element = rect()
        val crossings = ScreenshotBindingProjection.intersectOutline(
            doubleArrayOf(-50.0, 25.0), doubleArrayOf(150.0, 25.0), element, 5.0
        ).map { it[0] }.sorted()
        assertEquals(2, crossings.size)
        assertEquals(-5.0, crossings[0], 1e-6)
        assertEquals(105.0, crossings[1], 1e-6)
    }

    @Test
    fun rotationMovesTheCrossingsWithTheElement() {
        val element = rect().copy(angle = (Math.PI / 2).toFloat())
        val crossings = ScreenshotBindingProjection.intersectOutline(
            doubleArrayOf(50.0, -200.0), doubleArrayOf(50.0, 300.0), element, 0.0
        )
        // rotated 90 degrees: the 100-wide body now spans y in [-25, 75]
        assertTrue(crossings.all { kotlin.math.abs(it[0] - 50.0) < 1e-3 })
        assertEquals(2, crossings.size)
    }

    // ---- snapping ------------------------------------------------------------------

    @Test
    fun snapToMidPullsThePointerOntoTheRightSide() {
        val element = rect()
        val snapped = ScreenshotBindingProjection.snapToMid(element, doubleArrayOf(99.0, 25.3), 0.05, 5.0)
        assertNotNull(snapped)
        // the original shifts the pivot by 0.1, so the anchored y is 24.9
        assertEquals(105.0, snapped!![0], 1e-6)
        assertEquals(24.9, snapped[1], 1e-6)
    }

    @Test
    fun snapToMidIgnoresPointersRightOnTheCentre() {
        assertNull(ScreenshotBindingProjection.snapToMid(rect(), doubleArrayOf(50.0, 25.0), 0.05, 5.0))
    }

    @Test
    fun midpointIndicatorsAreHiddenInsideStraightArrowsButNotElbowArrows() {
        val element = rect()
        val pointer = doubleArrayOf(50.0, 25.0)

        val linear = ScreenshotBindingProjection.outlineMidPointSuggestion(pointer, element, 1.0, OutlineMidPointMode.LINEAR)
        assertNull(linear.highlighted)
        assertTrue(linear.near.isEmpty())

        val elbow = ScreenshotBindingProjection.outlineMidPointSuggestion(pointer, element, 1.0, OutlineMidPointMode.ELBOW)
        assertEquals(4, elbow.near.size)

        val disabled = ScreenshotBindingProjection.outlineMidPointSuggestion(pointer, element, 1.0, OutlineMidPointMode.DISABLED)
        assertNull(disabled.highlighted)
        assertTrue(disabled.near.isEmpty())
    }

    @Test
    fun modeSelectionNeverTransposesTheTwoBooleans() {
        assertEquals(OutlineMidPointMode.DISABLED, OutlineMidPointMode.forArrow(elbowed = true, midpointSnappingEnabled = false))
        assertEquals(OutlineMidPointMode.DISABLED, OutlineMidPointMode.forArrow(elbowed = false, midpointSnappingEnabled = false))
        assertEquals(OutlineMidPointMode.LINEAR, OutlineMidPointMode.forArrow(elbowed = false, midpointSnappingEnabled = true))
        assertEquals(OutlineMidPointMode.ELBOW, OutlineMidPointMode.forArrow(elbowed = true, midpointSnappingEnabled = true))
    }

    // ---- fixed point ---------------------------------------------------------------

    @Test
    fun fixedPointRatioIsNormalisedAwayFromTheCentre() {
        val element = rect()
        val centre = ScreenshotBindingProjection.calculateFixedPointForBinding(element, doubleArrayOf(50.0, 25.0))
        assertEquals(0.5001, centre[0], 1e-9)
        assertEquals(0.5001, centre[1], 1e-9)

        val corner = ScreenshotBindingProjection.calculateFixedPointForBinding(element, doubleArrayOf(0.0, 0.0))
        assertEquals(0.0, corner[0], 1e-9)
        assertEquals(0.0, corner[1], 1e-9)
    }

    @Test
    fun straightArrowEndpointLandsOnTheInflatedOutline() {
        val element = rect()
        val line = arrow(50f to 25f, 200f to 25f)
        val bound = ScreenshotBindingProjection.bindPointToOutline(
            line, element, ScreenshotEndpointEdge.START, null, midpointSnappingEnabled = false
        )
        assertEquals(105.0, bound[0], 1e-6)
        assertEquals(25.0, bound[1], 1e-6)
    }

    @Test
    fun elbowArrowAvoidsTheRectangleCornerBeforeIntersecting() {
        val element = rect()
        // pointer past the top-left corner, so the corner guard moves it onto the left edge
        val line = arrow(0f to 0f, -40f to -10f, 60f to 60f, elbowed = true)
        val guarded = ScreenshotBindingProjection.avoidRectangularCorner(true, element, doubleArrayOf(-6.0, -1.0))
        assertEquals(-5.0, guarded[0], 1e-6)
        assertEquals(0.0, guarded[1], 1e-6)
        assertNotNull(
            ScreenshotBindingProjection.bindPointToOutline(
                line, element, ScreenshotEndpointEdge.START, null, midpointSnappingEnabled = true
            )
        )
    }

    // ---- update_bound_point --------------------------------------------------------

    @Test
    fun insideBindingResolvesToThePinnedRatio() {
        val element = rect()
        val binding = ScreenshotFixedPointBinding(1, ScreenshotBindMode.INSIDE, 0.5, 0.5)
        val line = arrow(0f to 0f, 80f to 0f, startBinding = binding)
        val resolved = ScreenshotBindingProjection.updateBoundPoint(
            line, ScreenshotEndpointEdge.START, binding, element, mapOf(1L to element), dragging = false
        )
        assertNotNull(resolved)
        assertEquals(50.01, resolved!![0], 1e-3)
        assertEquals(25.005, resolved[1], 1e-3)
    }

    @Test
    fun aBindingOnAnotherElementDoesNotMoveAPolylineEndpoint() {
        val element = rect()
        val other = rect(id = 2, x = 200f, y = 0f)
        val binding = ScreenshotFixedPointBinding(2, ScreenshotBindMode.INSIDE, 0.5, 0.5)
        val polyline = arrow(0f to 0f, 40f to 10f, 80f to 0f, startBinding = binding)
        assertNull(
            ScreenshotBindingProjection.updateBoundPoint(
                polyline, ScreenshotEndpointEdge.START, binding, element, mapOf(2L to other), dragging = false
            )
        )
    }

    @Test
    fun degenerateArrowWithZeroLengthIsRejected() {
        val element = rect()
        val binding = ScreenshotFixedPointBinding(1, ScreenshotBindMode.INSIDE, 0.5, 0.5)
        val degenerate = arrow(0f to 0f, 0f to 0f, startBinding = binding)
        assertNull(
            ScreenshotBindingProjection.updateBoundPoint(
                degenerate, ScreenshotEndpointEdge.START, binding, element, mapOf(1L to element), dragging = false
            )
        )
    }

    // ---- diagonal projection --------------------------------------------------------

    @Test
    fun diagonalProjectionPutsThePointerOnTheGuideCrossing() {
        val element = rect()
        val line = arrow(200f to 25f, -50f to 25f)
        val projected = ScreenshotBindingProjection.projectFixedPointOntoDiagonal(
            line, doubleArrayOf(200.0, 25.0), element, ScreenshotEndpointEdge.START,
            listOf(element), zoom = 1.0, midpointSnappingEnabled = true
        )
        assertNotNull(projected)
        assertEquals(50.0, projected!![0], 1e-3)
        assertEquals(25.0, projected[1], 1e-3)
    }

    @Test
    fun diagonalProjectionIsSkippedForTinyArrows() {
        val element = rect()
        val tiny = arrow(1f to 1f, 2f to 1f)
        assertNull(
            ScreenshotBindingProjection.projectFixedPointOntoDiagonal(
                tiny, doubleArrayOf(2.0, 1.0), element, ScreenshotEndpointEdge.START,
                listOf(element), zoom = 1.0, midpointSnappingEnabled = false
            )
        )
        assertTrue(tiny.spanWidth < 3.0)
        assertFalse(tiny.spanWidth.isNaN())
    }
}
