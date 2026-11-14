package local.voxfix.debug

/**
 * 截图工具栏的几何唯一来源。
 *
 * 取值来自 Snow Shot 1.1.8（固定提交 `afdc340b14e7f92a294a95c374d13ada0d8c7a82`）：
 * - 一级栏 `snow_shot/src/presentation/tools/screenshottoolbarmainpanel.cpp:32-43`
 * - 一/二级栏共用 `screenshottoolpalette.cpp:111,196-211`
 *
 * 手机没有鼠标悬停：原版未选中按钮完全透明、靠 hover 出浅底提示可点击，触屏照抄就等于
 * 「一排看不见边界的图标」。因此 [TOUCH_MIN] 与 [ScreenshotUiTokens.OUTLINE_IDLE_MAIN]
 * 属手机侧扩展，其余数值保持原版不变。
 */
internal object ScreenshotToolbarMetrics {
    // ---- 一级栏（原版 kButtonSize / kIconSize / TOOLBAR_*）----
    const val MAIN_CELL = 32
    const val MAIN_ICON = 24
    const val MAIN_GAP = 8
    const val SEPARATOR_WIDTH = 1
    const val SEPARATOR_HEIGHT = 16
    const val SEPARATOR_SIDE = 12
    const val PANEL_PAD_H = 12
    const val PANEL_PAD_V = 4
    const val PANEL_RADIUS = 8
    const val PANEL_ELEVATION = 6
    const val GRIP_WIDTH = 18
    const val GRIP_INSET = 8
    const val GRIP_GAP = 4

    // ---- 二级栏（原版 STYLE_*）----
    const val STYLE_CELL = 28
    const val STYLE_ICON = 18
    const val STYLE_GAP = 4
    const val STYLE_GROUP_GAP = 8
    const val STYLE_PAD_H = 10
    const val STYLE_PAD_V = 6
    const val STYLE_CELL_RADIUS = 6

    /** 一行二级栏的高度：28dp 格 + 上下各 6dp 内边距。 */
    const val STYLE_ROW = STYLE_CELL + STYLE_PAD_V * 2

    // ---- 手机侧扩展 ----
    /** 安卓最小触摸目标；视觉尺寸不变，只扩不可见热区。 */
    const val TOUCH_MIN = 48

    /** 单行化之后二级栏不再需要 280dp，留 3 行余量给选区栏与结果栏。 */
    const val DETAIL_MAX = 120
}

/**
 * 面板色值唯一来源。
 *
 * 原版面板底色取主题 `colorBgContainer`，手机沿用深色 `#1C1C1E`（截图覆盖层上不刺眼）。
 * 改造前同一语义出现过两份值（`#1C1C1E` / `#1F1F1F`、`#1677FF` / `#1668DC`），此处收敛。
 */
internal object ScreenshotUiTokens {
    const val PANEL = 0xFF1C1C1E.toInt()
    const val PANEL_ELEVATED = 0xFF1F1F1F.toInt()
    const val SEPARATOR = 0xFF3A3A3C.toInt()

    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT_DIM = 0xFF9A9A9E.toInt()

    /** 选中项文字用的浅主色，与原版 `colorPrimaryHover` 同色系。 */
    const val TEXT_ACCENT = 0xFF69B1FF.toInt()
    const val TEXT_DISABLED = 0x66FFFFFF
    const val DANGER = 0xFFFF4D4F.toInt()
    const val PRIMARY = 0xFF1677FF.toInt()

    /**
     * 原版选中态是 antd `Tonal`：浅主色底 + 主色图标，**无描边**（`button_style.cpp:418-430`）。
     * 深色主题下 `colorPrimaryBg` 约等于 `#15325B`。
     */
    const val TONAL_BG = 0xFF15325B.toInt()

    /** 二级栏 28dp 小格与文字 chip 的选中底（20% 主色，深色面板上成立）。 */
    const val TONAL_CELL = 0x331677FF

    /** 一级栏常驻 1dp 淡描边：替代原版鼠标 hover 的「可点击」提示。 */
    const val OUTLINE_IDLE_MAIN = 0x26FFFFFF

    /** 二级栏小格常驻描边（原版无，手机侧补，沿用以保持既有手感）。 */
    const val OUTLINE_IDLE_CELL = 0xFF424242.toInt()
    const val OUTLINE_SELECTED_MAIN = 0x991677FF.toInt()
}

/**
 * 数值参数的上下界。
 *
 * 字号不在此处：`ScreenshotFontSize` 已是唯一来源（6–256 / 6–512 / 6–512），不要开第二套口径。
 */
internal object ScreenshotStyleBounds {
    const val CORNER_MIN = 0
    const val CORNER_MAX = 83
    const val OUTLINE_WIDTH_MIN = 0
    const val OUTLINE_WIDTH_MAX = 12
    /** 箭头比例以十分之一为单位：10..30 = 1.0x..3.0x。 */
    const val ARROW_RATIO_MIN = 10
    const val ARROW_RATIO_MAX = 30
    const val WATERMARK_ANGLE_MIN = -90
    const val WATERMARK_ANGLE_MAX = 90
    const val WATERMARK_GAP_MIN = 10
    const val WATERMARK_GAP_MAX = 200
}
