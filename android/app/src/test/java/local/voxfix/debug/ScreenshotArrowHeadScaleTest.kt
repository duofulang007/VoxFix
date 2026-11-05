package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Head sizing contract from Snow Shot 1.1.8 `get_arrowhead_points` and
 * `crates/snow-draw-engine-document/tests/arrowhead_scaling.rs`. The two-end rule is not a separate
 * branch upstream: every head is capped at half of the segment it sits on (a quarter for the diamond
 * family), so two heads on one segment meet at the midpoint and never overlap.
 */
class ScreenshotArrowHeadScaleTest {
    private val heads = ScreenshotArrowGlyphs.PANEL_HEADS + ScreenshotArrowhead.SQUARE
    private val roundHeads = setOf(ScreenshotArrowhead.DOT, ScreenshotArrowhead.CIRCLE,
        ScreenshotArrowhead.CIRCLE_OUTLINE)
    private val diamondFamily = setOf(ScreenshotArrowhead.DIAMOND, ScreenshotArrowhead.DIAMOND_OUTLINE,
        ScreenshotArrowhead.SQUARE)

    /** `short_arrow_endpoint_sizes_remain_bounded`: a 20 unit arrow with a 20 wide stroke. */
    @Test
    fun shortArrowHeadsStayWithinHalfTheSegment() {
        for (head in heads) {
            val limit = ScreenshotArrowRendering.headReach(head, 20f)
            assertEquals(if (head in diamondFamily) 5f else 10f, limit, 1e-4f)
            if (head in roundHeads) {
                assertTrue("$head diameter", ScreenshotArrowRendering.headDiameter(head, 20f, 1f, 20f) <= 10f + 1e-4f)
                assertTrue("$head radius", ScreenshotArrowRendering.headDiameter(head, 20f, 3f, 20f) / 2f <= 10f + 1e-4f)
            } else {
                assertTrue("$head length", ScreenshotArrowRendering.headLength(head, 20f, 1f, 20f) <= limit + 1e-4f)
                // Only the diamond family draws a rear vertex, and it sits two head lengths back.
                if (head in diamondFamily) {
                    assertTrue("$head rear", ScreenshotArrowRendering.headLength(head, 20f, 3f, 20f) * 2f <= 10f + 1e-4f)
                }
            }
        }
    }

    /** `arrow_ratio_preserves_short_arrow_limits_and_none_endpoints`: the cap wins over the ratio. */
    @Test
    fun ratioCannotGrowACappedHead() {
        for (head in heads) {
            if (head in roundHeads) {
                assertEquals(ScreenshotArrowRendering.headDiameter(head, 20f, 1f, 20f),
                    ScreenshotArrowRendering.headDiameter(head, 20f, 3f, 20f), 1e-4f)
            } else {
                assertEquals(ScreenshotArrowRendering.headLength(head, 20f, 1f, 20f),
                    ScreenshotArrowRendering.headLength(head, 20f, 3f, 20f), 1e-4f)
            }
        }
    }

    /** A long segment lets the ratio through: the base size is 25 × 1.25 for the standard arrow. */
    @Test
    fun ratioScalesHeadsOnALongSegment() {
        val base = ScreenshotArrowRendering.headLength(ScreenshotArrowhead.ARROW, 2f, 1f, 1000f)
        assertEquals(31.25f, base, 1e-4f)
        assertEquals(base * 3f, ScreenshotArrowRendering.headLength(ScreenshotArrowhead.ARROW, 2f, 3f, 1000f), 1e-4f)
        // sqrt(width/2) growth: a 4 wide stroke is one step above the 2 wide reference.
        assertEquals(base * kotlin.math.sqrt(2f),
            ScreenshotArrowRendering.headLength(ScreenshotArrowhead.ARROW, 4f, 1f, 1000f), 1e-4f)
        // Diamond family base is 12 × 1.25 and its reach is a quarter, not a half.
        assertEquals(15f, ScreenshotArrowRendering.headLength(ScreenshotArrowhead.DIAMOND, 2f, 1f, 1000f), 1e-4f)
        // A 40 unit segment caps that same head at a quarter of its length.
        assertEquals(10f, ScreenshotArrowRendering.headLength(ScreenshotArrowhead.DIAMOND, 2f, 3f, 40f), 1e-4f)
    }

    /** A degenerate drag has no segment, so it has no head: the renderer then skips the head. */
    @Test
    fun zeroLengthSegmentHasNoHead() {
        for (head in heads) {
            assertEquals(0f, ScreenshotArrowRendering.headReach(head, 0f), 1e-6f)
            assertEquals(0f, ScreenshotArrowRendering.headLength(head, 4f, 1f, 0f), 1e-6f)
        }
    }

    /** The round adjustment is `min(strokeWidth, 2) - 2`, so a hairline stroke shrinks the disc. */
    @Test
    fun roundHeadsKeepTheStrokeAdjustment() {
        val hairline = ScreenshotArrowRendering.headDiameter(ScreenshotArrowhead.CIRCLE, 1f, 1f, 1000f)
        val reference = ScreenshotArrowRendering.headDiameter(ScreenshotArrowhead.CIRCLE, 2f, 1f, 1000f)
        assertEquals(reference - 1f, hairline, 1e-4f)
        assertEquals(17.75f, hairline, 1e-4f)
    }
}
