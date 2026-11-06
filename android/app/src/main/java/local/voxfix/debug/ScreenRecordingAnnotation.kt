package local.voxfix.debug

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 录制中标注层（上游 `recordingDrawingMode`）。
 *
 * 上游依据：`ScreenRecordingController::connectDrawingToolbar` 把截图工具条接到录制选区窗口的画布上，
 * 点任一绘制工具 → `InputMode::Drawing`（画布接收鼠标），`drawingDeactivationRequested` → 清空激活工具并切
 * `InputMode::PassThrough`（点击穿透回下面的应用，`screenrecordingareawindow.cpp` 的 `HTTRANSPARENT`）。
 * 录制侧只用 `WDA_EXCLUDEFROMCAPTURE` 排除**工具条窗口**（`excludeToolbarFromCapture`），画布仍在录制范围内，
 * 所以画上去的笔画会被录进视频。
 *
 * 手机侧映射：一层全屏透明覆盖窗承载 `ScreenshotDrawingLayer`（它只画对象、不画底图），工具条与样式面板
 * 放在该层顶部；窗口在「绘制」时接收触摸与输入焦点，其余时间穿透。
 *
 * 与原版的差异（明确记录，不写成复刻完成）：
 * 1. Android 没有窗口级“排除出录制”，工具条与样式面板可见时会入镜（原版排除了工具条窗口）；
 * 2. 取色滴管不可用（底图是透明位图，采不到真实屏幕），调色板里隐藏该按钮而不是伪造颜色；
 * 3. 文字／序号／水印／聚光灯／滤镜／自动打码／绘图模板未接；接了的工具样式与截图编辑器共用记忆。
 */
