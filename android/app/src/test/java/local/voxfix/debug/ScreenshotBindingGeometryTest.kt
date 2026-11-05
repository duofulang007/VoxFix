package local.voxfix.debug

import local.voxfix.debug.ScreenshotBindingGeometry.Bindable
import local.voxfix.debug.ScreenshotBindingGeometry.Bounds
import local.voxfix.debug.ScreenshotBindingGeometry.Heading
import local.voxfix.debug.ScreenshotBindingGeometry.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the T22 binding geometry port. Thresholds and expectations are taken
 * from the Snow Shot 1.1.8 sources listed in ScreenshotBindingGeometry, not from eyeballing
 * the phone build.
 */
class ScreenshotBindingGeometryTest {

    private fun p(x: Double, y: Double) = doubleArrayOf(x, y)

    private fun rect(
        id: Long = 1L, x: Float = 0f, y: Float = 0f, w: Float = 100f, h: Float = 50f,
        angle: Float = 0f, strokeWidth: Float = 0f, zIndex: Double? = null,
        opaque: Boolean? = null, interior: Boolean? = null
    ) = Bindable(
        id = id, shape = Shape.RECTANGLE, x = x, y = y, width = w, height = h,
        angle = angle, strokeWidth = strokeWidth, zIndex = zIndex,
        backgroundOpaque = opaque, interiorHitEnabled = interior
    )

    // ---- binding_geometry.rs ------------------------------------------------------

    @Test
    fun maxBindingDistanceStaysInsideFifteenToThirty() {
        // base = max(5, 15) = 15; base / (zoom * 1.5) then clamped to [15, 30]
        assertEquals(15.0, ScreenshotBindingGeometry.maxBindingDistance(1.0), 1e-9)
        assertEquals(15.0, ScreenshotBindingGeometry.maxBindingDistance(4.0), 1e-9)
        assertEquals(15.0, ScreenshotBindingGeometry.maxBindingDistance(1.5), 1e-9)
        assertEquals(20.0, ScreenshotBindingGeometry.maxBindingDistance(0.5), 1e-9)
        assertEquals(25.0, ScreenshotBindingGeometry.maxBindingDistance(0.4), 1e-9)
        assertEquals(30.0, ScreenshotBindingGeometry.maxBindingDistance(0.25), 1e-9)
    }

    @Test
    fun bindingGapAddsHalfTheStrokeWidth() {
        assertEquals(5.0, ScreenshotBindingGeometry.bindingGap(rect(strokeWidth = 0f), elbowed = false), 1e-9)
        assertEquals(7.0, ScreenshotBindingGeometry.bindingGap(rect(strokeWidth = 4f), elbowed = false), 1e-9)
        assertEquals(7.0, ScreenshotBindingGeometry.bindingGap(rect(strokeWidth = 4f), elbowed = true), 1e-9)
    }

    @Test
    fun fixedPointRatioNeverSitsExactlyOnTheCentre() {
        val centre = ScreenshotBindingGeometry.normalizeFixedPoint(0.5, 0.5)
        assertEquals(0.5001, centre[0], 1e-9)
        assertEquals(0.5001, centre[1], 1e-9)
        val tiny = ScreenshotBindingGeometry.normalizeFixedPoint(0.5 + 5e-5, 0.25)
        assertEquals(0.5001, tiny[0], 1e-9)
        assertEquals(0.25, tiny[1], 1e-9)
        val broken = ScreenshotBindingGeometry.normalizeFixedPoint(Double.NaN, Double.POSITIVE_INFINITY)
        assertEquals(0.5001, broken[0], 1e-9)
        assertEquals(0.5001, broken[1], 1e-9)
    }

    @Test
    fun fixedPointMapsToElementLocalRatioThenRotates() {
        val element = rect(x = 10f, y = 20f, w = 100f, h = 50f)
        val topLeft = ScreenshotBindingGeometry.fixedPointToGlobal(0.0, 0.0, element)
        assertEquals(10.0, topLeft[0], 1e-6)
        assertEquals(20.0, topLeft[1], 1e-6)
        val middle = ScreenshotBindingGeometry.fixedPointToGlobal(0.5, 0.5, element)
        assertEquals(60.0, middle[0], 0.02)
        assertEquals(45.0, middle[1], 0.02)
    }

    // ---- hit_test_shape_geometry.rs ----------------------------------------------

