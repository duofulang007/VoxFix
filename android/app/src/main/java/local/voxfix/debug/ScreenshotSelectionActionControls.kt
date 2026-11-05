package local.voxfix.debug

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout

/** The action groups follow Snow Shot's createSelectionActionFamily. */
internal class ScreenshotSelectionActionControls(context: Context, private val drawing: ScreenshotDrawingLayer) : HorizontalScrollView(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val selected = drawing.selectedIndices.size

    init {
        isHorizontalScrollBarEnabled = false
        contentDescription = "选择对象操作"
        addView(row)
        for ((order, icon, label) in listOf(
            Triple(ScreenshotDrawing.Order.BACK, R.drawable.snow_vertical_align_bottom, "置于底层"),
            Triple(ScreenshotDrawing.Order.BACKWARD, R.drawable.snow_arrow_down, "下移一层"),
            Triple(ScreenshotDrawing.Order.FORWARD, R.drawable.snow_arrow_up, "上移一层"),
            Triple(ScreenshotDrawing.Order.FRONT, R.drawable.snow_vertical_align_top, "置于顶层")
        )) button(icon, label, selected > 0) { drawing.reorderSelected(order) }
        separator()
        val modes = ScreenshotSelectionAlignment.entries
        val icons = listOf(
            R.drawable.snow_align_left, R.drawable.snow_align_center_horizontal,
            R.drawable.snow_align_right, R.drawable.snow_distribute_horizontal,
            R.drawable.snow_align_top, R.drawable.snow_align_center_vertical,
            R.drawable.snow_align_bottom, R.drawable.snow_distribute_vertical
        )
        val labels = listOf("左对齐", "水平居中", "右对齐", "水平分布", "顶对齐", "垂直居中", "底对齐", "垂直分布")
        for ((index, mode) in modes.withIndex()) {
            if (mode == ScreenshotSelectionAlignment.TOP) separator()
            button(icons[index], labels[index], selected >= mode.minimumCount) { drawing.alignSelected(mode) }
        }
        separator()
        button(R.drawable.snow_duplicate, "复制选中对象", selected > 0, drawing::duplicateSelected)
        ScreenshotTouchTargets.install(row)
    }

    private fun separator() {
        val divider = View(context).apply { setBackgroundColor(0x33FFFFFF) }
        row.addView(divider, LinearLayout.LayoutParams(dp(1).coerceAtLeast(1), dp(16)).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
        })
    }

    private fun button(icon: Int, label: String, enabled: Boolean, run: () -> Unit) {
        val button = ImageButton(context).apply {
            setImageResource(icon)
            setColorFilter(Color.WHITE)
            alpha = if (enabled) 1f else .25f
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            val style = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = style.getDrawable(0)
            style.recycle()
            contentDescription = label
            tooltipText = label
            setOnClickListener { run() }
            isEnabled = enabled
        }
        val afterButton = row.getChildAt(row.childCount - 1) is ImageButton
        row.addView(button, LinearLayout.LayoutParams(dp(32), dp(32)).apply { if (afterButton) marginStart = dp(4) })
    }
}
