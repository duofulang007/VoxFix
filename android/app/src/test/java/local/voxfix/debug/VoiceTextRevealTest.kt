package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class VoiceTextRevealTest {
    @Test fun desktopPacingAndUnicodeElements() {
        assertEquals("甲", VoiceTextReveal.step("", "甲乙丙丁", 0))
        assertEquals("甲乙丙丁", VoiceTextReveal.step("", "甲乙丙丁", 240))
        assertEquals("😀", VoiceTextReveal.step("", "😀e\u0301中", 0))
        assertEquals("😀e\u0301", VoiceTextReveal.step("😀", "😀e\u0301中", 0))
    }

    @Test fun coalescesCandidatesWithoutReplayingAndFinalsStayOrdered() {
        val frames = mutableListOf<Triple<String, Int, Boolean>>()
        val reveal = VoiceTextReveal { text, key, final -> frames.add(Triple(text, key, final)); true }
        reveal.offer("甲乙丙丁", 0, false, 0)
        assertTrue(reveal.tick(0)); assertEquals("甲", frames.last().first)
        reveal.offer("甲已丙丁戊", 0, false, 10)
        reveal.tick(35); assertEquals("甲已", frames.last().first)
        reveal.offer("甲已。", 0, true, 40)
        reveal.offer("下一句。", 16000, true, 40)
        reveal.tick(500)
        reveal.tick(800)
        assertEquals(listOf(0, 16000), frames.filter { it.third }.map { it.second })
        val count = frames.size
        reveal.offer("迟到草稿", 0, false, 900); reveal.offer("重复定稿", 0, true, 900)
        reveal.tick(900); assertEquals(count, frames.size)
    }

    @Test fun shortenedDraftWaitsForFinalAndCancellationDropsUnseenText() {
        var shown = ""
        val reveal = VoiceTextReveal { text, _, _ -> shown = text; true }
        reveal.offer("不要取消", 0, false, 0); reveal.tick(300)
        reveal.offer("要取消", 0, false, 400); reveal.tick(500)
        assertEquals("不要取消", shown)
        reveal.offer("要取消。", 0, true, 600); reveal.tick(900)
        assertEquals("要取消。", shown)
        reveal.offer("未见文字", 1, true, 1000)
        assertEquals(setOf(1), reveal.clear()); reveal.tick(2000)
        assertEquals("要取消。", shown)
    }

    @Test fun failedWriteIsNotAccountedAndCanBeRetainedOnDrain() {
        var accept = false
        var applied = ""
        val reveal = VoiceTextReveal { text, _, _ -> if (accept) applied = text; accept }
        reveal.offer("完整定稿。", 0, true, 0); assertFalse(reveal.tick(0))
        assertEquals("", applied); assertTrue(reveal.active)
        accept = true; reveal.tick(100, true)
        assertEquals("完整定稿。", applied); assertFalse(reveal.active)
        var retained = ""
        val blocked = VoiceTextReveal { text, _, final -> if (final) retained = text; final }
        blocked.offer("拼音中保留整句。", 1, true, 0); blocked.tick(0)
        assertEquals("拼音中保留整句。", retained); assertFalse(blocked.active)
    }

    @Test fun roundsKeepEverySampleAcrossAwkwardChunkAndThreeMinuteBoundaries() {
        val input = FloatArray(DesktopVoiceRules.MAX_SAMPLES * 2 + 137) { (it % 997) / 997f }
        var cursor = 0
        var actual = 0
        val offsets = mutableListOf<Int>()
        DesktopVoiceRounds.run({
            if (cursor == input.size) null else {
                val end = minOf(cursor + 997, input.size)
                input.copyOfRange(cursor, end).also { cursor = end }
            }
        }, { false }) { next, offset ->
            offsets.add(offset)
            while (true) {
                val chunk = next() ?: break
                for (sample in chunk) assertEquals(input[actual++], sample, 0f)
            }
        }
        assertEquals(input.size, actual)
        assertEquals(listOf(0, DesktopVoiceRules.MAX_SAMPLES, DesktopVoiceRules.MAX_SAMPLES * 2), offsets)
        DesktopVoiceRounds.run({ error("cancelled supplier") }, { true }) { _, _ -> error("cancelled round") }
    }
}
