package local.voxfix.debug

import local.voxfix.debug.ScreenshotBindingDecisions.Decision
import local.voxfix.debug.ScreenshotBindingDecisions.Disposition
import local.voxfix.debug.ScreenshotBindingGeometry.Bindable
import local.voxfix.debug.ScreenshotBindingGeometry.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Branch coverage for the endpoint-decision port. Each case names the original rule it
 * pins down, so a future edit that "simplifies" a guard fails here instead of on the phone.
 */
class ScreenshotBindingDecisionsTest {

    private fun rect(id: Long = 1L, x: Float = 0f, y: Float = 0f, w: Float = 100f, h: Float = 50f) =
        Bindable(id, Shape.RECTANGLE, x, y, w, h)

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

    private fun dragged(vararg entries: Pair<Int, Pair<Float, Float>>) =
        entries.map { it.first to ScreenshotDrawingPoint(it.second.first, it.second.second) }

    private fun input(
        arrow: ScreenshotBindingArrow,
        pointer: Pair<Float, Float>,
        dragged: List<Pair<Int, ScreenshotDrawingPoint>>,
        bindables: List<Bindable> = listOf(rect()),
        context: ScreenshotBindingDecisions.Context = ScreenshotBindingDecisions.Context(),
        options: ScreenshotBindingDecisions.Options = ScreenshotBindingDecisions.Options()
    ) = ScreenshotBindingDecisions.DragInput(
        arrow = arrow,
        pointer = doubleArrayOf(pointer.first.toDouble(), pointer.second.toDouble()),
        bindables = bindables,
        draggedPoints = dragged,
        context = context,
        options = options
    )

    private fun assertDisposition(expected: Disposition, decision: Decision) =
        assertEquals(expected, decision.disposition)

    @Test
    fun nothingDraggedChangesNothing() {
        val pair = ScreenshotBindingDecisions.decide(
            input(arrow(10f to 10f, 50f to 25f), 50f to 25f, emptyList())
        )
        assertDisposition(Disposition.UNCHANGED, pair.start)
        assertDisposition(Disposition.UNCHANGED, pair.end)
    }

    @Test
    fun draggingBothEndsClearsBothBindings() {
        val pair = ScreenshotBindingDecisions.decide(
            input(arrow(10f to 10f, 50f to 25f), 50f to 25f, dragged(0 to (10f to 10f), 1 to (50f to 25f)))
        )
        assertDisposition(Disposition.CLEAR, pair.start)
        assertDisposition(Disposition.CLEAR, pair.end)
    }

    @Test
    fun disabledBindingClearsOnlyTheDraggedEnd() {
        val pair = ScreenshotBindingDecisions.decide(
            input(
                arrow(10f to 10f, 50f to 25f), 50f to 25f, dragged(1 to (50f to 25f)),
                context = ScreenshotBindingDecisions.Context(isBindingEnabled = false)
            )
        )
        assertDisposition(Disposition.UNCHANGED, pair.start)
        assertDisposition(Disposition.CLEAR, pair.end)
    }

    @Test
    fun aPointerInsideTheElementBindsInside() {
        val pair = ScreenshotBindingDecisions.decide(
            input(arrow(10f to 10f, 50f to 25f), 50f to 25f, dragged(1 to (50f to 25f)))
        )
        assertDisposition(Disposition.UNCHANGED, pair.start)
        assertDisposition(Disposition.BIND, pair.end)
        assertEquals(ScreenshotBindMode.INSIDE, pair.end.mode)
        assertEquals(1L, pair.end.bindableId)
    }

    @Test
    fun aPointerNearButOutsideTheElementOrbits() {
        val pair = ScreenshotBindingDecisions.decide(
            input(arrow(10f to 10f, 105f to 25f), 105f to 25f, dragged(1 to (105f to 25f)))
        )
        assertDisposition(Disposition.BIND, pair.end)
        assertEquals(ScreenshotBindMode.ORBIT, pair.end.mode)
        assertNotNull(pair.end.focusPoint)
    }

    @Test
    fun theHitRadiusGrowsWhenZoomedOut() {
        // 20px away: out of range at zoom 1 (15px), in range at zoom 0.5 (20px)
        val atOne = ScreenshotBindingDecisions.decide(
            input(arrow(10f to 10f, 120f to 25f), 120f to 25f, dragged(1 to (120f to 25f)))
        )
        assertDisposition(Disposition.CLEAR, atOne.end)
        val atHalf = ScreenshotBindingDecisions.decide(
            input(
                arrow(10f to 10f, 120f to 25f), 120f to 25f, dragged(1 to (120f to 25f)),
                context = ScreenshotBindingDecisions.Context(zoom = 0.5)
            )
        )
        assertDisposition(Disposition.BIND, atHalf.end)
    }

    @Test
    fun bindModeInsideForcesInsideEvenFromOutside() {
        val pair = ScreenshotBindingDecisions.decide(
            input(
                arrow(10f to 10f, 105f to 25f), 105f to 25f, dragged(1 to (105f to 25f)),
                context = ScreenshotBindingDecisions.Context(bindMode = ScreenshotBindMode.INSIDE)
            )
        )
        assertEquals(ScreenshotBindMode.INSIDE, pair.end.mode)
    }

