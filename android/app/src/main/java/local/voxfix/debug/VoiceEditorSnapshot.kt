package local.voxfix.debug

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** A host snapshot with a verified absolute offset; no cursor-based guesswork. */
internal data class VoiceEditorSnapshot(val text: String, val offset: Int) {
    companion object {
        fun read(connection: InputConnection?): VoiceEditorSnapshot? {
            connection ?: return null
            val surrounding = runCatching { connection.getSurroundingText(640, 640, 0) }.getOrNull()
            if (surrounding != null && surrounding.offset >= 0 && surrounding.text.length <= 2048 &&
                surrounding.selectionStart in 0..surrounding.text.length && surrounding.selectionEnd in 0..surrounding.text.length)
                return VoiceEditorSnapshot(surrounding.text.toString(), surrounding.offset)
            val extracted = runCatching { connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 1536 }, 0) }.getOrNull()
                ?: return null
            val text = extracted.text ?: return null
            if (extracted.startOffset < 0 || extracted.partialStartOffset > 0 || extracted.partialEndOffset > 0 ||
                extracted.selectionStart !in 0..text.length || extracted.selectionEnd !in 0..text.length) return null
            val start = (minOf(extracted.selectionStart, extracted.selectionEnd) - 640).coerceAtLeast(0)
            val end = (maxOf(extracted.selectionStart, extracted.selectionEnd) + 640).coerceAtMost(text.length)
            if (end - start > 2048) return null
            return VoiceEditorSnapshot(text.subSequence(start, end).toString(), extracted.startOffset + start)
        }
    }
}
