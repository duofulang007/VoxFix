package local.voxfix.debug

import local.voxfix.debug.ScreenshotStrokeGeometry.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Parity checks for the free-draw pipeline. The numeric thresholds are the ones shipped with the
 * original Rust tests (`stroke.rs`, `free_draw_workflow.rs`), so a regression here means the port
 * drifted from Snow Shot 1.1.8 rather than from our own earlier behaviour.
 */
class ScreenshotStrokeGeometryTest {
    @Test fun continuingEitherEndpointPreservesTheOriginalVerticesAndOrder() {
        val points=listOf(ScreenshotDrawingPoint(100f,100f),ScreenshotDrawingPoint(200f,100f),ScreenshotDrawingPoint(300f,150f))
        val end=PenStrokeBuilder.continueFrom(points,4f,false)
        assertEquals(points,end.preview())
        end.append(ScreenshotDrawingPoint(400f,220f))
        val endResult=end.finish()
        assertEquals(points,endResult.points.take(points.size))
        assertEquals(ScreenshotDrawingPoint(400f,220f),endResult.points.last())
        val start=PenStrokeBuilder.continueFrom(points,4f,true)
        assertEquals(points,start.preview())
        start.append(ScreenshotDrawingPoint(20f,40f))
        val startResult=start.finish()
        assertEquals(ScreenshotDrawingPoint(20f,40f),startResult.points.first())
        assertEquals(points,startResult.points.takeLast(points.size))
    }
    private fun filter(spacing: Double = 1.0, response: Double = 6.0) =
        ScreenshotStrokeGeometry.Filter(GeoPoint(0.0, 0.0), spacing, response)

    @Test fun stabilizationReducesSlowPointerJitter() {
        val filter = filter()
        val output = ArrayList<GeoPoint>()
        for (x in 1..120) filter.ingest(GeoPoint(x.toDouble(), if (x % 2 == 0) 1.0 else -1.0), output)
        val maximum = output.drop(12).maxOf { abs(it.y) }
        assertTrue("stabilized deviation was $maximum", maximum < 0.35)
    }

    @Test fun spatialResamplingIsIndependentOfEventDensity() {
        fun polyline(subdivisions: Int): List<GeoPoint> {
            val controls = listOf(GeoPoint(0.0, 0.0), GeoPoint(30.0, 8.0), GeoPoint(60.0, -6.0), GeoPoint(90.0, 0.0))
            val filter = filter()
            val output = ArrayList<GeoPoint>()
            for (index in 0 until controls.size - 1) {
                val from = controls[index]; val to = controls[index + 1]
                for (step in 1..subdivisions) {
                    val t = step.toDouble() / subdivisions
                    filter.ingest(GeoPoint(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t), output)
                }
            }
            return output
        }
        val sparse = polyline(1); val dense = polyline(20)
        assertEquals(sparse.size, dense.size)
        assertTrue(sparse.zip(dense).all { (left, right) -> ScreenshotStrokeGeometry.distance(left, right) < 1e-9 })
    }

    @Test fun adaptiveResponseTracksDeliberateCorners() {
        val filter = filter()
        val output = ArrayList<GeoPoint>()
        for (x in 1..40) filter.ingest(GeoPoint(x.toDouble(), 0.0), output)
        for (y in 1..40) filter.ingest(GeoPoint(40.0, y.toDouble()), output)
        val corner = GeoPoint(40.0, 0.0)
        val closest = output.minOf { ScreenshotStrokeGeometry.distance(it, corner) }
        assertTrue("corner miss distance was $closest", closest < 3.0)
        assertTrue(ScreenshotStrokeGeometry.distance(output.last(), GeoPoint(40.0, 40.0)) < 3.0)
    }

    @Test fun subpixelForwardMotionRejectsLateralJitter() {
        val filter = filter(spacing = 0.75)
        val output = ArrayList<GeoPoint>()
        for (i in 1..1200) filter.ingest(GeoPoint(i * 0.1, if (i % 2 == 0) 1.0 else -1.0), output)
        val deviation = output.filter { it.x > 10.0 }.maxOfOrNull { abs(it.y) } ?: 0.0
        assertTrue("slow stroke lateral deviation: $deviation", deviation < 0.12)
    }

