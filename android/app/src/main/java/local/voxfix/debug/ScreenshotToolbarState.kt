package local.voxfix.debug

/** Toolbar rules for one unsaved screenshot: text actions only exist after recognition. */
enum class ScreenshotAction { FULL_SCREEN, RECOGNIZE, SAVE, COPY_TEXT, EDIT, SHOW_ORIGINAL, TRANSLATE, CLOSE, UNDO, REDO, SEPARATOR,
    ARROW, RECTANGLE, PEN, FILTER, HIGHLIGHT, SPOTLIGHT, TEXT, SERIAL, ERASER, SELECT, MOVE, BARCODE, PIN, SHARE,
    SCROLL, RECORD, LINE, WATERMARK }

data class ScreenshotToolbarState(
    val hasRegion: Boolean = false,
    val hasText: Boolean = false,
    val busy: Boolean = false,
    val editing: Boolean = false,
    val showingOriginal: Boolean = false,
    val translating: Boolean = false,
    val showingTranslation: Boolean = false,
    val hasDrawingHistory: Boolean = false,
    /**
     * 已被模块开关关闭的工具。默认空集，既有调用与 JVM 用例不受影响。
     * CLOSE 不受它约束——取消永远可达。
     */
    val hiddenActions: Set<ScreenshotAction> = emptySet(),
    /**
     * 再编辑模式（打开已有图片/工程）：选区层整层隐藏，「编辑选区」成了点开就无效的死 UI，
     * 这里直接不显示。默认 false，新截图路径行为不变。
     */
    val projectMode: Boolean = false
) {
    fun mainVisible(action: ScreenshotAction): Boolean = action !in secondaryActions
    fun mainEnabled(action: ScreenshotAction): Boolean = when {
        action==ScreenshotAction.CLOSE -> true
        action==ScreenshotAction.TRANSLATE && translating -> true
        busy || translating -> false
        action==ScreenshotAction.UNDO || action==ScreenshotAction.REDO -> hasRegion
        editing -> false
        action==ScreenshotAction.FULL_SCREEN -> !hasText
        else -> hasRegion
    }
    companion object {
        val secondaryActions=setOf(ScreenshotAction.SHOW_ORIGINAL,ScreenshotAction.EDIT,ScreenshotAction.COPY_TEXT)
    }
    fun visible(action: ScreenshotAction): Boolean {
        if (action == ScreenshotAction.CLOSE) return true
        if (action in hiddenActions) return false
        if (translating) return action == ScreenshotAction.TRANSLATE
        if (busy) return action == ScreenshotAction.CLOSE
        return when (action) {
        ScreenshotAction.SEPARATOR -> true
        ScreenshotAction.CLOSE -> true
        // Reselecting the whole screen would drop the recognized draft; a new drag still resets it.
        ScreenshotAction.FULL_SCREEN -> !hasRegion && !hasText && !editing
        ScreenshotAction.RECOGNIZE -> hasRegion && !hasText && !editing
        ScreenshotAction.SAVE -> hasRegion && !translating && !editing
        ScreenshotAction.SHOW_ORIGINAL -> hasText && !translating && !editing
        ScreenshotAction.UNDO, ScreenshotAction.REDO -> (hasText && editing) || (hasDrawingHistory && !editing && !showingOriginal && !showingTranslation)
        ScreenshotAction.ARROW, ScreenshotAction.RECTANGLE, ScreenshotAction.PEN,
        ScreenshotAction.FILTER,ScreenshotAction.HIGHLIGHT,ScreenshotAction.SPOTLIGHT,ScreenshotAction.TEXT,
        ScreenshotAction.SERIAL,ScreenshotAction.ERASER,ScreenshotAction.SELECT,ScreenshotAction.LINE -> hasRegion && !editing && !showingTranslation
        // 再编辑模式选区层已隐藏，「编辑选区」按钮只留新截图路径（只增不减：只是不再显示死入口）。
        ScreenshotAction.MOVE -> hasRegion && !editing && !showingTranslation && !projectMode
        ScreenshotAction.BARCODE,ScreenshotAction.PIN,ScreenshotAction.SHARE -> hasRegion && !editing
        ScreenshotAction.SCROLL,ScreenshotAction.RECORD,ScreenshotAction.WATERMARK -> hasRegion && !editing && !showingTranslation
        // Copying text and comparing with the original are only meaningful with a text layer;
        // an image copy would duplicate the save exit the user asked to keep separate.
        ScreenshotAction.COPY_TEXT, ScreenshotAction.EDIT -> hasText && !translating
        ScreenshotAction.TRANSLATE -> hasRegion && !editing
        }
    }

    fun enabled(action: ScreenshotAction): Boolean = when {
        action == ScreenshotAction.CLOSE -> visible(action)
        // While translating the same button becomes "cancel translation"; every other action
        // would either invalidate the text or start a second decode.
        action == ScreenshotAction.TRANSLATE -> visible(action) && (!busy || translating)
        else -> visible(action) && !busy && !translating
    }

    fun label(action: ScreenshotAction): String = when (action) {
        ScreenshotAction.SEPARATOR -> "分隔线"
        ScreenshotAction.ARROW -> "箭头"
        ScreenshotAction.RECTANGLE -> "矩形"
        ScreenshotAction.PEN -> "画笔"
        ScreenshotAction.FILTER -> "滤镜打码"
        ScreenshotAction.HIGHLIGHT -> "高亮"
        ScreenshotAction.SPOTLIGHT -> "聚光灯"
        ScreenshotAction.LINE -> "直线"
        ScreenshotAction.WATERMARK -> "水印"
        ScreenshotAction.TEXT -> "文字标注"
        ScreenshotAction.SERIAL -> "序号"
        ScreenshotAction.ERASER -> "橡皮擦"
        ScreenshotAction.SELECT -> "选择元素"
        ScreenshotAction.MOVE -> "编辑选区"
        ScreenshotAction.BARCODE -> "识别二维码和条码"
        ScreenshotAction.PIN -> "贴图到屏幕"
        ScreenshotAction.SHARE -> "分享图片"
        ScreenshotAction.SCROLL -> "长截屏"
        ScreenshotAction.RECORD -> "录制选区"
        ScreenshotAction.FULL_SCREEN -> "选择整屏"
        ScreenshotAction.RECOGNIZE -> "识别文字"
        ScreenshotAction.SAVE -> "保存截图"
        ScreenshotAction.COPY_TEXT -> if (showingTranslation) "复制译文" else "复制文字"
        ScreenshotAction.EDIT -> if (editing) "完成编辑" else "编辑文字"
        ScreenshotAction.SHOW_ORIGINAL -> if (showingOriginal) "显示识别结果" else "显示原图"
        ScreenshotAction.TRANSLATE -> when {
            translating -> "取消翻译"
            showingTranslation -> "显示原文"
            else -> "翻译文字"
        }
        ScreenshotAction.CLOSE -> "取消截图"
        ScreenshotAction.UNDO -> "撤销"
        ScreenshotAction.REDO -> "重做"
    }
}
