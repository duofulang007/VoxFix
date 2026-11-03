package local.voxfix.debug

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Android owns long-press selection, cross-line handles, magnifier and scrolling. */
class ScreenTextOverlay(
    private val context: Context,
    private val image: Bitmap?,
    private val recognize: (Rect) -> Unit,
    private val exit: () -> Unit,
    private val interaction: () -> Unit = {}
) {
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private val root = object : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean { interaction(); return super.dispatchTouchEvent(event) }
    }
    private val background = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; if (image != null) setImageBitmap(image) }
    private val scrim = View(context).apply { setBackgroundColor(0x50000000) }
    private val content = FrameLayout(context)
    private val status = TextView(context).apply { textSize = 14f; setTextColor(0xff59616d.toInt()); setPadding(dp(24), dp(4), dp(24), dp(12)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private var fullScreen = false
    private var halfOffset = 0f
    private var availableHeight = 0
    private var motion: ValueAnimator? = null
    private var closing = false
    private var compact = false
    private var bars = android.graphics.Insets.NONE
    private var imeBottom = 0
    private var controlsReady = false
    private var presented = false
    private var lastRegion: Rect? = null
    private var recognizing = false
    private val curve = PathInterpolator(.32f, .72f, 0f, 1f)
    var imeVisible = false
        private set
    private val text = object : EditText(context) {
        override fun onTextContextMenuItem(id: Int): Boolean {
            if (id == android.R.id.copy) { copySelection(); return true }
            return super.onTextContextMenuItem(id)
        }
        override fun onSelectionChanged(start: Int, end: Int) {
            super.onSelectionChanged(start, end)
            updateCopyLabel(start, end)
        }
    }.apply {
        textSize = 19f; setTextColor(0xff20232d.toInt()); setLineSpacing(dp(3).toFloat(), 1.15f)
        setPadding(dp(24), dp(8), dp(24), dp(20)); gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        hint = "未识别到文字，试试重新框选"; setHintTextColor(0xff69717c.toInt())
        contentDescription = "识别结果，可编辑文字"
        background = null
        isVerticalScrollBarEnabled = true
        val actions = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                // Copy and select-all live in the fixed toolbar; keep native cut/paste editing.
                for (id in listOf(android.R.id.copy, android.R.id.selectAll, android.R.id.shareText, android.R.id.textAssist)) menu.removeItem(id)
                return true
            }
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId == android.R.id.copy) { copySelection(); return true }
                return false
            }
            override fun onDestroyActionMode(mode: ActionMode) {}
        }
        customSelectionActionModeCallback = actions
        customInsertionActionModeCallback = actions
    }
    private val all = button("全选") {
        if (!text.text.isNullOrEmpty()) { text.requestFocus(); text.selectAll() }
    }
    private val copy = button("复制", true) { copySelection() }.apply { contentDescription = "复制文字并返回原页面" }
    private val region = button("重选") { startRegion() }.apply { contentDescription = "重新框选识别区域" }
    private val grip = FrameLayout(context).apply {
        contentDescription = "拖动调整面板，上拖展开，下拖关闭；点按切换半屏与全屏"
        isClickable = true; isFocusable = true
        setOnClickListener { toggleSize() }
        addView(View(context).apply { background = rounded(0xffb7bdc5.toInt(), 2) },
            FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER))
    }
    private val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), 0, dp(12), dp(4)) }
    private val toolbar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)); setBackgroundColor(Color.WHITE); elevation = dp(8).toFloat() }
    private var cropView: View? = null
    private val sheet = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f) }
        elevation = dp(8).toFloat()
    }
    init {
        if (image == null) root.setBackgroundColor(0xffeef0f3.toInt())
        root.addView(background, FrameLayout.LayoutParams(-1, -1))
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        sheet.addView(grip, LinearLayout.LayoutParams(-1, dp(48)))
        header.addView(TextView(context).apply { this.text = "识别结果"; textSize = 18f; gravity = Gravity.CENTER_VERTICAL; setTextColor(0xff20232d.toInt()); setTypeface(null, android.graphics.Typeface.BOLD) },
            LinearLayout.LayoutParams(-1, dp(40)))
        sheet.addView(header); sheet.addView(status)
        sheet.addView(text, LinearLayout.LayoutParams(-1, 0, 1f))
        listOf(region, all, copy).forEach { toolbar.addView(it, LinearLayout.LayoutParams(0, dp(48), if (it === copy) 1.6f else 1f).apply { leftMargin = dp(4); rightMargin = dp(4) }) }
        content.addView(sheet, FrameLayout.LayoutParams(-1, -1))
        content.addView(toolbar, FrameLayout.LayoutParams(-1, dp(68), Gravity.BOTTOM))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        scrim.setOnClickListener { closeAnimated() }
        grip.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
            }
            override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS) { closeAnimated(); return true }
                return super.performAccessibilityAction(host, action, args)
            }
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            imeVisible = insets.isVisible(WindowInsets.Type.ime())
            imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
            layoutSheet()
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutSheet(); presentSheet() }
        installSheetGesture()
        root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_BACK && !imeVisible) { if (event.action == KeyEvent.ACTION_UP) closeAnimated(); true } else false
        }
        status.visibility = View.GONE
        all.isEnabled = false; copy.isEnabled = false
        text.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                all.isEnabled = !s.isNullOrBlank(); copy.isEnabled = !s.isNullOrBlank(); interaction()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        controlsReady = true
    }
    private fun updateCopyLabel(start: Int, end: Int) {
        // EditText calls this while the overlay's fields are still being constructed.
        if (!controlsReady) return
        val selected = start >= 0 && end >= 0 && start != end
        copy.text = if (selected) "复制选中" else "复制"
        copy.contentDescription = if (selected) "复制选中文字并返回原页面" else "复制文字并返回原页面"
    }
    private fun layoutSheet() {
        if (root.height == 0 || closing) return
        val bottom = maxOf(bars.bottom, imeBottom)
        val nextHeight = (root.height - bars.top - bottom).coerceAtLeast(1)
        val landscape = root.width > root.height
        val nextCompact = imeVisible && nextHeight < dp(240)
        val nextHalf = if (landscape || imeVisible) 0f else (nextHeight - maxOf(nextHeight / 2, dp(300))).coerceAtLeast(0).toFloat()
        val params = content.layoutParams as FrameLayout.LayoutParams
        if (params.topMargin == bars.top && params.bottomMargin == bottom && params.leftMargin == bars.left && params.rightMargin == bars.right && availableHeight == nextHeight && halfOffset == nextHalf) return
        motion?.cancel()
        params.setMargins(bars.left, bars.top, bars.right, bottom); content.layoutParams = params
        availableHeight = nextHeight; halfOffset = nextHalf; compact = nextCompact
        header.visibility = if (compact) View.GONE else View.VISIBLE
        grip.visibility = if (compact || landscape) View.GONE else View.VISIBLE
        val footerHeight = dp(if (compact) 56 else 68)
        toolbar.setPadding(dp(16), dp(if (compact) 4 else 10), dp(16), dp(if (compact) 4 else 10))
        toolbar.layoutParams = (toolbar.layoutParams as FrameLayout.LayoutParams).apply { height = footerHeight }
        sheet.setPadding(0, 0, 0, footerHeight)
        setSheetHeight(availableHeight)
        positionSheet(if (fullScreen) 0f else halfOffset)
        settleSheet()
    }
    private fun setSheetHeight(height: Int) {
        val params = sheet.layoutParams
        if (params.height != height) { params.height = height; sheet.layoutParams = params }
    }
    private fun presentSheet() {
        if (presented || closing || !root.isAttachedToWindow || availableHeight == 0 || content.visibility != View.VISIBLE) return
        presented = true
        val target = sheet.translationY
        positionSheet(availableHeight.toFloat())
        animateSheet(target)
    }
    private fun positionSheet(offset: Float) {
        sheet.translationY = offset
        val dismissed = ((offset - halfOffset) / maxOf(1f, availableHeight - halfOffset)).coerceIn(0f, 1f)
        toolbar.translationY = (offset - halfOffset).coerceAtLeast(0f)
        toolbar.alpha = 1f - dismissed; scrim.alpha = 1f - dismissed
    }
    private fun settleSheet() {
        setSheetHeight((availableHeight - sheet.translationY).toInt().coerceAtLeast(1))
        grip.stateDescription = if (fullScreen || imeVisible) "全屏" else "半屏"
    }
    private fun toggleSize() {
        if (closing) return
        if (imeVisible) { fullScreen = false; hideKeyboard(); return }
        fullScreen = !fullScreen
        animateSheet(if (fullScreen) 0f else halfOffset)
    }
    private fun animateSheet(target: Float, finished: () -> Unit = { settleSheet() }) {
        motion?.cancel(); setSheetHeight(availableHeight)
        if (!ValueAnimator.areAnimatorsEnabled()) { positionSheet(target); finished(); return }
        motion = ValueAnimator.ofFloat(sheet.translationY, target).apply {
            duration = 240; interpolator = curve
            addUpdateListener { positionSheet(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) finished() }
            })
            start()
        }
    }
    private fun installSheetGesture() {
        var downY = 0f; var initial = 0f; var dragging = false
        var active = false
        var velocity: VelocityTracker? = null
        fun track(event: MotionEvent) {
            val raw = MotionEvent.obtain(event)
            raw.offsetLocation(event.rawX - event.x, event.rawY - event.y)
            velocity?.addMovement(raw); raw.recycle()
        }
        grip.setOnTouchListener { view, event ->
            if (closing) return@setOnTouchListener true
            if (!active && event.actionMasked != MotionEvent.ACTION_DOWN) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    motion?.cancel(); initial = sheet.translationY; downY = event.rawY; dragging = false; active = true
                    setSheetHeight(availableHeight)
                    velocity?.recycle(); velocity = VelocityTracker.obtain(); track(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    track(event)
                    val delta = event.rawY - downY
                    if (kotlin.math.abs(delta) > ViewConfiguration.get(context).scaledTouchSlop) dragging = true
                    if (dragging && !imeVisible) positionSheet((initial + delta).coerceIn(0f, availableHeight.toFloat()))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    active = false; track(event); velocity?.computeCurrentVelocity(1000)
                    if (event.actionMasked == MotionEvent.ACTION_UP && !dragging) view.performClick()
                    else {
                        if (event.actionMasked == MotionEvent.ACTION_UP && dragging && imeVisible) hideKeyboard()
                        if (event.actionMasked == MotionEvent.ACTION_UP && dragging && !imeVisible) {
                            val projected = sheet.translationY + (velocity?.yVelocity ?: 0f) * .12f
                            if (projected > halfOffset + dp(96)) closeAnimated()
                            else fullScreen = projected < halfOffset / 2
                        }
                        if (!closing) animateSheet(if (fullScreen) 0f else halfOffset)
                    }
                    velocity?.recycle(); velocity = null
                }
            }
            true
        }
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label; textSize = 15f; minWidth = 0; minimumWidth = 0
        isAllCaps = false; stateListAnimator = null
        isSingleLine = true
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x18000000),
            rounded(if (primary) 0xff246be8.toInt() else Color.TRANSPARENT, 24), null)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(0xffa0a4ad.toInt(), if (primary) Color.WHITE else 0xff30343c.toInt())))
        setPadding(dp(4), 0, dp(4), 0); setOnClickListener { action() }
    }
    fun show(windows: WindowManager) {
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        windows.addView(root, params); root.requestFocus(); root.requestApplyInsets()
    }
    fun loading() {
        recognizing = true
        val oldCrop = cropView; cropView = null
        oldCrop?.let { root.post { if (it.parent === root) root.removeView(it) } }
        content.visibility = View.VISIBLE; scrim.visibility = View.VISIBLE; status.visibility = View.VISIBLE; status.text = "正在识别…"
        text.hint = ""; text.contentDescription = "正在识别文字"
        text.isEnabled = false; hideKeyboard()
        all.isEnabled = false; copy.isEnabled = false; region.isEnabled = false
        root.post { presentSheet() }
    }
    fun result(value: String, error: String?) {
        val keepPrevious = recognizing && (error != null || value.isBlank()) && !text.text.isNullOrBlank()
        recognizing = false
        text.isEnabled = true
        if (!keepPrevious) { text.setText(value); text.scrollTo(0, 0) }
        text.hint = "未识别到文字，试试重新框选"; text.contentDescription = "识别结果，可编辑文字"
        status.text = if (keepPrevious) "${error ?: "未识别到文字"}，已保留上次结果" else error ?: ""
        status.visibility = if (error == null && !keepPrevious) View.GONE else View.VISIBLE
        all.isEnabled = !text.text.isNullOrBlank(); copy.isEnabled = all.isEnabled; region.isEnabled = image != null
    }
    data class ResultState(val value: String, val start: Int, val end: Int, val fullScreen: Boolean)
    fun retainedResult(): ResultState? = text.text?.toString()?.takeIf { it.isNotBlank() }?.let {
        ResultState(it, text.selectionStart, text.selectionEnd, fullScreen)
    }
    fun restoreResult(state: ResultState) {
        result(state.value, null)
        text.setSelection(state.start.coerceIn(0, state.value.length), state.end.coerceIn(0, state.value.length))
        fullScreen = state.fullScreen
        if (image == null) {
            status.text = "文字已保留；重新取字请返回原页面"
            status.visibility = View.VISIBLE
            region.contentDescription = "重新取字请返回原页面"
        }
    }
    private fun copySelection() {
        val start = minOf(text.selectionStart, text.selectionEnd)
        val end = maxOf(text.selectionStart, text.selectionEnd)
        val value = text.text ?: return
        val selected = if (start >= 0 && end > start) value.subSequence(start, end) else value
        if (selected.isBlank()) return
        try {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("屏幕取字", selected))
            closeAnimated()
        } catch (error: Exception) { Toast.makeText(context, "复制失败，请重试", Toast.LENGTH_SHORT).show() }
    }
    fun startRegion() {
        val image = image ?: return
        if (cropView != null) return
        hideKeyboard()
        content.visibility = View.GONE; scrim.visibility = View.GONE; presented = false
        cropView = ScreenTextRegionView(context, image.width, image.height, lastRegion, { picked ->
            lastRegion = Rect(picked); recognize(picked)
        })
        root.addView(cropView, FrameLayout.LayoutParams(-1, -1))
    }
    fun closeAnimated() {
        if (closing) return
        closing = true; hideKeyboard()
        if (content.visibility != View.VISIBLE || availableHeight == 0) { exit(); return }
        animateSheet(availableHeight.toFloat()) { exit() }
    }
    fun dismiss(windows: WindowManager) {
        motion?.cancel()
        hideKeyboard()
        background.setImageDrawable(null)
        runCatching { windows.removeViewImmediate(root) }
    }
    private fun hideKeyboard() {
        root.windowToken?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it, 0) }
        text.clearFocus(); root.requestFocus()
    }
}
