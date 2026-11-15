package local.voxfix.debug

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import local.voxfix.debug.ui.VoxFixTheme
import local.voxfix.debug.ui.VoxFixUi

/**
 * 工具栏布局：绘制 / 截图两栏各自编排。
 *
 * 外壳与其他二级页一致；编辑器画布保持深色 [ScreenshotToolbarEditor.BACKGROUND]，
 * 因为它预览的就是截图时那条深色工具条，不能令牌化成页面底色。
 */
class ScreenshotToolbarSettingsActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val palette = VoxFixTheme.palette(this)
        val editorHost = FrameLayout(this)
        // 按 section 记住两个滚动容器的位置：切 Tab 回来不再回到顶部。
        val scrollSnapshots = mutableMapOf<ScreenshotToolbarSection, Pair<Int, Int>>()
        var current = ScreenshotToolbarSection.DRAWING
        lateinit var segmented: VoxFixUi.Segmented

        fun show(index: Int) {
            val section = ScreenshotToolbarSection.values()[index]
            (editorHost.getChildAt(0) as? ScreenshotToolbarEditor)?.let { scrollSnapshots[current] = it.exportScroll() }
            current = section
            editorHost.removeAllViews()
            val editor = ScreenshotToolbarEditor(this, section)
            scrollSnapshots[section]?.let(editor::importScroll)
            editorHost.addView(editor, FrameLayout.LayoutParams(-1, -1))
            segmented.selected = index
        }

        segmented = VoxFixUi.Segmented(
            this, palette,
            ScreenshotToolbarSection.values().map { it.label },
            0
        ) { show(it) }

        setContentView(VoxFixUi.screenBody(
            this, palette, "工具栏布局",
            onBack = { finish() },
            action = "恢复默认",
            onAction = { (editorHost.getChildAt(0) as? ScreenshotToolbarEditor)?.reset() }
        ) { body ->
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val side = VoxFixTheme.dp(this@ScreenshotToolbarSettingsActivity, 20)
            column.addView(segmented, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = side
                rightMargin = side
                topMargin = VoxFixTheme.dp(this@ScreenshotToolbarSettingsActivity, 6)
                bottomMargin = VoxFixTheme.dp(this@ScreenshotToolbarSettingsActivity, 12)
            })
            // 画布本身仍是截图工具条的深色，但收进圆角卡片：整页与其它二级页同一种"卡片"语言。
            val canvas = FrameLayout(this).apply {
                background = VoxFixTheme.round(
                    ScreenshotToolbarEditor.BACKGROUND,
                    VoxFixTheme.dp(this@ScreenshotToolbarSettingsActivity, 18).toFloat()
                )
                clipToOutline = true
            }
            canvas.addView(editorHost, FrameLayout.LayoutParams(-1, -1))
            column.addView(canvas, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply {
                leftMargin = side
                rightMargin = side
                bottomMargin = side
            })
            body.addView(column, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
        })
        show(0)
    }
}
