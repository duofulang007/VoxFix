package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 橡皮擦线段采样，对照上游 `eraser_workflow.rs::eraser_segment_samples`（固定提交）。
 * 采样步长是视野半径的一半，所以 8 逻辑像素半径对应每 4 像素一个采样点。
 */
class ScreenshotEraserSamplesTest {
    private fun p(x: Float, y: Float) = ScreenshotDrawingPoint(x, y)

    /** `count = ceil(distance / (tolerance * 0.5))`，默认不含起点，最后一点落在终点。 */
    @Test fun halfRadiusStepsCoverTheSegment() {
        val samples = ScreenshotEraserSamples.segment(p(0f, 0f), p(100f, 0f), 8f)
        assertEquals(25, samples.size)
        assertEquals(4f, samples.first().x, 1e-4f)
        assertEquals(100f, samples.last().x, 1e-4f)
        assertEquals(0f, samples.last().y, 1e-4f)
    }

    /** `include_start` 只影响首点，不改变步长与终点。 */
    @Test fun includeStartPrependsTheOrigin() {
        val without = ScreenshotEraserSamples.segment(p(0f, 0f), p(100f, 0f), 8f, includeStart = false)
        val with = ScreenshotEraserSamples.segment(p(0f, 0f), p(100f, 0f), 8f, includeStart = true)
        assertEquals(without.size + 1, with.size)
        assertEquals(0f, with.first().x, 1e-4f)
        assertEquals(without.first().x, with[1].x, 1e-4f)
    }

    /** 斜向距离按真实长度取段数：3-4-5 三角形，半径 10 → 步长 5 → 10 段。 */
    @Test fun diagonalUsesTrueDistance() {
        val samples = ScreenshotEraserSamples.segment(p(0f, 0f), p(30f, 40f), 10f)
        assertEquals(10, samples.size)
        assertEquals(30f, samples.last().x, 1e-3f)
        assertEquals(40f, samples.last().y, 1e-3f)
    }

    /** 首尾重合时不插值：默认给空，`include_start` 时只给起点（上游 `distance <= EPSILON`）。 */
    @Test fun zeroLengthSegmentDoesNotSample() {
        assertTrue(ScreenshotEraserSamples.segment(p(12f, 34f), p(12f, 34f), 8f).isEmpty())
        val withStart = ScreenshotEraserSamples.segment(p(12f, 34f), p(12f, 34f), 8f, includeStart = true)
        assertEquals(1, withStart.size)
        assertEquals(12f, withStart.first().x, 1e-4f)
    }

    /** 容差非正或非有限（NaN／Infinity）时按 0 处理，退化成「起点可选 + 终点」。 */
    @Test fun invalidToleranceFallsBackToEndpoints() {
        listOf(0f, -3f, Float.NaN, Float.POSITIVE_INFINITY).forEach { tolerance ->
            val samples = ScreenshotEraserSamples.segment(p(0f, 0f), p(50f, 0f), tolerance)
            assertEquals("tolerance=$tolerance", 1, samples.size)
            assertEquals(50f, samples.first().x, 1e-4f)
            val withStart = ScreenshotEraserSamples.segment(p(0f, 0f), p(50f, 0f), tolerance, includeStart = true)
            assertEquals("tolerance=$tolerance", 2, withStart.size)
            assertEquals(0f, withStart.first().x, 1e-4f)
        }
    }

    /** 至少一段：距离小于半半径时仍然给出终点。 */
    @Test fun shortSegmentsStillYieldTheEnd() {
        val samples = ScreenshotEraserSamples.segment(p(0f, 0f), p(1f, 0f), 8f)
        assertEquals(1, samples.size)
        assertEquals(1f, samples.first().x, 1e-4f)
    }
}
