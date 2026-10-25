package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** Desktop hotword-rescue contract, ported without a model or a microphone. */
class DesktopHotwordTest {
    private class FakeSession(var text: String, pieces: Map<String, Int> = pieceMap()) : DesktopCtcSession {
        var calls = 0
        var closed = false
        var failure: Throwable? = null
        override val pieces = HotwordPieces("")
        override fun greedy(samples: FloatArray, trie: HotwordTrie?, boost: Float): String {
            calls++
            failure?.let { throw it }
            return text
        }
        override fun close() { closed = true }
        init { this.pieces.pieces.putAll(pieces) }
    }

    private val audio = FloatArray(1600) { .01f }

    @Test fun compactDropsEverythingButLowercaseAlnum() {
        assertEquals("appjson", DesktopHotwords.compact("这个app返回的是J SON数据"))
        assertEquals("", DesktopHotwords.compact("请把代码提交到"))
    }

    @Test fun nearMissAcceptsSpellingAndSpacingVariants() {
        for ((text, word) in listOf(
            "我今天用pyython写了一个脚本" to "Python",
            "所以我上学期也学了pyython" to "Python",
            "请把代码提交到gitthub" to "GitHub",
            "返回的是 J SON 数据" to "JSON",
            "这个app返回的是数据" to "API",
            "a" to "API")) {
            assertTrue(text, DesktopHotwords.nearMiss(text, listOf(word)).contains(word))
        }
    }

    @Test fun nearMissIgnoresCorrectAndUnrelatedText() {
        for ((text, word) in listOf(
            "这个app返回的是JSON数据" to "JSON",
            "请把代码提交到 gitHub" to "GitHub",
            "填 pdf 格式" to "PDF",
            "桌子上那杯水是我的" to "GitHub",
            "今天天气很好" to "Python",
            "I use Kotlin and Python" to "Kotlin",
            "I build an android application" to "API",
            "the main for engine system" to "Android",
            "I use a lot of tools" to "API")) {
            assertEquals(text, emptyList<String>(), DesktopHotwords.nearMiss(text, listOf(word)))
        }
    }

    @Test fun hitsSeparateCompactionFromSpelling() {
        assertEquals(1, DesktopHotwords.hotwordHits("这个app返回的是JSON数据", listOf("JSON", "API")))
        assertEquals(1, DesktopHotwords.hotwordHits("把文件保存成PDF格式", listOf("PDF", "GitHub")))
        assertEquals(0, DesktopHotwords.hotwordHits("普通句子", listOf("PDF")))
        assertEquals(0, DesktopHotwords.strongHits("返回的是 J SON 数据", listOf("JSON")))
        assertEquals(1, DesktopHotwords.strongHits("返回的是 JSON 数据", listOf("JSON")))
        assertEquals(1, DesktopHotwords.hotwordHits("返回的是 J SON 数据", listOf("JSON")))
    }

    @Test fun acceptGuardKeepsTheOriginalUnlessItGainsWords() {
        assertEquals("请把代码提交到github", DesktopHotwords.accept("请把代码提交到gitthub", "请把代码提交到github", listOf("GitHub")))
        // "J SON" already compacts to json, so only the spelled-out count may decide.
        assertEquals("这个app返回的是JSON数据", DesktopHotwords.accept("这个app返回的是J SON数据", "这个app返回的是JSON数据", listOf("JSON")))
        assertEquals("请把代码提交到gitthub", DesktopHotwords.accept("请把代码提交到gitthub", "请把代码提交到gitthub", listOf("GitHub")))
        assertEquals("请把很长的一句话提交到gitthub上面去",
            DesktopHotwords.accept("请把很长的一句话提交到gitthub上面去", "github", listOf("GitHub")))
    }

    @Test fun trieKeepsEveryVocabularySegmentationNotJustTheShortest() {
        // A word must be reachable along every vocabulary path the model may actually emit;
        // building only the shortest split is what makes biasing silently do nothing.
        val pieces = mapOf("a" to 1, "p" to 2, "i" to 3, "ap" to 4, "pi" to 5, "\u2581a" to 6, "\u2581ap" to 7)
        val paths = DesktopHotwords.segmentations("api", pieces)
        assertEquals(3, paths.size)
        assertTrue(paths.contains(listOf(1, 2, 3)))
        assertTrue(paths.contains(listOf(4, 3)))
        assertTrue(paths.contains(listOf(1, 5)))
        // Only the minimal split plus one extra token is kept, so long words stay cheap.
        assertEquals(2, DesktopHotwords.segmentations("api", pieces, extra = 0).size)
        val trie = DesktopHotwords.buildTrie(listOf("API"), pieces)
        assertEquals("API", trie.children[1]?.children?.get(2)?.children?.get(3)?.word)
        assertEquals("API", trie.children[6]?.children?.get(2)?.children?.get(3)?.word)
    }

    @Test fun rescueStaysDormantWithoutAFactoryCall() {
        var created = 0
        val rescue = DesktopHotwordRescue({ created++; FakeSession("unused") })
        assertEquals("我今天用pyython写了一个脚本", rescue.rescore(audio, "我今天用pyython写了一个脚本", emptyList()))
        assertEquals("桌子上那杯水是我的", rescue.rescore(audio, "桌子上那杯水是我的", listOf("GitHub")))
        assertEquals(0, created)
    }

