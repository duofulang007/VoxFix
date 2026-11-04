package local.voxfix.debug

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class ScreenshotDrawingLayer(context: Context,private val image: Bitmap,private val memory: ScreenshotToolMemory?=null,private val changed: () -> Unit) : View(context) {
    val history=ScreenshotDrawing()
    var region: Rect?=null
    var onTextRequest: ((ScreenshotDrawingPoint,Int?) -> Unit)?=null
    var selectedIndex: Int?=null
        private set
    val selected: ScreenshotDrawingStroke? get()=selectedIndex?.let { history.items.getOrNull(it) }
    val inspectedStyle: ScreenshotDrawingStyle get()=selected?.let {
        it.style.copy(color=it.color,width=(it.width/pixelUnit).roundToInt().coerceIn(1,64),fontSize=(it.style.fontSize*it.fontScale).roundToInt().coerceIn(8,96))
    } ?: style
    private val toolStyles=mutableMapOf<ScreenshotDrawingTool,ScreenshotDrawingStyle>()
    private val areaStyles=mutableMapOf<Pair<ScreenshotDrawingTool,ScreenshotAreaMode>,ScreenshotDrawingStyle>()
    var style=ScreenshotDrawingStyle()
        private set
    var tool: ScreenshotDrawingTool?=null
        set(value) {
            if(field==value) return
            finishStylePreview(false)
            field?.let { toolStyles[it]=style }
            field=value; pending=null; transform=null
            if(value!=ScreenshotDrawingTool.SELECT) selectedIndex=null
            style=toolStyles[value] ?: value?.let { memory?.style(it) } ?: defaultScreenshotDrawingStyle(value)
            invalidate()
        }
    var color: Int
        get()=style.color
        set(value) { applyStyle(style.copy(color=value)) }
    var strokeWidthDp: Int
        get()=style.width
        set(value) { applyStyle(style.copy(width=value)) }
    var nextNumber=memory?.startNumber ?: 1
    private var pending: MutableList<ScreenshotDrawingPoint>?=null
    private var cancelled=false
    private var pendingStyle=style
    private var down=ScreenshotDrawingPoint(0f,0f)
    private var lastEraser=ScreenshotDrawingPoint(0f,0f)
    private var transform: ScreenshotDrawingStroke?=null
    private var original: ScreenshotDrawingStroke?=null
    private var originalBounds=RectF()
    private var handle=-1
    /** Objects under the last selection tap, topmost first; the second one is reachable by cycling. */
    private var tapCandidates: List<Int> = emptyList()
    private var dragging=false
    private var previewOriginal: ScreenshotDrawingStyle?=null
    private var previewValue: ScreenshotDrawingStyle?=null
    private var textPreview: ScreenshotDrawingStroke?=null
    private var textPreviewIndex: Int?=null
    private val erased=mutableSetOf<Int>()
    var savedPixelUnit: Float=0f
    var savedFontUnit: Float=0f
    val pixelUnit get()=savedPixelUnit.takeIf { it>0 } ?: (resources.displayMetrics.density*image.width/(width.takeIf { it>0 } ?: image.width))
    val fontUnit get()=savedFontUnit.takeIf { it>0 } ?: (resources.displayMetrics.scaledDensity*image.width/(width.takeIf { it>0 } ?: image.width))
    private val unit get()=resources.displayMetrics.density*image.width/(width.takeIf { it>0 } ?: image.width)
    private val renderer=ScreenshotDrawingRenderer(image) { fontUnit }

    init { contentDescription="截图绘图区域"; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun applyStyle(value: ScreenshotDrawingStyle) {
        previewOriginal=null; previewValue=null
        val active=selected?.tool ?: tool
        if(active in setOf(ScreenshotDrawingTool.FILTER,ScreenshotDrawingTool.HIGHLIGHT) && value.area!=style.area) {
            areaStyles[active!! to style.area]=style
        }
        style=value
        if(selectedIndex==null && active!=null) memory?.save(active,value)
        selectedIndex?.let { index -> selected?.let { history.replace(index,it.copy(color=value.color,width=value.width*pixelUnit,style=value,fontScale=1f)) } }
        invalidate(); changed()
    }
    fun changeArea(area: ScreenshotAreaMode) {
        val active=selected?.tool ?: tool ?: return
        applyStyle(areaStyles[active to area] ?: memory?.style(active,area) ?: inspectedStyle.copy(area=area))
    }
    fun beginStylePreview() { if(previewOriginal==null) previewOriginal=inspectedStyle }
    fun previewStyle(value: ScreenshotDrawingStyle) { beginStylePreview(); previewValue=value; invalidate() }
    fun finishStylePreview(commit: Boolean) {
        val original=previewOriginal ?: return
        val value=previewValue ?: original
        previewOriginal=null; previewValue=null
        if(commit) applyStyle(value) else { style=original; invalidate() }
    }
    fun beginTextPreview(point: ScreenshotDrawingPoint,index: Int?) {
        textPreviewIndex=index
        textPreview=index?.let { history.items.getOrNull(it) } ?: ScreenshotDrawingStroke(ScreenshotDrawingTool.TEXT,listOf(point),
            style.color,style.width*pixelUnit,style,textWidth=minOf(240*pixelUnit,(image.width-point.x).coerceAtLeast(1f)))
    }
    fun previewText(value: String) { textPreview=textPreview?.copy(text=value); invalidate() }
    fun cancelTextPreview() { textPreview=null; textPreviewIndex=null; invalidate() }
    fun textBounds(point: ScreenshotDrawingPoint,index: Int?): RectF = renderer.bounds(index?.let { history.items[it] }
        ?: ScreenshotDrawingStroke(ScreenshotDrawingTool.TEXT,listOf(point),style.color,style.width*pixelUnit,style,text=" ",
            textWidth=minOf(240*pixelUnit,(image.width-point.x).coerceAtLeast(1f))))
    fun setNumber(value: Int) {
        nextNumber=value.coerceIn(1,9999)
        if(selectedIndex==null) memory?.startNumber=nextNumber
        selectedIndex?.let { index -> selected?.takeIf { it.tool==ScreenshotDrawingTool.SERIAL }?.let { history.replace(index,it.copy(number=nextNumber)) } }
        invalidate(); changed()
    }
    fun addText(point: ScreenshotDrawingPoint,text: String,index: Int?=null) {
        cancelTextPreview()
        if(text.isBlank()) { if(index!=null) { history.remove(setOf(index)); selectedIndex=null; invalidate(); changed() }; return }
        val editedIndex=index?.takeIf { it in history.items.indices }
        if(editedIndex!=null) history.replace(editedIndex,history.items[editedIndex].copy(text=text))
        else history.add(ScreenshotDrawingStroke(ScreenshotDrawingTool.TEXT,listOf(point),style.color,style.width*pixelUnit,style,text=text,
            textWidth=minOf(240*pixelUnit,(image.width-point.x).coerceAtLeast(1f))))
        // A finished text mark is handed to the selection tool: the usual next step is placing it.
        tool=ScreenshotDrawingTool.SELECT
        selectedIndex=editedIndex ?: history.items.lastIndex
        tapCandidates=listOf(selectedIndex!!)
        invalidate(); changed()
    }
    fun editSelectedText() { selected?.takeIf { it.tool==ScreenshotDrawingTool.TEXT }?.let { onTextRequest?.invoke(it.points.first(),selectedIndex) } }
    fun deleteSelected() { selectedIndex?.let { history.remove(setOf(it)) }; selectedIndex=null; tapCandidates=emptyList(); invalidate(); changed() }
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
        val result=history.items.mapIndexedNotNull { index,stroke ->
            if(index in erased) null else if(index==textPreviewIndex && textPreview!=null) textPreview
            else if(index==selectedIndex && previewValue!=null) stroke.copy(color=previewValue!!.color,width=previewValue!!.width*pixelUnit,style=previewValue!!,fontScale=1f)
            else if(index==selectedIndex && transform!=null) transform else stroke
        }.toMutableList()
        if(textPreviewIndex==null) textPreview?.let(result::add)
        if(includePending) pending?.let { points -> tool?.let { active ->
            result.add(ScreenshotDrawingStroke(active,points,pendingStyle.color,pendingStyle.width*pixelUnit,pendingStyle,number=nextNumber))
        } }
        return result
    }
    override fun onDraw(canvas: Canvas) {
        val crop=region ?: return
        if(width==0 || height==0 || image.isRecycled) return
        // Drawing may start outside the capture box; it stays visible there (over the dimmed area) so
        // the gesture is not silently clipped. Export still only keeps what is inside the box.
        canvas.save(); canvas.scale(width.toFloat()/image.width,height.toFloat()/image.height)
        renderer.draw(canvas,marks(true),Rect(0,0,image.width,image.height))
        selected?.takeIf { tool==ScreenshotDrawingTool.SELECT }?.let {
            val box=renderer.bounds(transform ?: it)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xFF1677FF.toInt(); style=Paint.Style.STROKE; strokeWidth=unit }
            canvas.drawRect(box,paint)
            paint.style=Paint.Style.FILL
            for(p in corners(box)) canvas.drawCircle(p.x,p.y,5*unit,paint)
        }
        canvas.restore()
    }
    private fun corners(rect: RectF)=ScreenshotSelection.corners(SelBox.of(rect))
    private fun candidates(point: ScreenshotDrawingPoint,excluded: Set<Int> = emptySet()): List<Int> {
        val outline=history.items.indices.reversed()
            .filter { it !in excluded && renderer.hit(history.items[it],point,7*unit,region,true) }
        val body=history.items.indices.reversed()
            .filter { it !in excluded && it !in outline && renderer.hit(history.items[it],point,7*unit,region,false) }
        return outline+body
    }
    /** Screen slop is expressed in bitmap pixels, which is the space the touches are mapped into. */
    private val slop get()=android.view.ViewConfiguration.get(context).scaledTouchSlop*image.width/(width.takeIf { it>0 } ?: image.width).toFloat()
    private fun eraseAt(point: ScreenshotDrawingPoint) {
        history.items.indices.filterTo(erased) { it !in erased && renderer.hit(history.items[it],point,7*unit) }
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
                down=point; erased.clear(); handle=-1; original=null; transform=null; dragging=false
                if(!cancelled) when(active) {
                    ScreenshotDrawingTool.SELECT -> {
                        tapCandidates=candidates(point)
                        val current=selected
                        if(current!=null) {
                            val box=SelBox.of(renderer.bounds(current))
                            handle=ScreenshotSelection.handle(box,point.x,point.y,ScreenshotSelection.HANDLE_TOLERANCE*unit)
                            // Tapping the inside of the current box grabs it; only a tap outside does
                            // a fresh hit test, which used to drop the selection and block moving.
                            if(handle<0 && !ScreenshotSelection.inside(box,point.x,point.y,7*unit)) selectedIndex=tapCandidates.firstOrNull()
                        } else selectedIndex=tapCandidates.firstOrNull()
                        selected?.let { stroke -> original=stroke; originalBounds=renderer.bounds(stroke); style=stroke.style }
                        changed()
                    }
                    ScreenshotDrawingTool.ERASER -> { lastEraser=point; eraseAt(point) }
                    ScreenshotDrawingTool.TEXT -> Unit
                    else -> { pendingStyle=style; pending=mutableListOf(point) }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL -> { cancelled=true; pending=null; erased.clear(); transform=null; dragging=false }
            MotionEvent.ACTION_MOVE -> if(!cancelled) when(active) {
                ScreenshotDrawingTool.SELECT -> original?.let { stroke ->
                    val box=originalBounds
                    if(!dragging && hypot(point.x-down.x,point.y-down.y)>=slop) dragging=true
                    if(dragging) {
                        val selected=SelBox.of(box)
                        // Marks may live outside the capture box, so movement is bounded by the image.
                        val canvas=SelBox(0f,0f,image.width.toFloat(),image.height.toFloat())
                        if(handle<0) {
                            val dx=ScreenshotSelection.moveX(selected,canvas,point.x-down.x)
                            val dy=ScreenshotSelection.moveY(selected,canvas,point.y-down.y)
                            transform=stroke.transformed(box.left,box.top,box.left+dx,box.top+dy)
                        } else {
                            val next=ScreenshotSelection.resize(selected,handle,point.x,point.y,ScreenshotSelection.MIN_SIZE*unit)
                            transform=stroke.copy(textHeight=box.height()).transformed(box.left,box.top,next.left,next.top,
                                next.width/box.width().coerceAtLeast(1f),next.height/box.height().coerceAtLeast(1f))
                        }
                    }
                }
                ScreenshotDrawingTool.ERASER -> eraseTo(point)
                else -> pending?.let { points ->
                    val freehand=active==ScreenshotDrawingTool.PEN || (active in setOf(ScreenshotDrawingTool.FILTER,ScreenshotDrawingTool.HIGHLIGHT) && pendingStyle.area==ScreenshotAreaMode.PEN)
                    if(freehand || points.size==1) points.add(point) else points[points.lastIndex]=point
                }
            }
            MotionEvent.ACTION_UP -> {
                if(!cancelled) when(active) {
                    ScreenshotDrawingTool.SELECT -> selectedIndex?.let { index -> transform?.let { history.replace(index,it) } }
                    ScreenshotDrawingTool.ERASER -> { eraseTo(point); history.remove(erased); selectedIndex=null; tapCandidates=emptyList() }
                    ScreenshotDrawingTool.TEXT -> onTextRequest?.invoke(point,null)
                    ScreenshotDrawingTool.SERIAL -> {
                        history.add(ScreenshotDrawingStroke(active,listOf(point),style.color,style.width*pixelUnit,style,number=nextNumber)); nextNumber++
                    }
                    else -> pending?.let { points ->
                        val freehand=active==ScreenshotDrawingTool.PEN || (active in setOf(ScreenshotDrawingTool.FILTER,ScreenshotDrawingTool.HIGHLIGHT) && pendingStyle.area==ScreenshotAreaMode.PEN)
                        if(freehand || points.size==1) points.add(point) else points[points.lastIndex]=point
                        history.add(ScreenshotDrawingStroke(active,points,pendingStyle.color,pendingStyle.width*pixelUnit,pendingStyle))
                    }
                }
                pending=null; erased.clear(); transform=null; original=null; cancelled=false; dragging=false; changed()
            }
        }
        invalidate(); return true
    }
    fun undo() { pending=null; selectedIndex=null; history.undo(); invalidate(); changed() }
    fun redo() { selectedIndex=null; history.redo(); invalidate(); changed() }
    fun close() { cancelTextPreview(); finishStylePreview(false); renderer.close() }
    fun render(crop: Rect,composed: Bitmap?): Bitmap? {
        if(history.items.isEmpty()) return composed
        val output=composed ?: Bitmap.createBitmap(image,crop.left,crop.top,crop.width(),crop.height()).let {
            if(it!==image && it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888,true).also { _ -> if(it!==image) it.recycle() }
        }
        val result=if(output.isMutable) output else output.copy(Bitmap.Config.ARGB_8888,true).also { output.recycle() }
        val canvas=Canvas(result)
        canvas.save(); canvas.clipRect(0,0,crop.width(),minOf(crop.height(),result.height)); canvas.translate(-crop.left.toFloat(),-crop.top.toFloat())
        renderer.draw(canvas,history.items,crop); canvas.restore(); return result
    }
}
