package local.voxfix.debug

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/**
 * 录制中标注层（上游 `recordingDrawingMode`）。
 *
 * 上游依据：`ScreenRecordingController::connectDrawingToolbar` 把**整套**截图绘制工具接到录制选区窗口的
 * 画布上（`screenrecordingcontroller.cpp:585-658`）：选择、形状、箭头、直线、画笔、矩形高亮、笔迹高亮、
 * 矩形滤镜、笔迹滤镜、自动筛选、聚光灯、橡皮、水印、文字、序号，外加撤销／重做与右键停用
 * （`drawingDeactivationRequested` → 清空激活工具并切 `InputMode::PassThrough`）。
 * 点任一工具 → `InputMode::Drawing`（画布接收输入）；录制侧只用 `WDA_EXCLUDEFROMCAPTURE` 排除
 * **工具条窗口**（`excludeToolbarFromCapture`），画布仍在录制范围内，所以画上去的笔画会被录进视频。
 *
 * 手机侧映射：一层全屏透明覆盖窗承载 `ScreenshotDrawingLayer`（它只画对象、不画底图），工具条与样式面板
 * 放在该层顶部；窗口在「绘制」时接收触摸与输入焦点，其余时间穿透。
 *
 * 与原版的差异（明确记录，不写成复刻完成）：
 * 1. Android 没有窗口级“排除出录制”，工具条与样式面板可见时会入镜（原版排除了工具条窗口）；
 * 2. 取色滴管不可用（底图是透明位图，采不到真实屏幕），调色板里隐藏该按钮而不是伪造颜色；
 * 3. 滤镜与自动筛选：原版录制画布同样没有页面位图（`screenrecordingareawindow.cpp` 从不 `setImage`，
 *    滤镜在 `snow_canvas_renderer.cpp` 里采样画布像素），所以录制中两者都没有像素可采。手机保留滤镜工具
 *    的入口与参数（与原版同构），但不提供「自动筛选」——它要在透明底图上跑轮廓识别，结果只会是噪声；
 * 4. 工具条按屏宽折行显示（原版是桌面单行）；
 * 5. 服务重建后标注层不重建（笔画随旧实例丢弃）。
 */
