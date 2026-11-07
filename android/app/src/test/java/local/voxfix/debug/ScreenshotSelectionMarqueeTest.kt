package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 拉框矩形与比例锁定（1:1），对照上游 `marqueeScreenshotSelectionRect` 与
 * `aspectRatioLockedMarqueeRect`（固定提交）。
 */
class ScreenshotSelectionMarqueeTest {
    /** 像素格含首尾：10→19 正好 10 个像素宽。 */
    @Test fun marqueeSpansInclusiveCells() {
        val rect = ScreenshotSelectionMarquee.marquee(10f, 10f, 19f, 19f)
        assertEquals(10f, rect.width)
        assertEquals(10f, rect.height)
        assertEquals(10f, rect.left)
        assertEquals(10f, rect.top)
    }

    /** 同一格按下不算选区：宽高都是 0，锚在该格。 */
    @Test fun clickOnTheSameCellIsEmpty() {
        val rect = ScreenshotSelectionMarquee.marquee(42.4f, 17.6f, 42.4f, 17.6f)
        assertEquals(0f, rect.width)
        assertEquals(0f, rect.height)
        assertEquals(42f, rect.left)
        assertEquals(18f, rect.top)
    }

    /** 锁定后按较大跨度撑成正方形，从按下格的左边／上边向外长。 */
    @Test fun lockedSquareUsesTheLargerSpan() {
        val rect = ScreenshotSelectionMarquee.lockedSquare(10f, 10f, 60f, 30f, 1080f, 2400f)
        assertEquals(51f, rect.width)
        assertEquals(51f, rect.height)
        assertEquals(10f, rect.left)
        assertEquals(10f, rect.top)
    }

    /** 反向拖动时锚在按下格的右边／下边，矩形仍覆盖按下点。 */
    @Test fun lockedSquareAnchorsOnReverseDrag() {
        val rect = ScreenshotSelectionMarquee.lockedSquare(150f, 150f, 100f, 100f, 1080f, 2400f)
        assertEquals(51f, rect.width)
        assertEquals(51f, rect.height)
        assertEquals(100f, rect.left)
        assertEquals(100f, rect.top)
    }

    /** 贴边时按可用空间收缩，不越过画布。 */
    @Test fun lockedSquareShrinksAtTheCanvasEdge() {
        val rect = ScreenshotSelectionMarquee.lockedSquare(180f, 20f, 199f, 100f, 200f, 200f)
        assertEquals(20f, rect.width)
        assertEquals(20f, rect.height)
        assertEquals(200f, rect.right)
        assertEquals(40f, rect.bottom)
    }
}
