package local.voxfix.debug

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** A late correction owns only the unchanged, already committed voice range. */
internal class VoiceRefinement(private val owner: InputConnection, private val generation: Long,
                               private val start: Int, text: String) {
    var text = text
        private set

    fun matches(connection: InputConnection?, currentGeneration: Long): Boolean {
        if (connection !== owner || generation != currentGeneration) return false
        val extracted = owner.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 1 }, 0) ?: return false
        val end = start + text.length
        return extracted.startOffset >= 0 && extracted.selectionStart >= 0 && extracted.selectionEnd >= 0 &&
            extracted.startOffset + extracted.selectionStart == end && extracted.startOffset + extracted.selectionEnd == end &&
            (text.isEmpty() || owner.getTextBeforeCursor(text.length, 0)?.toString() == text)
    }

    fun apply(connection: InputConnection?, currentGeneration: Long, value: String): Boolean {
        if (value.isBlank() || !matches(connection, currentGeneration)) return false
        owner.beginBatchEdit()
        try {
            if (!owner.setSelection(start, start + text.length)) return false
            if (!owner.commitText(value, 1)) {
                owner.setSelection(start + text.length, start + text.length)
                return false
            }
            text = value
            return true
        } finally { owner.endBatchEdit() }
    }
}
