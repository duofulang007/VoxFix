package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.FutureTask

class SpeechSegmentTest {
    @Test fun corroboratedCantoneseEvidenceSurvivesSegmentDelivery() {
        val raw = "How could looked on fortun learn go to hackym name"
        val candidate = "包括六堂课同两个测验。"
        val pending = FutureTask { SecondPass(candidate, "<|yue|>", cantoneseAgreement = true) }.apply { run() }
        assertEquals(candidate, SpeechSegment(raw, pending).finish())
        val late = FutureTask { SecondPass(candidate, "<|yue|>", cantoneseAgreement = true) }
        val segment = SpeechSegment(raw, late)
        assertEquals(raw, segment.finish(0, cancel = false))
        late.run()
        assertEquals(candidate, segment.awaitRefined(1))
    }

    @Test fun boundaryAgreementSurvivesSegmentDeliveryWithoutBypassingNumericProtection() {
        for ((candidate, agreement, expected) in listOf(
            Triple("不要取消订单。", true, "不要取消订单。"),
            Triple("不要取消订单。", false, "要取消订单"))) {
            val pending = FutureTask { SecondPass(candidate, "<|zh|>", agreement) }.apply { run() }
            assertEquals(expected, SpeechSegment("要取消订单", pending).finish())
        }
        val changedAmount = FutureTask { SecondPass("花了5角。", "<|zh|>", true) }.apply { run() }
        assertEquals("花了5元", SpeechSegment("花了5元", changedAmount).finish())
    }

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

    /** 「先上屏、后改写」：松手不取消二遍，晚到的结果能改名这块文字。 */
    @Test(timeout = 2000) fun lateRefineReplacesTextWhenTheSecondPassArrivesAfterRelease() {
        val pending = FutureTask { SecondPass("两只小企鹅都有嘢食。", "<|yue|>") }
        val segment = SpeechSegment("隆家输给我都要夜色", pending)
        org.junit.Assert.assertTrue(segment.pending())
        assertEquals("隆家输给我都要夜色", segment.finish(0, cancel = false))
        val worker = Thread { Thread.sleep(10); pending.run() }.apply { start() }
        try { assertEquals("两只小企鹅都有嘢食。", segment.awaitRefined(1000)) } finally { worker.join() }
    }

    /** 二遍一直没回来时，晚到改写返回 null，调用方按实时文字定稿。 */
    @Test(timeout = 2000) fun lateRefineGivesUpWhenTheSecondPassIsTooSlow() {
        val pending = FutureTask { SecondPass("两只小企鹅都有嘢食。", "<|yue|>") }
        val segment = SpeechSegment("隆家输给我都要夜色", pending)
        assertEquals("隆家输给我都要夜色", segment.finish(0, cancel = false))
        org.junit.Assert.assertNull(segment.awaitRefined(10))
    }

    @Test(timeout = 2000) fun finalBudgetTimesOutAndCancelsWithoutLosingStreamingText() {
        val pending = FutureTask { SecondPass("二遍", "<|zh|>") }
        assertEquals("原文", SpeechSegment("原文", pending).finish(10))
        org.junit.Assert.assertTrue(pending.isCancelled)
    }

    @Test fun completedButUnreadSecondPassStillNeedsLateDelivery() {
        val pending = FutureTask { SecondPass("Apple.", "<|en|>") }
        val segment = SpeechSegment("", pending)
        assertEquals("", segment.finish(0, cancel = false))
        pending.run()
        org.junit.Assert.assertTrue("Completion between release and handoff must not lose the result", segment.pending())
        assertEquals("Apple.", segment.awaitRefined(1))
        org.junit.Assert.assertFalse(segment.pending())
    }
}