    @Test fun resetAndShortStrokesPreserveEndpoints() {
        val filter = filter(spacing = 0.75)
        val output = ArrayList<GeoPoint>()
        filter.ingest(GeoPoint(0.2, 0.1), output)
        assertTrue(output.isEmpty())
        assertEquals(GeoPoint(0.2, 0.1), filter.settleEndpoint())
        assertNull(filter.settleEndpoint())
        filter.reset(GeoPoint(40.0, 20.0))
        filter.ingest(GeoPoint(40.0, 20.0), output)
        assertTrue(output.isEmpty())
        assertNull(filter.settleEndpoint())
        filter.ingest(GeoPoint(42.0, 20.0), output)
        assertTrue(output.all { it.x >= 40.0 && it.y == 20.0 })
    }

    @Test fun simplificationRetainsCornersAndEndpoints() {
        // Same fixture as the upstream `rdp_retains_corners` test.
        val corner = listOf(GeoPoint(0.0, 0.0), GeoPoint(10.0, 0.0), GeoPoint(10.0, 10.0))
        assertEquals(corner, ScreenshotStrokeGeometry.simplify(corner, 0.5))
        val noisy = listOf(GeoPoint(0.0, 0.0), GeoPoint(5.0, 0.05), GeoPoint(10.0, 0.0))
        assertEquals(listOf(noisy.first(), noisy.last()), ScreenshotStrokeGeometry.simplify(noisy, 0.5))
    }

    @Test fun tuningMatchesOriginalScreenSpaceFormulas() {
        val tuning = ScreenshotStrokeGeometry.Tuning(5.25f)
        assertEquals(0.75, tuning.sampleSpacing, 1e-9)
        assertEquals(6.0, tuning.responseDistance, 1e-9)
        assertEquals(0.35, tuning.tolerance, 1e-9)
        assertEquals(10.0, tuning.closureRadius, 1e-9)
        val wide = ScreenshotStrokeGeometry.Tuning(40f)
        assertEquals(1.2, wide.sampleSpacing, 1e-9)
        assertEquals(14.0, wide.responseDistance, 1e-9)
        assertEquals(1.25, wide.tolerance, 1e-9)
        assertEquals(20.0, wide.closureRadius, 1e-9)
    }

    @Test fun clickOnlyStrokeBecomesADotSegment() {
        val builder = PenStrokeBuilder(ScreenshotDrawingPoint(50f, 60f), 4f)
        val result = builder.finish()
        assertEquals(2, result.points.size)
        assertFalse(result.closed)
        val length = ScreenshotStrokeGeometry.distance(
            ScreenshotStrokeGeometry.GeoPoint(result.points[0].x.toDouble(), result.points[0].y.toDouble()),
            ScreenshotStrokeGeometry.GeoPoint(result.points[1].x.toDouble(), result.points[1].y.toDouble()))
        assertEquals(ScreenshotStrokeGeometry.DOT_SEGMENT, length, 1e-6)
    }

    @Test fun returningToStartClosesAndDropsTheDuplicateVertex() {
        val builder = PenStrokeBuilder(ScreenshotDrawingPoint(100f, 100f), 2f)
        listOf(160f to 100f, 220f to 100f, 220f to 160f, 220f to 220f,
            160f to 220f, 100f to 220f, 100f to 160f, 100f to 104f).forEach { (x, y) ->
            builder.append(ScreenshotDrawingPoint(x, y))
        }
        val preview = builder.preview()
        assertTrue(preview.size >= 4)
        val result = builder.finish()
        assertTrue("expected closure, vertices=${result.points.size}", result.closed)
        assertTrue(result.points.size >= 3)
        val first = result.points.first(); val last = result.points.last()
        assertTrue("closing vertex was not dropped", ScreenshotStrokeGeometry.distance(
            GeoPoint(first.x.toDouble(), first.y.toDouble()), GeoPoint(last.x.toDouble(), last.y.toDouble())) > 1.0)
    }

    @Test fun previewMatchesWhatLiftingStores() {
        val builder = PenStrokeBuilder(ScreenshotDrawingPoint(10f, 10f), 6f)
        val path = (1..60).map { ScreenshotDrawingPoint(10f + it * 4f, 10f + it * 2f) }
        path.forEach { builder.append(it) }
        val preview = builder.preview()
        val result = builder.finish()
        assertEquals(preview, result.points)
        assertEquals(path.last(), result.points.last())
        assertFalse(result.closed)
    }

