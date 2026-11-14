package local.voxfix.debug

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

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
        // `createSelectionActionFamily`: opacity, then copy/delete, then reset (reset stays enabled
        // with nothing selected, like the original's `m_resetCanvasButton`).
        opacity()
        separator()
        button(R.drawable.snow_duplicate, "复制选中对象", selected > 0, drawing::duplicateSelected)
        button(R.drawable.snow_delete, "删除选中对象", selected > 0, drawing::deleteSelected)
        separator()
        button(R.drawable.snow_reload, "重置画布", true, drawing::resetCanvas)
        ScreenshotTouchTargets.install(row)
    }

    /** Opacity of the whole selection, 0–100 with a ±5 wheel step and a Mixed readout. */
    private fun opacity() {
        val style = drawing.inspectedStyle
        val mixed = ScreenshotStyleProperty.OPACITY in drawing.mixedStyleProperties
        val percent = (style.opacity * 100).roundToInt().coerceIn(0, 100)
        val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val readout = TextView(context).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            text = if (mixed) "透明度 -" else "透明度 $percent%"
            contentDescription = if (mixed) "透明度 Mixed" else "透明度 $percent%"
        }
        line.addView(readout, LinearLayout.LayoutParams(dp(72), dp(32)))
        var base = style
        var tracking = false
        val bar = SeekBar(context).apply {
            max = 100
            progress = percent
            contentDescription = "选区透明度"
            // 本视图自身就是 HorizontalScrollView：不声明豁免的话，拖动会被自己抢走变成行滚动。
            bindUninterruptedDrag()
            isEnabled = selected > 0
            alpha = if (selected > 0) 1f else .25f
            setOnGenericMotionListener { _, event ->
                val delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (event.actionMasked == MotionEvent.ACTION_SCROLL && delta != 0f) {
                    progress = (progress + if (delta > 0) 5 else -5).coerceIn(0, 100); true
                } else false
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seek: SeekBar) { base = drawing.inspectedStyle; tracking = true; drawing.beginStylePreview() }
                override fun onProgressChanged(seek: SeekBar, value: Int, fromUser: Boolean) {
                    readout.text = "透明度 $value%"; readout.contentDescription = "透明度 $value%"
                    seek.stateDescription = "$value%"
                    if (tracking) drawing.previewStyle(base.copy(opacity = value / 100f), ScreenshotStyleProperty.OPACITY)
                    else drawing.applyStyle(drawing.inspectedStyle.copy(opacity = value / 100f), ScreenshotStyleProperty.OPACITY)
                }
                override fun onStopTrackingTouch(seek: SeekBar) { if (tracking) drawing.finishStylePreview(true); tracking = false }
            })
        }
        line.addView(bar, LinearLayout.LayoutParams(dp(104), dp(32)))
        row.addView(line, LinearLayout.LayoutParams(dp(176), dp(32)))
    }

    private fun separator() {
        val divider = View(context).apply { setBackgroundColor(0x33FFFFFF) }
        row.addView(divider, LinearLayout.LayoutParams(dp(1).coerceAtLeast(1), dp(16)).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
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