    @Test fun acceptedRescueUsesTheBiasedText() {
        val fake = FakeSession("请把代码提交到github")
        val rescue = DesktopHotwordRescue({ fake })
        assertEquals("请把代码提交到github", rescue.rescore(audio, "请把代码提交到gitthub", listOf("GitHub")))
        assertEquals(1, fake.calls)
        assertEquals(1, rescue.stats.rescued)
    }

    @Test fun noGainFailureAndSilenceKeepTheOriginalText() {
        val flat = FakeSession("请把代码提交到gitthub")
        assertEquals("请把代码提交到gitthub", DesktopHotwordRescue({ flat }).rescore(audio, "请把代码提交到gitthub", listOf("GitHub")))

        val broken = FakeSession("unused").apply { failure = IllegalStateException("model missing") }
        val rescue = DesktopHotwordRescue({ broken })
        assertEquals("请把代码提交到gitthub", rescue.rescore(audio, "请把代码提交到gitthub", listOf("GitHub")))
        assertEquals(1, rescue.stats.failed)
        assertTrue(broken.closed)
        assertFalse(rescue.active)
        // Like the desktop, a decode failure releases the session; the next near-miss
        // rebuilds it rather than keeping a broken graph resident.
        rescue.rescore(audio, "请把代码提交到gitthub", listOf("GitHub"))
        assertEquals(2, broken.calls)
        assertEquals(2, rescue.stats.failed)

        assertEquals("请把代码提交到gitthub", DesktopHotwordRescue({ FakeSession("请把代码提交到github") })
            .rescore(FloatArray(1600), "请把代码提交到gitthub", listOf("GitHub")))
    }

    @Test fun releaseDropsTheSessionAndRebuildsOnDemand() {
        val first = FakeSession("请把代码提交到github")
        var session: FakeSession = first
        val rescue = DesktopHotwordRescue({ session }, idleMillis = 10_000)
        rescue.rescore(audio, "请把代码提交到gitthub", listOf("GitHub"))
        assertSame(first, rescue.session)
        rescue.release()
        assertNull(rescue.session)
        assertTrue(first.closed)
        session = FakeSession("请把代码提交到github")
        assertEquals("请把代码提交到github", rescue.rescore(audio, "请把代码提交到gitthub", listOf("GitHub")))
        rescue.release()
    }

    @Test fun idleTimerReleasesTheSecondGraph() {
        val fake = FakeSession("请把代码提交到github")
        var now = 0L
        val rescue = DesktopHotwordRescue({ fake }, idleMillis = 50, clock = { now })
        rescue.rescore(audio, "请把代码提交到gitthub", listOf("GitHub"))
        assertTrue(rescue.active)
        now = 10_000
        val deadline = System.currentTimeMillis() + 5_000
        while (rescue.active && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertFalse(rescue.active)
        assertTrue(fake.closed)
    }

    @Test fun fbankMatchesKaldiGoldenFrames() {
        // Deterministic signal; expected values are kaldi_native_fbank output for the same
        // audio with the desktop's options (hamming, snip_edges, dither 0, 80 bins).
        val samples = FloatArray(32_000) { index ->
            val t = index / 16_000.0
            val value = .4 * sin(2 * PI * 220 * t) + .2 * sin(2 * PI * 1330 * t) +
                .1 * sin(2 * PI * 3100 * t) + .05 * sin(2 * PI * 6000 * t)
            (value * (.3 + .7 * kotlin.math.abs(sin(2 * PI * .7 * t)))).toFloat()
        }
        val frames = DesktopVoiceFbank.frames(samples)
        assertEquals(198, frames.size)
        val expected = mapOf(
            0 to doubleArrayOf(13.81804, 11.04124, 11.12953, 11.94323, 15.32164, 18.72451, 20.28985, 21.25396),
            50 to doubleArrayOf(15.61117, 12.40578, 12.56957, 13.54390, 17.09681, 20.53893, 22.10768, 23.07655),
            150 to doubleArrayOf(14.78691, 11.87474, 11.98502, 12.84809, 16.27714, 19.70018, 21.26723, 22.23346))
        for ((frame, values) in expected) for (bin in values.indices)
            assertEquals("frame $frame bin $bin", values[bin], frames[frame][bin], 2e-3)
        val stacked = DesktopVoiceFbank.stack(frames, 7, 6, FloatArray(560), FloatArray(560) { 1f })
        assertEquals(33, stacked.size)
        assertEquals(560, stacked[0].size)
        // LFR centre clamping: frame 0 repeats the first frame for the left half.
        assertEquals(frames[0][0], stacked[0][0].toDouble(), 1e-3)
        assertEquals(frames[0][79], stacked[0][239].toDouble(), 1e-3)
        assertEquals(frames[0][0], stacked[0][240].toDouble(), 1e-3)
    }

    private companion object {
        fun pieceMap(): Map<String, Int> {
            val pieces = HashMap<String, Int>()
            listOf("git", "hub", "▁git", "▁hub", "gi", "t", "th", "ub", "json", "▁json").forEachIndexed { index, piece ->
                pieces[piece] = index + 1
            }
            return pieces
        }
    }
}