@SuppressLint("ViewConstructor")
internal class ScreenRecordingAnnotation(
    context: Context,
    private val width: Int,
    private val height: Int
) : FrameLayout(context) {
    private val base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val memory = ScreenshotToolMemory(context)
    internal val layer = ScreenshotDrawingLayer(context, base, memory) { }

    /** User pressed 退出绘制 on the bar; the service switches the window back to pass-through. */
    var onExitRequested: (() -> Unit)? = null

    private val topColumn = LinearLayout(context)
    private val bar = LinearLayout(context)
    private val stylesScroll = android.widget.ScrollView(context)
    private var stylesControls: ScreenshotDrawingControls? = null
    private var editorPanel: LinearLayout? = null
    private val toolButtons = LinkedHashMap<ScreenshotDrawingTool, ImageView>()
    private var undoButton: ImageView? = null
    private var redoButton: ImageView? = null
    private var current = ScreenshotDrawingTool.PEN

    init {
        // The layer only paints objects; `region` must be set or `onDraw` bails out.
        layer.region = Rect(0, 0, width, height)
        layer.tool = null
        // Endpoint dots are an editing hint; in a recording they would be burnt into the file.
        layer.showPenFeedback = false
        // The eyedropper would sample the transparent base, never the live screen.
        layer.colorSamplingEnabled = false
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = panelSurface()
        bar.setPadding(dp(6), dp(4), dp(6), dp(4))

        stylesScroll.background = panelSurface()
        stylesScroll.setPadding(dp(12), dp(4), dp(12), dp(4))
        stylesScroll.isHorizontalScrollBarEnabled = false
        stylesScroll.visibility = View.GONE

        topColumn.orientation = LinearLayout.VERTICAL
        topColumn.gravity = Gravity.CENTER_HORIZONTAL
        topColumn.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        topColumn.addView(stylesScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        topColumn.visibility = View.GONE
        addView(topColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(28) })

        tool(ScreenshotDrawingTool.PEN, ScreenshotAction.PEN, "画笔")
        tool(ScreenshotDrawingTool.RECTANGLE, ScreenshotAction.RECTANGLE, "形状")
        tool(ScreenshotDrawingTool.LINE, ScreenshotAction.LINE, "直线")
        tool(ScreenshotDrawingTool.ARROW, ScreenshotAction.ARROW, "箭头")
        tool(ScreenshotDrawingTool.HIGHLIGHT, ScreenshotAction.HIGHLIGHT, "高亮")
        tool(ScreenshotDrawingTool.ERASER, ScreenshotAction.ERASER, "橡皮")
        action(R.drawable.snow_undo, "撤销标注") { layer.undo() }?.also { undoButton = it }
        action(R.drawable.snow_redo, "重做标注") { layer.redo() }?.also { redoButton = it }
        action(R.drawable.snow_delete, "清空标注") { layer.history.reset(); layer.invalidate(); rebuildStyles() }
        action(R.drawable.snow_close, "退出绘制") { onExitRequested?.invoke() }
        syncButtons()
    }

    /** Draw mode on: the service makes the window touchable/focusable and calls this. */
    fun setDrawing(active: Boolean) {
        topColumn.visibility = if (active) View.VISIBLE else View.GONE
        layer.tool = if (active) current else null
        if (active) rebuildStyles() else closeEditor()
        syncButtons()
    }

    private fun tool(tool: ScreenshotDrawingTool, action: ScreenshotAction, label: String) {
        val view = icon(ScreenshotToolbarEditor.icon(action), label)
        view.setOnClickListener {
            current = tool
            layer.tool = tool
            rebuildStyles()
            syncButtons()
        }
        toolButtons[tool] = view
        bar.addView(view)
    }

    private fun action(icon: Int, label: String, run: () -> Unit): ImageView {
        val view = icon(icon, label)
        view.setOnClickListener { run(); syncButtons() }
        bar.addView(view)
        return view
    }

    private fun icon(resource: Int, label: String) = ImageView(context).apply {
        setImageResource(resource)
        setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        contentDescription = label
        tooltipText = label
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(7), dp(7), dp(7), dp(7))
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(2) }
    }

    /** Same panel the screenshot editor uses; it reads style state straight off the layer. */
    private fun rebuildStyles() {
        closeEditor()
        stylesScroll.removeAllViews()
        val controls = ScreenshotDrawingControls(context, layer, ::editValue,
            showSelectActions = false, showDrawTemplates = null)
        stylesControls = controls
        // `stylesScroll` is a ScrollView (FrameLayout family): its children must use its LayoutParams.
        stylesScroll.addView(controls, LayoutParams(LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT))
        // Cap the panel so a tall tool (font rows) cannot cover the whole recording area.
        stylesScroll.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val limit = (height * 0.4f).toInt().coerceAtLeast(dp(44))
        (stylesScroll.layoutParams as LinearLayout.LayoutParams).height =
            stylesScroll.measuredHeight.coerceAtMost(limit)
        stylesScroll.visibility =
            if (controls.hasContent && layer.tool != null) View.VISIBLE else View.GONE
    }

    /**
     * Chip values (字号/序号/…) edit inline: a small panel in this window, which is focusable while
     * drawing, so the IME can attach exactly like in the screenshot editor.
     */
    private fun editValue(title: String, value: String, save: (String) -> Unit) {
        closeEditor()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelSurface()
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        panel.addView(TextView(context).apply {
            text = title; textSize = 15f; setTextColor(Color.WHITE)
        })
        val field = ScreenshotTextEditor(context, value).apply { contentDescription = title }
        if (title == "字号") field.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        else if (title == "序号") field.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        panel.addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(6)
        })
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun editorButton(label: String, run: () -> Unit) = TextView(context).apply {
            text = label; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(0xFF69B1FF.toInt()); contentDescription = label
            setOnClickListener { run() }
        }
        buttons.addView(editorButton("取消") { closeEditor() },
            LinearLayout.LayoutParams(0, dp(40), 1f))
        buttons.addView(editorButton("保存") {
            save(field.text.toString())
            closeEditor()
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        panel.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.CENTER).apply { topMargin = dp(120) })
        editorPanel = panel
        field.requestFocus()
    }

    private fun closeEditor() {
        editorPanel?.let { runCatching { removeView(it) } }
        editorPanel = null
    }

    private fun panelSurface() = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(0xF0202020.toInt())
        setStroke(dp(1), 0x40FFFFFF)
    }

    /** Undo/redo dim when there is nothing to apply, exactly like the screenshot toolbar. */
    private fun syncButtons() {
        toolButtons.forEach { (tool, view) ->
            val selected = tool == current
            view.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                if (selected) setColor(0xFF1677FF.toInt())
            }
        }
        undoButton?.alpha = if (layer.history.canUndo) 1f else 0.35f
        redoButton?.alpha = if (layer.history.canRedo) 1f else 0.35f
    }

    fun release() {
        layer.tool = null
        if (!base.isRecycled) base.recycle()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
