package local.voxfix.debug

/** Session-only drafts, never written to storage. */
internal class ScreenshotEditHistory(initial: List<ScreenshotTextBlock>) {
    private val values = mutableListOf(initial.toList())
    private var position = 0
    fun record(value: List<ScreenshotTextBlock>) {
        if (value == values[position]) return
        while (values.lastIndex > position) values.removeAt(values.lastIndex)
        values.add(value.toList())
        if (values.size > 50) values.removeAt(0)
        position = values.lastIndex
    }
    fun undo(): List<ScreenshotTextBlock>? = if (position>0) values[--position] else null
    fun redo(): List<ScreenshotTextBlock>? = if (position<values.lastIndex) values[++position] else null
    val canUndo get() = position>0
    val canRedo get() = position<values.lastIndex
}
