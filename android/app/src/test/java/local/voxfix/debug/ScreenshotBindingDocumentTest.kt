package local.voxfix.debug

import local.voxfix.debug.ScreenshotBindingDecisions.Disposition
import local.voxfix.debug.ScreenshotBindingGeometry.Bounds
import local.voxfix.debug.ScreenshotBindingGeometry.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Document-level contract for T22: what becomes a bindable, and how bindings persist. */
class ScreenshotBindingDocumentTest {

    private fun rectStroke(
        id: Long = 5L,
        left: Float = 0f, top: Float = 0f, right: Float = 100f, bottom: Float = 50f,
        filled: Boolean = false, fillColor: Int = 0xFFFFFFFF.toInt()
    ) = ScreenshotDrawingStroke(
        tool = ScreenshotDrawingTool.RECTANGLE,
        points = listOf(ScreenshotDrawingPoint(left, top), ScreenshotDrawingPoint(right, bottom)),
        color = 0xFFF5222D.toInt(),
        width = 4f,
        style = ScreenshotDrawingStyle(hasFill = filled, fillColor = fillColor),
        id = id
    )

    private fun arrowStroke(
        vararg points: Pair<Float, Float>,
        id: Long = 9L,
        startBinding: ScreenshotFixedPointBinding? = null,
        endBinding: ScreenshotFixedPointBinding? = null
    ) = ScreenshotDrawingStroke(
        tool = ScreenshotDrawingTool.ARROW,
        points = points.map { ScreenshotDrawingPoint(it.first, it.second) },
        color = 0xFFF5222D.toInt(),
        width = 2f,
        id = id,
        startBinding = startBinding,
        endBinding = endBinding
    )

    private fun boundsOf(stroke: ScreenshotDrawingStroke): Bounds? {
        if (stroke.points.isEmpty()) return null
        return Bounds(
            stroke.points.minOf { it.x }, stroke.points.minOf { it.y },
            stroke.points.maxOf { it.x }, stroke.points.maxOf { it.y }
        )
    }

    // ---- element -> bindable -------------------------------------------------------

    @Test
    fun everyElementBecomesARectangleProxy() {
        val shapes = listOf(
            rectStroke(id = 3L),
            ScreenshotDrawingStroke(
                ScreenshotDrawingTool.RECTANGLE,
                listOf(ScreenshotDrawingPoint(200f, 0f), ScreenshotDrawingPoint(240f, 40f)),
                0, 4f, ScreenshotDrawingStyle(shape = ScreenshotRectangleShape.DIAMOND), id = 4L
            )
        )
        val bindables = ScreenshotBindingDocument.bindables(shapes, ::boundsOf) { shapes.indexOf(it) }
        assertEquals(2, bindables.size)
        // a diamond is still fed to the engine as a rectangle, exactly like the original proxy
        assertTrue(bindables.all { it.shape == Shape.RECTANGLE })
        assertEquals(3L, bindables[0].id)
        assertEquals(0f, bindables[0].x, 1e-6f)
        assertEquals(100f, bindables[0].width, 1e-6f)
        assertEquals(50f, bindables[0].height, 1e-6f)
        assertEquals(0.0, bindables[0].zIndex)
        assertEquals(4f, bindables[0].strokeWidth, 1e-6f)
    }

    @Test
    fun fillAlphaDecidesWhetherTheElementIsOpaque() {
        val unfilled = ScreenshotBindingDocument.bindables(listOf(rectStroke(filled = false)), ::boundsOf) { 0 }
        assertFalse(ScreenshotBindingGeometry.isBackgroundOpaque(unfilled[0]))
        val transparentFill = ScreenshotBindingDocument.bindables(
            listOf(rectStroke(filled = true, fillColor = 0x00FFFFFF)), ::boundsOf
        ) { 0 }
        assertFalse(ScreenshotBindingGeometry.isBackgroundOpaque(transparentFill[0]))
        val solid = ScreenshotBindingDocument.bindables(
            listOf(rectStroke(filled = true, fillColor = 0xFFFFFFFF.toInt())), ::boundsOf
        ) { 0 }
        assertTrue(ScreenshotBindingGeometry.isBackgroundOpaque(solid[0]))
    }

    @Test
    fun arrowsAndLinesNeverBecomeBindables() {
        val strokes = listOf(
            rectStroke(id = 1L),
            arrowStroke(0f to 0f, 50f to 50f, id = 2L),
            ScreenshotDrawingStroke(
                ScreenshotDrawingTool.LINE,
                listOf(ScreenshotDrawingPoint(0f, 0f), ScreenshotDrawingPoint(20f, 20f)),
                0, 2f, id = 3L
            )
        )
        val bindables = ScreenshotBindingDocument.bindables(strokes, ::boundsOf) { 0 }
        assertEquals(listOf(1L), bindables.map { it.id })
    }

    @Test
    fun degenerateElementsAreSkipped() {
        val flat = ScreenshotDrawingStroke(
            ScreenshotDrawingTool.RECTANGLE,
            listOf(ScreenshotDrawingPoint(10f, 10f)), 0, 2f, id = 6L
        )
        assertTrue(ScreenshotBindingDocument.bindables(listOf(flat), ::boundsOf) { 0 }.isEmpty())
    }

    // ---- decisions -> stroke -------------------------------------------------------