    @Test
    fun diamondVerticesKeepTheFloorHalfPlusOneOffset() {
        val diamond = Bindable(2, Shape.DIAMOND, 0f, 0f, 101f, 61f)
        val v = ScreenshotBindingGeometry.diamondVertices(diamond)
        assertEquals(51.5, v[0][0], 1e-9)   // floor(101)/2 + 1
        assertEquals(0.0, v[0][1], 1e-9)
        assertEquals(31.5, v[1][1], 1e-9)   // floor(61)/2 + 1
        assertEquals(101.0, v[1][0], 1e-9)
    }

    @Test
    fun rectangleInteriorAndOutlineDistanceAgreeWithTheBounds() {
        val element = rect(x = 0f, y = 0f, w = 100f, h = 50f)
        assertTrue(ScreenshotBindingGeometry.isPointInBindable(p(50.0, 25.0), element))
        assertFalse(ScreenshotBindingGeometry.isPointInBindable(p(101.0, 25.0), element))
        // inside: nearest edge wins, 2px from the left border
        assertEquals(2.0, ScreenshotBindingGeometry.distanceToOutline(p(2.0, 25.0), element), 1e-9)
        // outside: straight-line distance to the clamped corner region
        assertEquals(3.0, ScreenshotBindingGeometry.distanceToOutline(p(-3.0, 25.0), element), 1e-9)
        assertEquals(5.0, ScreenshotBindingGeometry.distanceToOutline(p(50.0, -5.0), element), 1e-9)
    }

    @Test
    fun ellipseAndDiamondHitTestsUseTheirOwnOutline() {
        val ellipse = Bindable(3, Shape.ELLIPSE, 0f, 0f, 100f, 50f)
        assertTrue(ScreenshotBindingGeometry.isPointInBindable(p(50.0, 25.0), ellipse))
        assertFalse(ScreenshotBindingGeometry.isPointInBindable(p(5.0, 5.0), ellipse))  // corner outside
        assertEquals(25.0, ScreenshotBindingGeometry.distanceToOutline(p(50.0, 25.0), ellipse), 1e-9)  // centre -> min radius

        val diamond = Bindable(4, Shape.DIAMOND, 0f, 0f, 100f, 50f)
        assertTrue(ScreenshotBindingGeometry.isPointInBindable(p(50.0, 25.0), diamond))
        assertFalse(ScreenshotBindingGeometry.isPointInBindable(p(2.0, 2.0), diamond))
    }

    @Test
    fun rotationIsAppliedBeforeHitTesting() {
        // 100x2 bar rotated 90 degrees around its centre becomes vertical
        val bar = rect(x = 0f, y = 0f, w = 100f, h = 2f, angle = (Math.PI / 2).toFloat())
        assertTrue(ScreenshotBindingGeometry.isPointInBindable(p(50.0, 40.0), bar))
        assertFalse(ScreenshotBindingGeometry.isPointInBindable(p(95.0, 1.0), bar))
    }

    // ---- hit_test_visibility.rs ---------------------------------------------------

    @Test
    fun explicitZIndexReordersCandidatesButTheDefaultKeepsPaintOrder() {
        val a = rect(id = 1, zIndex = 5.0)
        val b = rect(id = 2, zIndex = 1.0)
        // explicit z wins over paint order: b (z=1) is below a (z=5)
        assertEquals(listOf(2L, 1L), ScreenshotBindingGeometry.sortByZIndex(listOf(a, b)).map { it.id })
        assertEquals(listOf(2L, 1L), ScreenshotBindingGeometry.sortByZIndex(listOf(b, a)).map { it.id })

        val plain = listOf(rect(id = 7), rect(id = 8))
        assertEquals(listOf(7L, 8L), ScreenshotBindingGeometry.sortByZIndex(plain).map { it.id })
    }

    @Test
    fun interiorHitCanBeDisabledWithoutDisablingBinding() {
        val outlineOnly = rect(interior = false)
        assertTrue(ScreenshotBindingGeometry.isBindingEnabled(outlineOnly))
        assertFalse(ScreenshotBindingGeometry.isInteriorHitEnabled(outlineOnly))
        // centre is far from the outline, so an outline-only element must not grab it
        assertFalse(ScreenshotBindingGeometry.isPointNearForBindingHit(p(50.0, 25.0), outlineOnly, 15.0))
        assertTrue(ScreenshotBindingGeometry.isPointNearForBindingHit(p(0.0, 25.0), outlineOnly, 15.0))
    }

