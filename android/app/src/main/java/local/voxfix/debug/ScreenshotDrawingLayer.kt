package local.voxfix.debug

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.*

class ScreenshotDrawingLayer(context: Context,private var image: Bitmap,private val memory: ScreenshotToolMemory?=null,private val changed: () -> Unit) : View(context) {
    val history=ScreenshotDrawing()
    var region: Rect?=null
        set(value) {
            field = value
            if (autoFilterActive) autoFilterSession?.request(value)
        }
    private var autoFilterSession: ScreenshotAutoFilterSession? = null
    private var autoFilterMode = false
    internal val autoFilterActive get() = tool == ScreenshotDrawingTool.FILTER && autoFilterMode
    internal val autoFilterRegions get() = autoFilterSession?.result
    internal val autoFilterFailed get() = autoFilterSession?.failed == true
    private var autoFilterPress: ScreenshotDrawingPoint? = null
    private var autoFilterPressRegions: List<ScreenshotAutoFilter.Region>? = null

    internal fun chooseAutoFilter() {
        autoFilterMode = true
        requestAutoFilter()
        changed()
    }

    private fun requestAutoFilter() {
        val session = autoFilterSession ?: ScreenshotAutoFilterSession(image, ::invalidate, changed)
            .also { autoFilterSession = it }
        session.request(region)
    }

    internal fun replaceSource(next: Bitmap) {
        autoFilterSession?.close()
        autoFilterSession = null
        image = next
        renderer.replaceSource(next)
        if (autoFilterActive) requestAutoFilter()
        invalidate()
    }

