package local.voxfix.debug

internal class InputSession {
    private var generation = 0L
    private var active = false
    @Synchronized fun open(): Long { active = true; return ++generation }
    @Synchronized fun close() { active = false; generation++ }
    @Synchronized fun accepts(ticket: Long) = active && generation == ticket
    @Synchronized fun consume(ticket: Long): Boolean {
        if (!accepts(ticket)) return false
        close()
        return true
    }
    companion object {
        fun isVoiceSelection(start: Int, hasText: Boolean, selectionStart: Int, selectionEnd: Int, composingStart: Int, composingEnd: Int): Boolean =
            selectionStart == selectionEnd && ((!hasText && selectionStart == start) || composingStart == start && selectionStart == composingEnd)
    }
}
