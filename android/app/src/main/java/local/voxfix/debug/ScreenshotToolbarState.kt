package local.voxfix.debug

/** Toolbar rules for one unsaved screenshot: text actions only exist after recognition. */
enum class ScreenshotAction { FULL_SCREEN, RECOGNIZE, SAVE, COPY_TEXT, EDIT, SHOW_ORIGINAL, CLOSE }

data class ScreenshotToolbarState(
    val hasRegion: Boolean = false,
    val hasText: Boolean = false,
    val busy: Boolean = false,
    val editing: Boolean = false,
    val showingOriginal: Boolean = false
) {
    fun visible(action: ScreenshotAction): Boolean = when (action) {
        ScreenshotAction.CLOSE -> true
        // Reselecting the whole screen would drop the recognized draft; a new drag still resets it.
        ScreenshotAction.FULL_SCREEN -> !hasText && !editing
        ScreenshotAction.RECOGNIZE -> hasRegion && !editing
        ScreenshotAction.SAVE -> hasRegion
        // Copying text and comparing with the original are only meaningful with a text layer;
        // an image copy would duplicate the save exit the user asked to keep separate.
        ScreenshotAction.COPY_TEXT, ScreenshotAction.EDIT, ScreenshotAction.SHOW_ORIGINAL -> hasText
    }

    fun enabled(action: ScreenshotAction): Boolean = visible(action) && (action == ScreenshotAction.CLOSE || !busy)

    fun label(action: ScreenshotAction): String = when (action) {
        ScreenshotAction.FULL_SCREEN -> "选择整屏"
        ScreenshotAction.RECOGNIZE -> "识别文字"
        ScreenshotAction.SAVE -> "保存截图"
        ScreenshotAction.COPY_TEXT -> "复制文字"
        ScreenshotAction.EDIT -> if (editing) "完成编辑" else "编辑文字"
        ScreenshotAction.SHOW_ORIGINAL -> if (showingOriginal) "显示识别结果" else "显示原图"
        ScreenshotAction.CLOSE -> "取消截图"
    }
}
