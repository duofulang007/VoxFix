package local.voxfix.debug

enum class ScreenshotToolbarSection(val label: String, val key: String) {
    DRAWING("绘制","screenshot.toolbar.drawing"), ACTIONS("截图","screenshot.toolbar.actions");

    // Snow Shot's cancel is a fixed exit, not a draggable custom tool.
    fun includes(action: ScreenshotAction): Boolean = action!=ScreenshotAction.CLOSE && action !in ScreenshotToolbarState.secondaryActions && (action in drawingActions) == (this == DRAWING)

    companion object {
        val drawingActions = ScreenshotDrawingTools.actions.values.toSet()+setOf(ScreenshotAction.LINE,ScreenshotAction.WATERMARK,ScreenshotAction.UNDO,ScreenshotAction.REDO,ScreenshotAction.SEPARATOR)
    }
}

fun ScreenshotToolbarLayout.inSection(section: ScreenshotToolbarSection) = ScreenshotToolbarLayout(
    columns.map { it.filter(section::includes) }.filter { it.isNotEmpty() },hidden.filter(section::includes))

object ScreenshotToolbarPreferences {
    fun load(context: android.content.Context, section: ScreenshotToolbarSection): ScreenshotToolbarLayout {
        val preferences = ScreenTextSettingsActivity.preferences(context)
        val stored = preferences.getString(section.key,null) ?: preferences.getString(ScreenshotToolbarEditor.KEY,null)
        return ScreenshotToolbarLayout.decode(stored).inSection(section)
    }
    fun save(context: android.content.Context, section: ScreenshotToolbarSection, layout: ScreenshotToolbarLayout) {
        val preferences = ScreenTextSettingsActivity.preferences(context)
        // Populate both sections in one write before removing the legacy combined layout.
        val other = ScreenshotToolbarSection.values().first { it != section }
        val preserved = load(context,other)
        preferences.edit().putString(section.key,layout.inSection(section).encode())
            .putString(other.key,preserved.encode()).remove(ScreenshotToolbarEditor.KEY).apply()
    }
    fun combined(context: android.content.Context): ScreenshotToolbarLayout {
        val drawing = load(context,ScreenshotToolbarSection.DRAWING)
        val actions = load(context,ScreenshotToolbarSection.ACTIONS)
        return ScreenshotToolbarLayout(drawing.columns+actions.columns,drawing.hidden+actions.hidden)
    }
}
