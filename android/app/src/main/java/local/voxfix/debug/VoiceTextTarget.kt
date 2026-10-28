package local.voxfix.debug

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** Own only the active composing range. Never navigate, delete, or use the clipboard to correct it. */
internal class VoiceTextTarget(private val connection: InputConnection) {
    private data class Snapshot(val before: String, val selected: String, val after: String, val position: Int?)
    private var expected: Snapshot? = snapshot()
    private var touched = false
    private var composing = false
    var blocked = false
        private set
    var shown = ""
        private set
    private var key = -1
    val canPreview get() = expected != null

    private fun snapshot(): Snapshot? {
        val before = connection.getTextBeforeCursor(8256, 0)?.toString() ?: return null
        val after = connection.getTextAfterCursor(64, 0)?.toString() ?: return null
        val selected = connection.getSelectedText(0)?.toString().orEmpty()
        if (selected.length > 8192) return null
        val extracted = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 1 }, 0)
        val position = extracted?.let { it.startOffset + minOf(it.selectionStart, it.selectionEnd) }
        return Snapshot(before, selected, after, position)
    }

    fun write(text: String, segment: Int, final: Boolean): Boolean = try { writeChecked(text, segment, final) }
        catch (_: Exception) { blocked = true; false }

    private fun writeChecked(text: String, segment: Int, final: Boolean): Boolean {
        if (blocked || text.length > 8192) return false
        if (key != segment) { check(!composing); shown = ""; key = segment }
        val actual = snapshot()
        if (expected != null && actual != expected || composing && touched && (actual == null || expected == null)) {
            blocked = true; return false
        }
        // Hosts without readable context receive finals only; no speculative replacement or retry.
        if (actual == null && !final) return false
        if (!final && text.length < shown.length) return true
        if (text.isEmpty() && !composing) return true
        val ok = if (final) connection.commitText(text, 1) else connection.setComposingText(text, 1)
        touched = true
        if (!ok) { blocked = true; return false }
        val after = snapshot()
        if (actual != null) {
            val prefix = if (composing) actual.before.dropLast(shown.length) else actual.before
            val wantedBefore = (prefix + text).takeLast(8256)
            if (after == null || after.before != wantedBefore || after.after != actual.after || after.selected.isNotEmpty() ||
                actual.position != null && after.position != actual.position - (if (composing) shown.length else 0) + text.length) {
                blocked = true; return false
            }
        }
        expected = after
        shown = text; composing = !final
        return true
    }

    fun freeze() {
        if (composing && !blocked && snapshot() == expected) connection.finishComposingText()
        composing = false
    }
}
