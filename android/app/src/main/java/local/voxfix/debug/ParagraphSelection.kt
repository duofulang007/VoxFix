package local.voxfix.debug

internal object ParagraphSelection {
    fun boundary(text: String, head: Int, direction: Int): Int {
        val position = head.coerceIn(0, text.length)
        return if (direction > 0) {
            val from = if (text.getOrNull(position) == '\n') position + 1 else position
            text.indexOf('\n', from).takeIf { it >= 0 } ?: text.length
        } else {
            if (position < 2) 0 else text.lastIndexOf('\n', position - 2) + 1
        }
    }
}
