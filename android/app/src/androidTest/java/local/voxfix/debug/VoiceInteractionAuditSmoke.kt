package local.voxfix.debug

import android.app.Instrumentation
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest

/** Isolated platform probes of the input-connection call sequences found in the IME. */
internal object VoiceInteractionAuditSmoke {
    private class Editor(instrumentation: Instrumentation) : BaseInputConnection(View(instrumentation.targetContext), true) {
        val content = SpannableStringBuilder().apply { Selection.setSelection(this, 0) }
        var rejectSelection = false
        var rejectCommit = false
        override fun getEditable(): Editable = content
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int) = ExtractedText().apply {
            text = content.toString(); startOffset = 0
            selectionStart = Selection.getSelectionStart(content); selectionEnd = Selection.getSelectionEnd(content)
        }
        override fun setSelection(start: Int, end: Int): Boolean = !rejectSelection && super.setSelection(start, end)
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = !rejectCommit && super.commitText(text, newCursorPosition)
    }

    fun regression(instrumentation: Instrumentation): String {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val typing = Editor(instrumentation)
                check(typing.commitText("语音结果。", 1))
                val old = VoiceRefinement(typing, 1, 0, "语音结果。")
                check(typing.setComposingText("ni", 1))
                check(typing.content.toString() == "语音结果。ni")
                check(!old.apply(typing, 1, "二遍结果。"))
                check(BaseInputConnection.getComposingSpanStart(typing.content) == 5)

                val late = Editor(instrumentation)
                check(late.commitText("前语音结果。后", 1))
                check(late.setSelection(6, 6))
                val range = VoiceRefinement(late, 2, 1, "语音结果。")
                check(range.apply(late, 2, "修正。"))
                check(late.content.toString() == "前修正。后")
                check(Selection.getSelectionStart(late.content) == 4)
                check(BaseInputConnection.getComposingSpanStart(late.content) < 0)

                val next = Editor(instrumentation)
                check(next.setComposingText("hao", 1))
                check(!range.apply(next, 2, "旧段"))
                check(!range.apply(late, 3, "旧代"))
                check(BaseInputConnection.getComposingSpanStart(next.content) == 0)

                val repeated = Editor(instrumentation)
                check(repeated.commitText("你好你好", 1))
                check(!VoiceRefinement(repeated, 1, 0, "你好").apply(repeated, 1, "改写"))
                check(repeated.content.toString() == "你好你好")

                val empty = Editor(instrumentation)
                check(VoiceRefinement(empty, 1, 0, "").apply(empty, 1, "Apple."))
                check(empty.content.toString() == "Apple.")

                val edited = Editor(instrumentation)
                check(edited.commitText("改变", 1))
                check(!VoiceRefinement(edited, 1, 0, "原文").apply(edited, 1, "修正"))
                check(edited.setSelection(0, 2))
                check(!VoiceRefinement(edited, 1, 0, "改变").apply(edited, 1, "修正"))

                val rejected = Editor(instrumentation)
                check(rejected.commitText("原文", 1))
                val rejectedRange = VoiceRefinement(rejected, 1, 0, "原文")
                rejected.rejectSelection = true
                check(!rejectedRange.apply(rejected, 1, "修正"))
                rejected.rejectSelection = false; rejected.rejectCommit = true
                check(!rejectedRange.apply(rejected, 1, "修正"))
                check(rejected.content.toString() == "原文")
                check(Selection.getSelectionStart(rejected.content) == 2 && Selection.getSelectionEnd(rejected.content) == 2)
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
        return "VOICE_INTERACTION_FIXED_PASS typing-preserved; exact-range; no-duplicates; owner/generation/cursor/content/selection-guards; empty-recovery; host-rejection; no real editor touched"
    }

    fun run(instrumentation: Instrumentation): String {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                fun editor() = object : BaseInputConnection(View(instrumentation.targetContext), true) {
                    val content = SpannableStringBuilder().apply { Selection.setSelection(this, 0) }
                    override fun getEditable(): Editable = content
                }
                val typing = editor()
                check(typing.setComposingText("语音结果。", 1))
                check(typing.setComposingText("ni", 1))
                check(typing.editable.toString() == "ni") { "Platform no longer replaces the old composing region" }

                val late = editor()
                check(late.setComposingText("语音结果。", 1))
                check(late.finishComposingText())
                check(late.getTextBeforeCursor(5, 0).toString() == "语音结果。")
                check(late.setComposingText("二遍结果。", 1))
                check(late.editable.toString() == "语音结果。二遍结果。") { "Platform no longer appends when composition is absent" }

                val moved = editor()
                check(moved.setComposingText("ni", 1))
                check(BaseInputConnection.getComposingSpanStart(moved.editable) >= 0)
                check(moved.finishComposingText())
                check(BaseInputConnection.getComposingSpanStart(moved.editable) < 0)
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
        return "VOICE_INTERACTION_AUDIT_REPRODUCED platform-only: typing-replaces-voice; suffix-check-allows-duplicate; stale-finish-ends-new-composition; no real editor touched"
    }
}
