package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.FutureTask

class SpeechSegmentTest {
    @Test fun liveTextAndReleaseNeverWaitForAnUnfinishedSecondPass() {
        val pending = FutureTask { SecondPass("10点。", "<|zh|>") }
        val segment = SpeechSegment("十点", pending)
        assertEquals("十点", segment.current())
        assertEquals("十点", segment.finish())
        pending.run()
        assertEquals("十点", segment.current())
    }

    @Test fun completedCorrectionsKeepTheExistingContentProtection() {
        for ((second, expected) in listOf("10点。" to "10点。", "15点。" to "十点")) {
            val pending = FutureTask { SecondPass(second, "<|zh|>") }.apply { run() }
            assertEquals(expected, SpeechSegment("十点", pending).finish())
        }
    }

    @Test fun secondPassFailureKeepsTheStreamingText() {
        val pending = FutureTask<SecondPass> { error("decode failed") }.apply { run() }
        assertEquals("原文", SpeechSegment("原文", pending).finish())
    }

    /** 粤语整句替换：流式那串普通话同音汉字没有比较价值，语种标签才是指挥棒。 */
    @Test fun cantoneseSecondPassReplacesMandarinHomophones() {
        val pending = FutureTask { SecondPass("我哋今日唔使返工。", "<|yue|>") }.apply { run() }
        assertEquals("我哋今日唔使返工。", SpeechSegment("我们今日不是返工", pending).finish())
    }

    /** 日文标签与正文不符时仍退回保守判定。 */
    @Test fun foreignLabelWithoutEvidenceStillFallsBack() {
        val pending = FutureTask { SecondPass("今天天气很好", "<|ja|>") }.apply { run() }
        assertEquals("今天天气不错", SpeechSegment("今天天气不错", pending).finish())
    }

    @Test(timeout = 2000) fun finalBudgetAdoptsASecondPassThatCompletesAfterRelease() {
        val pending = FutureTask { SecondPass("有无人知道湾仔活道系点去", "<|yue|>") }
        val worker = Thread { Thread.sleep(15); pending.run() }.apply { start() }
        try {
            assertEquals("有无人知道湾仔活道系点去", SpeechSegment("姚某人自己都忘记我都还挺会干", pending).finish(1000))
        } finally { worker.join() }
    }

    @Test(timeout = 2000) fun finalBudgetTimesOutAndCancelsWithoutLosingStreamingText() {
        val pending = FutureTask { SecondPass("二遍", "<|zh|>") }
        assertEquals("原文", SpeechSegment("原文", pending).finish(10))
        org.junit.Assert.assertTrue(pending.isCancelled)
    }
}