    internal fun fillAutoFilterCategory(category: Int) {
        if (!autoFilterActive) return
        val regions = autoFilterRegions.orEmpty().filter { it.category == category }
        history.fillAutoRegions(regions, style, pixelUnit, toggle = false)
        invalidate()
        changed()
    }
    /** Canvas-level watermark; committed on the same stack, so undo/redo reverts it too. */
    val watermark: ScreenshotWatermark get()=history.watermark
    fun setWatermark(value: ScreenshotWatermark) {
        val next=value.normalized()
        if(next==watermark) return
        history.watermark=next; invalidate(); changed()
    }
    var onTextRequest: ((ScreenshotDrawingPoint,Int?) -> Unit)?=null
    var colorSamplingBase:(()->Bitmap?)?=null
    fun pickColor(done:(Int?)->Unit) {
        val snapshot=runCatching {
            val base=colorSamplingBase?.invoke() ?: image.copy(Bitmap.Config.ARGB_8888,true)
            val result=if(base.isMutable) base else base.copy(Bitmap.Config.ARGB_8888,true).also { base.recycle() }
            renderer.draw(Canvas(result),marks(true),Rect(0,0,image.width,image.height),watermark)
            result
        }.getOrNull()
        // The sampler needs a view group to live in and a laid-out target; being attached to a real
        // window is not required, so the same path can be exercised without a window token.
        val host=(rootView as? ViewGroup)?.takeIf { it!==(this as View) }
        if(snapshot==null || host==null || width<=0 || height<=0 || image.isRecycled) {
            snapshot?.recycle(); done(null); return
        }
        ScreenshotColorSampler(this,snapshot,done).show()
    }
    var selectedIndex: Int?
        get()=selection.primary
        private set(value) { selection.select(value) }
    val selectedIndices: Set<Int> get()=selection.indices
    val selected: ScreenshotDrawingStroke? get()=selectedIndex?.let { history.items.getOrNull(it) }
    private val styleIndices get()=ScreenshotSelectionStyle.indices(history.items,selectedIndices,selectedIndex)
    val inspectedObject get()=styleIndices.firstOrNull()?.let { history.items[it] }
    val inspectedTool get()=inspectedObject?.tool ?: tool
    val inspectedStyle: ScreenshotDrawingStyle get()=inspectedObject?.let { ScreenshotSelectionStyle.inspected(it,pixelUnit) } ?: style
    val mixedStyleProperties get()=ScreenshotSelectionStyle.mixed(history.items,styleIndices,pixelUnit)
    fun isStyleMixed(vararg properties: ScreenshotStyleProperty)=properties.any { it in mixedStyleProperties }
    val mixedNumber get()=styleIndices.map { history.items[it] }.filter { it.tool==ScreenshotDrawingTool.SERIAL }.map { it.number }.distinct().size>1
    private val toolStyles=mutableMapOf<ScreenshotDrawingTool,ScreenshotDrawingStyle>()
    private val areaStyles=mutableMapOf<Pair<ScreenshotDrawingTool,ScreenshotAreaMode>,ScreenshotDrawingStyle>()
    var style=ScreenshotDrawingStyle()
        private set
    /** 工程重开时恢复上次工具样式；样式面板随后由调用方 rebuild 才能高亮。 */
    fun restoreStyle(value: ScreenshotDrawingStyle) { style = value }
    /** Endpoint hints help continue a stroke while editing; the recording overlay turns them off. */
    internal var showPenFeedback=true
    /**
     * Painting and input are limited to this rect (image coordinates) when set. The recording
     * overlay passes the capture region, because upstream's annotation canvas *is* that region;
     * the screenshot editor leaves it null so drawing outside the box stays possible.
     */
    internal var clip: Rect?=null
    /**
     * The eyedropper samples the screenshot base; over a live recording the base is transparent, so
     * the annotation layer turns it off and the palette hides the button instead of faking a colour.
     */
    internal var colorSamplingEnabled=true
    var tool: ScreenshotDrawingTool?=null
        set(value) {
            if(field==value) return
            finishStylePreview(false)
            field?.let { toolStyles[it]=style }
            field=value; quickDrag=false; pending=null; pen=null; continuation=null; selection.cancel(); penFeedback.clear(); eraserPointers.clear()
            autoFilterPress = null
            autoFilterPressRegions = null
            autoFilterSession?.hits = emptyList()
            if(value!=ScreenshotDrawingTool.SELECT) selectedIndex=null
            style=toolStyles[value] ?: value?.let { memory?.style(it) } ?: defaultScreenshotDrawingStyle(value)
            if (autoFilterActive) requestAutoFilter()
            invalidate()
        }
    var color: Int
        get()=style.color
        set(value) { applyStyle(inspectedStyle.copy(color=value),ScreenshotStyleProperty.COLOR) }
    var strokeWidthDp: Int
        get()=style.width
        set(value) { applyStyle(inspectedStyle.copy(width=value),ScreenshotStyleProperty.WIDTH) }
    var nextNumber=memory?.startNumber ?: 1L
    private var pending: MutableList<ScreenshotDrawingPoint>?=null
    /** Live pen pipeline: stabilizer plus bounded RDP window; null for every other tool. */
    private var pen: PenStrokeBuilder?=null
    private var continuation: ScreenshotPenContinuation?=null
    private var penTouch=false
    private var cancelled=false
    private var pendingStyle=style
    private var serialLabelStyle=defaultScreenshotDrawingStyle(ScreenshotDrawingTool.TEXT)
    private var down=ScreenshotDrawingPoint(0f,0f)
    /**
     * 上游 `state.eraser.active_pointers`（`eraser_workflow.rs::process_eraser_pointer_event`）：
     * 橡皮擦按 pointer id 各自记录上一位置，多根手指可以同时擦，互不相干。
     */
    private val eraserPointers=HashMap<Int,ScreenshotDrawingPoint>()
    /** Objects under the last selection tap, topmost first; the second one is reachable by cycling. */
    private var tapCandidates: List<Int> = emptyList()
    private var previewOriginal: ScreenshotDrawingStyle?=null
    private var previewValue: ScreenshotDrawingStyle?=null
    private var previewProperties=emptySet<ScreenshotStyleProperty>()
    private var textPreview: ScreenshotDrawingStroke?=null
    private var textPreviewIndex: Int?=null
    private val erased=mutableSetOf<Int>()
    var savedPixelUnit: Float=0f
    var savedFontUnit: Float=0f
    /**
     * 首次打开时按「整幅」视图宽度冻结单位。
     *
     * pixelUnit/fontUnit 会随视图宽度变化；若不冻结，切到「适应宽度」后视图变宽、
     * 单位变小，同一根 4px 笔画会被算成更细的线，字号也会被写小。
     */
    fun freezeUnits(viewWidth: Int) {
        if (savedPixelUnit > 0f || image.width <= 0 || viewWidth <= 0) return
        val metrics = resources.displayMetrics
        savedPixelUnit = metrics.density * image.width / viewWidth
        savedFontUnit = metrics.scaledDensity * image.width / viewWidth
    }
    val pixelUnit get()=savedPixelUnit.takeIf { it>0 } ?: (resources.displayMetrics.density*image.width/(width.takeIf { it>0 } ?: image.width))
    val fontUnit get()=savedFontUnit.takeIf { it>0 } ?: (resources.displayMetrics.scaledDensity*image.width/(width.takeIf { it>0 } ?: image.width))
    private val unit get()=resources.displayMetrics.density*image.width/(width.takeIf { it>0 } ?: image.width)/viewZoom.coerceAtLeast(0.05f)
    private val renderer=ScreenshotDrawingRenderer(image,{ resources.displayMetrics.density }) { fontUnit }
    private val snapPreferences by lazy { ScreenshotSnapConfig.preferences(context) }
    /** 视图缩放（整幅=1）：选框把手、触点判定等屏幕尺寸 UI 用它保持视觉大小不随捏合变化。 */
    var viewZoom: Float = 1f
    /** SELECT 在空白处拖动时转发为画布平移（屏幕像素增量）；点到对象时由选择逻辑接管，不触发。 */
    var onCanvasPan: ((Float, Float) -> Unit)? = null
    private var selectPanActive = false
    private var selectPanLast = ScreenshotDrawingPoint(0f, 0f)
    private val selection by lazy { ScreenshotObjectSelection(history,renderer,{ unit },{ slop }) }
    private val penFeedback by lazy { ScreenshotPenFeedback(this,image.width,image.height) }

