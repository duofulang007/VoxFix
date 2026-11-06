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

/**
 * 录制中标注层（上游 `recordingDrawingMode`）。
 *
 * 上游依据：`ScreenRecordingController::connectDrawingToolbar` 把截图工具条接到录制选区窗口的画布上，
 * 点任一绘制工具 → `InputMode::Drawing`（画布接收鼠标），`drawingDeactivationRequested` → 清空激活工具并切
 * `InputMode::PassThrough`（点击穿透回下面的应用，`screenrecordingareawindow.cpp` 的 `HTTRANSPARENT`）。
 * 录制侧只用 `WDA_EXCLUDEFROMCAPTURE` 排除**工具条窗口**（`excludeToolbarFromCapture`），画布仍在录制范围内，
 * 所以画上去的笔画会被录进视频。
 *
 * 手机侧映射：一层全屏透明覆盖窗承载 `ScreenshotDrawingLayer`（它只画对象、不画底图），工具条放在该层顶部；
 * 窗口在「绘制」时接收触摸、其余时间穿透。
 *
 * 与原版的差异（明确记录，不写成复刻完成）：
 * 1. Android 没有窗口级“排除出录制”，工具条可见时会入镜（原版把工具条排除掉了）；
 * 2. v1 只接自包含工具——画笔／形状／直线／箭头／高亮／橡皮 ＋ 撤销／重做／清空；参数面板与
 *    文字／序号／水印／聚光灯／滤镜／自动打码仍缺，颜色线宽沿用各工具上次在截图编辑器里的记忆。
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

    private val bar = LinearLayout(context)
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
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(0xF0202020.toInt())
            setStroke(dp(1), 0x40FFFFFF)
        }
        bar.setPadding(dp(6), dp(4), dp(6), dp(4))
        addView(bar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(28) })
        // Idle until the service switches this window into draw mode.
        bar.visibility = View.GONE

        tool(ScreenshotDrawingTool.PEN, ScreenshotAction.PEN, "画笔")
        tool(ScreenshotDrawingTool.RECTANGLE, ScreenshotAction.RECTANGLE, "形状")
        tool(ScreenshotDrawingTool.LINE, ScreenshotAction.LINE, "直线")
        tool(ScreenshotDrawingTool.ARROW, ScreenshotAction.ARROW, "箭头")
        tool(ScreenshotDrawingTool.HIGHLIGHT, ScreenshotAction.HIGHLIGHT, "高亮")
        tool(ScreenshotDrawingTool.ERASER, ScreenshotAction.ERASER, "橡皮")
        action(R.drawable.snow_undo, "撤销标注") { layer.undo() }?.also { undoButton = it }
        action(R.drawable.snow_redo, "重做标注") { layer.redo() }?.also { redoButton = it }
        action(R.drawable.snow_delete, "清空标注") { layer.history.reset(); layer.invalidate() }
        action(R.drawable.snow_close, "退出绘制") { onExitRequested?.invoke() }
        syncButtons()
    }

    /** Draw mode on: the service makes the window touchable and calls this. */
    fun setDrawing(active: Boolean) {
        bar.visibility = if (active) View.VISIBLE else View.GONE
        layer.tool = if (active) current else null
        if (active) syncButtons()
    }

    private fun tool(tool: ScreenshotDrawingTool, action: ScreenshotAction, label: String) {
        val view = icon(ScreenshotToolbarEditor.icon(action), label)
        view.setOnClickListener {
            current = tool
            layer.tool = tool
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