@SuppressLint("ViewConstructor")
internal class ScreenRecordingAnnotation(
    context: Context,
    private val width: Int,
    private val height: Int
) : FrameLayout(context) {
    private val base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val memory = ScreenshotToolMemory(context)

    /** `changed` also drives the tool sync: the layer switches to SELECT after text/template work. */
    internal val layer = ScreenshotDrawingLayer(context, base, memory) { syncToolFromLayer() }

    /** User pressed 退出绘制 on the bar; the service switches the window back to pass-through. */
    var onExitRequested: (() -> Unit)? = null

    /**
     * IME needs a focusable window, but a focusable full-screen window sorts above the recording bar.
     * The service grants focus only while one of the inline editors is open.
     */
    var onFocusRequest: ((Boolean) -> Unit)? = null
    private var focusRequested = false

    private val topColumn = LinearLayout(context)
    private val bar = LinearLayout(context)
    private val stylesScroll = ScrollView(context)
    private var stylesControls: ScreenshotDrawingControls? = null
    private var editorPanel: LinearLayout? = null
    private var textInput: ScreenshotAnnotationInput? = null
    private var imeBottom = 0
    private val toolButtons = LinkedHashMap<ScreenshotDrawingTool, ImageView>()
    private var undoButton: ImageView? = null
    private var redoButton: ImageView? = null
    private var current = ScreenshotDrawingTool.PEN
    private val drawTemplates by lazy { ScreenshotDrawTemplateStore(context) }
    private var templatePopup: PopupWindow? = null

    init {
        // The layer only paints objects; `region` must be set or `onDraw` bails out.
        layer.region = Rect(0, 0, width, height)
        layer.tool = null
        // Endpoint dots are an editing hint; in a recording they would be burnt into the file.
        layer.showPenFeedback = false
        // The eyedropper would sample the transparent base, never the live screen.
        layer.colorSamplingEnabled = false
        layer.onTextRequest = { point, index -> beginText(point, index) }
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        bar.orientation = LinearLayout.VERTICAL
        bar.background = panelSurface()
        bar.setPadding(dp(6), dp(4), dp(6), dp(4))

        // Upstream palette order, wrapped to the screen width (see ScreenRecordingAnnotationLayout).
        val entries = ScreenRecordingAnnotationLayout.tools
        val cells = entries.map { (tool, label) -> toolButton(tool, label) }.toMutableList<View>()
        cells.add(action(R.drawable.snow_undo, "撤销标注") { layer.undo(); syncButtons() }.also { undoButton = it })
        cells.add(action(R.drawable.snow_redo, "重做标注") { layer.redo(); syncButtons() }.also { redoButton = it })
        cells.add(action(R.drawable.snow_delete, "清空标注") { layer.history.reset(); layer.invalidate(); rebuildStyles() })
        cells.add(action(R.drawable.snow_close, "退出绘制") { onExitRequested?.invoke() })
        val perRow = ScreenRecordingAnnotationLayout.cellsPerRow(width, resources.displayMetrics.density)
        ScreenRecordingAnnotationLayout.rows(cells.size, perRow).forEach { row ->
            bar.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                row.forEach { addView(cells[it]) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }

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

        // Text editing lives in this window: the IME inset decides where the input field may sit.
        setOnApplyWindowInsetsListener { _, insets ->
            imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
            post { placeTextInput() }
            insets
        }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { placeTextInput() } }
        syncButtons()
    }

    /** Draw mode on: the service makes the window touchable/focusable and calls this. */
    fun setDrawing(active: Boolean) {        topColumn.visibility = if (active) View.VISIBLE else View.GONE
        layer.tool = if (active) current else null
        if (active) {
            rebuildStyles()
            requestApplyInsets()
        } else {
            closeText()
            closeTemplatePopup()
            closeEditor()
            imeBottom = 0
        }
        syncButtons()
    }

    /**
     * Upstream annotates on the region window itself, so marks, the watermark and the spotlight mask
     * all stop at the region edge. The overlay is full-screen, so the same rect clips painting and
     * swallows touches outside it (there is no canvas there upstream either).
     */
    fun setCanvasRegion(region: Rect) {
        layer.clip = Rect(region)
        layer.invalidate()
    }

    private fun toolButton(tool: ScreenshotDrawingTool, label: String): View {
        val action = ScreenshotDrawingTools.actions[tool]
        val view = if (action != null) icon(ScreenshotToolbarEditor.icon(action), label)
        else icon(R.drawable.snow_pen, label)
        view.setOnClickListener {
            current = tool
            layer.tool = tool
            closeText()
            closeEditor()
            rebuildStyles()
            syncButtons()
        }
        toolButtons[tool] = view
        return view
    }

    private fun action(icon: Int, label: String, run: () -> Unit): ImageView {
        val view = icon(icon, label)
        view.setOnClickListener { run(); syncButtons() }
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
            showSelectActions = false, showDrawTemplates = ::showDrawTemplates,
            allowAutoFilter = false)
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
            if (controls.hasContent && layer.tool != null && textInput == null) View.VISIBLE else View.GONE
    }

    /** The layer moves itself (SELECT after a text mark or a template insert); keep the bar honest. */
    private fun syncToolFromLayer() {
        val active = layer.tool
        if (active != null && active != current) {
            current = active
            rebuildStyles()
        }
        syncButtons()
    }

    /**
     * Text marks and serial labels edit through the same popup the pin editor uses: a field laid
     * over the object plus 保存／取消, displaced above the IME when the keyboard covers it.
     */
    /** Keep the window focusable exactly while an inline editor exists (and the IME is needed). */
    private fun syncFocus() {
        val needed = textInput != null || editorPanel != null
        if (needed == focusRequested) return
        focusRequested = needed
        onFocusRequest?.invoke(needed)
    }

    private fun beginText(point: ScreenshotDrawingPoint, index: Int?) {
        closeText()
        closeEditor()
        // The window has to be focusable before the field asks for focus, or the IME never attaches.
        requestFocusForEditor()
        val scale = if (layer.width > 0) layer.width.toFloat() / base.width else 1f
        // No actionBounds: the screenshot editor anchors the bar under a bottom toolbar, while the
        // recording bar sits on top, so 保存／取消 goes to the bottom of the safe area instead.
        textInput = ScreenshotAnnotationInput(this, layer, point, index, {
            val rect = layer.textBounds(point, index)
            RectF(rect.left * scale, rect.top * scale, rect.right * scale, rect.bottom * scale)
        }) { closeText(); rebuildStyles() }
        stylesScroll.visibility = View.GONE
        syncFocus()
        post { placeTextInput() }
    }

    /** Focus first, field second: the window flag has to land before `requestFocus()`. */
    private fun requestFocusForEditor() {
        if (focusRequested) return
        focusRequested = true
        onFocusRequest?.invoke(true)
    }

    private fun placeTextInput() {
        val input = textInput ?: return
        val bottom = (height - imeBottom).coerceAtLeast(dp(44))
        input.place(toolBarBottom() + dp(4), bottom - dp(4))
    }

    private fun closeText() {
        val input = textInput ?: return
        textInput = null
        runCatching { input.close() }
        syncFocus()
    }

    /** Bottom edge of the toolbar in window coordinates (top margin is fixed at 28dp). */
    private fun toolBarBottom() = dp(28) + bar.height

    /**
     * Chip values (字号/序号/模板名称/…) edit inline: a small panel in this window, which is focusable
     * while drawing, so the IME can attach exactly like in the screenshot editor.
     */
    private fun editValue(title: String, value: String, save: (String) -> Unit) {
        closeEditor()
        requestFocusForEditor()
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
        syncFocus()
    }

    private fun closeEditor() {
        editorPanel?.let { runCatching { removeView(it) } }
        editorPanel = null
        syncFocus()
    }

    /**
     * 绘图模板：存所选对象为模板、点名称居中插入、确认删除（`draw-template.rs` 的手机实现已在截图编辑器
     * 落地，这里复用同一份存储与同一套动作，只是弹层挂在本窗口）。
     */
    private fun showDrawTemplates(anchor: View) {
        if (!anchor.isAttachedToWindow) return
        val names = runCatching { drawTemplates.names() }.getOrElse {
            toast("无法读取绘图模板"); return
        }
        closeTemplatePopup()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val scroll = ScrollView(context).apply { addView(list) }
        val popup = PopupWindow(scroll, minOf(dp(280), width - dp(16)).coerceAtLeast(1),
            dp((44 * (names.size + 1)).coerceIn(132, 300)), true).apply {
            setBackgroundDrawable(panelSurface())
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
        }
        templatePopup = popup
        popup.setOnDismissListener { if (templatePopup === popup) templatePopup = null }
        fun rowButton(label: String, description: String, run: () -> Unit) = TextView(context).apply {
            text = label; textSize = 15f; gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE); contentDescription = description; tooltipText = description
            minHeight = dp(44); isFocusable = true; setPadding(dp(8), 0, dp(8), 0)
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat() }
            setOnClickListener { run() }
        }
        list.addView(rowButton("将所选对象存为模板", "将所选对象存为模板") {
            popup.dismiss()
            val template = layer.selectedTemplate() ?: return@rowButton
            val suffix = generateSequence(1) { it + 1 }.first { n -> names.none { it == "模板 $n" } }
            editValue("模板名称", "模板 $suffix") { value ->
                runCatching { drawTemplates.save(value, template) }
                    .onSuccess { toast("绘图模板已保存") }
                    .onFailure { toast(it.message ?: "无法保存绘图模板") }
            }
        }.apply { isEnabled = layer.selectedIndices.isNotEmpty(); alpha = if (isEnabled) 1f else .4f })
        for (name in names) {
            val row = LinearLayout(context)
            row.addView(rowButton(name, "插入模板 $name") {
                popup.dismiss()
                val inserted = runCatching { layer.insertTemplate(drawTemplates.load(name)) }.getOrDefault(false)
                if (inserted) { current = layer.tool ?: current; rebuildStyles(); syncButtons() }
                else toast("无法插入绘图模板")
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            row.addView(rowButton("删除", "删除模板 $name") {
                list.removeAllViews()
                list.addView(TextView(context).apply {
                    text = "删除「$name」后无法撤销"; textSize = 14f
                    setTextColor(0xFFFFA39E.toInt()); minHeight = dp(44)
                })
                list.addView(rowButton("确认删除", "确认删除模板 $name") {
                    popup.dismiss()
                    runCatching { drawTemplates.delete(name) }.onFailure { toast("无法删除绘图模板") }
                })
                list.addView(rowButton("取消", "取消删除模板") { popup.dismiss() })
            }, LinearLayout.LayoutParams(dp(64), dp(44)))
            list.addView(row)
        }
        popup.showAtLocation(this, Gravity.CENTER, 0, 0)
    }

    private fun closeTemplatePopup() {
        templatePopup?.let { runCatching { it.dismiss() } }
        templatePopup = null
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
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
        closeText()
        closeTemplatePopup()
        closeEditor()
        if (!base.isRecycled) base.recycle()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