    init { contentDescription="截图绘图区域"; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun applyStyle(value: ScreenshotDrawingStyle,vararg properties: ScreenshotStyleProperty) {
        previewOriginal=null; previewValue=null; previewProperties=emptySet()
        val active=inspectedTool
        val fields=properties.toSet().ifEmpty { ScreenshotStyleProperty.values().toSet() }
        if(selectedIndices.isEmpty() && active in setOf(ScreenshotDrawingTool.FILTER,ScreenshotDrawingTool.HIGHLIGHT) && value.area!=style.area) {
            areaStyles[active!! to style.area]=style
        }
        if(selectedIndices.isEmpty()) {
            style=fields.fold(style) { s,p->p.patch(s,value) }
            if(active!=null) memory?.save(active,style)
        }
        if(active==ScreenshotDrawingTool.SPOTLIGHT) history.restyleSpotlights(value)
        else history.replaceAll(styleIndices.associateWith { ScreenshotSelectionStyle.patch(history.items[it],value,fields,pixelUnit) })
        invalidate(); changed()
    }
    fun changeArea(area: ScreenshotAreaMode) {
        val active=selected?.tool ?: tool ?: return
        if (active == ScreenshotDrawingTool.FILTER) autoFilterMode = false
        applyStyle(areaStyles[active to area] ?: memory?.style(active,area) ?: inspectedStyle.copy(area=area))
    }
    fun beginStylePreview() { if(previewOriginal==null) previewOriginal=inspectedStyle }
    fun previewStyle(value: ScreenshotDrawingStyle,vararg properties: ScreenshotStyleProperty) {
        beginStylePreview(); previewValue=value
        previewProperties=previewProperties+properties.toSet().ifEmpty { ScreenshotStyleProperty.values().toSet() }
        invalidate()
    }
    fun finishStylePreview(commit: Boolean) {
        val original=previewOriginal ?: return
        val value=previewValue ?: original
        val fields=previewProperties
        previewOriginal=null; previewValue=null; previewProperties=emptySet()
        if(commit && fields.isNotEmpty()) applyStyle(value,*fields.toTypedArray()) else invalidate()
    }
    fun beginTextPreview(point: ScreenshotDrawingPoint,index: Int?) {
        textPreviewIndex=index
        textPreview=index?.let { history.items.getOrNull(it) } ?: ScreenshotDrawingStroke(ScreenshotDrawingTool.TEXT,listOf(point),
            style.color,style.width*pixelUnit,style,textWidth=minOf(240*pixelUnit,(image.width-point.x).coerceAtLeast(1f)))
    }
    private fun isSerialLabel(index: Int?)=index?.let { history.items.getOrNull(it)?.id }?.let { id ->
        history.items.any { it.tool==ScreenshotDrawingTool.SERIAL && it.boundTextId==id }
    }==true
    fun previewText(value: String) {
        textPreview=if(isSerialLabel(textPreviewIndex)) textPreviewIndex?.let { renderer.fitSerialLabel(history.items[it].copy(text=value)) }
            else textPreview?.let { if(it.autoResize) renderer.naturalFit(it.copy(text=value)) else it.copy(text=value) }
        invalidate()
    }
    fun cancelTextPreview() { textPreview=null; textPreviewIndex=null; invalidate() }
    fun textBounds(point: ScreenshotDrawingPoint,index: Int?): RectF {
        val box=renderer.bounds(index?.let { history.items[it] }
            ?: ScreenshotDrawingStroke(ScreenshotDrawingTool.TEXT,listOf(point),style.color,style.width*pixelUnit,style,text=" ",
                textWidth=minOf(240*pixelUnit,(image.width-point.x).coerceAtLeast(1f))))
        // The draft follows the measured label, while the input field stays wide enough to type.
        if(isSerialLabel(index)) {
            val center=box.centerX(); val width=minOf(240*pixelUnit,image.width.toFloat())
            box.left=center-width/2; box.right=center+width/2
        }
        return box
    }
    private fun textCreationStyle()=toolStyles[ScreenshotDrawingTool.TEXT] ?: memory?.style(ScreenshotDrawingTool.TEXT)
        ?: defaultScreenshotDrawingStyle(ScreenshotDrawingTool.TEXT)
    private fun openSerialText(index: Int) {
        tool=ScreenshotDrawingTool.SELECT; selectedIndex=index; tapCandidates=listOf(index)
        val label=history.items[index]; style=label.style
        invalidate(); changed(); onTextRequest?.invoke(label.points.first(),index)
    }
    fun editSerialText() {
        val serial=selected?.takeIf { it.tool==ScreenshotDrawingTool.SERIAL } ?: return
        val label=ScreenshotSerialLabels.create(serial,textCreationStyle(),fontUnit,pixelUnit)
        history.attachSerialText(serial.id,label)?.let(::openSerialText)
    }
    fun setNumber(value: Long) {
        val number=value.coerceAtLeast(0)
        if(selectedIndices.isEmpty()) { nextNumber=number; memory?.startNumber=number }
        else history.replaceAll(styleIndices.filter { history.items[it].tool==ScreenshotDrawingTool.SERIAL && history.items[it].style.serialShape!=ScreenshotSerialShape.CIRCLE }
            .associateWith { history.items[it].copy(number=number) })
        invalidate(); changed()
    }
    fun stepNumber(direction: Int) {
        if(direction==0) return
        val current=(inspectedObject?.takeIf { it.tool==ScreenshotDrawingTool.SERIAL }?.number ?: nextNumber).coerceAtLeast(0)
        setNumber(if(direction>0) { if(current<Long.MAX_VALUE) current+1 else current } else (current-1).coerceAtLeast(0))
    }
    fun addText(point: ScreenshotDrawingPoint,text: String,index: Int?=null) {
        cancelTextPreview()
        if(text.isBlank()) { if(index!=null) { history.remove(setOf(index)); selectedIndex=null; invalidate(); changed() }; return }
        val editedIndex=index?.takeIf { it in history.items.indices }
        if(editedIndex!=null) {
            val value=history.items[editedIndex].copy(text=text)
            val fitted=if(isSerialLabel(editedIndex)) renderer.fitSerialLabel(value)
                else if(value.autoResize) renderer.naturalFit(value) else renderer.wrappedFit(value)
            history.replace(editedIndex,fitted)
        }
        else history.add(renderer.naturalFit(ScreenshotDrawingStroke(ScreenshotDrawingTool.TEXT,listOf(point),style.color,style.width*pixelUnit,style,text=text,
            textWidth=minOf(240*pixelUnit,(image.width-point.x).coerceAtLeast(1f)),autoResize=true)))
        // A finished text mark is handed to the selection tool: the usual next step is placing it.
        tool=ScreenshotDrawingTool.SELECT
        selectedIndex=editedIndex ?: history.items.lastIndex
        tapCandidates=listOf(selectedIndex!!)
        invalidate(); changed()
    }
    fun editSelectedText() { selected?.takeIf { it.tool==ScreenshotDrawingTool.TEXT }?.let { onTextRequest?.invoke(it.points.first(),selectedIndex) } }
    fun deleteSelected() { history.remove(selectedIndices); selectedIndex=null; tapCandidates=emptyList(); invalidate(); changed() }
    internal fun reorderSelected(action: ScreenshotDrawing.Order) {
        val overlapIds = tapCandidates.mapNotNull { history.items.getOrNull(it)?.id }.toSet()
        if (!history.reorder(selectedIndices, action)) return
        tapCandidates = history.items.indices.reversed().filter { history.items[it].id in overlapIds }
        invalidate()
        changed()
    }
    internal fun alignSelected(alignment: ScreenshotSelectionAlignment) {
        if (!selection.align(alignment)) return
        tapCandidates=emptyList()
        invalidate()
        changed()
    }
    internal fun duplicateSelected() {
        val copies=history.duplicate(selectedIndices)
        if (copies.isEmpty()) return
        selection.selectIndices(copies)
        tapCandidates=emptyList()
        invalidate()
        changed()
    }
    internal fun selectedTemplate(): ScreenshotDrawTemplate? {
        val indices = selectedIndices
        if (indices.isEmpty()) return null
        val strokes = history.templateObjects(indices)
        val bounds = renderer.bounds(strokes.first()).let(::RectF)
        strokes.drop(1).forEach { bounds.union(renderer.bounds(it)) }
        val document = ScreenshotDocument.full(image.width, image.height).copy(objects = strokes)
        return ScreenshotDrawTemplate(document, indices.mapTo(linkedSetOf()) { history.items[it].id },
            bounds.centerX(), bounds.centerY())
    }

    internal fun insertTemplate(template: ScreenshotDrawTemplate): Boolean {
        val crop = region ?: return false
        val copies = history.insertTemplate(template.document.objects, template.selectedIds,
            crop.exactCenterX() - template.centerX, crop.exactCenterY() - template.centerY)
        if (copies.isEmpty()) return false
        selection.selectIndices(copies)
        tapCandidates = emptyList()
        invalidate()
        changed()
        return true
    }
    /** How many objects sit under the last selection tap; more than one can be cycled through. */
    val overlapCount: Int get()=tapCandidates.size
    fun cycleSelection() {
        if(tapCandidates.size<2) return
        val current=tapCandidates.indexOf(selectedIndex)
        selectedIndex=tapCandidates[(if(current<0) 0 else current+1)%tapCandidates.size]
        selected?.let { style=it.style }
        invalidate(); changed()
    }
    private fun marks(includePending: Boolean): List<ScreenshotDrawingStroke> {
        val previewIndices=if(previewValue!=null) styleIndices.toSet() else emptySet()
        val result=history.items.mapIndexedNotNull { index,stroke ->
            // Pending erasures stay visible at half opacity until the finger lifts, so the user sees
            // what is about to go (Snow Shot previews eraser hits the same way).
            if(index==continuation?.index && pending!=null && continuation?.valid(history)==true)
                renderer.withWorldPoints(stroke,pending!!)
            else if(index in erased) stroke.copy(style=stroke.style.copy(opacity=stroke.style.opacity*0.5f))
            else if(index==textPreviewIndex && textPreview!=null) textPreview
            else if(stroke.tool==ScreenshotDrawingTool.SPOTLIGHT && inspectedTool==ScreenshotDrawingTool.SPOTLIGHT && previewValue!=null)
                stroke.copy(color=previewValue!!.color,style=stroke.style.copy(color=previewValue!!.color,opacity=previewValue!!.opacity))
            else if(index in previewIndices && previewValue!=null) ScreenshotSelectionStyle.patch(stroke,previewValue!!,previewProperties,pixelUnit)
            else selection.preview[index] ?: stroke
        }.toMutableList()
        if(textPreviewIndex==null) textPreview?.let(result::add)
        if(includePending && continuation==null) pending?.let { points -> tool?.let { active ->
            val stroke=ScreenshotDrawingStroke(active,points,pendingStyle.color,pendingStyle.width*pixelUnit,pendingStyle,
                number=nextNumber,smooth=pen!=null)
            if(active==ScreenshotDrawingTool.SERIAL && hypot(points.last().x-down.x,points.last().y-down.y)>=3*unit) {
                result.add(stroke.copy(points=listOf(down),id=-1,boundTextId=-2))
                result.add(ScreenshotSerialLabels.create(stroke,serialLabelStyle,fontUnit,pixelUnit,points.last()).copy(id=-2))
            } else result.add(stroke)
        } }
        return result
    }
    override fun onDraw(canvas: Canvas) {
        val crop=region ?: return
        if(width==0 || height==0 || image.isRecycled) return
        // Drawing may start outside the capture box; it stays visible there (over the dimmed area) so
        // the gesture is not silently clipped. Export still only keeps what is inside the box.
        canvas.save(); canvas.scale(width.toFloat()/image.width,height.toFloat()/image.height)
        // Recording overlay: upstream draws on the region window itself, so everything — watermark
        // and spotlight mask included — stops at the region edge. Null keeps the editor unchanged.
        clip?.let { canvas.clipRect(it) }
        renderer.draw(canvas,marks(true),Rect(0,0,image.width,image.height),watermark)
        // 绘图工具下按住已画元素就进入选择移动，所以选中框也要跟着画：抬手后保留，直到在空白处
        // 起笔（放弃选择）或切换工具。
        if(tool==ScreenshotDrawingTool.SELECT || selectedIndex!=null) selection.draw(canvas)
        if(autoFilterActive) autoFilterSession?.draw(canvas, unit, ::postInvalidateOnAnimation)
        canvas.restore()
        if(tool==ScreenshotDrawingTool.PEN && showPenFeedback && (pending==null || continuation!=null))
            penFeedback.draw(canvas,if(pending==null) history.items.flatMap { ScreenshotPenContinuation.endpoints(it,renderer) } else emptyList())
    }
    override fun onDetachedFromWindow() {
        pending=null; pen=null; continuation=null; penFeedback.clear(); selection.cancel(); eraserPointers.clear()
        super.onDetachedFromWindow()
    }
    private fun penPoint(point: ScreenshotDrawingPoint): ScreenshotDrawingPoint {
        val sample=continuation?.sample(point) ?: return point
        return ScreenshotDrawingPoint(sample.x.coerceIn(0f,image.width.toFloat()),sample.y.coerceIn(0f,image.height.toFloat()))
    }
    private fun candidates(point: ScreenshotDrawingPoint,excluded: Set<Int> = emptySet()): List<Int> {
        val outline=history.items.indices.reversed()
            .filter { it !in excluded && history.items[it].autoFilterCategory == null && renderer.hit(history.items[it],point,7*unit,region,true) }
        val body=history.items.indices.reversed()
            .filter { it !in excluded && it !in outline && history.items[it].autoFilterCategory == null && renderer.hit(history.items[it],point,7*unit,region,false) }
        return outline+body
    }
    private val ERASER_RADIUS get()=8f
    /**
     * 按下时命中已有元素、正在拖它（而不是画新对象）。对应上游
     * `PrimaryPointerIntent::BeginElementSelectionMove`：绘图工具下也能直接拖动已画元素。
     */
    private var quickDrag=false
    /**
     * 上游 `ToolPolicy.selection_scope` + `quick_selection_enabled`（`input/policy.rs`、
     * `input/hit_resolution.rs`）：每种工具只命中自己作用域内的既有元素，命中就进入「选中并移动」，
     * 只有空白处才创建新对象。手机没有指针形状提示，直接按命中结果分派。
     */
    private fun quickSelectionCandidate(active: ScreenshotDrawingTool,point: ScreenshotDrawingPoint): Int {
        val accepts: (ScreenshotDrawingStroke)->Boolean = when(active) {
            ScreenshotDrawingTool.RECTANGLE -> { item->item.tool==ScreenshotDrawingTool.RECTANGLE }
            ScreenshotDrawingTool.LINE -> { item->item.tool==ScreenshotDrawingTool.LINE }
            ScreenshotDrawingTool.ARROW -> { item->item.tool==ScreenshotDrawingTool.ARROW }
            // 画笔保留「端点接续」优先：本地按上游 `free_draw` 实现了贴着已有笔迹端点继续画并在真机
            // 验收过；上游同样把快速选择做成可按工具禁用（`set_quick_selection_disabled_tools`），
            // 在这里等价于对 FreeDraw 关掉快速选择。要移动笔迹用「选择」工具。
            // 滤镜与高亮按「矩形 / 画笔」分成两个作用域，对应上游 RectangleFilter 与 PenFilter。
            ScreenshotDrawingTool.FILTER -> { item->item.tool==ScreenshotDrawingTool.FILTER && item.style.area==style.area }
            ScreenshotDrawingTool.HIGHLIGHT -> { item->item.tool==ScreenshotDrawingTool.HIGHLIGHT && item.style.area==style.area }
            ScreenshotDrawingTool.SPOTLIGHT -> { item->item.tool==ScreenshotDrawingTool.SPOTLIGHT }
            else -> return -1
        }
        return candidates(point).firstOrNull { accepts(history.items[it]) } ?: -1
    }
    /** Curve lines sample the drag path; below this spacing the extra vertices add nothing. */
    private val LINE_SAMPLE_SPACING get()=1.5f*unit
    /** Screen slop is expressed in bitmap pixels, which is the space the touches are mapped into. */
    private val slop get()=android.view.ViewConfiguration.get(context).scaledTouchSlop*image.width/(width.takeIf { it>0 } ?: image.width).toFloat()/viewZoom.coerceAtLeast(0.05f)
    /** Snow Shot's eraser tolerance is an 8px viewport radius (`ERASER_RADIUS_VIEW`). */
    private fun eraseAt(point: ScreenshotDrawingPoint) {
        history.items.indices.filterTo(erased) { it !in erased && renderer.hit(history.items[it],point,ERASER_RADIUS*unit) }
    }
    private fun eraseTo(id: Int,point: ScreenshotDrawingPoint) {
        val from=eraserPointers.put(id,point)
        if(from==null) eraseAt(point) else eraseSamples(from,point)
    }
    /**
     * 上游 `queue_eraser_hits(document, &samples)`：把线段按半半径采样后逐点命中。命中只记进
     * [erased]，拖动中不删对象（保持半透明预览）。
     */
    private fun eraseSamples(from: ScreenshotDrawingPoint,to: ScreenshotDrawingPoint) {
        ScreenshotEraserSamples.segment(from,to,ERASER_RADIUS*unit).forEach(::eraseAt)
    }
    /** 上游 `Up | Cancel` 分支：先按该指针的上一位置到抬手点采样，再把它移出 `active_pointers`。 */
    private fun eraseLift(id: Int,point: ScreenshotDrawingPoint) {
        val from=eraserPointers.remove(id) ?: return
        eraseSamples(from,point)
    }
    /**
     * 上游只在 `active_pointers` 清空（最后一根手指抬起）时才把 `pending_ids` 交给 `delete_elements`，
     * 因此多指同擦整体仍然只占一条历史。
     */
    private fun commitEraser() {
        history.remove(erased); selectedIndex=null; tapCandidates=emptyList()
    }
    /** 某一根手指的画布坐标，沿用单指时代的越界夹取。 */
    private fun eraserPoint(event: MotionEvent,index: Int): ScreenshotDrawingPoint {
        val x=event.getX(index)*image.width/width
        val y=event.getY(index)*image.height/height
        return ScreenshotDrawingPoint(x.coerceIn(0f,image.width.toFloat()),y.coerceIn(0f,image.height.toFloat()))
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val active=tool ?: return false
        if(region==null) return false
        if(width==0 || height==0) return false
        val rawX=event.x*image.width/width
        val rawY=event.y*image.height/height
        // Outside the clipping canvas there is no canvas at all upstream: let the touch through.
        clip?.let { if(!it.contains(rawX.toInt(),rawY.toInt())) return false }
        val point=ScreenshotDrawingPoint(rawX.coerceIn(0f,image.width.toFloat()),rawY.coerceIn(0f,image.height.toFloat()))
        if (autoFilterActive) return touchAutoFilter(event, point)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelled=rawX<0 || rawY<0 || rawX>image.width || rawY>image.height
                down=point; erased.clear(); selection.cancel(); continuation=null; penFeedback.clear()
                penTouch=active==ScreenshotDrawingTool.PEN && event.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER
                if(!cancelled) when(active) {
                    ScreenshotDrawingTool.SELECT -> {
                        selection.snap = ScreenshotSnapConfig.fromPreferences(snapPreferences)
                        tapCandidates=candidates(point)
                        selection.press(point,event.metaState and android.view.KeyEvent.META_SHIFT_ON!=0,tapCandidates)
                        selected?.let { style=it.style }
                        changed()
                        // 什么都没点到：这一趟拖动转成画布平移；点到对象则保持原选择手感。
                        selectPanActive = selected == null && tapCandidates.isEmpty()
                        selectPanLast = point
                    }
                    ScreenshotDrawingTool.ERASER -> { eraserPointers.clear(); eraseTo(event.getPointerId(0),point) }
                    ScreenshotDrawingTool.TEXT -> Unit
                    ScreenshotDrawingTool.WATERMARK -> Unit
                    else -> {
                        val quick=quickSelectionCandidate(active,point)
                        if(quick>=0) {
                            quickDrag=true
                            selection.snap=ScreenshotSnapConfig.fromPreferences(snapPreferences)
                            tapCandidates=listOf(quick)
                            selection.press(point,false,listOf(quick))
                            selected?.let { style=it.style }
                            changed()
                        } else {
                            // 在空白处开始画新对象＝放弃上一步快速选择的对象。
                            selectedIndex=null; selection.select(null)
                            pendingStyle=style; pending=mutableListOf(point)
                            if(active==ScreenshotDrawingTool.SERIAL) serialLabelStyle=textCreationStyle()
                            continuation=if(active==ScreenshotDrawingTool.PEN) ScreenshotPenContinuation.find(history,renderer,point,unit,penTouch,slop) else null
                            pen=continuation?.builder ?: if(active==ScreenshotDrawingTool.PEN) PenStrokeBuilder(point,pendingStyle.width*pixelUnit) else null
                            continuation?.let {
                                pendingStyle=it.original.style; pending=it.builder.preview().toMutableList()
                                if(penTouch) penFeedback.grab(it.endpoint,point)
                            }
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if(active==ScreenshotDrawingTool.ERASER && !cancelled) {
                    // 上游 `Down` 对任意 pointer 都有效：后按下的手指各自加入 active_pointers 一起擦，
                    // 不取消已经开始的这一次手势（原版测试 multiple_pointers_commit_only_after_final_pointer_ends）。
                    val index=event.actionIndex
                    eraseTo(event.getPointerId(index),eraserPoint(event,index))
                } else {
                    cancelled=true; quickDrag=false; pending=null; pen=null; continuation=null; erased.clear(); selection.cancel(); penFeedback.clear()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 上游 `Up` 分支按 pointer 逐个收尾；只要 active_pointers 还有人，就不提交。
                if(active==ScreenshotDrawingTool.ERASER && !cancelled) {
                    val index=event.actionIndex
                    eraseLift(event.getPointerId(index),eraserPoint(event,index))
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                // 上游把 `Cancel` 与 `Up` 同等处理：已经命中的对象在取消时照样提交成一条历史。
                if(active==ScreenshotDrawingTool.ERASER && !cancelled) {
                    for(index in 0 until event.pointerCount) eraseLift(event.getPointerId(index),eraserPoint(event,index))
                    commitEraser()
                }
                cancelled=true; quickDrag=false; pending=null; pen=null; continuation=null; erased.clear(); selection.cancel(); penFeedback.clear(); selectPanActive=false
            }
            MotionEvent.ACTION_MOVE -> if(selectPanActive) {
                // 空白处拖动＝平移画布：point 是图片像素坐标，屏幕增量＝图片增量 ×(视图宽/图片宽) × viewZoom。
                // 缺第一项时长图（整幅 contain 后视图宽<图片宽）平移会被放大数倍，表现为底图乱窜。
                val viewScale=(width.takeIf { it>0 } ?: image.width.coerceAtLeast(1)).toFloat()/image.width.coerceAtLeast(1)
                onCanvasPan?.invoke((point.x-selectPanLast.x)*viewScale*viewZoom,(point.y-selectPanLast.y)*viewScale*viewZoom)
                selectPanLast = point
            }
                else if(quickDrag) selection.move(point,event.metaState and android.view.KeyEvent.META_SHIFT_ON!=0,image.width,image.height,event.metaState and android.view.KeyEvent.META_ALT_ON!=0)
                else if(!cancelled) when(active) {
                ScreenshotDrawingTool.SELECT -> selection.move(point,event.metaState and android.view.KeyEvent.META_SHIFT_ON!=0,image.width,image.height,event.metaState and android.view.KeyEvent.META_ALT_ON!=0)
                ScreenshotDrawingTool.ERASER -> for(index in 0 until event.pointerCount) {
                    val id=event.getPointerId(index)
                    // 只处理本工具跟踪过的手指：POINTER_DOWN 落在框外等情况不会进入 active_pointers。
                    if(id in eraserPointers) eraseTo(id,eraserPoint(event,index))
                }
                else -> pending?.let { points ->
                    val builder=pen
                    if(builder!=null) {
                        // Dragging shows the same simplified vertices that lifting will store.
                        val sample=penPoint(point)
                        if(builder.append(sample)) pending=builder.preview().toMutableList()
                        if(continuation!=null && penTouch) penFeedback.move(sample,point)
                    } else if(active==ScreenshotDrawingTool.LINE) {
                        if(pendingStyle.curved) {
                            val last=points.last()
                            if(hypot(point.x-last.x,point.y-last.y)>=LINE_SAMPLE_SPACING) points.add(point)
                        } else if(points.size==1) points.add(point) else points[points.lastIndex]=point
                    } else if(active==ScreenshotDrawingTool.ARROW) {
                        when(pendingStyle.arrowType) {
                            ScreenshotArrowType.CURVE -> {
                                val last=points.last()
                                if(hypot(point.x-last.x,point.y-last.y)>=LINE_SAMPLE_SPACING) points.add(point)
                            }
                            ScreenshotArrowType.ELBOW -> {
                                // Preview the routed dog-leg, not the straight drag, so lifting does not jump.
                                val routed=ScreenshotArrowRendering.elbowPoints(down,point)
                                points.clear(); points.addAll(routed)
                            }
                            ScreenshotArrowType.STRAIGHT -> if(points.size==1) points.add(point) else points[points.lastIndex]=point
                        }
                    } else {
                        val freehand=active==ScreenshotDrawingTool.PEN || (active in setOf(ScreenshotDrawingTool.FILTER,ScreenshotDrawingTool.HIGHLIGHT) && pendingStyle.area==ScreenshotAreaMode.PEN)
                        if(freehand || points.size==1) points.add(point) else points[points.lastIndex]=point
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                selectPanActive=false
                if(quickDrag) selection.release(point,event.metaState and android.view.KeyEvent.META_SHIFT_ON!=0,image.width,image.height,event.metaState and android.view.KeyEvent.META_ALT_ON!=0)
                else if(!cancelled) when(active) {
                    ScreenshotDrawingTool.SELECT -> selection.release(point,event.metaState and android.view.KeyEvent.META_SHIFT_ON!=0,image.width,image.height,event.metaState and android.view.KeyEvent.META_ALT_ON!=0)
                    ScreenshotDrawingTool.ERASER -> {
                        eraseLift(event.getPointerId(0),point)
                        // 正常序列下 POINTER_UP 已经把其余手指移走；异常序列残留时一并清掉，
                        // 避免把上一轮的指针位置带进下一次手势。
                        if(eraserPointers.isNotEmpty()) eraserPointers.clear()
                        commitEraser()
                    }
                    ScreenshotDrawingTool.TEXT -> onTextRequest?.invoke(point,null)
                    ScreenshotDrawingTool.SERIAL -> {
                        val serial=ScreenshotDrawingStroke(active,listOf(down),pendingStyle.color,pendingStyle.width*pixelUnit,pendingStyle,number=nextNumber)
                        val withText=hypot(point.x-down.x,point.y-down.y)>=3*unit
                        if(withText) history.addSerialWithText(serial,ScreenshotSerialLabels.create(serial,serialLabelStyle,fontUnit,pixelUnit,point))
                        else history.add(serial)
                        if(pendingStyle.serialShape!=ScreenshotSerialShape.CIRCLE && nextNumber<Long.MAX_VALUE) nextNumber++
                        pending=null
                        if(withText) openSerialText(history.items.lastIndex)
                    }
                    else -> {
                        val builder=pen
                        if(builder!=null) {
                            val target=continuation
                            builder.append(penPoint(point))
                            if(target==null || (target.moved && target.valid(history))) {
                                val result=builder.finish()
                                if(target==null) history.add(ScreenshotDrawingStroke(active,result.points,pendingStyle.color,
                                    pendingStyle.width*pixelUnit,pendingStyle,smooth=true,closedPath=result.closed))
                                else history.replace(target.index,renderer.withWorldPoints(target.original,result.points).copy(closedPath=result.closed))
                            }
                        } else pending?.let { points ->
                            val freehand=active==ScreenshotDrawingTool.PEN || (active in setOf(ScreenshotDrawingTool.FILTER,ScreenshotDrawingTool.HIGHLIGHT) && pendingStyle.area==ScreenshotAreaMode.PEN)
                            if(active==ScreenshotDrawingTool.LINE) {
                                if(pendingStyle.curved) {
                                    val last=points.last()
                                    if(hypot(point.x-last.x,point.y-last.y)>=LINE_SAMPLE_SPACING) points.add(point)
                                } else if(points.size==1) points.add(point) else points[points.lastIndex]=point
                            } else if(active==ScreenshotDrawingTool.ARROW) {
                                when(pendingStyle.arrowType) {
                                    ScreenshotArrowType.CURVE -> {
                                        val last=points.last()
                                        if(hypot(point.x-last.x,point.y-last.y)>=LINE_SAMPLE_SPACING) points.add(point)
                                    }
                                    ScreenshotArrowType.ELBOW -> {
                                        val routed=ScreenshotArrowRendering.elbowPoints(down,point)
                                        points.clear(); points.addAll(routed)
                                    }
                                    ScreenshotArrowType.STRAIGHT -> if(points.size==1) points.add(point) else points[points.lastIndex]=point
                                }
                            } else if(freehand || points.size==1) points.add(point) else points[points.lastIndex]=point
                            // A dragged curve stores the sampled path; collapse it to the vertex set
                            // the head geometry measures against (see `simplifyCurve`).
                            val stored=if(active==ScreenshotDrawingTool.ARROW && pendingStyle.arrowType==ScreenshotArrowType.CURVE)
                                ScreenshotArrowRendering.simplifyCurve(points,LINE_SAMPLE_SPACING) else points
                            history.add(ScreenshotDrawingStroke(active,stored,pendingStyle.color,pendingStyle.width*pixelUnit,pendingStyle))
                        }
                    }
                }
                pending=null; pen=null; continuation=null; erased.clear(); selection.cancel(); penFeedback.clear(); cancelled=false; quickDrag=false; changed()
            }
        }
        invalidate(); return true
    }
    /** `resetCanvasRequested`: drop every mark and the watermark; undo brings them back. */
    fun resetCanvas() {
        pending=null; pen=null; continuation=null; penFeedback.clear(); quickDrag=false; selection.cancel(); selectedIndex=null
        history.reset(); invalidate(); changed()
    }
    fun undo() { pending=null; pen=null; continuation=null; penFeedback.clear(); selectedIndex=null; history.undo(); invalidate(); changed() }
    fun redo() { pending=null; pen=null; continuation=null; penFeedback.clear(); selectedIndex=null; history.redo(); invalidate(); changed() }
    private fun touchAutoFilter(event: MotionEvent, point: ScreenshotDrawingPoint): Boolean {
        val session = autoFilterSession ?: return true
        val regions = session.result
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                autoFilterPress = point
                autoFilterPressRegions = regions
                session.hits = listOfNotNull(ScreenshotAutoFilter.hit(regions.orEmpty(), point.x, point.y))
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val start = autoFilterPress
                if (start != null && regions != null && regions === autoFilterPressRegions) {
                    val dragging = hypot((point.x - start.x) * width / image.width,
                        (point.y - start.y) * height / image.height) >= slop
                    session.hits = if (dragging) ScreenshotAutoFilter.intersecting(regions, start, point)
                        else listOfNotNull(ScreenshotAutoFilter.hit(regions, point.x, point.y))
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        history.fillAutoRegions(session.hits, style, pixelUnit, toggle = true)
                        changed()
                    }
                }
            }
        }
        if (event.actionMasked in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN)) {
            autoFilterPress = null
            autoFilterPressRegions = null
            session.hits = emptyList()
        }
        invalidate()
        return true
    }

    fun close() { autoFilterSession?.close(); pending=null; pen=null; continuation=null; penFeedback.clear(); selection.cancel(); cancelTextPreview(); finishStylePreview(false); renderer.close() }
    fun render(crop: Rect,composed: Bitmap?): Bitmap? {
        // The watermark is not an object, but it still has to be composed into the export.
        if(history.items.isEmpty() && !watermark.isVisible()) return composed
        val output=composed ?: Bitmap.createBitmap(image,crop.left,crop.top,crop.width(),crop.height()).let {
            if(it!==image && it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888,true).also { _ -> if(it!==image) it.recycle() }
        }
        val result=if(output.isMutable) output else output.copy(Bitmap.Config.ARGB_8888,true).also { output.recycle() }
        val canvas=Canvas(result)
        canvas.save(); canvas.clipRect(0,0,crop.width(),minOf(crop.height(),result.height)); canvas.translate(-crop.left.toFloat(),-crop.top.toFloat())
        renderer.draw(canvas,history.items,crop,watermark); canvas.restore(); return result
    }
}
