package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * A dragged curved shaft is sampled per 1.5 unit; the original's curve arrows carry the few clicked
 * vertices (`creation_workflow.rs`), and `get_segment_length` sizes the head from that vertex gap.
 * [ScreenshotArrowRendering.simplifyCurve] restores such a vertex set without moving the path by
 * more than the sampling step.
 */
class ScreenshotArrowCurveSimplifyTest {
    private fun line(count: Int, from: ScreenshotDrawingPoint, to: ScreenshotDrawingPoint) =
        (0..count).map { index ->
            val t = index.toFloat() / count
            ScreenshotDrawingPoint(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
        }

    @Test
    fun sampledStraightDragCollapsesToItsTwoEnds() {
        val points = line(80, ScreenshotDrawingPoint(0f, 0f), ScreenshotDrawingPoint(400f, 400f))
        val simplified = ScreenshotArrowRendering.simplifyCurve(points, 1.5f)
        assertEquals(2, simplified.size)
        assertEquals(points.first(), simplified.first())
        assertEquals(points.last(), simplified.last())
    }

    @Test
    fun aCornerSurvivesSimplification() {
        val points = line(40, ScreenshotDrawingPoint(0f, 0f), ScreenshotDrawingPoint(200f, 0f)) +
            line(40, ScreenshotDrawingPoint(200f, 0f), ScreenshotDrawingPoint(200f, 200f)).drop(1)
        val simplified = ScreenshotArrowRendering.simplifyCurve(points, 1.5f)
        assertEquals(3, simplified.size)
        assertTrue(simplified.contains(ScreenshotDrawingPoint(200f, 0f)))
    }

    @Test
    fun aBendBeyondToleranceIsKept() {
        val points = listOf(ScreenshotDrawingPoint(0f, 0f), ScreenshotDrawingPoint(100f, 6f),
            ScreenshotDrawingPoint(200f, 0f))
        assertEquals(3, ScreenshotArrowRendering.simplifyCurve(points, 1.5f).size)
        // A bend inside the tolerance is the sampling noise the simplification removes.
        val shallow = listOf(ScreenshotDrawingPoint(0f, 0f), ScreenshotDrawingPoint(100f, 1f),
            ScreenshotDrawingPoint(200f, 0f))
        assertEquals(2, ScreenshotArrowRendering.simplifyCurve(shallow, 1.5f).size)
    }

    /** The simplification must not move the drawn path further than the sampling step. */
    @Test
    fun noPointDriftsBeyondTheTolerance() {
        val points = (0..120).map { index ->
            val t = index / 120f
            ScreenshotDrawingPoint(300f * t, 120f * kotlin.math.sin(t * 3.1f))
        }
        val simplified = ScreenshotArrowRendering.simplifyCurve(points, 1.5f)
        assertTrue(simplified.size < points.size)
        fun distanceToSegment(p: ScreenshotDrawingPoint, a: ScreenshotDrawingPoint, b: ScreenshotDrawingPoint): Float {
            val dx = b.x - a.x; val dy = b.y - a.y
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared == 0f) return hypot(p.x - a.x, p.y - a.y)
            val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
            return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
        }
        for (point in points) {
            var best = Float.MAX_VALUE
            for (index in 0 until simplified.size - 1)
                best = minOf(best, distanceToSegment(point, simplified[index], simplified[index + 1]))
            assertTrue("drift $best", best <= 1.5f + 1e-3f)
        }
    }

    @Test
    fun shortAndDegenerateInputsStayAsTheyAre() {
        val two = listOf(ScreenshotDrawingPoint(0f, 0f), ScreenshotDrawingPoint(10f, 10f))
        assertEquals(two, ScreenshotArrowRendering.simplifyCurve(two, 1.5f))
        val three = line(2, ScreenshotDrawingPoint(0f, 0f), ScreenshotDrawingPoint(10f, 0f))
        assertEquals(three, ScreenshotArrowRendering.simplifyCurve(three, 0f))
        assertEquals(three, ScreenshotArrowRendering.simplifyCurve(three, -1f))
    }
}
