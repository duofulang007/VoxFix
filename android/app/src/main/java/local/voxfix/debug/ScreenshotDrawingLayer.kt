package local.voxfix.debug

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.*

class ScreenshotDrawingLayer(context: Context,private val image: Bitmap,private val memory: ScreenshotToolMemory?=null,private val changed: () -> Unit) : View(context) {
    val history=ScreenshotDrawing()
    var region: Rect?=null
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
    var tool: ScreenshotDrawingTool?=null
        set(value) {
            if(field==value) return
            finishStylePreview(false)
            field?.let { toolStyles[it]=style }
            field=value; pending=null; pen=null; continuation=null; selection.cancel(); penFeedback.clear()
            if(value!=ScreenshotDrawingTool.SELECT) selectedIndex=null
            style=toolStyles[value] ?: value?.let { memory?.style(it) } ?: defaultScreenshotDrawingStyle(value)
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
    private var lastEraser=ScreenshotDrawingPoint(0f,0f)
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
    val pixelUnit get()=savedPixelUnit.takeIf { it>0 } ?: (resources.displayMetrics.density*image.width/(width.takeIf { it>0 } ?: image.width))
    val fontUnit get()=savedFontUnit.takeIf { it>0 } ?: (resources.displayMetrics.scaledDensity*image.width/(width.takeIf { it>0 } ?: image.width))
    private val unit get()=resources.displayMetrics.density*image.width/(width.takeIf { it>0 } ?: image.width)
    private val renderer=ScreenshotDrawingRenderer(image) { fontUnit }
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
            else textPreview?.copy(text=value)
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
            history.replace(editedIndex,if(isSerialLabel(editedIndex)) renderer.fitSerialLabel(value) else value)
        }
        else history.add(ScreenshotDrawingStroke(ScreenshotDrawingTool.TEXT,listOf(point),style.color,style.width*pixelUnit,style,text=text,
            textWidth=minOf(240*pixelUnit,(image.width-point.x).coerceAtLeast(1f))))
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
        renderer.draw(canvas,marks(true),Rect(0,0,image.width,image.height),watermark)
        if(tool==ScreenshotDrawingTool.SELECT) selection.draw(canvas)
        canvas.restore()
        if(tool==ScreenshotDrawingTool.PEN && (pending==null || continuation!=null))
            penFeedback.draw(canvas,if(pending==null) history.items.flatMap { ScreenshotPenContinuation.endpoints(it,renderer) } else emptyList())
    }
    override fun onDetachedFromWindow() {
        pending=null; pen=null; continuation=null; penFeedback.clear(); selection.cancel()
        super.onDetachedFromWindow()
    }
    private fun penPoint(point: ScreenshotDrawingPoint): ScreenshotDrawingPoint {
        val sample=continuation?.sample(point) ?: return point
        return ScreenshotDrawingPoint(sample.x.coerceIn(0f,image.width.toFloat()),sample.y.coerceIn(0f,image.height.toFloat()))
    }
    private fun candidates(point: ScreenshotDrawingPoint,excluded: Set<Int> = emptySet()): List<Int> {
        val outline=history.items.indices.reversed()
            .filter { it !in excluded && renderer.hit(history.items[it],point,7*unit,region,true) }
        val body=history.items.indices.reversed()
            .filter { it !in excluded && it !in outline && renderer.hit(history.items[it],point,7*unit,region,false) }
        return outline+body
    }
    private val ERASER_RADIUS get()=8f
    /** Curve lines sample the drag path; below this spacing the extra vertices add nothing. */
    private val LINE_SAMPLE_SPACING get()=1.5f*unit
    /** Screen slop is expressed in bitmap pixels, which is the space the touches are mapped into. */
    private val slop get()=android.view.ViewConfiguration.get(context).scaledTouchSlop*image.width/(width.takeIf { it>0 } ?: image.width).toFloat()
    /** Snow Shot's eraser tolerance is an 8px viewport radius (`ERASER_RADIUS_VIEW`). */
    private fun eraseAt(point: ScreenshotDrawingPoint) {
        history.items.indices.filterTo(erased) { it !in erased && renderer.hit(history.items[it],point,ERASER_RADIUS*unit) }
    }
    private fun eraseTo(point: ScreenshotDrawingPoint) {
        val from=lastEraser
        val steps=ceil(hypot(point.x-from.x,point.y-from.y)/(4*unit)).toInt().coerceIn(1,4096)
        for(step in 1..steps) {
            val t=step.toFloat()/steps
            val sample=ScreenshotDrawingPoint(from.x+(point.x-from.x)*t,from.y+(point.y-from.y)*t)
            eraseAt(sample)
        }
        lastEraser=point
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val active=tool ?: return false
        if(region==null) return false
        if(width==0 || height==0) return false
        val rawX=event.x*image.width/width
        val rawY=event.y*image.height/height
        val point=ScreenshotDrawingPoint(rawX.coerceIn(0f,image.width.toFloat()),rawY.coerceIn(0f,image.height.toFloat()))
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelled=rawX<0 || rawY<0 || rawX>image.width || rawY>image.height
                down=point; erased.clear(); selection.cancel(); continuation=null; penFeedback.clear()
                penTouch=active==ScreenshotDrawingTool.PEN && event.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER
                if(!cancelled) when(active) {
                    ScreenshotDrawingTool.SELECT -> {
                        tapCandidates=candidates(point)
                        selection.press(point,event.metaState and android.view.KeyEvent.META_SHIFT_ON!=0,tapCandidates)
                        selected?.let { style=it.style }
                        changed()
                    }
                    ScreenshotDrawingTool.ERASER -> { lastEraser=point; eraseAt(point) }
                    ScreenshotDrawingTool.TEXT -> Unit
                    ScreenshotDrawingTool.WATERMARK -> Unit
                    else -> {
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
            MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL -> { cancelled=true; pending=null; pen=null; continuation=null; erased.clear(); selection.cancel(); penFeedback.clear() }
            MotionEvent.ACTION_MOVE -> if(!cancelled) when(active) {
                ScreenshotDrawingTool.SELECT -> selection.move(point,event.metaState and android.view.KeyEvent.META_SHIFT_ON!=0,image.width,image.height,event.metaState and android.view.KeyEvent.META_ALT_ON!=0)
                ScreenshotDrawingTool.ERASER -> eraseTo(point)
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
                if(!cancelled) when(active) {
                    ScreenshotDrawingTool.SELECT -> selection.release(point,event.metaState and android.view.KeyEvent.META_SHIFT_ON!=0,image.width,image.height,event.metaState and android.view.KeyEvent.META_ALT_ON!=0)
                    ScreenshotDrawingTool.ERASER -> { eraseTo(point); history.remove(erased); selectedIndex=null; tapCandidates=emptyList() }
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
                pending=null; pen=null; continuation=null; erased.clear(); selection.cancel(); penFeedback.clear(); cancelled=false; changed()
            }
        }
        invalidate(); return true
    }
    fun undo() { pending=null; pen=null; continuation=null; penFeedback.clear(); selectedIndex=null; history.undo(); invalidate(); changed() }
    fun redo() { pending=null; pen=null; continuation=null; penFeedback.clear(); selectedIndex=null; history.redo(); invalidate(); changed() }
    fun close() { pending=null; pen=null; continuation=null; penFeedback.clear(); selection.cancel(); cancelTextPreview(); finishStylePreview(false); renderer.close() }
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
