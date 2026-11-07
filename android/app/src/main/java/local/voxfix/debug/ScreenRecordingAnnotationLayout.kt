package local.voxfix.debug

/**
 * 录制标注工具条的排布（纯函数，便于 JVM 断言）。
 *
 * 工具集合与顺序对齐上游 `ScreenRecordingController::connectDrawingToolbar`
 * （`screenrecordingcontroller.cpp:585-658`）接到截图调色板上的那一组，顺序取
 * `ScreenshotToolbarLayout.defaults()` 的绘制段：选择、画笔、形状、箭头、直线、滤镜、高亮、聚光灯、
 * 文字、序号、橡皮、水印。原版是桌面单行工具条，手机按屏宽折行（差异已记录在
 * `ScreenRecordingAnnotation` 的类注释里）。
 */
internal object ScreenRecordingAnnotationLayout {
    /** 单个按钮边长与右边距，与 `ScreenRecordingAnnotation.icon()` 一致。 */
    const val CELL_DP = 36
    const val GAP_DP = 2

    /** 工具条左右内边距（每侧）。 */
    const val PADDING_DP = 6

    /** 一行最多 8 个：再多会让按钮挤在 360dp 屏内难以点中。 */
    const val MAX_PER_ROW = 8

    /** 上游工具顺序 + 手机侧中文标签（contentDescription / tooltip 用它）。 */
    val tools = listOf(
        ScreenshotDrawingTool.SELECT to "选择",
        ScreenshotDrawingTool.PEN to "画笔",
        ScreenshotDrawingTool.RECTANGLE to "形状",
        ScreenshotDrawingTool.ARROW to "箭头",
        ScreenshotDrawingTool.LINE to "直线",
        ScreenshotDrawingTool.FILTER to "滤镜",
        ScreenshotDrawingTool.HIGHLIGHT to "高亮",
        ScreenshotDrawingTool.SPOTLIGHT to "聚光灯",
        ScreenshotDrawingTool.TEXT to "文字",
        ScreenshotDrawingTool.SERIAL to "序号",
        ScreenshotDrawingTool.ERASER to "橡皮",
        ScreenshotDrawingTool.WATERMARK to "水印"
    )

    /** 一行能放几个：留出两侧内边距后按按钮间距整除，最多 [MAX_PER_ROW]。 */
    fun cellsPerRow(widthPx: Int, density: Float): Int {
        val pitch = (CELL_DP + GAP_DP) * density
        val available = widthPx - 2 * PADDING_DP * density
        if (pitch <= 0f) return 1
        return (available / pitch).toInt().coerceIn(1, MAX_PER_ROW)
    }

    /** 把 [count] 个按钮切成若干行（每行最多 [perRow] 个）。 */
    fun rows(count: Int, perRow: Int): List<List<Int>> {
        if (count <= 0) return emptyList()
        return (0 until count).toList().chunked(perRow.coerceAtLeast(1))
    }
}
