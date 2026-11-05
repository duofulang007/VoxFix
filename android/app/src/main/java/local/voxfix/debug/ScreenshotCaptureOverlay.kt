package local.voxfix.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Insets
import android.graphics.PixelFormat
import android.graphics.PointF
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

/** One save: what the user sees, plus the project that produced it. */
class ScreenshotSaveRequest(
    val region: Rect,
    val composite: Bitmap?,
    val document: ScreenshotDocument,
    /** Set when this replaces an existing project record; the record keeps its id and revisions. */
    val replaceId: String? = null,
    /** Revision the editor opened; a newer save must win instead of being overwritten. */
    val expectedRevision: Int? = null,
    /** Set when a plain image is being annotated: the new record borrows that record's pixels. */
    val copySourceFromId: String? = null
)

/** A record reopened for editing. [document] is a copy; leaving the editor must not touch it. */
class ScreenshotProject(val document: ScreenshotDocument, val replaceId: String?, val revision: Int?, val copySourceFromId: String?)

/**
 * Frozen image, adjustable selection and an unsaved text layer. Saving is one optional exit;
 * copy, in-place editing and original/result switching appear only after recognition.
 *
 * Palette metrics follow Snow Shot's toolbar main panel (32px buttons, 24px icons, 12/4px panel
 * margins, 8px item spacing, 1x16px separators with 12px sides, 8px radius, white surface).
 */
