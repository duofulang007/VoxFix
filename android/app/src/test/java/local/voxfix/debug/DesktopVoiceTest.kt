package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class DesktopVoiceTest {
    @Test fun stableLowHumIsNotSpeechButChangingVoiceIsKept() {
        for (hz in listOf(40, 50, 60, 80, 100, 120, 160, 200, 240)) {
            val hum = FloatArray(6400) { i ->
                val phase = 2 * Math.PI * hz * i / 16000
                (.03 * (kotlin.math.sin(phase) + .3 * kotlin.math.sin(phase * 2) +
                    .15 * kotlin.math.sin(phase * 3) + .07 * kotlin.math.sin(phase * 4)) *
                    (.6 + .4 * kotlin.math.sin(2 * Math.PI * 3 * i / 16000))).toFloat()
            }
            if (hz >= 160) assertTrue("modulated $hz Hz", DesktopHumGuard.isHum(hum))
            assertTrue("steady $hz Hz", DesktopHumGuard.isHum(FloatArray(6400) {
                (.001 * kotlin.math.sin(2 * Math.PI * hz * it / 16000)).toFloat()
            }))
            val voice = FloatArray(6400) { i ->
                (.02 * kotlin.math.sin(2 * Math.PI * (100.0 * i / 16000 + 80.0 * i * i / (16000.0 * 16000)))).toFloat()
            }
            assertFalse(DesktopHumGuard.isHum(voice))
            assertFalse(DesktopHumGuard.isHum(hum + voice))
            assertFalse(DesktopHumGuard.isHum(FloatArray(hum.size) { hum[it] + voice[it] }))
        }
        assertFalse(DesktopHumGuard.isHum(FloatArray(6400)))
        assertFalse(DesktopHumGuard.isHum(FloatArray(6400) { .1f }))
    }

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
        assertEquals("你好", DesktopVoiceRules.clean("<|zh|><FIL/>你好<L/><IL/>"))
        assertEquals("你好", DesktopVoiceRules.clean("< FIL / >你好< l / >"))
        assertEquals("a < b / c > d <HTML/>", DesktopVoiceRules.clean("a < b / c > d <HTML/>"))
        assertEquals("", DesktopVoiceRules.clean("<。"))
        assertEquals("", DesktopVoiceRules.clean("<FIL/>。"))
        assertEquals("", DesktopVoiceRules.clean("!!!"))
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

    @Test fun shortReleaseRequiresVoicedAudioInsteadOfBroadbandNoise() {
        assertFalse(DesktopVoiceRules.hasVoicedFrame(FloatArray(10400), 10400))
        assertFalse(DesktopVoiceRules.hasVoicedFrame(FloatArray(320), 320))
        assertFalse(DesktopVoiceRules.hasVoicedFrame(FloatArray(6400) { .1f }, 6400))
        val random = java.util.Random(20261026)
        repeat(16) {
            val noise = FloatArray(10400) { (random.nextDouble() * .2 - .1).toFloat() }
            assertFalse(DesktopVoiceRules.hasVoicedFrame(noise, noise.size))
        }
        val voiced = FloatArray(3200) { (.02 * kotlin.math.sin(2 * Math.PI * 220 * it / 16000)).toFloat() }
        assertTrue(DesktopVoiceRules.hasVoicedFrame(voiced, voiced.size))
        assertFalse(DesktopVoiceRules.hasVoicedFrame(FloatArray(3200).apply { this[1600] = .5f }, 3200))
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
        val audio = FloatArray(80000) { .1f }
        val planner = DesktopVoiceSegments(audio)
        repeat(40) { planner.append(FloatArray(1600) { .1f }) }
        audio.fill(0f, 53920, 54080)
        val first = planner.frame(-1, listOf(DesktopVoiceBounds(0, 64000))).finals.single()
        assertEquals(54000, first.end)
        val last = planner.frame(-1, emptyList(), true).finals.single()
        assertEquals(first.end, last.start)
        assertEquals(64000, last.end)
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