    @Test
    fun bindWritesTheResolvedRatioAndClearDropsIt() {
        val element = rectStroke(id = 5L)
        val bindables = ScreenshotBindingDocument.bindables(listOf(element), ::boundsOf) { 0 }.associateBy { it.id }
        val stroke = arrowStroke(10f to 10f, 50f to 25f)

        val bind = ScreenshotBindingDecisions.DecisionPair(
            ScreenshotBindingDecisions.Decision(Disposition.UNCHANGED),
            ScreenshotBindingDecisions.Decision(Disposition.BIND, ScreenshotBindMode.INSIDE, 5L, doubleArrayOf(50.0, 25.0))
        )
        val bound = ScreenshotBindingDocument.applyBindings(stroke, bind, bindables, midpointSnappingEnabled = true)
        assertNull(bound.startBinding)
        assertNotNull(bound.endBinding)
        assertEquals(5L, bound.endBinding!!.elementId)
        assertEquals(ScreenshotBindMode.INSIDE, bound.endBinding!!.mode)

        val clear = ScreenshotBindingDecisions.DecisionPair(
            ScreenshotBindingDecisions.Decision(Disposition.CLEAR),
            ScreenshotBindingDecisions.Decision(Disposition.CLEAR)
        )
        val cleared = ScreenshotBindingDocument.applyBindings(bound, clear, bindables, midpointSnappingEnabled = true)
        assertNull(cleared.startBinding)
        assertNull(cleared.endBinding)
    }

    @Test
    fun unchangedKeepsTheExistingBinding() {
        val element = rectStroke(id = 5L)
        val bindables = ScreenshotBindingDocument.bindables(listOf(element), ::boundsOf) { 0 }.associateBy { it.id }
        val existing = ScreenshotFixedPointBinding(5L, ScreenshotBindMode.ORBIT, 0.9, 0.5)
        val stroke = arrowStroke(10f to 10f, 50f to 25f, startBinding = existing, endBinding = existing)
        val keep = ScreenshotBindingDecisions.DecisionPair(
            ScreenshotBindingDecisions.Decision(Disposition.UNCHANGED),
            ScreenshotBindingDecisions.Decision(Disposition.UNCHANGED)
        )
        val next = ScreenshotBindingDocument.applyBindings(stroke, keep, bindables, midpointSnappingEnabled = false)
        assertEquals(existing, next.startBinding)
        assertEquals(existing, next.endBinding)
    }

    @Test
    fun boundEndpointsFollowTheElement() {
        val element = rectStroke(id = 5L, left = 0f, top = 0f, right = 100f, bottom = 50f)
        val bindables = ScreenshotBindingDocument.bindables(listOf(element), ::boundsOf) { 0 }.associateBy { it.id }
        // pinned to the right edge, halfway down
        val pin = ScreenshotFixedPointBinding(5L, ScreenshotBindMode.INSIDE, 1.0, 0.5)
        val stroke = arrowStroke(10f to 10f, 400f to 400f, endBinding = pin)
        val resolved = ScreenshotBindingDocument.resolveEndpoints(stroke, bindables)
        assertEquals(100.0, resolved.points.last().x.toDouble(), 0.02)
        assertEquals(25.0, resolved.points.last().y.toDouble(), 0.02)
        // the free end is untouched
        assertEquals(stroke.points.first(), resolved.points.first())
    }

    @Test
    fun aMissingTargetLeavesTheStrokeAlone() {
        val stroke = arrowStroke(10f to 10f, 50f to 25f, endBinding = ScreenshotFixedPointBinding(99L, ScreenshotBindMode.INSIDE, 1.0, 0.5))
        assertEquals(stroke, ScreenshotBindingDocument.resolveEndpoints(stroke, emptyMap()))
    }

    // ---- persistence (JSON glue is covered by the device smoke; this covers the rules) ----

    @Test
    fun aValidPayloadParsesBack() {
        val binding = ScreenshotBindingDocument.parseBinding(5L, "ORBIT", 0.25, 0.75)
        assertEquals(ScreenshotFixedPointBinding(5L, ScreenshotBindMode.ORBIT, 0.25, 0.75), binding)
    }

    @Test
    fun malformedPayloadsAreRejected() {
        assertNull(ScreenshotBindingDocument.parseBinding(5L, "SIDEWAYS", 0.25, 0.75))
        assertNull(ScreenshotBindingDocument.parseBinding(5L, null, 0.25, 0.75))
        assertNull(ScreenshotBindingDocument.parseBinding(0L, "INSIDE", 0.25, 0.75))
        assertNull(ScreenshotBindingDocument.parseBinding(-3L, "INSIDE", 0.25, 0.75))
        assertNull(ScreenshotBindingDocument.parseBinding(5L, "INSIDE", Double.NaN, 0.75))
        assertNull(ScreenshotBindingDocument.parseBinding(5L, "INSIDE", 0.25, Double.POSITIVE_INFINITY))
    }

    @Test
    fun bindingsCompareByValueSoHistorySeesAChange() {
        val first = arrowStroke(10f to 10f, 50f to 25f, endBinding = ScreenshotFixedPointBinding(5L, ScreenshotBindMode.INSIDE, 1.0, 0.5))
        val second = arrowStroke(10f to 10f, 50f to 25f, endBinding = ScreenshotFixedPointBinding(5L, ScreenshotBindMode.INSIDE, 1.0, 0.6))
        assertNotEquals(first, second)
        assertEquals(first, arrowStroke(10f to 10f, 50f to 25f, endBinding = ScreenshotFixedPointBinding(5L, ScreenshotBindMode.INSIDE, 1.0, 0.5)))
        // a stroke without bindings must not equal one with them
        assertNotEquals(first, arrowStroke(10f to 10f, 50f to 25f))
    }
}
