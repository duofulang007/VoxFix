package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class VoiceGrowthTest {
    private val range: (String, Int, Int) -> IntRange = { text, a, b ->
        var start = a; var end = b
        while (start > 0 && text[start - 1].isLetterOrDigit()) start--
        while (end < text.length && text[end].isLetterOrDigit()) end++
        start until end
    }
    private val sound: (String) -> String = { it.lowercase().replace("悦", "玥").replace("月", "玥").replace("博", "泊") }
    private fun learn(old: String, corrected: String, known: Set<String> = emptySet()): List<VoiceCorrection> {
        val tracker = VoiceCorrectionTracker()
        tracker.arm(old, 0, 0, old, 1, 0, known)
        return tracker.observeAll(corrected, 0, 1, 10, false, sound, range, true)
    }
    @Test fun unknownEnglishAndMixedTermsLearnWithoutManualRegistration() {
        assertEquals("Word", learn("沃", "Word").single().word)
        for ((wrong, word) in listOf("axcel" to "Excel", "githup" to "GitHub", "艾克塞尔" to "Excel", "X" to "Notion", "sherpa-onn" to "sherpa-onnx", "qwen3" to "Qwen3")) {
            val old = "please use $wrong today"
            val rules = learn(old, "please use $word today")
            assertEquals("$wrong -> $word", 1, rules.size)
            assertEquals(wrong, rules.single().heard); assertEquals(word, rules.single().word)
            assertEquals(old, VoiceLexicon(corrections = rules).correct(old))
            assertEquals("please use $word today", VoiceLexicon(corrections = rules.map { it.copy(hits = 2) }).correct(old))
        }
    }
    @Test fun deletionComposingCandidateAndIndependentSessionsAreOneSampleEach() {
        val old = "联系 张月 确认"
        val tracker = VoiceCorrectionTracker()
        tracker.arm(old, 0, 0, old, 1, 0)
        assertTrue(tracker.observeAll("联系  确认", 0, 1, 1, false, sound, range, false).isEmpty())
        assertTrue(tracker.observeAll("联系 zhangyue 确认", 0, 1, 2, true, sound, range, false).isEmpty())
        val corrected = "联系 张玥 确认"
        val rule = tracker.observeAll(corrected, 0, 1, 3, false, sound, range, true).single()
        assertEquals("张月", rule.heard); assertEquals("张玥", rule.word)
        assertTrue(tracker.observeAll(corrected, 0, 1, 4, false, sound, range, true).isEmpty())
        assertEquals(rule, learn(old, corrected).single())
    }
    @Test fun multipleEditsAreSeparateWordsNotOneSavedSentence() {
        val rules = learn("please use axcel and githup today", "please use Excel and GitHub today")
        assertEquals(setOf("Excel", "GitHub"), rules.map { it.word }.toSet())
        assertTrue(rules.all { it.heard.length <= 6 && it.left.length <= 4 && it.right.length <= 4 })
        val lexicon = VoiceLexicon(corrections = rules.map { it.copy(hits = 2) })
        assertEquals("please use Excel and GitHub today", lexicon.correct("please use axcel and githup today"))
    }
    @Test fun partialLettersPureDeletionNumbersNegationAndRewritesDoNotBecomeRules() {
        val tracker = VoiceCorrectionTracker()
        tracker.arm("use axcel today", 0, 0, "use axcel today", 1, 0)
        assertTrue(tracker.observeAll("use Ex today", 0, 1, 1, false, sound, range, false).isEmpty())
        assertEquals("Excel", tracker.observeAll("use Excel today", 0, 1, 2, false, sound, range, true).single().word)
        for ((old, text) in listOf("use axcel today" to "use  today", "金额100元" to "金额900元", "不要转账" to "需要转账", "delete everything" to ""))
            assertTrue("$old -> $text", learn(old, text).isEmpty())
    }
    @Test fun generatedUnseenWordsUseTheSameLearningPath() {
        for (suffix in 'a'..'z') {
            val word = "NexaTool$suffix"
            val wrong = "NexaTol$suffix"
            val rule = learn("use $wrong now", "use $word now").single()
            assertEquals(word, rule.word)
            assertEquals("use $word now", VoiceLexicon(corrections = listOf(rule.copy(hits = 2))).correct("use $wrong now"))
        }
    }
    @Test fun aNewEnglishTermLearnsFromChineseAndAcceptsAnotherHomophone() {
        val pronunciation: (String) -> String = { it.replace("沃", "wo").replace("卧", "wo").replace("握", "wo") }
        val rules = listOf("沃", "卧").map { wrong ->
            val old = "我的${wrong}文档"
            val tracker = VoiceCorrectionTracker()
            tracker.arm(old, 0, 0, old, 1, 0)
            assertTrue(tracker.observeAll("我的文档", 0, 1, 1, false, pronunciation, range, false).isEmpty())
            assertTrue(tracker.observeAll("我的word文档", 0, 1, 2, true, pronunciation, range, false).isEmpty())
            tracker.observeAll("我的Word文档", 0, 1, 3, false, pronunciation, range, true).single()
        }
        val lexicon = VoiceLexicon(listOf(VoiceWord("Word", hits = 2)), rules)
        assertEquals("我的沃文档", VoiceLexicon(listOf(VoiceWord("Word", hits = 1)), rules).correct("我的沃文档", pronunciation))
        for (heard in listOf("沃", "卧", "握")) assertEquals("我的Word文档", lexicon.correct("我的${heard}文档", pronunciation))
        assertEquals("我的卧室", lexicon.correct("我的卧室", pronunciation))
        assertEquals("沃土种庄稼", lexicon.correct("沃土种庄稼", pronunciation))
    }
    @Test fun vocabularyBecomesPortableOnlyAfterIndependentContexts() {
        val word = VoiceWord("张玥", hits = 3)
        val rules = listOf(VoiceCorrection("张月", "张玥", "联系", "确认", 2), VoiceCorrection("张悦", "张玥", "我和", "开会", 1))
        val lexicon = VoiceLexicon(listOf(word), rules)
        assertEquals("张玥", lexicon.correct("张月", sound) { setOf(0, 2) })
        assertEquals("张月", VoiceLexicon(listOf(word), rules.take(1)).correct("张月", sound) { setOf(0, 2) })
        assertEquals("这张月票", lexicon.correct("这张月票", sound) { setOf(0, 1, 2, 4) })
    }
    @Test fun pronunciationPunctuationAndCaseAreAlsoLearnedWithoutSpecialNames() {
        val rule = learn("use Xia. now", "use Word now").single()
        assertEquals("Xia.", rule.heard)
        assertEquals("use Word now", VoiceLexicon(corrections = listOf(rule.copy(hits = 2))).correct("use Xia. now"))
        val words = listOf(VoiceWord("Qwen3", hits = 4), VoiceWord("QWEN3", hits = 2))
        assertEquals("Qwen3", VoiceLexicon(words).correct("qwen3"))
        assertEquals("Qwen3", VoiceLexicon(words.reversed()).correct("qwen3"))
    }
    @Test fun conflictUsesSharedEvidenceAndPunctuationVariantsKeepTheSameSound() {
        val phonetic: (String) -> String = { it.replace("沃", "wo").replace("卧", "wo").replace("。", "") }
        val rules = listOf(VoiceCorrection("沃", "Word", "我的", "文档", 1), VoiceCorrection("卧", "Word", "我的", "文档", 2), VoiceCorrection("沃", "Excel", "我的", "文档", 2))
        assertEquals("我的Word文档", VoiceLexicon(listOf(VoiceWord("Word", hits = 3), VoiceWord("Excel", hits = 2)), rules).correct("我的沃文档", phonetic))
        val dotted = VoiceLexicon(corrections = listOf(VoiceCorrection("沃。", "Word", "我的", "文档", 2)))
        assertEquals("我的Word文档", dotted.correct("我的卧。文档", phonetic))
    }
    @Test fun adjacentCorrectionsRememberSourceContextAndWeakerReadingsNeedMoreEvidence() {
        val old = "我的沃文的"
        val target = "我的Word文档"
        val pronunciation: (String) -> String = { text -> text.replace("沃", "wo").replace("卧", "wo").replace("文的", "wende").replace("文档", "wendang") }
        val words: (String, Int, Int) -> IntRange = { _, a, b -> if (a >= 6) 6..7 else a until b }
        val rules = VoiceEditExtractor.extract(old, target, emptySet(), true, pronunciation, words)
        assertEquals(setOf("Word", "文档"), rules.map { it.word }.toSet())
        assertEquals("我的Word文的", VoiceLexicon(corrections = rules.map { it.copy(hits = 2) }).correct(old, pronunciation))
        assertEquals(target, VoiceLexicon(corrections = rules.map { it.copy(hits = 3) }).correct(old, pronunciation))
        assertEquals(target, VoiceLexicon(corrections = rules.map { it.copy(hits = 3) }).correct("我的卧文档", pronunciation))
        val nearby = VoiceEditExtractor.extract("红axcel蓝", "鸿Excel兰", emptySet(), true, pronunciation) { _, a, b -> a until b }
        assertEquals(setOf("红" to "蓝", "红" to "兰", "鸿" to "蓝", "鸿" to "兰"), nearby.map { it.left to it.right }.toSet())
        assertEquals("红Excel兰", VoiceLexicon(corrections = nearby.map { it.copy(hits = 2) }).correct("红axcel兰"))
        val later = listOf(VoiceCorrection("沃", "Word", "我的", "文的", 2), VoiceCorrection("文的", "文档", "Word", "", 4), VoiceCorrection("文的", "文档", "我的沃", "", 1))
        val profile = listOf(VoiceWord("Word", hits = 2), VoiceWord("文档", hits = 5))
        assertEquals(target, VoiceLexicon(profile, later).correct(old, pronunciation))
        val ambiguous = later + VoiceCorrection("沃", "Excel", "我的", "文的", 2)
        assertEquals(old, VoiceLexicon(profile + VoiceWord("Excel", hits = 2), ambiguous).correct(old, pronunciation))
    }
}
