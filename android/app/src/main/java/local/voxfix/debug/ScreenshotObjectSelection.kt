package local.voxfix.debug

import android.graphics.*
import kotlin.math.*

/** T22 selection transaction; coordinates are image pixels, never view pixels. */
internal class ScreenshotObjectSelection(private val history: ScreenshotDrawing,
    private val renderer: ScreenshotDrawingRenderer,private val unit: ()->Float,private val slop: ()->Float) {
    private var ids: Set<Long> = emptySet()
    val indices: Set<Int> get() {
        val byId=history.items.mapIndexed { index,stroke -> stroke.id to index }.toMap()
        return ids.mapNotNull { byId[it] }.toCollection(linkedSetOf())
    }
    val primary: Int? get()=indices.lastOrNull()
    var preview: Map<Int,ScreenshotDrawingStroke> = emptyMap()
        private set
    private var original: Map<Int,ScreenshotDrawingStroke> = emptyMap()
    private var originalBox=RectF()
    private var frameRotation=0f
    private var previewRotation=0f
    private var committedBox: RectF?=null
    private var committedRotation=0f
    private var committedObjects: Map<Int,ScreenshotDrawingStroke> = emptyMap()
    private var liveBox: RectF?=null
    private var down=ScreenshotDrawingPoint(0f,0f)
    private var pointer=down
    private var baseIds: Set<Long> = emptySet()
    private var mode=Mode.NONE
    private var handle=-1
    private var resizeHandle: ScreenshotResizeHandle?=null
    private var segment: ScreenshotLinearHandles.Segment?=null
    private var elbowRoute: ScreenshotElbowSegment.Route?=null
    private var moving=false
    private var additive=false
    private enum class Mode { NONE, MARQUEE, MOVE, RESIZE, ROTATE, POINT, SEGMENT }

    fun select(index: Int?) {
        selectIndices(index?.let(::setOf) ?: emptySet())
    }
    fun selectIndices(selected: Set<Int>) {
        cancel(); committedBox=null
        ids=selected.mapNotNullTo(linkedSetOf()) { history.items.getOrNull(it)?.id }
    }
    fun align(alignment: ScreenshotSelectionAlignment): Boolean {
        val selected = indices
        if (selected.size < alignment.minimumCount) return false
        val items = history.items
        val indexById = items.withIndex().associate { (index, stroke) -> stroke.id to index }
        val labels = selected.mapNotNull { index -> indexById[items[index].boundTextId]?.let { index to it } }.toMap()
        val ownerByLabel = labels.entries.associate { it.value to it.key }
        val units = selected.mapTo(linkedSetOf()) { ownerByLabel[it] ?: it }
        val bounds = units.associateWith { index ->
            val box = renderer.selectionWorldBounds(items[index])
            labels[index]?.let { box.union(renderer.selectionWorldBounds(items[it])) }
            SelBox.of(box)
        }
        val changes = mutableMapOf<Int, ScreenshotDrawingStroke>()
        for ((index, delta) in alignment.deltas(bounds)) {
            if (delta.x == 0f && delta.y == 0f) continue
            changes[index] = items[index].transformed(0f, 0f, delta.x, delta.y)
            labels[index]?.let { changes[it] = items[it].transformed(0f, 0f, delta.x, delta.y) }
        }
        if (changes.isEmpty()) return false
        history.replaceAll(changes)
        committedBox = null
        return true
    }
    private fun box(strokes: Map<Int,ScreenshotDrawingStroke>): RectF? {
        if(strokes.isEmpty()) return null
        if(strokes==committedObjects) committedBox?.let { return RectF(it) }
        if(strokes.size==1) return renderer.selectionBounds(strokes.values.single())
        return RectF().also { result -> strokes.values.forEachIndexed { i,it ->
            val bounds=renderer.selectionWorldBounds(it); if(i==0) result.set(bounds) else result.union(bounds)
        } }
    }
    private fun angle(strokes: Map<Int,ScreenshotDrawingStroke>)=
        if(strokes==committedObjects && committedBox!=null) committedRotation
        else if(strokes.size==1) strokes.values.single().rotation else 0f
    private fun current()=indices.associateWith { preview[it] ?: history.items[it] }
    private fun center(box: RectF)=ScreenshotDrawingPoint(box.centerX(),box.centerY())
    private fun local(point: ScreenshotDrawingPoint,box: RectF,angle: Float)=
        ScreenshotObjectTransform.rotate(point,center(box),-angle)
    private fun framePadding(strokes: Map<Int,ScreenshotDrawingStroke>): Float=when {
        strokes.size==1 && strokes.values.single().tool==ScreenshotDrawingTool.TEXT -> 14f*unit()
        strokes.values.all { it.tool==ScreenshotDrawingTool.ARROW || it.tool==ScreenshotDrawingTool.LINE } -> 0f
        else -> 4f*unit()
    }
    private fun rotationPoint(box: RectF,angle: Float,padding: Float)=ScreenshotObjectTransform.rotate(
        ScreenshotDrawingPoint(box.centerX(),box.top-padding-20*unit()),center(box),angle)
    private fun cornerOutset(strokes: Map<Int,ScreenshotDrawingStroke>)=
        if(strokes.values.all { it.tool==ScreenshotDrawingTool.ARROW || it.tool==ScreenshotDrawingTool.LINE }) 8f*unit() else 0f
    private fun near(a: ScreenshotDrawingPoint,b: ScreenshotDrawingPoint,radius: Float)=hypot(a.x-b.x,a.y-b.y)<=radius
    private fun pointHandles(stroke: ScreenshotDrawingStroke): List<ScreenshotDrawingPoint> {
        if(stroke.tool!=ScreenshotDrawingTool.LINE && stroke.tool!=ScreenshotDrawingTool.ARROW) return emptyList()
        if(stroke.points.size<2) return emptyList()
        return renderer.worldPoints(stroke)
    }
    fun press(point: ScreenshotDrawingPoint,shift: Boolean,candidates: List<Int>) {
        cancel(); baseIds=ids; down=point; pointer=point; additive=shift
        if(shift && candidates.isNotEmpty()) {
            val id=history.items[candidates.first()].id
            ids=if(id in ids) ids-id else ids+id
            return
        }
        var strokes=current()
        var bounds=box(strokes)
        var angle=angle(strokes)
        if(bounds!=null) {
            val local=local(point,bounds,angle)
            val single=strokes.values.singleOrNull()
            val points=if(strokes.size==1) pointHandles(strokes.values.single()) else emptyList()
            handle=single?.let { ScreenshotLinearHandles.pointHit(it,points,point,unit()) } ?: -1
            segment=single?.let { ScreenshotLinearHandles.segmentHit(it,points,point,unit()) }
            val frameVisible=ScreenshotLinearHandles.frameVisible(strokes.values)
            mode=when {
                handle>=0 -> Mode.POINT
                segment!=null -> Mode.SEGMENT
                single!=null && single.tool in setOf(ScreenshotDrawingTool.LINE,ScreenshotDrawingTool.ARROW) && renderer.hit(single,point,4*unit(),null,false) -> Mode.MOVE
                frameVisible && near(rotationPoint(bounds,angle,framePadding(strokes)),point,6*unit()) -> Mode.ROTATE
                else -> {
                    val padding=framePadding(strokes)
                    val padded=RectF(bounds).apply { inset(-padding,-padding) }
                    val textRing=strokes.size==1 && strokes.values.single().tool==ScreenshotDrawingTool.TEXT &&
                        local.x>padded.left && local.x<padded.right && local.y>padded.top && local.y<padded.bottom &&
                        !bounds.contains(local.x,local.y)
                    resizeHandle=if(textRing || !frameVisible) null else ScreenshotSelectionResize.hit(SelBox.of(bounds),local,padding,cornerOutset(strokes),unit(),padding==0f)
                    when {
                        textRing -> Mode.MOVE
                        resizeHandle!=null -> Mode.RESIZE
                        frameVisible && local.x in bounds.left..bounds.right && local.y in bounds.top..bounds.bottom -> Mode.MOVE
                        else -> Mode.NONE
                    }
                }
            }
        }
        if(mode==Mode.NONE) {
            val hit=candidates.firstOrNull()
            if(hit==null) {
                if(!shift) ids=emptySet()
                mode=Mode.MARQUEE; return
            }
            if(hit !in indices) select(hit)
            strokes=current(); bounds=box(strokes); angle=angle(strokes)
            mode=Mode.MOVE
        }
        original=strokes; originalBox.set(bounds!!); frameRotation=angle; previewRotation=angle
        if(mode==Mode.SEGMENT) strokes.values.singleOrNull()?.takeIf(ScreenshotElbowSegment::isElbow)?.let {
            elbowRoute=ScreenshotElbowSegment.Route(it.points,it.fixedElbowSegments,segment!!.index)
        }
    }
    fun move(point: ScreenshotDrawingPoint,shift: Boolean,imageWidth: Int,imageHeight: Int,alt: Boolean=false) {
        pointer=point
        if(!moving && hypot(point.x-down.x,point.y-down.y)>=if(mode==Mode.SEGMENT && elbowRoute==null) 10*unit() else slop()) moving=true
        if(!moving || original.isEmpty()) return
        val box=originalBox
        preview=when(mode) {
            Mode.MOVE -> {
                val selected=SelBox.of(box)
                val canvas=SelBox(0f,0f,imageWidth.toFloat(),imageHeight.toFloat())
                val dx=ScreenshotSelection.moveX(selected,canvas,point.x-down.x)
                val dy=ScreenshotSelection.moveY(selected,canvas,point.y-down.y)
                liveBox=RectF(box).apply { offset(dx,dy) }; previewRotation=frameRotation
                val moved=original.mapValues { (_,stroke) -> stroke.transformed(0f,0f,dx,dy) }
                moved+boundFollowers(moved)
            }
            Mode.ROTATE -> {
                val delta=ScreenshotObjectTransform.rotationDelta(center(box),down,point,frameRotation,shift)
                previewRotation=ScreenshotObjectTransform.normalize(frameRotation+delta)
                liveBox=RectF(box)
                original.mapValues { (_,stroke) ->
                    ScreenshotObjectTransform.rotate(stroke,center(renderer.localBounds(stroke)),center(box),delta)
                }
            }
            Mode.POINT -> original.mapValues { (_,stroke) ->
                // Keep the initial pointer-to-control offset, as begin_arrow_interaction does.
                val world=pointHandles(stroke).toMutableList()
                val start=world[handle]
                val target=ScreenshotDrawingPoint(start.x+point.x-down.x,start.y+point.y-down.y)
                if (ScreenshotElbowSegment.isElbow(stroke)) {
                    val oldCenter = center(renderer.localBounds(stroke))
                    val delta = ScreenshotDrawingPoint(point.x - down.x, point.y - down.y)
                    val localDelta = ScreenshotObjectTransform.rotate(delta, ScreenshotDrawingPoint(0f, 0f), -stroke.rotation)
                    val tip = stroke.points[handle]
                    val localTarget = ScreenshotDrawingPoint(tip.x + localDelta.x, tip.y + localDelta.y)
                    keepElbowFrame(stroke, ScreenshotElbowSegment.moveEndpoint(stroke, handle, localTarget), oldCenter)
                } else {
                    val reference=world[if(handle==0) 1 else if(handle==world.lastIndex) world.lastIndex-1 else handle-1]
                    world[handle]=if(shift) ScreenshotObjectTransform.lockPoint(reference,target) else target
                    // T22: an arrow tip may attach to the element under the pointer. The result
                    // already carries the projected tip, so nothing else has to move it.
                    val bound=ScreenshotBindingDrag.dragEndpoint(stroke,world,handle,point,bindables(stroke.id))
                    if(bound==null) renderer.withWorldPoints(stroke,world)
                    else renderer.withWorldPoints(
                        stroke.copy(startBinding=bound.startBinding,endBinding=bound.endBinding),bound.points)
                }
            }
            Mode.SEGMENT -> original.mapValues { (_,stroke) ->
                if (elbowRoute!=null) moveElbow(stroke,point)
                else {
                    val points=pointHandles(stroke)
                    val inserted=ScreenshotLinearHandles.insert(points,segment!!,ScreenshotDrawingPoint(point.x-down.x,point.y-down.y),shift,unit())
                    if(inserted===points) stroke else renderer.withWorldPoints(stroke,inserted)
                }
            }
            Mode.RESIZE -> {
                val h=resizeHandle!!
                val p=local(point,box,frameRotation); val start=local(down,box,frameRotation)
                val members=original.values.map { it to SelBox.of(renderer.selectionBounds(it)) }
                val frame=SelBox.of(box)
                val g=ScreenshotSelectionResize.geometry(frame,h,ScreenshotDrawingPoint(p.x-start.x,p.y-start.y),shift,alt,members,unit())
                liveBox=ScreenshotSelectionResize.frame(frame,frameRotation,g).let { RectF(it.left,it.top,it.right,it.bottom) }
                previewRotation=frameRotation
                original.mapValues { (_,stroke) ->
                    when {
                        stroke.tool==ScreenshotDrawingTool.ARROW || stroke.tool==ScreenshotDrawingTool.LINE ->
                            renderer.withWorldPoints(stroke,renderer.worldPoints(stroke).map { ScreenshotSelectionResize.worldPoint(it,frame,frameRotation,g) })
                        original.size==1 && stroke.tool==ScreenshotDrawingTool.TEXT -> resizeText(stroke,h,g,alt)
                        else -> ScreenshotSelectionResize.member(stroke,SelBox.of(renderer.selectionBounds(stroke)),frame,frameRotation,g)
                    }
                }
            }
            else -> emptyMap()
        }
        if(mode==Mode.RESIZE && original.values.any { it.tool in setOf(ScreenshotDrawingTool.TEXT,ScreenshotDrawingTool.SERIAL,ScreenshotDrawingTool.ARROW,ScreenshotDrawingTool.LINE) }) {
            liveBox=this.box(preview); previewRotation=angle(preview)
        }
    }
    /** Bind targets for a dragged arrow: every other element, as rectangle proxies. */
    private fun bindables(excludedId: Long): List<ScreenshotBindingGeometry.Bindable> {
        val items=history.items
        val boxes=HashMap<Long,RectF>(items.size)
        for(stroke in items) boxes[stroke.id]=renderer.selectionWorldBounds(stroke)
        return ScreenshotBindingDocument.bindables(
            items.filter { it.id != excludedId },
            { stroke -> boxes[stroke.id]?.let {
                ScreenshotBindingGeometry.Bounds(it.left,it.top,it.right,it.bottom) } },
            { stroke -> items.indexOfFirst { it.id == stroke.id } })
    }
    /**
     * Arrows pinned to a moved element must follow it even though they are not selected.
     * Endpoints are resolved in world space so a rotated arrow keeps its own frame.
     */
    private fun boundFollowers(changed: Map<Int,ScreenshotDrawingStroke>): Map<Int,ScreenshotDrawingStroke> {
        if(changed.isEmpty()) return emptyMap()
        val items=history.items
        val changedIds=changed.keys.mapNotNull { items.getOrNull(it)?.id }.toSet()
        if(changedIds.isEmpty()) return emptyMap()
        val merged=items.mapIndexed { index,stroke -> changed[index] ?: stroke }
        val boxes=HashMap<Long,RectF>(merged.size)
        for(stroke in merged) boxes[stroke.id]=renderer.selectionWorldBounds(stroke)
        val targets=ScreenshotBindingDocument.bindables(merged,
            { stroke -> boxes[stroke.id]?.let {
                ScreenshotBindingGeometry.Bounds(it.left,it.top,it.right,it.bottom) } },
            { stroke -> merged.indexOfFirst { it.id == stroke.id } })
        val byId=targets.associateBy { it.id }
        val followers=HashMap<Int,ScreenshotDrawingStroke>()
        items.forEachIndexed { index,stroke ->
            if(index in changed || stroke.tool!=ScreenshotDrawingTool.ARROW) return@forEachIndexed
            val start=stroke.startBinding
            val end=stroke.endBinding
            val touched=(start!=null && start.elementId in changedIds) || (end!=null && end.elementId in changedIds)
            if(!touched) return@forEachIndexed
            val world=renderer.worldPoints(stroke).toMutableList()
            val arrow=ScreenshotBindingDocument.arrowOf(stroke).copy(points=world)
            end?.let { binding -> byId[binding.elementId]?.let { element ->
                ScreenshotBindingProjection.updateBoundPoint(arrow,ScreenshotEndpointEdge.END,binding,element,byId,false)
                    ?.let { world[world.lastIndex]=ScreenshotDrawingPoint(it[0].toFloat(),it[1].toFloat()) } } }
            start?.let { binding -> byId[binding.elementId]?.let { element ->
                ScreenshotBindingProjection.updateBoundPoint(arrow,ScreenshotEndpointEdge.START,binding,element,byId,false)
                    ?.let { world[0]=ScreenshotDrawingPoint(it[0].toFloat(),it[1].toFloat()) } } }
            followers[index]=renderer.withWorldPoints(stroke,world)
        }
        return followers
    }
    private fun moveElbow(stroke: ScreenshotDrawingStroke,point: ScreenshotDrawingPoint): ScreenshotDrawingStroke {
        val oldCenter=center(renderer.localBounds(stroke))
        val localPointer=ScreenshotObjectTransform.rotate(point,oldCenter,-stroke.rotation)
        val route=ScreenshotElbowSegment.move(elbowRoute!!,localPointer)
        elbowRoute=route
        return keepElbowFrame(stroke, stroke.copy(points=route.points,fixedElbowSegments=route.fixed), oldCenter)
    }
    private fun keepElbowFrame(stroke: ScreenshotDrawingStroke,next: ScreenshotDrawingStroke,oldCenter: ScreenshotDrawingPoint): ScreenshotDrawingStroke {
        if(stroke.rotation==0f || next===stroke) return next
        // Keep the original local coordinate frame throughout the drag. Rebase only the preview.
        val nextCenter=center(renderer.localBounds(next))
        val worldCenter=ScreenshotObjectTransform.rotate(nextCenter,oldCenter,stroke.rotation)
        return next.transformed(0f,0f,worldCenter.x-nextCenter.x,worldCenter.y-nextCenter.y)
    }
    private fun resizeText(stroke: ScreenshotDrawingStroke,h: ScreenshotResizeHandle,g: ScreenshotSelectionResize.Geometry,alt: Boolean): ScreenshotDrawingStroke {
        val original=SelBox.of(originalBox)
        val widthOnly=h.x!=0f && h.y==0f
        val scale=if(widthOnly) 1f else ScreenshotSelectionResize.textScale(h,g,stroke.style.fontSize*stroke.fontScale)
        val width=if(widthOnly) (original.width*g.sx).coerceAtLeast(unit()) else original.width*scale
        val sized=stroke.copy(style=stroke.style.copy(fontSize=stroke.style.fontSize*scale),textWidth=width,textHeight=0f)
        val measured=renderer.measureText(sized)
        val next=ScreenshotSelectionResize.textFrame(original,h,frameRotation,measured.width(),measured.height(),alt)
        return sized.copy(points=listOf(ScreenshotDrawingPoint(next.left,next.top)),textWidth=next.width,textHeight=next.height)
    }
    fun release(point: ScreenshotDrawingPoint,shift: Boolean,imageWidth: Int,imageHeight: Int,alt: Boolean=false) {
        move(point,shift,imageWidth,imageHeight,alt)
        if(mode==Mode.MARQUEE) {
            val rect=RectF(min(down.x,point.x),min(down.y,point.y),max(down.x,point.x),max(down.y,point.y))
            val hits=history.items.filter { stroke ->
                if(rect.width()<=0 || rect.height()<=0) false
                else if(stroke.tool==ScreenshotDrawingTool.ARROW || stroke.tool==ScreenshotDrawingTool.LINE) {
                    val b=renderer.bounds(stroke)
                    b.right>=rect.left && b.left<=rect.right && b.bottom>=rect.top && b.top<=rect.bottom
                } else ScreenshotObjectTransform.intersects(SelBox.of(renderer.selectionBounds(stroke)),stroke.rotation,SelBox.of(rect))
            }.map { it.id }.toSet()
            ids=if(additive) baseIds+hits else hits
        } else {
            if(mode==Mode.POINT) preview=preview.mapValues { (_,stroke) ->
                if (ScreenshotElbowSegment.isElbow(stroke)) stroke
                else renderer.withWorldPoints(stroke,ScreenshotObjectTransform.finishLinearPoints(pointHandles(stroke),handle,
                    stroke.tool==ScreenshotDrawingTool.LINE,8*unit()))
            }
            history.replaceAll(preview)
            if(preview.isNotEmpty()) {
                committedBox=liveBox?.let(::RectF); committedRotation=previewRotation
                committedObjects=indices.associateWith { history.items[it] }
            }
        }
        reset()
    }
    fun cancel() { if(mode==Mode.MARQUEE) ids=baseIds; reset() }
    private fun reset() { preview=emptyMap(); original=emptyMap(); liveBox=null; mode=Mode.NONE; handle=-1; resizeHandle=null; segment=null; elbowRoute=null; moving=false }
    fun draw(canvas: Canvas) {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xFF4096FF.toInt(); strokeWidth=unit(); style=Paint.Style.STROKE }
        if(mode==Mode.MARQUEE && moving) {
            val rect=RectF(min(down.x,pointer.x),min(down.y,pointer.y),max(down.x,pointer.x),max(down.y,pointer.y))
            paint.style=Paint.Style.FILL; paint.color=0x334096FF; canvas.drawRect(rect,paint)
            paint.style=Paint.Style.STROKE; paint.color=0xFF4096FF.toInt(); canvas.drawRect(rect,paint)
        }
        val strokes=current(); val box=box(strokes) ?: return
        val angle=if(liveBox!=null) previewRotation else angle(strokes)
        val frame=liveBox ?: box
        if(strokes.size>1) for(stroke in strokes.values) {
            if(stroke.tool==ScreenshotDrawingTool.ARROW || stroke.tool==ScreenshotDrawingTool.LINE) canvas.drawRect(renderer.bounds(stroke),paint)
            else {
                val member=renderer.selectionBounds(stroke)
                canvas.save(); canvas.rotate(Math.toDegrees(stroke.rotation.toDouble()).toFloat(),member.centerX(),member.centerY())
                if(stroke.tool!=ScreenshotDrawingTool.TEXT) member.inset(-4*unit(),-4*unit())
                canvas.drawRect(member,paint); canvas.restore()
            }
        }
        if(ScreenshotLinearHandles.frameVisible(strokes.values)) {
            canvas.save(); canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat(),frame.centerX(),frame.centerY())
            val padding=framePadding(strokes)
            canvas.drawRect(RectF(frame).apply { inset(-padding,-padding) },paint)
            if(strokes.size==1 && strokes.values.single().tool==ScreenshotDrawingTool.TEXT) canvas.drawRect(frame,paint)
            for(p in ScreenshotResizeHandle.values().filter { it.corner }.map { ScreenshotSelectionResize.handlePoint(SelBox.of(frame),it,padding,cornerOutset(strokes)) }) {
                paint.style=Paint.Style.FILL; paint.color=Color.WHITE
                canvas.drawRoundRect(p.x-4*unit(),p.y-4*unit(),p.x+4*unit(),p.y+4*unit(),2*unit(),2*unit(),paint)
                paint.style=Paint.Style.STROKE; paint.color=0xFF4096FF.toInt()
                canvas.drawRoundRect(p.x-4*unit(),p.y-4*unit(),p.x+4*unit(),p.y+4*unit(),2*unit(),2*unit(),paint)
            }
            val y=frame.top-framePadding(strokes)-20*unit()
            paint.style=Paint.Style.FILL; paint.color=Color.WHITE; canvas.drawCircle(frame.centerX(),y,4*unit(),paint)
            paint.style=Paint.Style.STROKE; paint.color=0xFF4096FF.toInt(); canvas.drawCircle(frame.centerX(),y,4*unit(),paint)
            canvas.restore()
        }
        if(strokes.size==1) {
            val stroke=strokes.values.single(); val points=pointHandles(stroke)
            val loop=ScreenshotLinearHandles.loop(stroke,points,unit())
            if(loop) {
                val p=points.last(); paint.style=Paint.Style.STROKE; paint.color=0xFF4096FF.toInt()
                canvas.drawCircle(p.x,p.y,10*unit(),paint)
            }
            for(i in ScreenshotLinearHandles.pointIndices(stroke).filter { it in points.indices }) {
                if(loop && i==points.lastIndex) continue
                val p=points[i]
                paint.style=Paint.Style.FILL; paint.color=0xE6FFFFFF.toInt(); canvas.drawCircle(p.x,p.y,5*unit(),paint)
                paint.style=Paint.Style.STROKE; paint.color=0xFF4096FF.toInt(); canvas.drawCircle(p.x,p.y,5*unit(),paint)
            }
            for(mid in ScreenshotLinearHandles.segments(stroke,points,unit())) {
                val fixed=mid.index in stroke.fixedElbowSegments
                paint.style=Paint.Style.FILL; paint.color=if(fixed) 0xE6FFFFFF.toInt() else 0xB31677FF.toInt()
                canvas.drawCircle(mid.center.x,mid.center.y,5*unit(),paint)
                if(fixed) {
                    paint.style=Paint.Style.STROKE; paint.color=0xFF4096FF.toInt()
                    canvas.drawCircle(mid.center.x,mid.center.y,5*unit(),paint)
                }
            }
        }
    }
}
