package local.voxfix.debug

/** Snow Shot 1.1.8 positions: stacks are stored top to bottom; the bottom available tool is the entry. */
data class ScreenshotToolbarLayout(val columns: List<List<ScreenshotAction>>, val hidden: List<ScreenshotAction> = emptyList()) {
    fun main(state: ScreenshotToolbarState): List<List<ScreenshotAction>> {
        val cells=columns.mapNotNull { column -> column.filter { state.mainVisible(it) && it!=ScreenshotAction.CLOSE }.reversed().takeIf { it.isNotEmpty() } }
        return cells.filterIndexed { index,column -> column!=listOf(ScreenshotAction.SEPARATOR) ||
            (index>0 && index<cells.lastIndex && cells[index-1]!=column) }+listOf(listOf(ScreenshotAction.CLOSE))
    }
    fun encode() = columns.joinToString("/") { it.joinToString(",") { action -> action.name } } +
        "|" + hidden.joinToString(",") { it.name }

    fun place(action: ScreenshotAction, position: Int, item: Int? = null): ScreenshotToolbarLayout {
        var destination = position.coerceIn(0, columns.size)
        var insertion = item
        if (action == ScreenshotAction.SEPARATOR || columns.getOrNull(destination)?.contains(ScreenshotAction.SEPARATOR) == true)
            insertion = null
        val next = columns.map { it.toMutableList() }.toMutableList()
        val source = next.indexOfFirst { action in it }
        if (source >= 0) {
            val sourceItem = next[source].indexOf(action)
            next[source].removeAt(sourceItem)
            if (source == destination && insertion != null && sourceItem < insertion) insertion--
            if (next[source].isEmpty() && !(source == destination && insertion != null)) {
                next.removeAt(source)
                if (source < destination) destination--
            }
        }
        destination = destination.coerceIn(0,next.size)
        if (insertion == null || destination == next.size) next.add(destination, mutableListOf(action))
        else next[destination].add(insertion.coerceIn(0,next[destination].size),action)
        return ScreenshotToolbarLayout(next, hidden - action)
    }

    fun move(action: ScreenshotAction, target: ScreenshotAction? = null, stack: Boolean = false): ScreenshotToolbarLayout {
        if (action == target) return this
        val position = columns.indexOfFirst { target in it }.takeIf { it >= 0 } ?: columns.size
        return place(action,position,if (stack) 0 else null)
    }

    fun hide(action: ScreenshotAction, position: Int = hidden.size): ScreenshotToolbarLayout {
        val next = hidden.toMutableList()
        val old = next.indexOf(action)
        next.remove(action)
        next.add((position - if (old in 0 until position) 1 else 0).coerceIn(0,next.size),action)
        return ScreenshotToolbarLayout(columns.map { it - action }.filter { it.isNotEmpty() },next)
    }

    fun visible(state: ScreenshotToolbarState): List<List<ScreenshotAction>> {
        val result = columns.mapNotNull { column -> column.filter(state::visible).reversed().takeIf { it.isNotEmpty() } }.toMutableList()
        // A hidden tool must not block cancellation or completion of an active operation.
        for (action in listOf(ScreenshotAction.CLOSE) +
            (if (state.editing) listOf(ScreenshotAction.EDIT) else emptyList()) +
            (if (state.translating) listOf(ScreenshotAction.TRANSLATE) else emptyList())) {
            if (!state.visible(action)) continue
            val position = result.indexOfFirst { action in it }
            if (position < 0) result.add(listOf(action))
            else if (result[position].size > 1) {
                result[position] = result[position] - action
                result.add(position+1,listOf(action))
            }
        }
        return result.filterIndexed { index, column ->
            column != listOf(ScreenshotAction.SEPARATOR) ||
                (index > 0 && index < result.lastIndex && result[index-1] != column)
        }
    }

    companion object {
        fun defaults() = ScreenshotToolbarLayout(listOf(
            listOf(ScreenshotAction.FULL_SCREEN),
            listOf(ScreenshotAction.MOVE),
            listOf(ScreenshotAction.SELECT),
            listOf(ScreenshotAction.PEN,ScreenshotAction.RECTANGLE,ScreenshotAction.ARROW),
            listOf(ScreenshotAction.LINE),
            listOf(ScreenshotAction.FILTER),
            listOf(ScreenshotAction.HIGHLIGHT),listOf(ScreenshotAction.SPOTLIGHT),
            listOf(ScreenshotAction.TEXT),listOf(ScreenshotAction.SERIAL),listOf(ScreenshotAction.ERASER),
            listOf(ScreenshotAction.WATERMARK),
            listOf(ScreenshotAction.RECOGNIZE,ScreenshotAction.TRANSLATE),
            listOf(ScreenshotAction.SHOW_ORIGINAL,ScreenshotAction.EDIT),
            listOf(ScreenshotAction.SAVE,ScreenshotAction.COPY_TEXT),
            listOf(ScreenshotAction.SEPARATOR),
            listOf(ScreenshotAction.UNDO,ScreenshotAction.REDO),
            listOf(ScreenshotAction.BARCODE),listOf(ScreenshotAction.PIN),listOf(ScreenshotAction.SHARE),
            listOf(ScreenshotAction.SCROLL),listOf(ScreenshotAction.RECORD),
            listOf(ScreenshotAction.CLOSE)
        ))

        fun decode(value: String?): ScreenshotToolbarLayout {
            if (value == null) return defaults()
            val seen = mutableSetOf<ScreenshotAction>()
            fun actions(text: String) = text.split(',').mapNotNull { name ->
                ScreenshotAction.values().firstOrNull { it.name == name }?.takeIf { seen.add(it) }
            }
            val parts = value.split('|', limit = 2)
            val columns = parts[0].split('/').map(::actions).flatMap { column ->
                if (ScreenshotAction.SEPARATOR in column) listOf(column - ScreenshotAction.SEPARATOR,listOf(ScreenshotAction.SEPARATOR))
                else listOf(column)
            }.filter { it.isNotEmpty() }.toMutableList()
            val hidden = actions(parts.getOrElse(1) { "" })
            for (column in defaults().columns) column.filter { it !in seen }.takeIf { it.isNotEmpty() }?.let { columns.add(it) }
            return ScreenshotToolbarLayout(columns, hidden)
        }
    }
}
