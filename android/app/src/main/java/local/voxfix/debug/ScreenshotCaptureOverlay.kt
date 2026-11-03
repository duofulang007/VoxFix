package local.voxfix.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Insets
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView

/**
 * Frozen image, adjustable selection and an unsaved text layer. Saving is one optional exit;
 * copy, in-place editing and original/result switching appear only after recognition.
 *
 * Palette metrics follow Snow Shot's toolbar main panel (32px buttons, 24px icons, 12/4px panel
 * margins, 8px item spacing, 1x16px separators with 12px sides, 8px radius, white surface).
 */
class ScreenshotCaptureOverlay(
    private val context: Context,
    private val image: Bitmap,
    private val save: (Rect, Bitmap?) -> Unit,
    private val recognize: (Rect) -> Unit,
    private val onText: (String) -> Unit,
    private val exit: () -> Unit,
    private val interaction: () -> Unit
) {
    private companion object {
        const val BUTTON = 32
        const val ICON = 24
        const val PANEL_H = 12
        const val PANEL_V = 4
        const val ITEM_SPACING = 8
        const val SEPARATOR_WIDTH = 1
        const val SEPARATOR_HEIGHT = 16
        const val SEPARATOR_SIDE = 12
        const val PANEL_RADIUS = 8
        const val COLOR_TEXT = 0xE0000000.toInt()
        const val COLOR_TEXT_DISABLED = 0x40000000
        const val COLOR_DANGER = 0xFFFF4D4F.toInt()
        const val COLOR_PRIMARY = 0xFF1677FF.toInt()
        const val COLOR_SEPARATOR = 0xFFD9D9D9.toInt()
    }
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private val root = object : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean { interaction(); return super.dispatchTouchEvent(event) }
    }
    // Background, selection and results move together when the keyboard covers the edited line.
    private val content = FrameLayout(context)
    private val background = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(image) }
    private var picked: Rect? = null
    private var busy = false
    private var editing = false
    private var showingOriginal = false
    private var textLayer: ScreenshotTextLayer? = null
    private var bars = Insets.NONE
    private var imeBottom = 0
    private var windows: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var toolbarWidth = 0
    private val selector = ScreenTextRegionView(context,image.width,image.height,null,::select)
    private val resultLayer = FrameLayout(context).apply { visibility = View.INVISIBLE }
    private fun surface(radius: Int) = GradientDrawable().apply { setColor(0xffffffff.toInt()); cornerRadius = dp(radius).toFloat() }
    private val toolbar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(PANEL_H),dp(PANEL_V),dp(PANEL_H),dp(PANEL_V))
        background = surface(PANEL_RADIUS)
        elevation = dp(6).toFloat()
    }
    private val status = TextView(context).apply {
        text = "拖动框选，可移动和拉边调整"; textSize = 12f; setTextColor(COLOR_TEXT)
        setPadding(dp(10),dp(6),dp(10),dp(6)); background = surface(PANEL_RADIUS)
        elevation = dp(6).toFloat(); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val full = Action(ScreenshotAction.FULL_SCREEN, R.drawable.ic_screenshot_fullscreen) { selectFull() }
    private val recognizeAction = Action(ScreenshotAction.RECOGNIZE, R.drawable.snow_recognize) { beginRecognize() }
    private val saveAction = Action(ScreenshotAction.SAVE, R.drawable.snow_save) { beginSave() }
    private val copyAction = Action(ScreenshotAction.COPY_TEXT, R.drawable.snow_copy) { copyText() }
    private val editAction = Action(ScreenshotAction.EDIT, R.drawable.snow_edit) { setEditing(!editing) }
    private val eyeAction = Action(ScreenshotAction.SHOW_ORIGINAL, R.drawable.snow_eye) { toggleOriginal() }
    private val closeAction = Action(ScreenshotAction.CLOSE, R.drawable.snow_close, danger = true) { exit() }
    private val actions = listOf(full,recognizeAction,saveAction,closeAction,copyAction,eyeAction,editAction)
    // Snow Shot groups the palette as tools | actions | result actions, with OCR actions last.
    private val groups = listOf(
        listOf(full),
        listOf(recognizeAction,saveAction),
        listOf(closeAction,copyAction),
        listOf(eyeAction,editAction)
    )
    init {
        root.addView(content,FrameLayout.LayoutParams(-1,-1))
        content.addView(background,FrameLayout.LayoutParams(-1,-1))
        content.addView(selector,FrameLayout.LayoutParams(-1,-1))
        content.addView(resultLayer,FrameLayout.LayoutParams(-1,-1))
        selector.onRegionMoved = ::select
        selector.contentDescription = "拖动框选截图区域，可在框内移动或拉边调整，松手后可识别或保存"
        selector.setOnTouchListener { _,_ -> busy || editing }
        root.addView(toolbar,FrameLayout.LayoutParams(dp(200),dp(BUTTON+PANEL_V*2)))
        root.addView(status,FrameLayout.LayoutParams(-2,-2))
        root.setOnApplyWindowInsetsListener { _,insets ->
            bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
            applyImeShift(); placeControls(); insets
        }
        root.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> placeControls() }
        sync()
    }
    private fun state() = ScreenshotToolbarState(picked != null,textLayer != null,busy,editing,showingOriginal)
    private fun sync() {
        val state = state()
        eyeAction.active = showingOriginal
        editAction.active = editing
        actions.forEach {
            it.visibility = if (state.visible(it.action)) View.VISIBLE else View.GONE
            it.isEnabled = state.enabled(it.action)
            val label = state.label(it.action)
            it.contentDescription = label; it.tooltipText = label
            it.refreshTint()
        }
        rebuildToolbar()
        placeControls()
    }
    private fun rebuildToolbar() {
        toolbar.removeAllViews()
        var width = dp(PANEL_H*2)
        var firstGroup = true
        for (group in groups) {
            val visible = group.filter { it.visibility == View.VISIBLE }
            if (visible.isEmpty()) continue
            if (!firstGroup) width += addSeparator()
            visible.forEachIndexed { index,action ->
                if (index > 0) width += addGap()
                toolbar.addView(action,LinearLayout.LayoutParams(dp(BUTTON),dp(BUTTON)))
                width += dp(BUTTON)
            }
            firstGroup = false
        }
        toolbarWidth = width
        val layout = toolbar.layoutParams as? FrameLayout.LayoutParams
        if (layout == null || layout.width != width || layout.height != dp(BUTTON+PANEL_V*2))
            toolbar.layoutParams = (layout ?: FrameLayout.LayoutParams(width,dp(BUTTON+PANEL_V*2))).apply {
                this.width = width; height = dp(BUTTON+PANEL_V*2)
            }
    }
    private fun addGap(): Int {
        toolbar.addView(Space(context),LinearLayout.LayoutParams(dp(ITEM_SPACING),1))
        return dp(ITEM_SPACING)
    }
    private fun addSeparator(): Int {
        val line = View(context).apply { setBackgroundColor(COLOR_SEPARATOR) }
        toolbar.addView(line,LinearLayout.LayoutParams(dp(SEPARATOR_WIDTH),dp(SEPARATOR_HEIGHT)).apply {
            marginStart = dp(SEPARATOR_SIDE); marginEnd = dp(SEPARATOR_SIDE)
        })
        return dp(SEPARATOR_WIDTH+SEPARATOR_SIDE*2)
    }
    private fun select(region: Rect) {
        picked = region.takeIf { it.width() >= 2 && it.height() >= 2 }?.let(::Rect)
        // Blocks belong to the previous region; moving the box invalidates them.
        clearText()
        status.text = "拖动框选，可移动和拉边调整"
        status.visibility = if (picked == null) View.VISIBLE else View.GONE
        sync()
    }
    private fun selectFull() {
        if (busy || editing) return
        selector.selectRegion(Rect(0,0,image.width,image.height))
    }
    private fun beginRecognize() {
        val region = picked ?: return
        android.util.Log.i("VoxFix","SCREENSHOT_OCR_TAP region=${region.width()}x${region.height()}")
        if (busy) return
        setEditing(false)
        busy = true; status.text = "正在识别…"; status.visibility = View.VISIBLE
        sync(); recognize(Rect(region))
    }
    fun textResult(blocks: List<ScreenshotTextBlock>) {
        busy = false
        if (blocks.isEmpty()) {
            clearText()
            status.text = "未识别到文字，可重新框选"; status.visibility = View.VISIBLE
            sync(); return
        }
        val layer = ScreenshotTextLayer(context,image,blocks,::textChanged) { setEditing(true) }
        textLayer = layer; showingOriginal = false
        resultLayer.removeAllViews()
        resultLayer.addView(layer,FrameLayout.LayoutParams(-1,-1))
        resultLayer.visibility = View.VISIBLE
        // The capture range must stay visible; only the recognized text loses its own frame.
        selector.visibility = View.VISIBLE
        status.visibility = View.GONE
        sync()
    }
    fun failure(message: String) {
        busy = false
        status.text = message; status.visibility = View.VISIBLE
        sync()
    }
    private fun textChanged(value: String) { onText(value) }
    private fun clearText() {
        if (textLayer == null && resultLayer.childCount == 0) return
        setEditing(false)
        textLayer = null; showingOriginal = false
        resultLayer.removeAllViews(); resultLayer.visibility = View.INVISIBLE
        applyImeShift()
    }
    private fun copyText() {
        val value = textLayer?.copyText()?.takeIf { it.isNotBlank() } ?: return
        android.util.Log.i("VoxFix","SCREENSHOT_COPY selection=${value.length} total=${textLayer?.text()?.length ?: 0}")
        try {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("截图文字",value))
            // A toast keeps the confirmation away from the selected region.
            android.widget.Toast.makeText(context,"已复制文字",android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            android.widget.Toast.makeText(context,"复制失败，请重试",android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    private fun toggleOriginal() {
        if (textLayer == null) return
        showingOriginal = !showingOriginal
        if (showingOriginal) setEditing(false)
        resultLayer.visibility = if (showingOriginal) View.INVISIBLE else View.VISIBLE
        sync()
    }
    private fun setEditing(next: Boolean) {
        if (editing == next) return
        if (next && textLayer == null) return
        editing = next
        val layer = textLayer
        if (next) {
            // The overlay window is focusable from creation, so the fields can own the input
            // connection; toggling FLAG_NOT_FOCUSABLE later never binds the IME.
            root.requestFocus()
            root.postDelayed({
                layer?.beginEditing()
                layer?.focusedField()?.let {
                    context.getSystemService(InputMethodManager::class.java).showSoftInput(it,InputMethodManager.SHOW_IMPLICIT)
                }
            },80)
        } else {
            layer?.endEditing(); hideKeyboard(); content.translationY = 0f
        }
        sync()
    }
    private fun hideKeyboard() {
        root.windowToken?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it,0) }
    }
    private fun applyImeShift() {
        val layer = textLayer
        if (!editing || layer == null || imeBottom <= 0 || root.height == 0) { content.translationY = 0f; return }
        val limit = root.height - imeBottom - dp(8)
        val bottom = layer.focusedBottom()
        content.translationY = if (bottom > limit) (limit - bottom).toFloat() else 0f
    }
    private fun placeControls() {
        val width = root.width; val height = root.height
        if (width == 0 || height == 0) return
        val rect = picked
        val safeBottom = height - maxOf(bars.bottom,imeBottom) - dp(8)
        val left = rect?.let { it.left * width / image.width } ?: (width-toolbarWidth) / 2
        val bottom = rect?.let { it.bottom * height / image.height } ?: safeBottom
        val top = rect?.let { it.top * height / image.height } ?: bottom
        val proposed = if (rect != null && bottom + dp(48) > safeBottom) top - dp(48) else bottom + dp(8)
        val x = left.coerceIn(bars.left+dp(8),maxOf(bars.left+dp(8),width-bars.right-toolbarWidth-dp(8)))
        val y = proposed.coerceIn(bars.top+dp(8),maxOf(bars.top+dp(8),safeBottom-dp(BUTTON+PANEL_V*2)))
        toolbar.translationX = x.toFloat(); toolbar.translationY = y.toFloat()
        status.maxWidth = (width-bars.left-bars.right-dp(16)).coerceAtLeast(1)
        status.translationX = x.toFloat(); status.translationY = (y-dp(34)).coerceAtLeast(bars.top+dp(8)).toFloat()
    }
    private fun beginSave() {
        val region = picked ?: return
        if (busy) return
        busy = true; status.text = "正在保存…"; status.visibility = View.VISIBLE
        sync()
        val composed = try {
            // Save what the user sees: an edited result layer is exported, original view is not.
            if (textLayer != null && resultLayer.visibility == View.VISIBLE) textLayer?.render(region) else null
        } catch (error: Exception) {
            android.util.Log.w("VoxFix","SCREENSHOT_RENDER_FAILED",error)
            busy = false; status.text = "保存失败，请重试"; sync(); return
        }
        save(Rect(region),composed)
    }
    /** Back key: leave editing first, otherwise the service closes the whole capture. */
    fun back(): Boolean {
        if (!editing) return false
        setEditing(false); return true
    }
    val isEditing: Boolean get() = editing
    fun show(windowManager: WindowManager) {
        windows = windowManager
        val layout = WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT; setFitInsetsTypes(0); windowAnimations = 0
            // Focusable from the start: text fields must own a real input connection.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        params = layout
        windowManager.addView(root,layout); root.requestFocus(); root.requestApplyInsets()
    }
    fun dismiss(windowManager: WindowManager) {
        hideKeyboard(); background.setImageDrawable(null)
        runCatching { windowManager.removeViewImmediate(root) }
        params = null; windows = null
    }
    private inner class Action(
        val action: ScreenshotAction,
        icon: Int,
        private val danger: Boolean = false,
        run: () -> Unit
    ) : ImageView(context) {
        var active = false
        init {
            setImageResource(icon); scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp((BUTTON-ICON)/2),dp((BUTTON-ICON)/2),dp((BUTTON-ICON)/2),dp((BUTTON-ICON)/2))
            background = RippleDrawable(ColorStateList.valueOf(0x14000000),null,null)
            isClickable = true; isFocusable = true
            setOnClickListener { run() }
        }
        fun refreshTint() {
            val color = when {
                !isEnabled -> COLOR_TEXT_DISABLED
                danger -> COLOR_DANGER
                active -> COLOR_PRIMARY
                else -> COLOR_TEXT
            }
            setColorFilter(color,PorterDuff.Mode.SRC_IN)
        }
        override fun drawableStateChanged() { super.drawableStateChanged(); refreshTint() }
    }
}
