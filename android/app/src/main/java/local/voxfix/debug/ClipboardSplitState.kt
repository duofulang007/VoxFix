package local.voxfix.debug

/** Only the original selected range or the last text written by this session may be replaced. */
internal class ClipboardSplitState(val id: Int, val generation: Long, val start: Int, val original: String) {
    var text: String? = null
        private set
    val end: Int get() = start + (text ?: original).length

    fun matches(currentGeneration: Long, selectionStart: Int, selectionEnd: Int, selected: String, before: String): Boolean {
        if (currentGeneration != generation) return false
        return if (text == null) minOf(selectionStart, selectionEnd) == start &&
            maxOf(selectionStart, selectionEnd) == end && selected == original
        else selectionStart == end && selectionEnd == end && before == text
    }

    fun accepted(value: String) { text = value.takeIf { it.isNotEmpty() } }
}
