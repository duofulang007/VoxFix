package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class VoiceLearningTest {
    private val sound: (String) -> String = { it.replace("月", "玥").replace("悦", "玥") }
    private val nameRange: (String, Int, Int) -> IntRange = { _, _, _ -> 3..4 }
    @Test fun learnsExplicitEnglishWordWithDifferentLengthAndKeepsContext() {
        for (heard in listOf("AXCEL", "EX CELL", "艾克塞尔", "Xia", "X要表", "医校")) {
            val tracker = VoiceCorrectionTracker()
            val original = "请用${heard}打开表格。"
            tracker.arm(original, 0, 0, original, 1, 0, setOf("Excel"))
            assertNull(tracker.observe("请用Ex打开表格。", 0, 1, 1, false, sound, nameRange))
            val rule = tracker.observe("请用Excel打开表格。", 0, 1, 2, false, sound, nameRange)!!
            assertEquals(heard, rule.heard); assertEquals("Excel", rule.word)
            VoiceLexicon.validateCorrection(rule)
            val lexicon = VoiceLexicon(listOf(VoiceWord("Excel", manual = true)), listOf(rule))
            assertEquals("请用Excel打开表格。", lexicon.correct(original))
            assertEquals("他在讨论${heard}这个词。", lexicon.correct("他在讨论${heard}这个词。"))
            assertEquals("请用${heard}LED打开表格。", lexicon.correct("请用${heard}LED打开表格。"))
        }
        val tracker = VoiceCorrectionTracker()
        val original = "你把它发到那个医校里面。"
        tracker.arm(original, 0, 0, original, 1, 0, setOf("Excel"))
        val rule = tracker.observe("你把它发到那个 Excel 里面。", 0, 1, 10, false, sound, nameRange)!!
        assertEquals("医校", rule.heard); assertEquals("Excel", rule.word)
        val lexicon = VoiceLexicon(listOf(VoiceWord("Excel", manual = true)), listOf(rule))
        assertEquals("你把它发到那个Excel里面。", lexicon.correct(original))
        assertEquals("我在医校工作。", lexicon.correct("我在医校工作。"))
    }
    @Test fun englishLearningRejectsUnknownTargetsAndChangesOutsideTheVoiceInsertion() {
        val tracker = VoiceCorrectionTracker()
        tracker.arm("前文用AXCEL。后文", 0, 2, "用AXCEL。", 1, 0, setOf("Excel"))
        assertNull(tracker.observe("前文用Word。后文", 0, 1, 10, false, sound, nameRange))
        assertNull(tracker.observe("改写用Excel。后文", 0, 1, 20, false, sound, nameRange))
        for (rule in listOf(VoiceCorrection("转账100", "Excel", "", ""), VoiceCorrection("AXCEL!", "Excel", "", ""))) {
            assertTrue(runCatching { VoiceLexicon.validateCorrection(rule) }.isFailure)
        }
    }
    @Test fun manualChineseWordWinsImmediatelyOnlyAtWordBoundaries() {
        val lexicon = VoiceLexicon(listOf(VoiceWord("张玥", manual = true)))
        val boundaries: (String) -> Set<Int> = { setOf(0, 2, 4, it.length) }
        assertEquals("联系张玥确认", lexicon.correct("联系张悦确认", sound, boundaries))
        assertEquals("张玥", lexicon.correct("张悦", sound) { setOf(0, it.length) })
        assertEquals("这张月票", lexicon.correct("这张月票", sound) { setOf(0, 1, 2, 4) })
        assertEquals("联系李悦确认", lexicon.correct("联系李悦确认", sound, boundaries))
        val ambiguous = VoiceLexicon(listOf(VoiceWord("张玥", manual = true), VoiceWord("张月", manual = true)))
        assertEquals("联系张悦确认", ambiguous.correct("联系张悦确认", sound, boundaries))
        assertEquals("联系张悦确认", VoiceLexicon(listOf(VoiceWord("张玥", hits = 20))).correct("联系张悦确认", sound, boundaries))
    }
    @Test fun learningRejectsPasswordsAndNoPersonalizationFields() {
        assertTrue(VoiceCorrectionTracker.allowed(1, 0))
        for (type in listOf(0x81, 0x91, 0xe1, 0x12)) assertFalse(VoiceCorrectionTracker.allowed(type, 0))
        assertFalse(VoiceCorrectionTracker.allowed(1, android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
    }
    @Test fun tracksOnlyAnchoredSameEditorHomophoneCorrection() {
        val tracker = VoiceCorrectionTracker()
        tracker.arm("前文请联系张月确认。后文", 10, 12, "请联系张月确认。", 1, 0)
        assertNull(tracker.observe("前文请联系张玥确认。后文", 10, 1, 100, true, sound, nameRange))
        val rule = tracker.observe("前文请联系张玥确认。后文", 10, 1, 200, false, sound, nameRange)!!
        assertEquals("张月", rule.heard); assertEquals("张玥", rule.word)
        assertNull(tracker.observe("前文请联系张玥确认。后文", 10, 1, 300, false, sound, nameRange))
        assertEquals("请联系张月确认。", VoiceLexicon(corrections = listOf(rule)).correct("请联系张月确认。"))
        assertEquals("请联系张玥确认。", VoiceLexicon(corrections = listOf(rule.copy(hits = 2))).correct("请联系张月确认。"))
        assertEquals("这张月票下周到期", VoiceLexicon(corrections = listOf(rule)).correct("这张月票下周到期"))
        assertEquals("我和张月开会", VoiceLexicon(corrections = listOf(rule)).correct("我和张月开会"))
    }
    @Test fun deletionWhileChoosingDoesNotEraseThePendingObservation() {
        val tracker = VoiceCorrectionTracker()
        tracker.arm("请联系张月确认。", 0, 0, "请联系张月确认。", 1, 0)
        assertNull(tracker.observe("请联系张确认。", 0, 1, 100, false, sound, nameRange))
        assertNotNull(tracker.observe("请联系张玥确认。", 0, 1, 200, false, sound, nameRange))
    }
    @Test fun rejectsChangedMeaningExpiredEditorOrLostAnchors() {
        for ((text, editor, now) in listOf(Triple("明天我要去上班。", 1L, 10L), Triple("明天我要去上学。", 2L, 10L), Triple("明天我要去上学。", 1L, 120001L))) {
            val tracker = VoiceCorrectionTracker()
            tracker.arm("明天我要去上学。", 0, 0, "明天我要去上学。", 1, 0)
            assertNull(tracker.observe(text, 0, editor, now, false, { it }, { _, _, _ -> 5..6 }))
        }
        val tracker = VoiceCorrectionTracker()
        tracker.arm("前文请联系张月确认。后文", 0, 2, "请联系张月确认。", 1, 0)
        assertNull(tracker.observe("他人请联系张玥确认。后文", 0, 1, 10, false, sound, nameRange))
        assertFalse(tracker.active())
    }
    @Test fun hotwordsAreBoundedValidatedAndGainStrengthWithoutControlSyntax() {
        val tokens = setOf("张", "玥")
        val weak = VoiceLexicon(listOf(VoiceWord("张玥"))).hotwords(tokens)
        val strong = VoiceLexicon(listOf(VoiceWord("张玥", hits = 5))).hotwords(tokens)
        assertTrue(weak.substringAfter(":").toDouble() < strong.substringAfter(":").toDouble())
        assertEquals("张玥 :3.00", VoiceLexicon(listOf(VoiceWord("张玥", manual = true))).hotwords(tokens))
        assertEquals("", VoiceLexicon(listOf(VoiceWord("陌生"))).hotwords(tokens))
        for (bad in listOf("词/替换", "词 :9", "词\n语", " ", "a")) assertTrue(runCatching { VoiceLexicon.validateWord(bad) }.isFailure)
    }
    @Test fun competingCorrectionsDoNotBecomeArbitraryReplacementOrCascade() {
        val rules = listOf(VoiceCorrection("张月", "张玥", "联系", "确认", 2), VoiceCorrection("张月", "张悦", "联系", "确认", 2))
        assertEquals("联系张月确认", VoiceLexicon(corrections = rules).correct("联系张月确认"))
        val cascade = listOf(VoiceCorrection("张月", "张玥", "联系", "确认", 2), VoiceCorrection("张玥", "张悦", "联系", "确认", 2))
        assertEquals("联系张玥确认", VoiceLexicon(corrections = cascade).correct("联系张月确认"))
    }
    @Test fun secondPassProtectionOnlyKeepsEquivalentPersonalSpelling() {
        val lexicon = VoiceLexicon(listOf(VoiceWord("张玥", manual = true), VoiceWord("VoxFix", manual = true)))
        assertEquals("联系张玥", lexicon.protect("联系张玥", "联系张月", sound))
        assertEquals("不要联系张月", lexicon.protect("联系张玥", "不要联系张月", sound))
        assertEquals("使用VoxFix工具", lexicon.correct("使用VOXFIX工具"))
        assertEquals("VOXFIXED", lexicon.correct("VOXFIXED"))
    }
    @Test fun confirmedContextAlsoHandlesAnotherHomophoneWithoutGlobalReplacement() {
        val lexicon = VoiceLexicon(corrections = listOf(VoiceCorrection("张月", "张玥", "联系", "确认", 2)))
        assertEquals("联系张玥确认", lexicon.correct("联系张悦确认", sound))
        assertEquals("这张月票", lexicon.correct("这张月票", sound))
        assertEquals("联系李月确认", lexicon.correct("联系李月确认", sound))
        assertEquals("联系张悦确认", lexicon.correct("联系张悦确认", phonetic = { "" }))
        val end = VoiceLexicon(corrections = listOf(VoiceCorrection("张月", "张玥", "联系", "", 2)))
        assertEquals("联系张玥。", end.correct("联系张悦。", sound))
        assertEquals("联系张月票", end.correct("联系张月票", sound))
    }
    @Test fun finalPunctuationDoesNotPreventLearningTheLastWord() {
        val tracker = VoiceCorrectionTracker()
        tracker.arm("联系张月。", 0, 0, "联系张月。", 1, 0)
        val rule = tracker.observe("联系张玥。", 0, 1, 10, false, sound, { _, _, _ -> 2..3 })!!
        assertEquals("", rule.right)
        assertEquals("联系张玥", VoiceLexicon(corrections = listOf(rule.copy(hits = 2))).correct("联系张悦", sound))
    }
}
