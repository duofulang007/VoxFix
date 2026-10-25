package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class DesktopVoiceTest {
    @Test fun punctuationNeverChangesWordsNegationOrNumbers() {
        for ((original, changed) in listOf("不要取消" to "要取消。", "128.5元" to "1285元。", ".NET" to "NET。"))
            assertEquals(original, DesktopVoiceRules.punctuate(original, changed))
        assertEquals("你好。", DesktopVoiceRules.punctuate("你好。", "你好。。"))
        assertEquals("API\u3000成功。", DesktopVoiceRules.punctuate("API\u3000成功", "API\u3000成功。"))
    }

    @Test fun gainBoundsAndState() {
        assertTrue(DesktopQuietBoost().process(FloatArray(32)).all { it == 0f })
        assertEquals(.025f, DesktopQuietBoost().process(floatArrayOf(.001f))[0], .00001f)
        assertEquals(.95f, DesktopQuietBoost().process(floatArrayOf(1f))[0], .00001f)
        val gain = DesktopQuietBoost()
        gain.process(floatArrayOf(.5f))
        assertEquals(.001f, gain.process(floatArrayOf(.001f))[0], .00001f)
    }

    @Test fun scriptAndJoiningRules() {
        assertEquals("API test", DesktopVoiceRules.join("API", "test"))
        assertEquals("中文API", DesktopVoiceRules.join("中文", "API"))
        assertTrue(DesktopVoiceRules.allowed("中文 API 128.5元\u0085"))
        assertFalse(DesktopVoiceRules.allowed("こんにちは"))
        assertFalse(DesktopVoiceRules.allowed("😀"))
        assertEquals("你好", DesktopVoiceRules.clean("<|zh|>你好<|NEUTRAL|>"))
    }

    @Test fun dictionaryLongestLiteralReplacementAndWordEdges() {
        val dictionary = DesktopVoiceDictionary(listOf(
            DesktopVoiceDictionary.Entry("APP", listOf("app", "app p", "a p p")),
            DesktopVoiceDictionary.Entry("GitHub", listOf("get up")),
            DesktopVoiceDictionary.Entry("$1", listOf("cost"))))
        assertEquals("APP and APP", dictionary.correct("app p and a p p"))
        assertEquals("forget upper GitHub", dictionary.correct("forget upper get up"))
        assertEquals("$1", dictionary.correct("cost"))
    }

    @Test fun dictionaryContextAndSequentialRules() {
        val dictionary = DesktopVoiceDictionary(listOf(
            DesktopVoiceDictionary.Entry("JSON", listOf("Jason"), true),
            DesktopVoiceDictionary.Entry("result", listOf("JSON"))))
        assertEquals("Jason", dictionary.correct("Jason"))
        assertEquals("result", dictionary.correct("Jason", chineseContext = true))
        assertEquals("这个result", dictionary.correct("这个Jason"))
    }

    @Test fun previewClockAndShortReleaseKeepOpening() {
        val planner = DesktopVoiceSegments(FloatArray(32000))
        repeat(5) { planner.append(FloatArray(1600)); assertNull(planner.frame(0, emptyList()).draft) }
        planner.append(FloatArray(1600))
        assertEquals(DesktopVoiceBounds(0, 9600), planner.frame(0, emptyList()).draft)
        planner.append(FloatArray(1600))
        assertNull(planner.frame(0, emptyList()).draft)
        assertEquals(listOf(DesktopVoiceBounds(0, 11200)), planner.frame(-1, listOf(DesktopVoiceBounds(4000, 11000)), true).finals)
    }

    @Test fun forcedCutCarriesRemainderWithoutGap() {
        val audio = FloatArray(200000) { .1f }
        val planner = DesktopVoiceSegments(audio)
        repeat(100) { planner.append(FloatArray(1600) { .1f }) }
        audio.fill(0f, 137920, 138080)
        val first = planner.frame(-1, listOf(DesktopVoiceBounds(0, 160000))).finals.single()
        assertEquals(138000, first.end)
        val last = planner.frame(-1, emptyList(), true).finals.single()
        assertEquals(first.end, last.start)
        assertEquals(160000, last.end)
    }

    @Test fun vadPositionTracksNativePruningAndStart() {
        val tracker = DesktopVadPosition()
        repeat(10) { tracker.accepted(1600, false, emptyList()) }
        tracker.accepted(1600, true, emptyList())
        assertEquals(14464, tracker.start)
        tracker.accepted(1600, true, emptyList())
        assertEquals(14464, tracker.start)
        tracker.accepted(1600, false, listOf(DesktopVoiceBounds(14464, 16000)))
        assertEquals(-1, tracker.start)
    }

    @Test fun pcmValidationRejectsNonFiniteAndOverflow() {
        val planner = DesktopVoiceSegments(FloatArray(1600))
        assertThrows(IllegalArgumentException::class.java) { planner.append(FloatArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { planner.append(floatArrayOf(Float.NaN)) }
        planner.append(FloatArray(1600))
        assertThrows(IllegalStateException::class.java) { planner.append(floatArrayOf(0f)) }
    }
}