    @Test
    fun altKeyForcesInside() {
        val pair = ScreenshotBindingDecisions.decide(
            input(
                arrow(10f to 10f, 105f to 25f), 105f to 25f, dragged(1 to (105f to 25f)),
                options = ScreenshotBindingDecisions.Options(altKey = true)
            )
        )
        assertEquals(ScreenshotBindMode.INSIDE, pair.end.mode)
    }

    @Test
    fun sameElementOnBothEndsBindsBothInside() {
        val element = rect()
        val existing = ScreenshotFixedPointBinding(1L, ScreenshotBindMode.ORBIT, 0.9, 0.5)
        val pair = ScreenshotBindingDecisions.decide(
            input(
                arrow(105f to 25f, 50f to 25f, endBinding = existing), 105f to 25f, dragged(0 to (105f to 25f))
            )
        )
        assertEquals(ScreenshotBindMode.INSIDE, pair.start.mode)
        assertEquals(ScreenshotBindMode.INSIDE, pair.end.mode)
    }

    @Test
    fun complexBindingsKeepOrbitWhenTheOppositeEndAlreadyOrbits() {
        val existing = ScreenshotFixedPointBinding(1L, ScreenshotBindMode.ORBIT, 0.9, 0.5)
        val pair = ScreenshotBindingDecisions.decide(
            input(
                arrow(105f to 25f, 50f to 25f, endBinding = existing), 105f to 25f, dragged(0 to (105f to 25f)),
                options = ScreenshotBindingDecisions.Options(complexBindings = true)
            )
        )
        assertEquals(ScreenshotBindMode.ORBIT, pair.start.mode)
    }

    @Test
    fun anOppositeInsideBindingIsNeverOverriddenByAnOrbitHint() {
        val existing = ScreenshotFixedPointBinding(1L, ScreenshotBindMode.INSIDE, 0.9, 0.5)
        val pair = ScreenshotBindingDecisions.decide(
            input(
                arrow(10f to 10f, 50f to 25f, endBinding = existing), 400f to 400f, dragged(0 to (10f to 10f)),
                options = ScreenshotBindingDecisions.Options(
                    angleLocked = true,
                    oppositeOrbitFocusPoint = doubleArrayOf(60.0, 30.0)
                )
            )
        )
        assertDisposition(Disposition.CLEAR, pair.start)
        assertDisposition(Disposition.UNCHANGED, pair.end)
    }

    @Test
    fun elbowDragOnlyBindsTheDraggedEndAndAlwaysOrbits() {
        val pair = ScreenshotBindingDecisions.decide(
            input(
                arrow(10f to 10f, 50f to 25f, elbowed = true), 50f to 25f, dragged(1 to (50f to 25f))
            )
        )
        assertDisposition(Disposition.UNCHANGED, pair.start)
        assertEquals(ScreenshotBindMode.ORBIT, pair.end.mode)
    }

    @Test
    fun elbowDragWithoutAHitClearsTheDraggedEnd() {
        val pair = ScreenshotBindingDecisions.decide(
            input(arrow(10f to 10f, 500f to 400f, elbowed = true), 500f to 400f, dragged(1 to (500f to 400f)))
        )
        assertDisposition(Disposition.CLEAR, pair.end)
    }

    @Test
    fun toBindingAlwaysOrbitsForElbowArrows() {
        val element = rect()
        val binding = ScreenshotBindingDecisions.toBinding(
            arrow(10f to 10f, 50f to 25f, elbowed = true), ScreenshotEndpointEdge.END, element,
            ScreenshotBindMode.INSIDE, doubleArrayOf(50.0, 25.0), midpointSnappingEnabled = false
        )
        assertEquals(ScreenshotBindMode.ORBIT, binding.mode)
        assertEquals(1L, binding.elementId)
    }

    @Test
    fun toBindingKeepsInsideForStraightArrowsAndNormalisesTheRatio() {
        val element = rect()
        val binding = ScreenshotBindingDecisions.toBinding(
            arrow(10f to 10f, 50f to 25f), ScreenshotEndpointEdge.END, element,
            ScreenshotBindMode.INSIDE, doubleArrayOf(50.0, 25.0), midpointSnappingEnabled = true
        )
        assertEquals(ScreenshotBindMode.INSIDE, binding.mode)
        assertEquals(0.5001, binding.fixedX, 1e-9)
        assertEquals(0.5001, binding.fixedY, 1e-9)
    }

    @Test
    fun outOfRangeIndicesAreDroppedBeforeDeciding() {
        val map = ScreenshotBindingDecisions.normalizeDraggedPoints(
            arrow(1f to 1f, 2f to 2f), dragged(0 to (1f to 1f), 9 to (9f to 9f), -1 to (0f to 0f))
        )
        assertEquals(setOf(0), map.keys)
        assertNull(map[9])
    }
}
