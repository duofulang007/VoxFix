package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.FutureTask

class SpeechSegmentTest {
    @Test fun liveTextAndReleaseNeverWaitForAnUnfinishedSecondPass() {
        val pending = FutureTask { "10点。" }
        val segment = SpeechSegment("十点", pending)
        assertEquals("十点", segment.current())
        assertEquals("十点", segment.finish())
        pending.run()
        assertEquals("十点", segment.current())
    }

    @Test fun completedCorrectionsKeepTheExistingContentProtection() {
        for ((second, expected) in listOf("10点。" to "10点。", "15点。" to "十点")) {
            val pending = FutureTask { second }.apply { run() }
            assertEquals(expected, SpeechSegment("十点", pending).finish())
        }
    }

    @Test fun secondPassFailureKeepsTheStreamingText() {
        val pending = FutureTask<String> { error("decode failed") }.apply { run() }
        assertEquals("原文", SpeechSegment("原文", pending).finish())
    }
}
