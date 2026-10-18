package local.voxfix.debug

import android.app.Instrumentation
import android.content.Context
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.SurroundingText

/** Android's actual composition/selection implementation, isolated editor and SQLite only. */
internal object VoiceLearningWorkflowSmoke {
    private class Editor(context: Context, private val fallback: Boolean = false) : BaseInputConnection(View(context), true) {
        val content = SpannableStringBuilder()
        init { Selection.setSelection(content, 0) }
        override fun getEditable(): Editable = content
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? =
            if (fallback) null else super.getSurroundingText(beforeLength, afterLength, flags)
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int) = ExtractedText().apply {
            text = content.toString(); startOffset = 0; partialStartOffset = -1; partialEndOffset = -1
            selectionStart = Selection.getSelectionStart(content); selectionEnd = Selection.getSelectionEnd(content)
        }
    }
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val name = "voice-workflow-${System.nanoTime()}.db"
        var failure: Throwable? = null
        var cases = 0
        instrumentation.runOnMainSync {
            try {
                val rows = listOf(
                    Triple("科", "科", "Kotlin"),
                    Triple("请联系张月确认时间", "张月", "张玥"),
                    Triple("我的沃文档", "沃", "Word"),
                    Triple("请用医校打开表格", "医校", "Excel"),
                    Triple("请用githup提交代码", "githup", "GitHub"),
                    Triple("我在欧博写笔记", "欧博", "欧泊"),
                    Triple("use sherpa-onn today", "sherpa-onn", "sherpa-onnx")
                ) + ('a'..'l').map { Triple("use NexaTol$it today", "NexaTol$it", "NexaTool$it") }
                PersonalLexicon(context, name).use { store ->
                    for ((old, heard, word) in rows) {
                        repeat(2) { cycle ->
                            val editor = Editor(context, cycle == 1)
                            val prefix = if (cycle == 1) "前".repeat(900) + "【正文】" else ""
                            val suffix = if (cycle == 1) "【后文锚点】" + "后".repeat(900) else ""
                            check(editor.commitText(prefix + old + suffix, 1))
                            check(editor.setSelection(prefix.length + old.length, prefix.length + old.length))
                            val initial = checkNotNull(VoiceEditorSnapshot.read(editor))
                            if (cycle == 1) check(initial.offset > 0 && initial.text.length <= 2048)
                            val tracker = VoiceCorrectionTracker()
                            tracker.arm(initial.text, initial.offset, prefix.length, old, 1, 0)
                            val start = prefix.length + old.indexOf(heard)
                            check(editor.setSelection(start, start + heard.length))
                            check(editor.commitText("", 1))
                            var text = checkNotNull(VoiceEditorSnapshot.read(editor))
                            check(tracker.observeAll(text.text, text.offset, 1, 1, false, VoiceLanguage::phonetic, VoiceLanguage::wordRange, false).isEmpty())
                            check(editor.setComposingText("pingyin", 1))
                            text = checkNotNull(VoiceEditorSnapshot.read(editor))
                            check(tracker.observeAll(text.text, text.offset, 1, 2, true, VoiceLanguage::phonetic, VoiceLanguage::wordRange, false).isEmpty())
                            check(editor.commitText(word, 1))
                            text = checkNotNull(VoiceEditorSnapshot.read(editor))
                            val rules = tracker.observeAll(text.text, text.offset, 1, 3, false, VoiceLanguage::phonetic, VoiceLanguage::wordRange, true)
                            check(rules.single().word == word) { "Wrong extracted word for $heard -> $word: $rules" }
                            store.learnVoice(rules)
                            check(tracker.observeAll(text.text, text.offset, 1, 4, false, VoiceLanguage::phonetic, VoiceLanguage::wordRange, true).isEmpty())
                        }
                        val row = store.voiceWords().single { it.word == word }
                        check(!row.manual && row.hits == 2) { "Correction did not grow exactly twice: $word" }
                        check(store.voiceSnapshot().correct(old, VoiceLanguage::phonetic, VoiceLanguage::boundaries) == old.replace(heard, word)) { "Correction not applied: $old" }
                        cases++
                    }
                    val before = store.exportBackup()
                    check(runCatching { store.learnVoice(listOf(VoiceCorrection("AXCEL", "Excel", "", ""), VoiceCorrection("100元", "900元", "", ""))) }.isFailure)
                    check(store.exportBackup() == before)
                    store.importBackup(before); check(store.exportBackup() == before)
                }
                PersonalLexicon(context, name).use { store ->
                    val dictionary = store.voiceSnapshot()
                    check(dictionary.correct("我的卧文档", VoiceLanguage::phonetic, VoiceLanguage::boundaries) == "我的Word文档")
                    for (text in listOf("我的卧室很大", "我在医校工作", "这张月票下周到期", "不要转账100元"))
                        check(dictionary.correct(text, VoiceLanguage::phonetic, VoiceLanguage::boundaries) == text)
                }
            } catch (error: Throwable) { failure = error }
        }
        try { failure?.let { throw it } } finally { context.deleteDatabase(name) }
        return "VOICE_WORKFLOW_PASS cases=$cases cycles=${cases * 2}; deletion/composition/candidate, extracted fallback, automatic words, persistence, backup and negative controls"
    }
}