class ScreenshotCaptureOverlay(
    private val context: Context,
    private var image: Bitmap,
    private val save: (ScreenshotSaveRequest) -> Unit,
    private val recognize: (Rect) -> Unit,
    private val onText: (String) -> Unit,
    private val exit: () -> Unit,
    private val interaction: () -> Unit,
    private val translator: ScreenshotTranslator,
    private val toolMemory: ScreenshotToolMemory?=null,
    /** Reopening a saved record: the viewport is fixed and its marks are already loaded. */
    private val project: ScreenshotProject?=null
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
        const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        const val COLOR_TEXT_DISABLED = 0x66FFFFFF
        const val COLOR_DANGER = 0xFFFF4D4F.toInt()
        const val COLOR_PRIMARY = 0xFF1677FF.toInt()
        const val COLOR_SEPARATOR = 0xFF3A3A3C.toInt()
        const val COLOR_PANEL = 0xFF1C1C1E.toInt()
    }
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private val root: FrameLayout = object : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean { interaction(); return super.dispatchTouchEvent(event) }
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width = View.MeasureSpec.getSize(widthSpec)
            if (width > 0 && toolbarAvailable != width-bars.left-bars.right-dp(16)) rebuildToolbar(width)
            super.onMeasure(widthSpec,heightSpec)
        }
    }
    // Background, selection and results move together when the keyboard covers the edited line.
    private val content = FrameLayout(context)
    private val background = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(image) }
    private var picked: Rect? = null
    private var busy = false
    private var recapturing = false
    internal val isRecapturing get() = recapturing
    internal var onRecaptureRequested: (() -> Unit)? = null
    internal var onScrollRequested: ((Rect, Int, Boolean) -> Unit)? = null
    /** Starts a region recording for the current rectangular selection. */
    internal var onRecordRequested: ((Rect) -> Unit)? = null
    private val recaptureDrafts = linkedSetOf<String>()
    private var editing = false
    private var showingOriginal = false
    private var translating = false
    private var translateAfterRecognition = false
    private var discardRecognition = false
    private var showingTranslation = false
    private var translationComplete = false
    private var pair = TranslatePair(
        TranslateLanguage.fromPreference(
            ScreenTextSettingsActivity.preferences(context).getString("translate.source", null),
            TranslateLanguage.AUTO
        ),
        TranslateLanguage.fromPreference(
            ScreenTextSettingsActivity.preferences(context).getString("translate.target", null),
            TranslateLanguage.ZH
        )
    ).normalized()
    private var pairBar: TranslatePairBar? = null
    private var feedback: TextView? = null
    private val hideFeedback = Runnable { feedback?.visibility = View.GONE }
    private var translationLayer: ScreenshotTextLayer? = null
    private var textLayer: ScreenshotTextLayer? = null
    private var bars = Insets.NONE
    private var imeBottom = 0
    private var windows: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var toolbarWidth = 0
    private var toolbarHeight = dp(BUTTON+PANEL_V*2)
    private var toolbarAvailable = 0
    private val toolbarLayout = ScreenshotToolbarPreferences.combined(context)
    private var stackPopup: View? = null
    private var stackBackdrop: View? = null
    private var stackGroup: Set<ScreenshotAction>? = null
    private val groupEntries = mutableMapOf<Set<ScreenshotAction>,ScreenshotAction>()
    private val drawing = ScreenshotDrawingLayer(context,image,toolMemory) { rebuildDrawingStyles(); sync() }
    private val drawingStyles: android.widget.ScrollView = object : android.widget.ScrollView(context) {
        override fun onMeasure(widthSpec: Int,heightSpec: Int) {
            val available=(root.height-maxOf(bars.bottom,imeBottom)-bars.top).takeIf { it>0 } ?: image.height
            val limit=minOf(dp(240),(available*.4f).toInt()).coerceAtLeast(dp(44))
            val parentLimit=if(View.MeasureSpec.getMode(heightSpec)==View.MeasureSpec.UNSPECIFIED) limit else View.MeasureSpec.getSize(heightSpec)
            super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(minOf(limit,parentLimit),View.MeasureSpec.AT_MOST))
        }
    }.apply {
        background = surface(PANEL_RADIUS)
        setPadding(dp(12),dp(4),dp(12),dp(4)); elevation = dp(6).toFloat(); visibility = View.GONE
    }
    /** Selected-object actions live in the bar's right column, not a full-width bottom row. */
    private val selectSideActions = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.TOP
        setPadding(dp(2),dp(4),dp(2),dp(4)); contentDescription = "选中对象操作"; visibility = View.GONE
    }
    private var selectActionKey: String? = null
    private var annotationEditor: View? = null
    private var annotationBackdrop: View? = null
    private var annotationField: ScreenshotTextEditor? = null
    private var annotationInput: ScreenshotAnnotationInput? = null
    private var drawingControlKey: Pair<ScreenshotDrawingTool?,Int?>?=null
    private var resultTool: ScreenshotAction?=null
    private var toolbarAnchorKey=""
    private var toolbarAnchor=android.graphics.Point()
    /** Set once the user drags the grip: the bar then stays where they put it. */
    private var toolbarManual: android.graphics.Point?=null
    private val detailBody = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
    private var secondaryMaxHeight=dp(280)
    private val secondaryPanel=object : LinearLayout(context) {
        override fun onMeasure(widthSpec: Int,heightSpec: Int) {
            val parentLimit=if(View.MeasureSpec.getMode(heightSpec)==View.MeasureSpec.UNSPECIFIED) secondaryMaxHeight else View.MeasureSpec.getSize(heightSpec)
            super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(minOf(parentLimit,secondaryMaxHeight),View.MeasureSpec.AT_MOST))
        }
    }.apply {
        orientation=LinearLayout.VERTICAL; background=surface(PANEL_RADIUS); elevation=dp(6).toFloat()
        contentDescription="当前工具详细操作"; visibility=View.GONE
    }
    private val resultActions=LinearLayout(context).apply {
        gravity=Gravity.CENTER_VERTICAL; setPadding(dp(8),dp(4),dp(8),dp(4)); visibility=View.GONE
        contentDescription="识别与翻译详细操作"
    }
    private val languageDivider=View(context).apply { setBackgroundColor(COLOR_SEPARATOR); visibility=View.GONE }
    private var actionGeneration = 0
    private var disposed = false
    private var closeRequested = false
    private val actionWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val selector = ScreenTextRegionView(context,image.width,image.height,null,::select)
    private var smartPath = emptyList<ScreenshotDocRect>()
    private var smartIndex = 0
    private val smartBar = LinearLayout(context).apply {
        background = android.graphics.drawable.InsetDrawable(surface(20), 0, dp(4), 0, dp(4))
        setPadding(0, 0, 0, 0)
        elevation = dp(4).toFloat()
        visibility = View.GONE
    }
    private val smartTarget = smartButton(R.drawable.snow_select, "选择元素") {
        selector.smartWindowMode = !selector.smartWindowMode
        if (smartPath.isNotEmpty()) {
            commitSmartSelection(smartPath, if (selector.smartWindowMode) smartPath.lastIndex else 0)
        } else syncSmartSelection()
    }
    private val smartInner = smartButton(R.drawable.snow_region_reduce, "缩小一层") { commitSmartSelection(smartPath, smartIndex - 1) }
    private val smartOuter = smartButton(R.drawable.snow_region_add, "扩大一层") { commitSmartSelection(smartPath, smartIndex + 1) }

    internal fun enableSmartSelection(snapshot: ScreenshotSmartSelection?) {
        val frame = image
        var text: ScreenshotSmartText? = null
        selector.smartFrame = frame
        selector.smartHit = { x, y ->
            val native = snapshot?.path(x, y, frame.width, frame.height).orEmpty()
            text?.refine(x, y, native) ?: native
        }
        selector.onSmartCommit = ::commitSmartSelection
        if (picked == null) status.text = "轻点选区，按住后滑动探查，也可直接拖框"
        syncSmartSelection()
        val input = runCatching { frame.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        actionWorker.execute {
            val geometry = try { runCatching { ScreenshotSmartText.detect(input) }.getOrNull() }
                finally { input.recycle() }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (disposed || image !== frame || geometry == null) return@post
                text = geometry
                selector.refineLastSmartTap()
            }
        }
    }

    private fun smartButton(icon: Int, label: String, action: () -> Unit) = android.widget.ImageButton(context).apply {
        setImageResource(icon)
        setColorFilter(COLOR_TEXT)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(13), dp(13), dp(13), dp(13))
        background = RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), null, surface(20))
        contentDescription = label
        tooltipText = label
        isFocusable = true
        setOnClickListener { action() }
        smartBar.addView(this, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun commitSmartSelection(path: List<ScreenshotDocRect>, index: Int) {
        val hit = path.getOrNull(index) ?: return
        selector.selectRegion(hit.toRect())
        smartPath = path
        smartIndex = index
        syncSmartSelection()
        placeControls()
    }

    private fun syncSmartSelection() {
        val visible = selector.smartHit != null && !busy && !editing && !translating &&
            resultTool == null && drawing.tool == null && !moveMode && regionShape == ScreenshotRegionShape.RECTANGLE &&
            captureRegion == null
        selector.smartEnabled = visible
        smartBar.visibility = if (visible) View.VISIBLE else View.GONE
        smartTarget.setImageResource(if (selector.smartWindowMode) R.drawable.ic_screenshot_fullscreen else R.drawable.snow_select)
        smartTarget.stateDescription = if (selector.smartWindowMode) "窗口" else "元素"
        smartTarget.contentDescription = if (selector.smartWindowMode) "切换到元素选择" else "切换到窗口选择"
        smartTarget.tooltipText = smartTarget.contentDescription
        smartInner.visibility = if (smartIndex > 0 && smartPath.size > 1) View.VISIBLE else View.GONE
        smartOuter.visibility = if (smartIndex < smartPath.lastIndex) View.VISIBLE else View.GONE
    }
    private val resultLayer = FrameLayout(context).apply { visibility = View.INVISIBLE }
    private fun surface(radius: Int) = GradientDrawable().apply { setColor(COLOR_PANEL); cornerRadius = dp(radius).toFloat() }
    /** Active tools paint a filled blue tile, matching Snow Shot's selected tool. */
    private fun activeSurface(radius: Int) = GradientDrawable().apply {
        setColor(COLOR_PRIMARY); cornerRadius = dp(radius).toFloat()
    }
    private val toolbar = ScreenshotToolbarPanel(context)
    /** A10/A11: the selection readout switches between physical pixels and density-independent dp. */
    private var logicalUnits = false
    private val status = TextView(context).apply {
        text = "拖动框选，可移动和拉边调整"; textSize = 12f; setTextColor(COLOR_TEXT)
        setPadding(dp(10),dp(6),dp(10),dp(6)); background = surface(PANEL_RADIUS)
        elevation = dp(6).toFloat(); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        isClickable = true
        setOnClickListener {
            if (selectActionsScroll.visibility == View.VISIBLE && regionOperation == null && picked != null) {
                logicalUnits = !logicalUnits; sync()
            }
        }
    }
    private fun selectionSize(rect: Rect): String {
        if (!logicalUnits) return "选区 ${rect.width()} × ${rect.height()} px"
        val density = context.resources.displayMetrics.density
        val scale = if (density > 0f) density else 1f
        return "选区 ${Math.round(rect.width() / scale)} × ${Math.round(rect.height() / scale)} dp"
    }
    private val full = Action(ScreenshotAction.FULL_SCREEN, R.drawable.ic_screenshot_fullscreen) { selectFull() }
    private val recognizeAction = Action(ScreenshotAction.RECOGNIZE, R.drawable.snow_recognize) { toggleRecognition() }
    private val saveAction = Action(ScreenshotAction.SAVE, R.drawable.snow_save) { beginSave() }
    private val copyAction = Action(ScreenshotAction.COPY_TEXT, R.drawable.snow_copy) { copyText() }
    private val editAction = Action(ScreenshotAction.EDIT, R.drawable.snow_edit) { setEditing(!editing, document = true) }
    private val translateAction = Action(ScreenshotAction.TRANSLATE, R.drawable.snow_translate) { requestTranslation() }
    private val eyeAction = Action(ScreenshotAction.SHOW_ORIGINAL, R.drawable.snow_eye) { toggleOriginal() }
    private val closeAction = Action(ScreenshotAction.CLOSE, R.drawable.snow_close, danger = true) {
        closeRequested = true
        translator.cancel()
        exit()
    }
    private fun editedLayer() = if (showingTranslation) translationLayer else textLayer
    private val undoAction = Action(ScreenshotAction.UNDO, R.drawable.snow_undo) { if (editing) editedLayer()?.undo() else drawing.undo() }
    private val redoAction = Action(ScreenshotAction.REDO, R.drawable.snow_redo) { if (editing) editedLayer()?.redo() else drawing.redo() }
    private val drawingActions = ScreenshotDrawingTools.actions.map { (tool,action) -> Action(action,ScreenshotToolbarEditor.icon(action)) { selectDrawing(tool) } }
    /** Moves recognised text blocks; a plain button because it is not a drawing tool. */
    private val textLayoutButton = ImageView(context).apply {
        setImageResource(R.drawable.snow_select); setColorFilter(COLOR_TEXT)
        setPadding(dp(10),dp(10),dp(10),dp(10)); isClickable = true; isFocusable = true
        contentDescription = "调整文字位置"; tooltipText = "拖动文字块移动，右下角改大小"
        setOnClickListener { toggleTextLayout() }
    }
    private val barcodeAction = Action(ScreenshotAction.BARCODE,R.drawable.snow_barcode) { scanBarcode() }
    private val pinAction = Action(ScreenshotAction.PIN,R.drawable.snow_pin) { pinImage() }
    private val shareAction = Action(ScreenshotAction.SHARE,R.drawable.snow_share) { shareImage() }
    private val scrollAction = Action(ScreenshotAction.SCROLL,R.drawable.snow_scroll) { chooseScrollAxis(false) }.apply {
        tooltipText = "长截屏，轻点纵向，长按选择方向"
        setOnLongClickListener { chooseScrollAxis(true); true }
    }
    private val recordAction = Action(ScreenshotAction.RECORD,R.drawable.snow_record) { requestRecording() }.apply {
        tooltipText = "录制选区，点按开始"
    }
    /** Edit-selection mode: add/subtract parts of the capture area, as Snow Shot's move bar does. */
    private val moveAction = Action(ScreenshotAction.MOVE,R.drawable.snow_move) { toggleMoveMode() }
    private val actions = listOf(full,recognizeAction,saveAction,closeAction,copyAction,eyeAction,editAction,translateAction,undoAction,redoAction,barcodeAction,pinAction,shareAction,scrollAction,recordAction,moveAction)+drawingActions
    private var moveMode = false
    private var captureRegion: ScreenshotRegion?=null
    private var regionOperation: ScreenshotRegionOp?=null
    /** Region shape for the next selection, persisted like the original's region_type preference. */
    private var regionShape = loadRegionShapePreference()
    private val selectActions=LinearLayout(context).apply {
        // Two rows so every control is visible on a phone; the scroll wrapper only guards narrow cases.
        orientation=LinearLayout.VERTICAL; setPadding(dp(4),dp(2),dp(4),dp(2)); visibility=View.VISIBLE
        contentDescription="选区操作"
    }
    private val addRegionButton=ImageView(context)
    private val subtractRegionButton=ImageView(context)
    private val regionRectButton=ImageView(context)
    private val regionPolylineButton=ImageView(context)
    private val regionCurveButton=ImageView(context)
    private val regionFreehandButton=ImageView(context)
    private val recaptureButton=ImageView(context)
    private val recaptureDraftButton=TextView(context).apply {
        text = "上次文字草稿"
        contentDescription = text
        gravity = Gravity.CENTER
        setTextColor(COLOR_TEXT)
        textSize = 12f
        isFocusable = true
        visibility = View.GONE
        setOnClickListener {
            showValueEditor("上次截图文字草稿",recaptureDrafts.joinToString("\n\n"),"复制") { value ->
                runCatching { context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("截图文字草稿",value)) }
                    .onSuccess { showFeedback("已复制文字草稿") }
                    .onFailure { showFeedback("复制失败，请重试") }
            }
        }
    }
    private val cursorButton=ImageView(context)
    private val qrButton=ImageView(context)
    private val pixelPhysicalButton=ImageView(context)
    private val pixelLogicalButton=ImageView(context)
    private val hideBarButton=ImageView(context)
    private var selectionReadoutHidden = false
    private val selectActionsScroll=android.widget.HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled=false; visibility=View.GONE
    }
    init {
        root.addView(content,FrameLayout.LayoutParams(-1,-1))
        drawing.colorSamplingBase={ if(resultLayer.visibility==View.VISIBLE) (if(showingTranslation) translationLayer else textLayer)?.render(Rect(0,0,image.width,image.height)) else null }
        content.addView(background,FrameLayout.LayoutParams(-1,-1))
        content.addView(selector,FrameLayout.LayoutParams(-1,-1))
        content.addView(resultLayer,FrameLayout.LayoutParams(-1,-1))
        content.addView(drawing,FrameLayout.LayoutParams(-1,-1))
        selector.onRegionMoved = ::select
        selector.onRegionOperation = ::applyRegionOperation
        selector.onDraftCommit = ::commitRegionShape
        selector.regionShape = regionShape
        selector.contentDescription = "拖动框选截图区域，可在框内移动或拉边调整，松手后可识别或保存"
        selector.setOnTouchListener { _,_ -> busy || editing || translating || project!=null }
        root.addView(toolbar,FrameLayout.LayoutParams(dp(200),dp(BUTTON+PANEL_V*2)))
        root.addView(status,FrameLayout.LayoutParams(-2,-2))
        root.addView(smartBar,FrameLayout.LayoutParams(-2,-2))
        detailBody.addView(drawingStyles,LinearLayout.LayoutParams(0,-2,1f))
        detailBody.addView(selectSideActions,LinearLayout.LayoutParams(dp(76),-2))
        secondaryPanel.addView(detailBody,LinearLayout.LayoutParams(-1,-2))
        initSelectionActions()
        selectActionsScroll.addView(selectActions,android.widget.FrameLayout.LayoutParams(-2,-2))
        secondaryPanel.addView(selectActionsScroll,LinearLayout.LayoutParams(-1,dp(80)))
        resultActions.addView(eyeAction,LinearLayout.LayoutParams(dp(44),dp(44)))
        resultActions.addView(View(context).apply { setBackgroundColor(COLOR_SEPARATOR) },LinearLayout.LayoutParams(dp(1),dp(16)).apply { marginStart=dp(8); marginEnd=dp(8) })
        resultActions.addView(editAction,LinearLayout.LayoutParams(dp(44),dp(44)))
        resultActions.addView(textLayoutButton,LinearLayout.LayoutParams(dp(44),dp(44)))
        resultActions.addView(copyAction,LinearLayout.LayoutParams(dp(44),dp(44)))
        secondaryPanel.addView(resultActions,LinearLayout.LayoutParams(-1,-2))
        root.addView(secondaryPanel,FrameLayout.LayoutParams(dp(320),-2))
        drawing.onTextRequest = { point,index ->
            beginDrawingText(point,index)
        }
        toolbar.onDragStart = { closeStack() }
        toolbar.onDrag = ::dragToolbar
        root.setOnApplyWindowInsetsListener { _,insets ->
            bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
            applyImeShift(); placeControls(); insets
        }
        root.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> placeControls() }
        restoreProject()
        sync()
    }
    /** Reopen a saved record: same base image, same marks, same text, viewport no longer draggable. */
    private fun restoreProject() {
        val restored=project ?: return
        val document=restored.document
        picked=document.viewport.toRect()
        captureRegion=document.regionOperands?.let { value ->
            runCatching { ScreenshotRegion.fromJson(org.json.JSONArray(value)) }.getOrNull()
        } ?: document.regions.takeIf { it.isNotEmpty() }?.let { ScreenshotRegion.ofRects(it.map(ScreenshotDocRect::toRect)) }
        drawing.region=picked?.let(::Rect)
        drawing.savedPixelUnit=document.drawingUnit
        drawing.savedFontUnit=document.fontUnit
        drawing.history.load(document.objects, document.watermark ?: ScreenshotWatermark())
        drawing.invalidate()
        picked?.let { selector.selectRegion(Rect(it)) }
        captureRegion?.let { if (it.isCustom) selector.showRegion(it.path()) else selector.showParts(it.rects) }
        drawing.tool=ScreenshotDrawingTool.SELECT
        status.visibility=View.GONE
        val text=document.text ?: return
        if(text.blocks.isEmpty()) return
        resultTool=ScreenshotAction.RECOGNIZE
        val layer=ScreenshotTextLayer(context,image,text.blocks,::textChanged,{ setEditing(true,document = true) })
        layer.historyChanged=::sync
        textLayer=layer
        resultLayer.removeAllViews()
        resultLayer.addView(layer,FrameLayout.LayoutParams(-1,-1))
        if(text.translation.isNotEmpty()) {
            val translated=ScreenshotTextLayer(context,image,text.translation,{},{ setEditing(true,document = true) },
                fitToBlock=true,translationRegion=picked)
            translated.historyChanged=::sync
            translationLayer=translated
            resultLayer.addView(translated,FrameLayout.LayoutParams(-1,-1))
            translationComplete=true
        }
        showingTranslation=text.mode==ScreenshotTextMode.TRANSLATION && translationLayer!=null
        if(showingTranslation) { textLayer?.visibility=View.GONE; showPairBar() } else translationLayer?.visibility=View.GONE
        resultLayer.visibility=View.VISIBLE
        selector.visibility=View.VISIBLE
    }
    /** The style card only exists when the active tool really has options; an empty card is a white bar. */
    private fun drawingStyleCardVisible(): Boolean =
        drawing.tool!=null && annotationEditor==null &&
            (drawingStyles.getChildAt(0) as? ScreenshotDrawingControls)?.hasContent==true
    private fun state() = ScreenshotToolbarState(
        picked != null,textLayer != null,busy,editing,showingOriginal,translating || translateAfterRecognition,showingTranslation,
        drawing.history.canUndo || drawing.history.canRedo
    )
    private fun sync() {
        val state = state()
        eyeAction.active = showingOriginal
        recognizeAction.active=resultTool==ScreenshotAction.RECOGNIZE
        editAction.active = editing
        translateAction.active = resultTool==ScreenshotAction.TRANSLATE
        if (busy || editing || translating || resultTool!=null || moveMode) drawing.tool = null
        drawingActions.forEach { it.active = ScreenshotDrawingTools.actions[drawing.tool] == it.action }
        drawing.visibility = if (showingOriginal || showingTranslation || translating || editing) View.INVISIBLE else View.VISIBLE
        drawingStyles.visibility = if (drawingStyleCardVisible()) View.VISIBLE else View.GONE
        syncSelectSideActions()
        translateAction.setImageResource(R.drawable.snow_translate)
        actions.forEach {
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            val detail=it.action in ScreenshotToolbarState.secondaryActions
            it.visibility = if (if(detail) resultTool!=null && state.hasText else state.mainVisible(it.action)) View.VISIBLE else View.GONE
            it.isEnabled = if(detail) state.enabled(it.action) else state.mainEnabled(it.action)
            val label = state.label(it.action)
            it.contentDescription = label; it.tooltipText = label
            it.refreshTint()
        }
        undoAction.isEnabled=state.mainEnabled(ScreenshotAction.UNDO) && (if (editing) editedLayer()?.canUndo==true else drawing.history.canUndo)
        redoAction.isEnabled=state.mainEnabled(ScreenshotAction.REDO) && (if (editing) editedLayer()?.canRedo==true else drawing.history.canRedo)
        scrollAction.isEnabled = scrollAction.isEnabled && onScrollRequested != null
        recordAction.isEnabled = recordAction.isEnabled && onRecordRequested != null
        if(annotationEditor!=null) actions.filter { it.action!=ScreenshotAction.CLOSE }.forEach { it.isEnabled=false }
        rebuildToolbar()
        val working = busy || translating
        pairBar?.visibility = if (resultTool==ScreenshotAction.TRANSLATE && !editing) View.VISIBLE else View.GONE
        pairBar?.isEnabled=!working
        languageDivider.visibility=if(pairBar?.visibility==View.VISIBLE) View.VISIBLE else View.GONE
        resultActions.visibility=if(resultTool!=null && textLayer!=null && annotationEditor==null) View.VISIBLE else View.GONE
        moveAction.active=moveMode
        val showSelection=moveMode && picked!=null && !busy && !editing && !translating && resultTool==null && annotationEditor==null
        selectActionsScroll.visibility=if(showSelection) View.VISIBLE else View.GONE
        recaptureButton.isEnabled = showSelection && canRecapture()
        recaptureDraftButton.visibility = if(recaptureDrafts.isEmpty()) View.GONE else View.VISIBLE
        applyCellStyle(addRegionButton,regionOperation==ScreenshotRegionOp.ADD)
        applyCellStyle(subtractRegionButton,regionOperation==ScreenshotRegionOp.SUBTRACT)
        applyCellStyle(regionRectButton,regionShape==ScreenshotRegionShape.RECTANGLE)
        applyCellStyle(regionPolylineButton,regionShape==ScreenshotRegionShape.POLYLINE)
        applyCellStyle(regionCurveButton,regionShape==ScreenshotRegionShape.CURVE)
        applyCellStyle(regionFreehandButton,regionShape==ScreenshotRegionShape.FREEHAND)
        applyCellStyle(hideBarButton, selectionReadoutHidden)
        if(showSelection && regionOperation==null) picked?.let {
            status.text=selectionSize(it)
            status.contentDescription="${selectionSize(it)}，点击切换单位"
            status.visibility=if(selectionReadoutHidden) View.GONE else View.VISIBLE
        }
        secondaryPanel.visibility=if(annotationEditor==null && (drawingStyles.visibility==View.VISIBLE || resultActions.visibility==View.VISIBLE ||
            selectActionsScroll.visibility==View.VISIBLE || pairBar?.visibility==View.VISIBLE)) View.VISIBLE else View.GONE
        if (working || showingTranslation) status.visibility = View.GONE
        syncSmartSelection()
        placeControls()
    }
    /** Snow Shot's move-tool bar: icon controls only, no text labels. */
    /** Second-row buttons are outlined tiles: grey border idle, blue border and icon when active. */
    private fun applyCellStyle(view: ImageView,active: Boolean) {
        val stroke=if(active) COLOR_PRIMARY else COLOR_SEPARATOR
        val shape=GradientDrawable().apply {
            setColor(0x00000000); cornerRadius=dp(8).toFloat()
            setStroke(dp(1).coerceAtLeast(1),stroke)
        }
        view.background=RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF),shape,null)
        view.setColorFilter(if(active) COLOR_PRIMARY else COLOR_TEXT,PorterDuff.Mode.SRC_IN)
    }
    private fun initSelectionActions() {
        fun icon(view: ImageView,res: Int,label: String,run:()->Unit) {
            view.setImageResource(res)
            view.setPadding(dp(7),dp(7),dp(7),dp(7))
            view.isClickable=true; view.isFocusable=true
            view.contentDescription=label; view.tooltipText=label
            applyCellStyle(view,false)
            view.setOnClickListener { run() }
        }
        icon(addRegionButton,R.drawable.snow_region_add,"添加截图区域") { toggleRegionOperation(ScreenshotRegionOp.ADD) }
        icon(subtractRegionButton,R.drawable.snow_region_reduce,"减少截图区域") { toggleRegionOperation(ScreenshotRegionOp.SUBTRACT) }
        icon(regionRectButton,R.drawable.snow_region_rect,"矩形截图区域") { setRegionShape(ScreenshotRegionShape.RECTANGLE) }
        icon(regionPolylineButton,R.drawable.snow_region_polyline,"折线截图区域") { setRegionShape(ScreenshotRegionShape.POLYLINE) }
        icon(regionCurveButton,R.drawable.snow_region_curve,"曲线截图区域") { setRegionShape(ScreenshotRegionShape.CURVE) }
        icon(regionFreehandButton,R.drawable.snow_region_freehand,"自由手绘截图区域") { setRegionShape(ScreenshotRegionShape.FREEHAND) }
        icon(cursorButton,R.drawable.snow_cursor,"捕捉光标，暂未支持") { showFeedback("捕捉光标暂未支持") }
        icon(recaptureButton,R.drawable.snow_recapture,"重新截图") { if (canRecapture()) onRecaptureRequested?.invoke() }
        icon(qrButton,R.drawable.snow_barcode,"显示二维码") { scanBarcode() }
        icon(pixelPhysicalButton,R.drawable.snow_pixel_physical,"物理像素单位") { showFeedback("像素单位切换暂未支持") }
        icon(pixelLogicalButton,R.drawable.snow_pixel_logical,"逻辑像素单位") { showFeedback("像素单位切换暂未支持") }
        icon(hideBarButton,R.drawable.snow_eye_off,"隐藏选择工具栏") {
            selectionReadoutHidden = !selectionReadoutHidden
            val label = if (selectionReadoutHidden) "显示选择工具栏" else "隐藏选择工具栏"
            hideBarButton.contentDescription = label
            hideBarButton.tooltipText = label
            sync()
        }
        val row1=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        val row2=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        selectActions.addView(row1,LinearLayout.LayoutParams(-2,dp(36)))
        selectActions.addView(row2,LinearLayout.LayoutParams(-2,dp(36)).apply { topMargin=dp(4) })
        fun cell(row: LinearLayout,view: View,width: Int)=row.addView(view,
            LinearLayout.LayoutParams(width,dp(36)).apply { marginEnd=dp(4) })
        fun divider(row: LinearLayout) {
            val box=FrameLayout(context)
            box.addView(View(context).apply { setBackgroundColor(COLOR_SEPARATOR) },
                FrameLayout.LayoutParams(dp(1),dp(16),Gravity.CENTER))
            cell(row,box,dp(9))
        }
        cell(row1,addRegionButton,dp(36)); cell(row1,subtractRegionButton,dp(36)); divider(row1)
        for(button in listOf(regionRectButton,regionPolylineButton,regionCurveButton,regionFreehandButton)) cell(row1,button,dp(36))
        for(button in listOf(cursorButton,recaptureButton,qrButton)) cell(row2,button,dp(36))
        divider(row2)
        cell(row2,pixelPhysicalButton,dp(36)); cell(row2,pixelLogicalButton,dp(36)); divider(row2)
        cell(row2,hideBarButton,dp(36))
        cell(row2,recaptureDraftButton,dp(98))
    }
    /** Edit-selection mode mirrors Snow Shot's move tool: the bar owns the add/subtract controls. */
    private fun toggleMoveMode() {
        if(busy || translating || editing) return
        moveMode=!moveMode
        if(!moveMode) setRegionOperation(null)
        else { drawing.tool=null; resultTool=null; showingOriginal=false; showingTranslation=false; rebuildDrawingStyles() }
        moveAction.active=moveMode
        sync()
    }
    private fun setRegionOperation(op: ScreenshotRegionOp?) {
        regionOperation=op
        selector.opMode=op!=null
        if(op==null) { status.visibility=View.GONE; return }
        drawing.tool=null
        status.text=if(op==ScreenshotRegionOp.ADD) "拖动要加进来的区域" else "拖动要去掉的区域"
        status.visibility=View.VISIBLE
    }
    private fun toggleRegionOperation(op: ScreenshotRegionOp) {
        if(busy || translating || editing || picked==null) return
        setRegionOperation(if(regionOperation==op) null else op)
        rebuildDrawingStyles(); sync()
    }
    private fun applyRegionOperation(box: Rect) {
        applyRegionShape(rectVertices(box), ScreenshotRegionShape.RECTANGLE)
    }

    private fun rectVertices(box: Rect) = listOf(
        PointF(box.left.toFloat(), box.top.toFloat()), PointF(box.right.toFloat(), box.top.toFloat()),
        PointF(box.right.toFloat(), box.bottom.toFloat()), PointF(box.left.toFloat(), box.bottom.toFloat()))

    /** Region-type switching: construction drops, a confirmed selection survives (setRegionType). */
    private fun setRegionShape(value: ScreenshotRegionShape) {
        if (busy || translating || editing || project != null) return
        if (regionShape == value) return
        selector.cancelConstruction()
        selector.regionShape = value
        regionShape = value
        context.getSharedPreferences("screenshot-region-type", Context.MODE_PRIVATE).edit()
            .putString("screenshot_selection/region_type", value.id).apply()
        sync()
    }

    private fun loadRegionShapePreference() = ScreenshotRegionShape.fromId(
        context.getSharedPreferences("screenshot-region-type", Context.MODE_PRIVATE)
            .getString("screenshot_selection/region_type", null))

    /** A finished custom shape lands here: replace the selection, or feed the armed operation. */
    private fun commitRegionShape(vertices: List<PointF>, shape: ScreenshotRegionShape) {
        if (project != null || busy || translating || editing) return
        if (regionOperation != null) { applyRegionShape(vertices, shape); return }
        val next = ScreenshotRegion.ofShape(vertices, shape)
        if (next.isEmpty) return
        captureRegion = next.takeIf { it.isCustom || !it.isRectangle }
        picked = Rect(next.bounds)
        drawing.region = picked?.let(::Rect); drawing.invalidate()
        clearText()
        selector.showRegion(next.path())
        status.visibility = View.GONE
        moveMode = false; moveAction.active = false
        sync()
    }

    private fun applyRegionShape(vertices: List<PointF>, shape: ScreenshotRegionShape) {
        val op=regionOperation ?: return
        val base=captureRegion ?: picked?.let { ScreenshotRegion.ofRects(listOf(Rect(it))) } ?: return
        val next=base.combined(vertices, shape, op)
        if(next.isEmpty) { showFeedback("选区已为空，请重新框选"); picked?.let { selector.showParts(base.rects) }; return }
        captureRegion=next.takeIf { it.isCustom || !it.isRectangle }
        picked=Rect(next.bounds)
        drawing.region=picked?.let(::Rect); drawing.invalidate()
        clearText()
        if (next.isCustom) selector.showRegion(next.path()) else selector.showParts(next.rects)
        status.visibility=View.GONE
        sync()
    }
    private fun canRecapture() = project == null && onRecaptureRequested != null && !recapturing &&
        !busy && !editing && !translating && !translateAfterRecognition && annotationEditor == null &&
        moveMode && picked != null && regionOperation == null && !selector.selectionInProgress

    internal fun hideForRecapture(): Boolean {
        if (!canRecapture()) return false
        recapturing = true
        closeStack()
        root.visibility = View.INVISIBLE
        return true
    }

    internal fun replaceCaptureSource(next: Bitmap, snapshot: ScreenshotSmartSelection?) {
        listOf("识别文字" to textLayer, "译文" to translationLayer).forEach { (label, layer) ->
            if (layer != null && (layer.canUndo || layer.canRedo)) {
                layer.text().takeIf { it.isNotBlank() }?.let { recaptureDrafts.add("$label\n$it") }
            }
        }
        clearText()
        translationLayer = null
        onText("")
        image = next
        background.setImageBitmap(next)
        drawing.replaceSource(next)
        smartPath = emptyList()
        smartIndex = 0
        selector.smartFrame = null
        selector.smartHit = null
        enableSmartSelection(snapshot)
    }

    internal fun restoreAfterRecapture(error: String?) {
        recapturing = false
        root.visibility = View.VISIBLE
        root.requestFocus()
        sync()
        showFeedback(error ?: if (recaptureDrafts.isEmpty()) "已重新截图" else "已重新截图，已编辑文字保留在「上次文字草稿」")
    }

    private fun chooseScrollAxis(showDirections: Boolean) {
        val capture = onScrollRequested ?: return
        val crop = picked ?: return
        if (busy || editing || translating || annotationEditor != null) return
        if (captureRegion?.let { it.isCustom || it.rects.size != 1 } == true) {
            showFeedback("长截屏需要矩形选区")
            return
        }
        val top = ScreenshotScrollCapture.controlTop(crop.top,crop.bottom,bars.top,image.height-bars.bottom,
            dp(ScreenshotScrollCapture.BAR_DP))
        if (top == null) {
            showFeedback("请在选区上方或下方留出长截屏控制栏的空间")
            return
        }
        if (!showDirections) {
            capture(Rect(crop), top, false)
            return
        }
        android.widget.PopupMenu(context,toolbar).apply {
            menu.add(0,1,0,"纵向长截屏")
            menu.add(0,2,1,"横向长截屏")
            setOnMenuItemClickListener { choice ->
                capture(Rect(crop),top,choice.itemId == 2)
                true
            }
            show()
        }
    }

    private fun requestRecording() {
        val start = onRecordRequested ?: return
        val crop = picked ?: return
        if (busy || editing || translating || annotationEditor != null) return
        if (captureRegion?.let { it.isCustom || it.rects.size != 1 } == true) {
            showFeedback("录屏需要矩形选区")
            return
        }
        start(Rect(crop))
    }

    internal fun setScrollCaptureActive(active: Boolean, message: String? = null) {
        root.visibility = if (active) View.INVISIBLE else View.VISIBLE
        if (!active) {
            root.requestFocus()
            message?.let(::showFeedback)
        }
    }
    private fun rebuildToolbar(availableWidth: Int = root.width) {
        closeStack()
        actions.filter { it.action !in ScreenshotToolbarState.secondaryActions }.forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        toolbarAvailable=(availableWidth.takeIf { it>0 } ?: image.width)-bars.left-bars.right-dp(16)
        val cells=mutableListOf<Pair<View,Boolean>>()
        for(column in toolbarLayout.main(state())) {
            if(column==listOf(ScreenshotAction.CLOSE)) continue
            if(column==listOf(ScreenshotAction.SEPARATOR)) {
                cells.add(View(context).apply { setBackgroundColor(COLOR_SEPARATOR) } to true); continue
            }
            val visible=column.map { id -> actions.first { it.action==id } }
            val remembered=toolMemory?.entry(column) ?: groupEntries[column.toSet()]
            val entry=visible.firstOrNull { it.action==remembered } ?: visible.first()
            val cell=if(visible.size==1) entry else ScreenshotToolGroupView(context,entry,
                "展开工具组："+visible.joinToString("、") { it.contentDescription }) { anchor -> showStack(anchor,visible) }
            cells.add(cell to false)
        }
        val availableHeight=(root.height.takeIf { it>0 } ?: image.height)-maxOf(bars.bottom,imeBottom)-bars.top
        toolbar.setTools(cells,closeAction,toolbarAvailable,availableHeight)
        toolbarWidth=toolbar.packedWidth; toolbarHeight=toolbar.packedHeight
    }
    private fun closeStack() {
        stackPopup?.let { root.removeView(it) }; stackPopup = null
        stackBackdrop?.let { root.removeView(it) }; stackBackdrop = null
        stackGroup = null
        drawingStyles.visibility = if (drawingStyleCardVisible()) View.VISIBLE else View.GONE
    }
    private fun showStack(anchor: View, group: List<Action>) {
        val ids=group.map { it.action }.toSet()
        if(stackPopup!=null && stackGroup==ids) { closeStack(); return }
        closeStack()
        stackGroup=ids
        drawingStyles.visibility = View.GONE
        val available = (root.width-bars.left-bars.right-dp(16)).coerceAtLeast(dp(48))
        val columns = ((available-dp(16))/dp(48)).coerceIn(1,group.size)
        val panelWidth = dp(16+columns*48)
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8),dp(4),dp(8),dp(4)) }
        for (chunk in group.chunked(columns)) {
            val row = LinearLayout(context)
            chunk.forEach { action -> row.addView(ImageView(context).apply {
                contentDescription = action.contentDescription; tooltipText = contentDescription
                setImageDrawable(action.drawable.constantState?.newDrawable()?.mutate()); setColorFilter(if (action.active) COLOR_PRIMARY else COLOR_TEXT)
                setPadding(dp(12),dp(12),dp(12),dp(12)); isEnabled = action.isEnabled
                background = RippleDrawable(ColorStateList.valueOf(0x14000000),null,null)
                setOnClickListener {
                    groupEntries[group.map { it.action }.toSet()] = action.action
                    closeStack(); action.performClick(); sync()
                }
            },LinearLayout.LayoutParams(dp(48),dp(48))) }
            grid.addView(row)
        }
        val safeBottom = root.height-maxOf(bars.bottom,imeBottom)-dp(8)
        val regionTop = picked?.let { it.top*root.height/image.height } ?: toolbar.translationY.toInt()
        val regionBottom = picked?.let { it.bottom*root.height/image.height } ?: regionTop
        val toolbarAbove = toolbar.translationY+toolbarHeight <= regionTop
        val toolbarBelow = toolbar.translationY >= regionBottom
        val topEdge = if (toolbarAbove) toolbar.translationY.toInt() else regionTop
        val bottomEdge = if (toolbarBelow) toolbar.translationY.toInt()+toolbarHeight else regionBottom
        val topSpace = (topEdge-bars.top-dp(16)).coerceAtLeast(0)
        val bottomSpace = (safeBottom-bottomEdge-dp(8)).coerceAtLeast(0)
        val external = maxOf(topSpace,bottomSpace) >= dp(56)
        val limit = if (external) maxOf(topSpace,bottomSpace) else
            ((safeBottom-bars.top)*.25f).toInt().coerceAtLeast(dp(56))
        val panelHeight = minOf(dp(8+((group.size+columns-1)/columns)*48),dp(200),limit)
        // Keep the main bar above the dismiss surface so its group triggers can toggle or switch.
        stackBackdrop = View(context).apply { setOnClickListener { closeStack() } }.also {
            root.addView(it,root.indexOfChild(toolbar),FrameLayout.LayoutParams(-1,-1))
        }
        val panel = android.widget.ScrollView(context).apply {
            background = surface(PANEL_RADIUS); elevation = dp(8).toFloat(); addView(grid)
            contentDescription = "工具组选项"
        }
        stackPopup = panel
        root.addView(panel,FrameLayout.LayoutParams(panelWidth,panelHeight))
        val anchorBounds=Rect(0,0,anchor.width,anchor.height)
        root.offsetDescendantRectToMyCoords(anchor,anchorBounds)
        val x = anchorBounds.left.coerceIn(bars.left+dp(8),maxOf(bars.left+dp(8),root.width-bars.right-panelWidth-dp(8)))
        val below = toolbar.translationY.toInt()+toolbarHeight+dp(8)
        val y = if (external) {
            if (topSpace >= bottomSpace) topEdge-panelHeight-dp(8) else bottomEdge+dp(8)
        } else if (below+panelHeight <= safeBottom) below else toolbar.translationY.toInt()-panelHeight-dp(8)
        panel.translationX = x.toFloat()
        panel.translationY = y.coerceIn(bars.top+dp(8),maxOf(bars.top+dp(8),safeBottom-panelHeight)).toFloat()
    }
    private fun select(region: Rect) {
        // A reopened project keeps the viewport it was saved with; text and marks stay valid.
        if(project!=null) return
        // While adding or subtracting, the drag is reported through onRegionOperation instead.
        if(regionOperation!=null) return
        smartPath = emptyList()
        captureRegion=null
        moveMode=false; moveAction.active=false
        picked = region.takeIf { it.width() >= 2 && it.height() >= 2 }?.let(::Rect)
        drawing.region = picked?.let(::Rect); drawing.invalidate()
        // Blocks belong to the previous region; moving the box invalidates them.
        clearText()
        status.text = "拖动框选，可移动和拉边调整"
        status.visibility = if (picked == null) View.VISIBLE else View.GONE
        sync()
    }
    private fun selectDrawing(tool: ScreenshotDrawingTool) {
        if (busy || translating || editing || picked == null) return
        if(moveMode) { moveMode=false; setRegionOperation(null) }
        resultTool=null; showingOriginal=false; showingTranslation=false
        resultLayer.visibility=if(textLayer!=null) View.VISIBLE else View.INVISIBLE
        textLayer?.visibility=View.VISIBLE; translationLayer?.visibility=View.GONE; pairBar?.visibility=View.GONE
        drawing.tool = if (drawing.tool == tool) ScreenshotDrawingTool.SELECT else tool
        rebuildDrawingStyles(); sync()
    }
    private fun rebuildDrawingStyles() {
        val scroll=drawingStyles.scrollY
        val key=drawing.inspectedTool to drawing.selectedIndex
        val controls=(drawingStyles.getChildAt(0) as? ScreenshotDrawingControls)?.takeIf { drawingControlKey==key }?.also { it.refresh() }
            ?: ScreenshotDrawingControls(context,drawing,::editDrawingValue,showSelectActions=false,
                showDrawTemplates=::showDrawTemplates).also {
                drawingStyles.removeAllViews(); drawingStyles.addView(it); drawingControlKey=key
            }
        drawingStyles.post { drawingStyles.scrollTo(0,scroll) }
        drawingStyles.visibility=if(controls.hasContent && drawing.tool!=null && annotationEditor==null) View.VISIBLE else View.GONE
        syncSelectSideActions()
        placeControls()
    }
    /**
     * Selected-object actions sit in the detail bar's right column: the rows above rarely fill the
     * card width, and a full-width bottom row cost a whole 44dp band for two chips.
     */
    private fun syncSelectSideActions() {
        val key = drawing.selected?.takeIf { drawing.selectedIndices.size==1 }?.let { "${it.tool}:${it.id}:${drawing.overlapCount}" }
        if (key != selectActionKey) { selectActionKey = key; rebuildSelectActions() }
        selectSideActions.visibility = if (key != null && drawingStyles.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }
    private fun rebuildSelectActions() {
        selectSideActions.removeAllViews()
        if (drawing.selected == null) return
        if (drawing.overlapCount > 1)
            selectSideActions.addView(selectAction("切换重叠","切换重叠对象，共 ${drawing.overlapCount} 个") { drawing.cycleSelection(); sync() })
        selectSideActions.addView(selectAction("删除标注","删除选中的标注") { drawing.deleteSelected(); sync() })
    }
    private fun selectAction(label: String, description: String, run: () -> Unit) = TextView(context).apply {
        text=label; textSize=12f; gravity=Gravity.CENTER; setTextColor(COLOR_TEXT)
        contentDescription=description; tooltipText=description; isFocusable=true; minHeight=dp(30)
        setPadding(dp(4),dp(4),dp(4),dp(4))
        background=RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF),null,null)
        setOnClickListener { run() }
    }
    private fun closeDrawingEditor() {
        annotationInput?.close(); annotationInput=null
        annotationEditor?.let { root.removeView(it) }; annotationEditor=null; annotationField=null
        annotationBackdrop?.let { root.removeView(it) }; annotationBackdrop=null
        hideKeyboard()
    }
    private fun beginDrawingText(point: ScreenshotDrawingPoint,index: Int?) {
        closeStack(); closeDrawingEditor()
        val input=ScreenshotAnnotationInput(root,drawing,point,index,{
            val rect=drawing.textBounds(point,index)
            android.graphics.RectF(rect.left*content.width/image.width,rect.top*content.height/image.height,
                rect.right*content.width/image.width,rect.bottom*content.height/image.height)
        }, {
            val top=toolbar.translationY+toolbarHeight+dp(8)
            android.graphics.RectF(toolbar.translationX,top,toolbar.translationX+maxOf(toolbarWidth,dp(176)),top+dp(44))
        }) { closeDrawingEditor(); sync() }
        annotationInput=input; annotationEditor=input.actions; annotationField=input.field
        drawingStyles.visibility=View.GONE; sync()
    }
    private fun editDrawingValue(title: String,value: String,save: (String)->Unit) = showValueEditor(title,value,"保存",save=save)
    private val drawTemplates by lazy { ScreenshotDrawTemplateStore(context) }
    private var drawTemplatePopup: android.widget.PopupWindow? = null

    private fun showDrawTemplates(anchor: View) {
        if (!anchor.isAttachedToWindow) return
        val names = runCatching { drawTemplates.names() }.getOrElse {
            showFeedback("无法读取绘图模板"); return
        }
        drawTemplatePopup?.dismiss()
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8),dp(4),dp(8),dp(4)) }
        val scroll = android.widget.ScrollView(context).apply { addView(list) }
        val popup = android.widget.PopupWindow(scroll, minOf(dp(280),root.width-dp(16)).coerceAtLeast(1),
            dp((44 * (names.size + 1)).coerceIn(132,300)), true).apply {
            setBackgroundDrawable(surface(PANEL_RADIUS))
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
        }
        drawTemplatePopup = popup
        popup.setOnDismissListener { if(drawTemplatePopup === popup) drawTemplatePopup = null }
        fun action(label: String, run: () -> Unit) = selectAction(label,label,run).apply { minHeight=dp(44) }
        list.addView(action("将所选对象存为模板") {
            popup.dismiss()
            val template = drawing.selectedTemplate() ?: return@action
            val suffix = generateSequence(1) { it + 1 }.first { n -> names.none { it == "模板 $n" } }
            showValueEditor("模板名称", "模板 $suffix", "保存") { value ->
                runCatching { drawTemplates.save(value,template) }
                    .onSuccess { showFeedback("绘图模板已保存") }
                    .onFailure { showFeedback(it.message ?: "无法保存绘图模板") }
            }
        }.apply { isEnabled = drawing.selectedIndices.isNotEmpty(); alpha = if(isEnabled) 1f else .4f })
        for (name in names) {
            val row = LinearLayout(context)
            row.addView(action(name) {
                popup.dismiss()
                val inserted = runCatching { drawing.insertTemplate(drawTemplates.load(name)) }.getOrDefault(false)
                if (!inserted) showFeedback("无法插入绘图模板")
            },LinearLayout.LayoutParams(0,dp(44),1f))
            row.addView(action("删除") {
                list.removeAllViews()
                list.addView(TextView(context).apply { text="删除「$name」后无法撤销"; setTextColor(COLOR_TEXT); minHeight=dp(44) })
                list.addView(action("确认删除") {
                    popup.dismiss()
                    runCatching { drawTemplates.delete(name) }.onFailure { showFeedback("无法删除绘图模板") }
                })
                list.addView(action("取消") { popup.dismiss() })
            },LinearLayout.LayoutParams(dp(64),dp(44)))
            list.addView(row)
        }
        popup.showAtLocation(root,Gravity.CENTER,0,0)
    }

    private fun showValueEditor(title: String, value: String, positive: String, link: android.net.Uri? = null, save: (String)->Unit) {
        closeStack(); closeDrawingEditor()
        val panel=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL; background=surface(PANEL_RADIUS); elevation=dp(8).toFloat()
            setPadding(dp(8),dp(4),dp(8),dp(4))
        }
        panel.addView(TextView(context).apply { text=title; textSize=16f; setTextColor(COLOR_TEXT) })
        val field=ScreenshotTextEditor(context,value).apply { contentDescription=title }
        if(title=="模板名称") { field.isSingleLine = true; field.filters = arrayOf(android.text.InputFilter.LengthFilter(80)) }
        // The original stores font size as a double; the badge and the text tool clamp at different bounds.
        if(title=="字号") field.inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        else if(title=="序号") field.inputType=android.text.InputType.TYPE_CLASS_NUMBER
        panel.addView(field,LinearLayout.LayoutParams(-1,0,1f))
        val buttons=LinearLayout(context)
        fun button(label: String, run:()->Unit)=TextView(context).apply {
            text=label; textSize=16f; gravity=Gravity.CENTER; contentDescription=label; setTextColor(COLOR_PRIMARY)
            setOnClickListener { run() }
        }
        buttons.addView(button("取消") { closeDrawingEditor(); sync() },LinearLayout.LayoutParams(0,dp(44),1f))
        if(link!=null) buttons.addView(button("打开") {
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,link).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); exit() }
                .onFailure { showFeedback("没有可打开此链接的应用") }
        },LinearLayout.LayoutParams(0,dp(44),1f))
        buttons.addView(button(positive) {
            val result=field.text.toString()
            val fontTool=drawing.selected?.tool ?: drawing.tool
            val valid=when(title) {
                "颜色" -> result.matches(Regex("#[0-9a-fA-F]{6}"))
                "字号" -> ScreenshotFontSize.parse(fontTool,result)!=null
                "序号" -> result.toLongOrNull()?.let { it>=0 }==true
                else -> true
            }
            if(!valid) showFeedback(when(title) {
                "颜色" -> "请输入 #RRGGBB 颜色"
                "字号" -> "字号范围为 ${ScreenshotFontSize.format(ScreenshotFontSize.MIN)}–${ScreenshotFontSize.format(ScreenshotFontSize.max(fontTool))}"
                else -> "请输入非负整数"
            })
            else { closeDrawingEditor(); save(result); sync() }
        },LinearLayout.LayoutParams(0,dp(44),1f))
        panel.addView(buttons)
        annotationBackdrop=View(context).apply { setOnClickListener { } }.also { root.addView(it,FrameLayout.LayoutParams(-1,-1)) }
        annotationEditor=panel; annotationField=field
        root.addView(panel,FrameLayout.LayoutParams(minOf(dp(360),root.width.coerceAtLeast(dp(160))),dp(240)))
        drawingStyles.visibility=View.GONE; placeControls()
        field.requestFocus()
        sync()
        root.postDelayed({ if(annotationField===field) context.getSystemService(InputMethodManager::class.java).showSoftInput(field,InputMethodManager.SHOW_IMPLICIT) },80)
    }
    private fun selectFull() {
        if (busy || editing) return
        selector.selectRegion(Rect(0,0,image.width,image.height))
    }
    private fun composeCurrent(region: Rect): Bitmap? {
        val composed=if(textLayer!=null && resultLayer.visibility==View.VISIBLE)
            (if(showingTranslation) translationLayer else textLayer)?.render(region) else null
        return if(!showingOriginal && !showingTranslation) drawing.render(region,composed) else composed
    }
    private fun currentImage(): Bitmap {
        val region=picked ?: error("请先框选截图区域")
        val shaped=captureRegion
        val base=composeCurrent(region) ?: Bitmap.createBitmap(image,region.left,region.top,region.width(),region.height()).let {
            if(it===image) image.copy(Bitmap.Config.ARGB_8888,false) else it
        }
        if(shaped==null) return base
        return shaped.mask(if(base.isMutable) base else base.copy(Bitmap.Config.ARGB_8888,true),region)
    }
    private fun scanBarcode() {
        if(busy || translating || editing) return
        val bitmap=try { currentImage() } catch(error: Exception) { showFeedback("无法读取选区，请重试"); return }
        val token=++actionGeneration
        busy=true; sync()
        ScreenshotBarcode.scan(bitmap,{ codes ->
            if(token==actionGeneration && !disposed) {
                busy=false; sync()
                if(codes.isEmpty()) showFeedback("未识别到二维码或条码，可重新框选")
                else showValueEditor("识别结果",codes.joinToString("\n\n"),"复制",
                    codes.singleOrNull()?.let { android.net.Uri.parse(it) }?.takeIf { it.scheme in setOf("http","https") }) { text ->
                    runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("识别结果",text)) }
                        .onFailure { showFeedback("复制失败，请重试") }
                }
            }
        },{ message -> if(token==actionGeneration && !disposed) { busy=false; sync(); showFeedback(message) } })
    }
    private fun pinImage() {
        if(busy || translating || editing) return
        val region=picked ?: return
        if(content.width<=0 || content.height<=0) return
        val location=IntArray(2); content.getLocationOnScreen(location)
        val screenRect=Rect(location[0]+region.left*content.width/image.width,location[1]+region.top*content.height/image.height,
            location[0]+region.right*content.width/image.width,location[1]+region.bottom*content.height/image.height)
        try { ScreenshotPins.show(context,currentImage(),screenRect,currentDocument(region),image); exit() }
        catch(error: Exception) { showFeedback("无法贴图，请检查截图服务是否启用") }
    }
    private fun shareImage() {
        if(busy || translating || editing) return
        val bitmap=try { currentImage() } catch(error: Exception) { showFeedback("无法导出选区，请重试"); return }
        val token=++actionGeneration
        busy=true; sync()
        actionWorker.execute {
            try {
                val uri=ScreenshotShare.write(context,bitmap)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if(token==actionGeneration && !disposed) {
                        try { ScreenshotShare.open(context,uri); exit() }
                        catch(error: Exception) { busy=false; sync(); showFeedback("无法打开分享，请重试") }
                    }
                }
            } catch(error: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { if(token==actionGeneration && !disposed) { busy=false; sync(); showFeedback("导出失败，请检查可用空间") } }
            } finally { bitmap.recycle() }
        }
    }
    private fun beginRecognize(forTranslation: Boolean = false) {
        val region = picked ?: return
        android.util.Log.i("VoxFix","SCREENSHOT_OCR_TAP region=${region.width()}x${region.height()}")
        if (busy) return
        resultTool=if(forTranslation) ScreenshotAction.TRANSLATE else ScreenshotAction.RECOGNIZE
        setEditing(false)
        translateAfterRecognition = forTranslation
        discardRecognition = false
        busy = true; status.text = "正在识别…"; status.visibility = View.VISIBLE
        sync(); recognize(Rect(region))
    }
    fun textResult(blocks: List<ScreenshotTextBlock>) {
        busy = false
        val translateNext = translateAfterRecognition
        translateAfterRecognition = false
        if (discardRecognition) {
            discardRecognition = false
            sync()
            return
        }
        translationGeneration++
        translationComplete = false
        translating = false; showingTranslation = false
        translationLayer?.visibility = View.GONE
        pairBar?.visibility = View.GONE
        if (blocks.isEmpty()) {
            clearText()
            status.text = "未识别到文字，可重新框选"; status.visibility = View.VISIBLE
            sync(); return
        }
        if(resultTool==null) resultTool=ScreenshotAction.RECOGNIZE
        val layer = ScreenshotTextLayer(context,image,blocks,::textChanged, { setEditing(true, document = true) })
        layer.historyChanged=::sync
        translationLayer = null
        textLayer = layer; showingOriginal = false
        resultLayer.removeAllViews()
        resultLayer.addView(layer,FrameLayout.LayoutParams(-1,-1))
        resultLayer.visibility = View.VISIBLE
        // The capture range must stay visible; only the recognized text loses its own frame.
        selector.visibility = View.VISIBLE
        status.visibility = View.GONE
        sync()
        if (translateNext) { showPairBar(); startTranslation() }
    }
    fun failure(message: String) {
        busy = false
        translateAfterRecognition = false
        discardRecognition = false
        status.text = message; status.visibility = View.VISIBLE
        sync()
    }
    private fun textChanged(value: String) { if(!showingTranslation) translationComplete=false; onText(value) }
    private var translationGeneration = 0

    private fun requestTranslation() {
        if (translateAfterRecognition || translating) { abortTranslation(); return }
        if (busy || editing) return
        if (resultTool==ScreenshotAction.TRANSLATE && showingTranslation) { leaveResultTool(); return }
        resultTool=ScreenshotAction.TRANSLATE
        if (textLayer == null) { beginRecognize(forTranslation = true); return }
        if (textLayer?.textForTranslation().isNullOrBlank()) return
        showPairBar()
        if(translationComplete && translationLayer!=null) {
            showingOriginal=false; showingTranslation=true; resultLayer.visibility=View.VISIBLE
            translationLayer?.visibility=View.VISIBLE; textLayer?.visibility=View.GONE; sync(); return
        }
        startTranslation()
    }
    private fun toggleRecognition() {
        if(busy || translating || editing) return
        if(resultTool==ScreenshotAction.RECOGNIZE) { leaveResultTool(); return }
        resultTool=ScreenshotAction.RECOGNIZE
        if(textLayer==null) { beginRecognize(); return }
        showingTranslation=false; showingOriginal=false
        translationLayer?.visibility=View.GONE; textLayer?.visibility=View.VISIBLE; resultLayer.visibility=View.VISIBLE
        sync()
    }
    private fun leaveResultTool() {
        setEditing(false); resultTool=null; showingOriginal=true; showingTranslation=false
        translationLayer?.visibility=View.GONE; textLayer?.visibility=View.VISIBLE
        resultLayer.visibility=View.INVISIBLE; pairBar?.visibility=View.GONE; status.visibility=View.GONE
        sync()
    }

    private fun startTranslation() {
        val blocks = ScreenshotParagraphs.merge(textLayer?.currentBlocks() ?: return)
        resultTool=ScreenshotAction.TRANSLATE
        translationComplete = false
        val token = ++translationGeneration
        translating = true
        showingTranslation = false
        translationLayer?.let { resultLayer.removeView(it) }
        val streamingLayer=ScreenshotTextLayer(context,image,blocks, {}, { },fitToBlock=true,translationRegion=picked)
        for (index in 0 until streamingLayer.childCount) streamingLayer.getChildAt(index).isEnabled=false
        translationLayer=streamingLayer
        resultLayer.addView(streamingLayer,FrameLayout.LayoutParams(-1,-1))
        textLayer?.visibility = View.GONE
        showingOriginal=false
        resultLayer.visibility=View.VISIBLE
        status.visibility = View.GONE
        sync()
        android.util.Log.i("VoxFix", "TRANSLATE_TAP blocks=${blocks.size} pair=${pair.label}")
        translateScreenshotBlocks(translator, blocks, pair, { token == translationGeneration },
            result@{ result ->
                if (token != translationGeneration) return@result
                translating = false
                if (result.isEmpty()) {
                    showFeedback("翻译结果为空，请重试")
                } else {
                    translationLayer?.let { resultLayer.removeView(it) }
                    val layer = ScreenshotTextLayer(context,image,result, {}, { setEditing(true, document = true) }, fitToBlock = true, translationRegion=picked)
                    layer.historyChanged=::sync
                    translationLayer = layer
                    resultLayer.addView(layer, FrameLayout.LayoutParams(-1,-1))
                    textLayer?.visibility = View.GONE
                    showingOriginal = false
                    resultLayer.visibility = View.VISIBLE
                    showingTranslation = true
                    translationComplete = true
                }
                sync()
            },
            failure@{ message ->
                if (token != translationGeneration) return@failure
                translating = false
                translationLayer?.visibility=View.GONE
                textLayer?.visibility=View.VISIBLE
                status.text=message
                status.visibility=View.VISIBLE
                if (message == "翻译模型未下载") {
                    runCatching {
                        context.startActivity(android.content.Intent(context, ScreenTextSettingsActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                sync()
            }, update = { index,text ->
                if (token == translationGeneration) {
                    streamingLayer.updateLine(index,text)
                }
            })
    }

    private fun abortTranslation() {
        if (translateAfterRecognition) discardRecognition = true
        translateAfterRecognition = false
        translationGeneration++
        translating = false
        translator.cancel()
        resultTool=null; showingOriginal=true; resultLayer.visibility=View.INVISIBLE
        translationLayer?.visibility = View.GONE
        textLayer?.visibility = View.VISIBLE
        status.visibility = View.GONE
        sync()
    }

    private fun showPairBar() {
        val bar = pairBar ?: TranslatePairBar(
            context,
            onOpenSource = { if(!translating && !busy) pairBar?.showPicker(TranslatePairBar.Side.SOURCE, pair) },
            onSwap = { swapPair() },
            onOpenTarget = { if(!translating && !busy) pairBar?.showPicker(TranslatePairBar.Side.TARGET, pair) },
            onChoose = { language -> chooseLanguage(language) }
        ).also {
            pairBar = it
            secondaryPanel.addView(languageDivider,LinearLayout.LayoutParams(-1,dp(1).coerceAtLeast(1)).apply { marginStart=dp(12); marginEnd=dp(12) })
            secondaryPanel.addView(it,LinearLayout.LayoutParams(-1,dp(44)))
        }
        bar.showPair(pair)
        bar.visibility = View.VISIBLE
    }

    private fun chooseLanguage(language: TranslateLanguage) {
        if (translating || busy) return
        val side = pairBar?.currentSide() ?: return
        val next = (if (side == TranslatePairBar.Side.SOURCE) pair.copy(source = language) else pair.copy(target = language)).normalized()
        if (next == pair) { pairBar?.showPair(pair); return }
        pair = next
        persistPair()
        pairBar?.showPair(pair)
        retranslate()
    }

    private fun swapPair() {
        if (translating || busy) return
        pair = pair.swapped().normalized()
        persistPair()
        pairBar?.showPair(pair)
        retranslate()
    }

    private fun persistPair() {
        ScreenTextSettingsActivity.preferences(context).edit()
            .putString("translate.source", pair.source.preference)
            .putString("translate.target", pair.target.preference)
            .apply()
    }

    private fun retranslate() {
        if (translating || busy) return
        if (textLayer?.textForTranslation().isNullOrBlank()) return
        startTranslation()
    }

    private fun clearText() {
        resultTool=null
        translationComplete = false
        translateAfterRecognition = false
        discardRecognition = false
        translationGeneration++
        translating = false; showingTranslation = false
        translationLayer?.visibility = View.GONE
        pairBar?.visibility = View.GONE
        if (textLayer == null && resultLayer.childCount == 0) return
        setEditing(false)
        textLayer = null; showingOriginal = false
        resultLayer.removeAllViews(); resultLayer.visibility = View.INVISIBLE
        applyImeShift()
    }
    private fun copyText() {
        if (showingTranslation) {
            val translated = translationLayer?.copyText()?.takeIf { it.isNotBlank() } ?: return
            try {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("截图译文", translated))
                showFeedback("已复制译文")
            } catch (_: Exception) {
                showFeedback("复制失败，请重试")
            }
            return
        }
        val value = textLayer?.copyText()?.takeIf { it.isNotBlank() } ?: return
        android.util.Log.i("VoxFix","SCREENSHOT_COPY selection=${value.length} total=${textLayer?.text()?.length ?: 0}")
        try {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("截图文字",value))
            // An in-overlay pill is used instead of Toast: background toast limits on Android 12+
            // can drop it while the screenshot overlay is the only visible window.
            showFeedback("已复制文字")
        } catch (_: Exception) {
            showFeedback("复制失败，请重试")
        }
    }
    private fun showFeedback(message: String) {
        val view = feedback ?: TextView(context).apply {
            textSize = 12f
            setTextColor(0xE0000000.toInt())
            setPadding(dp(10),dp(6),dp(10),dp(6))
            background = surface(PANEL_RADIUS)
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }.also {
            feedback = it
            root.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        view.removeCallbacks(hideFeedback)
        view.text = message
        view.visibility = View.VISIBLE
        view.postDelayed(hideFeedback, 1600)
        placeControls()
    }
    private fun toggleOriginal() {
        if (textLayer == null) return
        showingOriginal = !showingOriginal
        if (showingOriginal) {
            setEditing(false)
        }
        resultLayer.visibility = if (showingOriginal) View.INVISIBLE else View.VISIBLE
        sync()
    }
    private fun setEditing(next: Boolean, document: Boolean = false) {
        if (editing == next) return
        if (next && textLayer == null) return
        if (next && translating) {
            translationGeneration++
            translating = false; showingTranslation = false
            translationLayer?.visibility = View.GONE
            pairBar?.visibility = View.GONE
        }
        editing = next
        val layer = if (showingTranslation) translationLayer else textLayer
        if (next) {
            layer?.endLayoutMode(); textLayoutButton.setColorFilter(COLOR_TEXT)
            showingOriginal = false
            resultLayer.visibility = View.VISIBLE
            // The overlay window is focusable from creation, so the fields can own the input
            // connection; toggling FLAG_NOT_FOCUSABLE later never binds the IME.
            root.requestFocus()
            root.postDelayed({
                if (!editing) return@postDelayed
                if (document) picked?.let { layer?.beginDocumentEditing(it) } else layer?.beginEditing()
                applyImeShift()
                placeControls()
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
        val layer = if (showingTranslation) translationLayer else textLayer
        content.translationY = 0f
        if (!editing || layer == null || root.height == 0) return
        layer.setEditingViewport(bars.top+dp(8), root.height-maxOf(imeBottom,bars.bottom)-dp(96))
    }
    /** The grip moves the whole bar instead of letting the auto anchor decide again. */
    private fun dragToolbar(dx: Float,dy: Float) {
        if(root.width==0) return
        val base=toolbarManual ?: android.graphics.Point(toolbar.translationX.toInt(),toolbar.translationY.toInt())
        toolbarManual=android.graphics.Point(base.x+dx.toInt(),base.y+dy.toInt())
        placeControls()
    }
    private fun placeControls() {
        val width=root.width; val height=root.height
        if(width==0 || height==0) return
        if(toolbarAvailable!=width-bars.left-bars.right-dp(16)) rebuildToolbar()
        val safeTop=bars.top+dp(8)
        val safeBottom=height-maxOf(bars.bottom,imeBottom)-dp(8)
        val rect=picked
        val left=rect?.let { it.left*width/image.width } ?: (width-toolbarWidth)/2
        val top=rect?.let { it.top*height/image.height } ?: safeBottom
        val bottom=rect?.let { it.bottom*height/image.height } ?: safeBottom
        val browsing = selector.smartEnabled && smartPath.isNotEmpty()
        val anchorKey="$width,$height,$rect,${bars.top},${bars.bottom},$imeBottom,$toolbarWidth,$toolbarHeight,$browsing"
        val manual=toolbarManual
        if(manual!=null) {
            toolbarAnchor=android.graphics.Point(
                manual.x.coerceIn(bars.left+dp(8),maxOf(bars.left+dp(8),width-bars.right-toolbarWidth-dp(8))),
                manual.y.coerceIn(safeTop,maxOf(safeTop,safeBottom-toolbarHeight)))
        } else if(toolbarAnchorKey!=anchorKey) {
            val reserve=dp(104)
            val proposed=when {
                bottom+toolbarHeight+dp(8)+reserve<=safeBottom -> bottom+dp(8)
                top-toolbarHeight-dp(8)-reserve>=safeTop -> top-toolbarHeight-dp(8)-reserve
                else -> safeBottom-toolbarHeight-reserve
            }
            val minX = bars.left+dp(8)
            val maxX = maxOf(minX,width-bars.right-toolbarWidth-dp(8))
            toolbarAnchor = if (browsing) android.graphics.Point((minX+maxX)/2, (safeBottom-toolbarHeight).coerceAtLeast(safeTop))
            else android.graphics.Point(left.coerceIn(minX,maxX),
                proposed.coerceIn(safeTop,maxOf(safeTop,safeBottom-toolbarHeight-dp(48))))
            toolbarAnchorKey=anchorKey
        }
        val x=toolbarAnchor.x
        val y=toolbarAnchor.y
        toolbar.translationX=x.toFloat(); toolbar.translationY=y.toFloat()
        if (smartBar.visibility == View.VISIBLE) {
            val safe = ScreenshotDocRect(bars.left+dp(8), safeTop, width-bars.right-dp(8), safeBottom)
            val selection = rect?.let {
                ScreenshotDocRect(left, top, it.right*width/image.width, bottom)
            }
            val panel = if (toolbar.visibility == View.VISIBLE)
                ScreenshotDocRect(x, y, x+toolbarWidth, y+toolbarHeight) else null
            val buttonCount = 1 + listOf(smartInner, smartOuter).count { it.visibility == View.VISIBLE }
            val dock = ScreenshotSmartSelection.dock(safe, selection, panel, dp(48)*buttonCount, dp(48), dp(8))
            smartBar.translationX = dock.left.toFloat()
            smartBar.translationY = dock.top.toFloat()
        }
        val detailWidth=minOf(maxOf(toolbarWidth,dp(320)),width-bars.left-bars.right-dp(16)).coerceAtLeast(1)
        val below=(safeBottom-y-toolbarHeight-dp(8)).coerceAtLeast(0)
        val above=(y-safeTop-dp(8)).coerceAtLeast(0)
        val detailsAbove=below<dp(104) && above>below
        val detailHeight=(if(detailsAbove) above else below).coerceAtLeast(1).coerceAtMost(dp(280))
        val detailTop=if(detailsAbove) y-dp(8)-detailHeight else y+toolbarHeight+dp(8)
        if(secondaryMaxHeight!=detailHeight) { secondaryMaxHeight=detailHeight; secondaryPanel.requestLayout() }
        val lp=secondaryPanel.layoutParams
        if(lp.width!=detailWidth || lp.height!=-2) secondaryPanel.layoutParams=FrameLayout.LayoutParams(detailWidth,-2)
        secondaryPanel.measure(View.MeasureSpec.makeMeasureSpec(detailWidth,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(detailHeight,View.MeasureSpec.AT_MOST))
        secondaryPanel.translationX=x.coerceAtMost(width-bars.right-detailWidth-dp(8)).coerceAtLeast(bars.left+dp(8)).toFloat()
        secondaryPanel.translationY=detailTop.toFloat()
        annotationInput?.place(safeTop,safeBottom)
        annotationEditor?.takeIf { annotationInput==null }?.let { panel ->
            val panelWidth=minOf(dp(360),width-bars.left-bars.right-dp(16)).coerceAtLeast(1)
            val panelHeight=minOf(dp(240),(safeBottom-safeTop-dp(8)).coerceAtLeast(dp(80)))
            panel.layoutParams=FrameLayout.LayoutParams(panelWidth,panelHeight)
            panel.translationX=(bars.left+(width-bars.left-bars.right-panelWidth)/2).toFloat()
            panel.translationY=(safeBottom-panelHeight).coerceAtLeast(safeTop).toFloat()
        }
        status.maxWidth=(width-bars.left-bars.right-dp(16)).coerceAtLeast(1)
        status.translationX=x.toFloat(); status.translationY=(y-dp(34)).coerceAtLeast(safeTop).toFloat()
        feedback?.let { view ->
            if(view.visibility!=View.VISIBLE) return@let
            view.maxWidth=(width-bars.left-bars.right-dp(32)).coerceAtLeast(1)
            view.measure(View.MeasureSpec.makeMeasureSpec(view.maxWidth,View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(dp(40),View.MeasureSpec.AT_MOST))
            view.translationX=((width-view.measuredWidth)/2).coerceAtLeast(bars.left+dp(8)).toFloat()
            view.translationY=(y-view.measuredHeight-dp(8)).coerceAtLeast(safeTop).toFloat()
        }
    }
    private fun beginSave() {
        val region = picked ?: return
        if (busy) return
        busy = true; status.text = "正在保存…"; status.visibility = View.VISIBLE
        sync()
        val composed = try {
            // Save what the user sees: an edited result layer is exported, original view is not.
            val rendered=composeCurrent(region)
            val shaped=captureRegion
            when {
                shaped==null -> rendered
                rendered!=null -> shaped.mask(if(rendered.isMutable) rendered else rendered.copy(Bitmap.Config.ARGB_8888,true),region)
                else -> {
                    val base=Bitmap.createBitmap(image,region.left,region.top,region.width(),region.height())
                    shaped.mask(if(base.isMutable) base else base.copy(Bitmap.Config.ARGB_8888,true),region)
                }
            }
        } catch (error: Exception) {
            android.util.Log.w("VoxFix","SCREENSHOT_RENDER_FAILED",error)
            busy = false; status.text = "保存失败，请重试"; sync(); return
        }
        save(ScreenshotSaveRequest(Rect(region),composed,currentDocument(region),
            project?.replaceId,project?.revision,project?.copySourceFromId))
    }
    /** Everything needed to reopen this screenshot, captured before the session is released. */
    private fun currentDocument(region: Rect)=ScreenshotDocument(image.width,image.height,
        ScreenshotDocRect.of(region),drawing.history.items,currentText(),captureRegion?.rects?.map(ScreenshotDocRect::of).orEmpty(),
        captureRegion?.takeIf { it.isCustom }?.let { region -> region.toJson().toString() },
        drawing.pixelUnit,drawing.fontUnit,drawing.watermark.takeIf { it.isVisible() })
    private fun currentText(): ScreenshotDocumentText? {
        val layer=textLayer ?: return null
        val blocks=layer.currentBlocks()
        if(blocks.isEmpty()) return null
        val translated=translationComplete && translationLayer!=null
        return ScreenshotDocumentText(blocks,
            if(translated) translationLayer?.currentBlocks().orEmpty() else emptyList(),
            if(showingTranslation && translated) ScreenshotTextMode.TRANSLATION else ScreenshotTextMode.ORIGINAL,
            translated)
    }
    /** Place recognised lines by hand instead of only editing their characters. */
    private fun toggleTextLayout() {
        if (busy || translating) return
        val layer = editedLayer() ?: return
        if (layer.isLayoutMode) { layer.endLayoutMode(); sync(); return }
        if (showingOriginal) { showingOriginal = false; resultLayer.visibility = View.VISIBLE }
        if (showingTranslation) { translationLayer?.visibility = View.VISIBLE; textLayer?.visibility = View.GONE }
        else { translationLayer?.visibility = View.GONE; textLayer?.visibility = View.VISIBLE }
        setEditing(false)
        layer.beginLayoutMode()
        if (!layer.isLayoutMode) showFeedback("译文按段落排版，改回原文后可拖动")
        else showFeedback("拖动文字块移动，右下角改大小")
        textLayoutButton.setColorFilter(if (layer.isLayoutMode) COLOR_PRIMARY else COLOR_TEXT)
        sync()
    }
    /** Back key: leave editing first, otherwise the service closes the whole capture. */
    fun back(): Boolean {
        if (recapturing) return false
        if (annotationEditor != null) { closeDrawingEditor(); sync(); return true }
        if (regionOperation != null) { setRegionOperation(null); sync(); return true }
        if (moveMode) { toggleMoveMode(); return true }
        editedLayer()?.takeIf { it.isLayoutMode }?.let { it.endLayoutMode(); textLayoutButton.setColorFilter(COLOR_TEXT); sync(); return true }
        if (stackPopup != null) { closeStack(); return true }
        if (drawing.tool != null && drawing.tool!=ScreenshotDrawingTool.SELECT) {
            drawing.tool = ScreenshotDrawingTool.SELECT; rebuildDrawingStyles(); sync(); return true
        }
        if (!editing) return false
        setEditing(false); return true
    }
    val isEditing: Boolean get() = editing || annotationEditor != null
    /** An idle timeout must not tear down a record the user opened from the clipboard. */
    val isProjectEditor: Boolean get() = project != null
    fun retainedResult(): ScreenTextOverlay.ResultState? {
        val source = textLayer?.retainedResult()
        val translated = if (translationComplete) translationLayer?.retainedResult()?.copy(translation = true) else null
        return if (showingTranslation && translated != null) translated.copy(alternate = source)
            else source?.copy(alternate = translated) ?: translated ?: recaptureDrafts.takeIf { it.isNotEmpty() }?.let {
                val draft = it.joinToString("\n\n")
                ScreenTextOverlay.ResultState(draft, draft.length, draft.length, false)
            }
    }
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
        disposed=true; actionGeneration++; actionWorker.shutdown()
        drawTemplatePopup?.dismiss()
        closeDrawingEditor(); drawing.close()
        closeStack()
        hideKeyboard(); background.setImageDrawable(null)
        translationGeneration++; translating = false
        feedback?.removeCallbacks(hideFeedback)
        runCatching { windowManager.removeViewImmediate(root) }
        pairBar = null; translationLayer = null; feedback = null
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
            setOnClickListener { if (!disposed && !closeRequested) { toolMemory?.remember(action); run() } }
        }
        fun refreshTint() {
            val color = when {
                !isEnabled -> COLOR_TEXT_DISABLED
                danger -> COLOR_DANGER
                active -> 0xFFFFFFFF.toInt()
                else -> COLOR_TEXT
            }
            setColorFilter(color,PorterDuff.Mode.SRC_IN)
            background = if(active) activeSurface(6) else RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF),null,null)
        }
        override fun drawableStateChanged() { super.drawableStateChanged(); refreshTint() }
    }
}