    @Test
    fun visibilityBoundsExcludeHiddenPartsOfAnElement() {
        val clipped = rect().copy(visibilityBounds = Bounds(0f, 0f, 10f, 50f))
        assertFalse(ScreenshotBindingGeometry.isVisibleAtPoint(p(80.0, 25.0), clipped))
        assertTrue(ScreenshotBindingGeometry.isVisibleAtPoint(p(5.0, 25.0), clipped))
    }

    // ---- hit_test_binding_hit.rs --------------------------------------------------

    @Test
    fun overlappingCandidatesResolveToTheSmallestArea() {
        val big = rect(id = 1, w = 200f, h = 200f)
        val small = rect(id = 2, x = 10f, y = 10f, w = 20f, h = 20f)
        val hit = ScreenshotBindingGeometry.hoveredBindable(p(20.0, 20.0), listOf(big, small), 15.0)
        assertEquals(2L, hit?.id)
    }

    @Test
    fun anOpaqueBackgroundStopsTheSearchFromReachingLowerLayers() {
        val behind = rect(id = 1, w = 200f, h = 200f)
        val front = rect(id = 2, x = 10f, y = 10f, w = 20f, h = 20f, opaque = true)
        val hit = ScreenshotBindingGeometry.hoveredBindable(p(15.0, 15.0), listOf(behind, front), 15.0)
        assertEquals(2L, hit?.id)
        assertEquals(
            listOf(1L, 2L),
            ScreenshotBindingGeometry.bindablesOverPoint(p(15.0, 15.0), listOf(behind, front), 15.0).map { it.id }
        )
    }

    @Test
    fun aPointerInEmptySpaceBindsNothing() {
        assertNull(ScreenshotBindingGeometry.hoveredBindable(p(500.0, 500.0), listOf(rect()), 15.0))
    }

    @Test
    fun containmentRequiresTheWholeShrunkenOutlineInside() {
        val outer = rect(id = 1, w = 100f, h = 100f)
        val inside = rect(id = 2, x = 20f, y = 20f, w = 20f, h = 20f)
        val straddling = rect(id = 3, x = 90f, y = 20f, w = 20f, h = 20f)
        assertTrue(ScreenshotBindingGeometry.isInsideOther(inside, outer))
        assertFalse(ScreenshotBindingGeometry.isInsideOther(straddling, outer))
    }

    // ---- geometry/direction --------------------------------------------------------

    @Test
    fun vectorHeadingFollowsTheWiderAxis() {
        assertEquals(Heading.RIGHT, ScreenshotBindingGeometry.vectorToHeading(p(0.0, 0.0), p(10.0, 1.0)))
        assertEquals(Heading.LEFT, ScreenshotBindingGeometry.vectorToHeading(p(0.0, 0.0), p(-10.0, 1.0)))
        assertEquals(Heading.DOWN, ScreenshotBindingGeometry.vectorToHeading(p(0.0, 0.0), p(1.0, 10.0)))
        assertEquals(Heading.UP, ScreenshotBindingGeometry.vectorToHeading(p(0.0, 0.0), p(0.0, -10.0)))
    }

    @Test
    fun headingConePicksTheSideTheApproachComesFrom() {
        val element = rect(x = 0f, y = 0f, w = 100f, h = 100f)
        assertEquals(Heading.UP, ScreenshotBindingGeometry.headingFromBindable(p(50.0, -20.0), element))
        assertEquals(Heading.RIGHT, ScreenshotBindingGeometry.headingFromBindable(p(120.0, 50.0), element))
        assertEquals(Heading.DOWN, ScreenshotBindingGeometry.headingFromBindable(p(50.0, 120.0), element))
        assertEquals(Heading.LEFT, ScreenshotBindingGeometry.headingFromBindable(p(-20.0, 50.0), element))
    }

    @Test
    fun elbowSnapOutsideTheRadiusAimsFromTheElementCentreToThePointer() {
        val element = rect()
        // original returns vector_to_heading(centre, point) once the pointer is out of range,
        // so the heading follows the centre->pointer ray, not the opposite endpoint
        val far = ScreenshotBindingGeometry.headingForElbowSnap(
            p(400.0, 400.0), p(500.0, 400.0), element,
            ScreenshotBindingGeometry.rotatedBounds(element), zoom = 1.0
        )
        assertEquals(Heading.DOWN, far)
        // inside the radius the AABB cone decides instead
        val near = ScreenshotBindingGeometry.headingForElbowSnap(
            p(50.0, -10.0), p(500.0, 400.0), element,
            ScreenshotBindingGeometry.rotatedBounds(element), zoom = 1.0
        )
        assertEquals(Heading.UP, near)
    }
}