    @Test fun centripetalControlsStayOnACollinearRun() {
        val controls = ScreenshotStrokeGeometry.centripetalControls(
            GeoPoint(0.0, 0.0), GeoPoint(10.0, 10.0), GeoPoint(20.0, 20.0), GeoPoint(30.0, 30.0))
        assertNotNull(controls)
        val (first, second) = controls!!
        assertTrue(abs(first.x - first.y) < 1e-9 && abs(second.x - second.y) < 1e-9)
        assertTrue(first.x in 10.0..20.0 && second.x in 10.0..20.0)
    }

    @Test fun duplicateVerticesFallBackToAStraightSegment() {
        val controls = ScreenshotStrokeGeometry.centripetalControls(
            GeoPoint(5.0, 5.0), GeoPoint(10.0, 10.0), GeoPoint(10.0, 10.0), GeoPoint(20.0, 5.0))
        assertNull(controls)
    }

    /** Same sampling form as the original `free_draw_hit_test` (16 steps per cubic). */
    private fun sample(start: GeoPoint, controls: Pair<GeoPoint, GeoPoint>, end: GeoPoint): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        for (step in 1..16) {
            val t = step / 16.0
            val mt = 1.0 - t
            points.add(GeoPoint(
                mt * mt * mt * start.x + 3.0 * mt * mt * t * controls.first.x + 3.0 * mt * t * t * controls.second.x + t * t * t * end.x,
                mt * mt * mt * start.y + 3.0 * mt * mt * t * controls.first.y + 3.0 * mt * t * t * controls.second.y + t * t * t * end.y))
        }
        return points
    }

    @Test fun smoothHitFollowsTheRenderedCurveNotTheChords() {
        val a = ScreenshotDrawingPoint(0f, 0f)
        val b = ScreenshotDrawingPoint(200f, 0f)
        val c = ScreenshotDrawingPoint(100f, 120f)
        val points = listOf(a, b, c)
        val vertices = listOf(GeoPoint(0.0, 0.0), GeoPoint(200.0, 0.0), GeoPoint(100.0, 120.0))
        val reflect = { anchor: GeoPoint, neighbor: GeoPoint -> GeoPoint(anchor.x * 2 - neighbor.x, anchor.y * 2 - neighbor.y) }
        // Neighbour rules mirror buildPath: open ends reflect, so the first cubic bulges toward c.
        val edge0 = ScreenshotStrokeGeometry.centripetalControls(
            reflect(vertices[0], vertices[1]), vertices[0], vertices[1], vertices[2])!!
        val edge1 = ScreenshotStrokeGeometry.centripetalControls(
            vertices[0], vertices[1], vertices[2], reflect(vertices[2], vertices[1]))!!
        val edge2Closed = ScreenshotStrokeGeometry.centripetalControls(
            vertices[1], vertices[2], vertices[0], vertices[1])!!
        val curve = sample(vertices[0], edge0, vertices[1]) + sample(vertices[1], edge1, vertices[2])

        fun distanceToCurve(p: GeoPoint) =
            (curve + sample(vertices[2], edge2Closed, vertices[0])).minOf {
                ScreenshotStrokeGeometry.distance(p, it)
            }

        // A probe sitting on the first cubic's mid sample answers; the bare chord under it misses.
        val onCurve = sample(vertices[0], edge0, vertices[1]).first()
        val limit = 0.5f + 2f * 0.5f
        assertTrue(ScreenshotStrokeGeometry.hitTestSmooth(points, false,
            ScreenshotDrawingPoint(onCurve.x.toFloat(), onCurve.y.toFloat()), 0.5f, 2f))
        val chordMid = GeoPoint(100.0, 0.0)
        assertTrue(distanceToCurve(chordMid) > limit)
        assertFalse(ScreenshotStrokeGeometry.hitTestSmooth(points, false,
            ScreenshotDrawingPoint(100f, 0f), 0.5f, 2f))
        // The closed stroke also walks the last->first edge, which the open stroke lacks.
        val closingMid = sample(vertices[2], edge2Closed, vertices[0]).first { it.y > 40.0 && it.y < 80.0 }
        assertTrue(distanceToCurve(closingMid) <= limit / 2 + 0.5)
        assertTrue(ScreenshotStrokeGeometry.hitTestSmooth(points, true,
            ScreenshotDrawingPoint(closingMid.x.toFloat(), closingMid.y.toFloat()), 0.5f, 2f))
        assertFalse(ScreenshotStrokeGeometry.hitTestSmooth(points, false,
            ScreenshotDrawingPoint(closingMid.x.toFloat(), closingMid.y.toFloat()), 0.5f, 2f))
    }
}
