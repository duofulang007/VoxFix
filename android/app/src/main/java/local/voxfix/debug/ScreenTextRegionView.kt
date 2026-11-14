package local.voxfix.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Toast
import kotlin.math.abs

/**
 * Rectangle selection keeps the original drag/move/resize flow. Polyline and curve selections add
 * one vertex per tap and close on a double tap (the original's mouse semantics); freehand draws
 * while pressed and closes on release. Construction semantics mirror
 * `screenshotoverlayinputhandler.cpp`: preview carries the pointer point for tap shapes but not
 * freehand, drafts fill OddEven, and a stroke with no filled area is rejected.
 */
internal class ScreenTextRegionView(
    context: Context,
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val previous: Rect?,
    private val recognize: (Rect) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    /**
     * 画布视口。设置后本层的「图片 ↔ 视图」换算全部走它，与底图/标注/文字层同一套映射。
     *
     * 改造前靠 `width/imageWidth` 的隐式关系，只在「视图尺寸 = 图片尺寸 × contain 比例」
     * 时成立；content 改为填满窗口后这个前提没了，不接视口就会把框画到错误位置。
     */
    var viewport: ScreenshotViewport? = null
    private val activeViewport get() = viewport?.takeIf { it.hasSize }
    private fun vScaleX(): Float = activeViewport?.scale ?: (if (width>0) width.toFloat()/imageWidth else 1f)
    private fun vScaleY(): Float = activeViewport?.scale ?: (if (height>0) height.toFloat()/imageHeight else 1f)
    private fun vx(imageX: Float): Float = activeViewport?.toScreenX(imageX) ?: (imageX*vScaleX())
    private fun vy(imageY: Float): Float = activeViewport?.toScreenY(imageY) ?: (imageY*vScaleY())
    private fun ivx(viewX: Float): Float = activeViewport?.toImageX(viewX)
        ?: (viewX*imageWidth/(width.takeIf { it>0 } ?: 1))
    private fun ivy(viewY: Float): Float = activeViewport?.toImageY(viewY)
        ?: (viewY*imageHeight/(height.takeIf { it>0 } ?: 1))
    private fun imageToView(): android.graphics.Matrix = android.graphics.Matrix().apply {
        val vp = activeViewport
        if (vp != null) { setScale(vp.scale, vp.scale); postTranslate(vp.offsetX, vp.offsetY) }
        else setScale(vScaleX(), vScaleY())
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val before = RectF()
    private var downX = 0f
    private var downY = 0f
    private var active = false
    private var edges = 0
    private var moved = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    var onRegionMoved: ((Rect) -> Unit)? = null
    /** Add/subtract: the drag always builds a new box and reports it instead of recognizing. */
    var onRegionOperation: ((Rect) -> Unit)? = null
    /** A finished custom shape (polyline/curve/freehand vertices in image coordinates). */
    var onDraftCommit: ((List<PointF>, ScreenshotRegionShape) -> Unit)? = null
    var smartHit: ((Int, Int) -> List<ScreenshotDocRect>)? = null
        set(value) {
            field = value
            lastSmartTap = null
        }
    var onSmartCommit: ((List<ScreenshotDocRect>, Int) -> Unit)? = null
    var smartFrame: Bitmap? = null
    var smartWindowMode = false
    var smartEnabled = true
        set(value) {
            field = value
            if (!value) lastSmartTap = null
        }
    private var smartPath = emptyList<ScreenshotDocRect>()
    private var smartIndex = -1
    private var smartPreview: RectF? = null
    private var smartPointer: PointF? = null
    private var smartTracking = false
    /** True once a long-press exploration is under way; moving then re-targets instead of drawing. */
    private var exploring = false
    private var lastSmartTap: PointF? = null
    val selectionInProgress get() = active || constructing
    var opMode = false
    /**
     * 只读态：编辑已有图片（工程）时整层不参与绘制与触摸。
     *
     * 这层的默认状态是 VISIBLE + 空 [rect]，而空 RectF 会让 onDraw 里的四块遮罩退化成
     * 一整幅 50% 黑；触摸侧又会把绘图/平移的手势全部吃掉。调用方虽然会把它设成 GONE，
     * 但只读态是第二道保险——不依赖可见性也能保证"不画、不吃事件"。
     */
    var readOnly = false
    private var parts: List<Rect> = emptyList()
    private var pendingSelection: Rect? = null
    /** Confirmed region rendered lit during add/subtract construction; null otherwise. */
    private var confirmedPath: Path? = null

    private var shape = ScreenshotRegionShape.RECTANGLE
    private var draft = mutableListOf<PointF>()
    private var constructing = false
    private var freehandPressed = false
    private var freehandFilter: ScreenshotStrokeGeometry.Filter? = null
    private var freehandRawStart = 0
    private var lastUpAt = 0L
    private var lastUpX = 0f
    private var lastUpY = 0f
    private var pressAt = 0L
    private var removedLast = false

    /** Region-type switching mirrors `setRegionType`: construction state drops, selection stays. */
    var regionShape: ScreenshotRegionShape
        get() = shape
        set(value) {
            if (shape == value) return
            cancelConstruction()
            shape = value
        }

    fun selectRegion(region: Rect) {
        lastSmartTap = null
        parts = emptyList()
        confirmedPath = null
        if (width == 0 || height == 0) { pendingSelection = Rect(region); return }
        rect.set(vx(region.left.toFloat()),vy(region.top.toFloat()),
            vx(region.right.toFloat()),vy(region.bottom.toFloat()))
        invalidate(); onRegionMoved?.invoke(Rect(region))
    }
    /** Show a multi-part region without re-firing the selection callback. */
    fun showParts(regions: List<Rect>) {
        parts = regions.map { Rect(it) }
        confirmedPath = null
        if (parts.isNotEmpty()) {
            val box = Rect().also { r -> parts.forEach { r.union(it) } }
            if (width > 0 && height > 0) rect.set(vx(box.left.toFloat()),vy(box.top.toFloat()),
                vx(box.right.toFloat()),vy(box.bottom.toFloat()))
        }
        invalidate()
    }
    /** Show a custom (non-rect) confirmed region; the box still tracks its bounds. */
    fun showRegion(path: Path?) {
        parts = emptyList()
        confirmedPath = path
        if (width > 0 && height > 0 && path != null) {
            val box = RectF()
            path.computeBounds(box, true)
            rect.set(vx(box.left), vy(box.top), vx(box.right), vy(box.bottom))
        }
        invalidate()
    }
    /** Escape equivalent: construction state drops and the draft disappears. */
    fun cancelConstruction() {
        constructing = false
        freehandPressed = false
        draft.clear()
        invalidate()
    }

    private fun toImage(x: Float, y: Float) = PointF(ivx(x), ivy(y))

    private fun draftPath(includePointer: PointF?): Path {
        val vertices = mutableListOf<PointF>().apply { addAll(draft) }
        if (includePointer != null && vertices.isNotEmpty() &&
            ScreenshotStrokeGeometry.distance(geo(vertices.last()), geo(includePointer)) > 0.01)
            vertices.add(includePointer)
        if (shape == ScreenshotRegionShape.FREEHAND && vertices.size > 1 &&
            vertices.last() == vertices.first()) vertices.removeAt(vertices.size - 1)
        val out = Path()
        when (shape) {
            ScreenshotRegionShape.CURVE, ScreenshotRegionShape.FREEHAND ->
                if (vertices.isNotEmpty()) ScreenshotStrokeGeometry.buildPath(
                    vertices.map(::geo), vertices.size >= 3, out)
            else -> if (vertices.isNotEmpty()) {
                out.moveTo(vertices[0].x, vertices[0].y)
                for (index in 1 until vertices.size) out.lineTo(vertices[index].x, vertices[index].y)
                if (vertices.size >= 3) out.close()
            }
        }
        out.fillType = Path.FillType.EVEN_ODD
        return out
    }

    /** Test hook: what the construction draft currently holds. */
    internal fun draftState() = draft.toList() to constructing

    private fun geo(point: PointF) =
        ScreenshotStrokeGeometry.GeoPoint(point.x.toDouble(), point.y.toDouble())

    /** `finishRegionDraft`: three vertices and a real filled area or the draft is discarded. */
    private fun attemptCommit() {
        val vertices = draft.toList()
        constructing = false
        draft.clear()
        if (vertices.size >= 3) {
            // A line (including collinear taps) has no filled area, exactly like the original.
            var area = 0.0
            for (index in vertices.indices) {
                val a = vertices[index]; val b = vertices[(index + 1) % vertices.size]
                area += a.x.toDouble() * b.y - b.x.toDouble() * a.y
            }
            if (abs(area) > 0.5) onDraftCommit?.invoke(vertices, shape)
        }
        invalidate()
    }

    /** `removeRegionVertex`: the last tap is taken back while constructing (phone extension). */
    private fun removeLastVertex() {
        if (!constructing || freehandPressed || draft.isEmpty()) return
        draft.removeAt(draft.size - 1)
        removedLast = true
        invalidate()
    }

    private fun ingestFreehand(points: List<PointF>) {
        for (point in points) {
            if (draft.size >= 65532) break
            draft.add(point)
            // Compact fixed windows so a delayed preview never simplifies an unbounded tail.
            if (draft.size - freehandRawStart >= 128) {
                val tail = ScreenshotStrokeGeometry.simplify(
                    draft.subList(freehandRawStart, draft.size).map(::geo), 0.125)
                    .map { PointF(it.x.toFloat(), it.y.toFloat()) }
                draft.subList(freehandRawStart, draft.size).clear()
                draft.addAll(tail)
                freehandRawStart = draft.size - 1
            }
        }
    }

    private fun region(): Rect {
        var left = ivx(rect.left).toInt()
        var top = ivy(rect.top).toInt()
        if (edges == 16) {
            // Rounding two moving edges independently changes the crop by one pixel.
            val fixedWidth = (ivx(before.right).toInt() - ivx(before.left).toInt()).coerceIn(1,imageWidth)
            val fixedHeight = (ivy(before.bottom).toInt() - ivy(before.top).toInt()).coerceIn(1,imageHeight)
            left = if (rect.right >= width) imageWidth - fixedWidth else left.coerceIn(0,imageWidth-fixedWidth)
            top = if (rect.bottom >= height) imageHeight - fixedHeight else top.coerceIn(0,imageHeight-fixedHeight)
            return Rect(left,top,left+fixedWidth,top+fixedHeight)
        }
        return Rect(left,top,ivx(rect.right).toInt(),ivy(rect.bottom).toInt())
    }

    init { contentDescription = "拖动框选文字；已有选框可拖动边缘调整或在框内移动，松手识别" }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pendingSelection?.let { pendingSelection = null; selectRegion(it); return }
        if (oldw == 0 && previous != null) rect.set(vx(previous.left.toFloat()),vy(previous.top.toFloat()),
            vx(previous.right.toFloat()),vy(previous.bottom.toFloat()))
    }
    override fun onDraw(canvas: Canvas) {
        if (readOnly) return
        if (constructing) {
            drawConstruction(canvas)
            return
        }
        val rect = smartPreview ?: this.rect
        // 空选区什么都不画。四块遮罩的写法在 rect 为空时（left=top=right=bottom=0）会把
        // `drawRect(0, rect.bottom, w, h)` 变成一整幅 50% 黑——这正是"进编辑器一片暗"的来源；
        // 末尾那句 `if (rect.isEmpty) return` 已经太晚，遮罩早画完了。
        if (rect.isEmpty && confirmedPath == null && parts.isEmpty()) return
        paint.style = Paint.Style.FILL; paint.color = 0x80000000.toInt()
        val confirmed = confirmedPath
        if (confirmed != null) {
            val scaled = Path()
            confirmed.transform(imageToView(), scaled)
            canvas.save(); canvas.clipPath(scaled, Region.Op.DIFFERENCE)
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint); canvas.restore()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * density; paint.color = 0xff4096ff.toInt()
            canvas.drawPath(scaled, paint)
        } else if (parts.size > 1) {
            val shape = Path()
            for (part in parts) shape.addRect(vx(part.left.toFloat()),vy(part.top.toFloat()),
                vx(part.right.toFloat()),vy(part.bottom.toFloat()),Path.Direction.CW)
            canvas.save(); canvas.clipPath(shape,Region.Op.DIFFERENCE)
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint); canvas.restore()
        } else {
            canvas.drawRect(0f, 0f, width.toFloat(), rect.top, paint)
            canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), paint)
            canvas.drawRect(0f, rect.top, rect.left, rect.bottom, paint)
            canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, paint)
        }
        if (rect.isEmpty) return
        val blue = 0xff4096ff.toInt()
        if (smartPreview != null && confirmedPath == null && parts.size <= 1) {
            // Exploration preview: tinted fill plus a dashed outline, so it reads as "about to pick
            // this" instead of the solid frame used for a committed selection.
            paint.style = Paint.Style.FILL; paint.color = 0x2E4096FF
            canvas.drawRect(rect, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * density; paint.color = blue
            paint.pathEffect = DashPathEffect(floatArrayOf(10 * density, 6 * density), 0f)
            canvas.drawRect(rect, paint)
            paint.pathEffect = null
        } else {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * density; paint.color = blue
            if (parts.size > 1) for (part in parts) canvas.drawRect(
                vx(part.left.toFloat()),vy(part.top.toFloat()),
                vx(part.right.toFloat()),vy(part.bottom.toFloat()),paint)
            else if (confirmedPath == null) canvas.drawRect(rect, paint)
        }
        smartPointer?.let {
            drawSmartLoupe(canvas, it)
            return
        }
        val points = mutableListOf<Pair<Float,Float>>()
        val side = minOf(rect.width(),rect.height())
        if (side > 32 * density) for (x in listOf(rect.left,rect.right)) for (y in listOf(rect.top,rect.bottom)) points.add(x to y)
        if (side > 64 * density) points.addAll(listOf(rect.centerX() to rect.top,rect.right to rect.centerY(),rect.centerX() to rect.bottom,rect.left to rect.centerY()))
        if (confirmedPath == null) for ((x,y) in points) {
            paint.style = Paint.Style.FILL; paint.color = blue; canvas.drawCircle(x,y,4*density,paint)
            paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.strokeWidth = 1.5f*density; canvas.drawCircle(x,y,4*density,paint)
        }
    }

    private fun previewSmart(x: Float, y: Float) {
        val point = toImage(x, y)
        smartPath = smartHit?.invoke(point.x.toInt(), point.y.toInt()).orEmpty()
        val current = rect.takeUnless { it.isEmpty }?.let {
            val bounds = region()
            ScreenshotDocRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
        smartIndex = ScreenshotSmartSelection.targetIndex(smartPath, current, point.x.toInt(), point.y.toInt(), smartWindowMode)
        val hit = smartPath.getOrNull(smartIndex)?.let {
            ScreenshotSmartSelection.padded(it, smartPadPixels, imageWidth, imageHeight)
        }
        val next = hit?.let {
            RectF(vx(it.left.toFloat()),vy(it.top.toFloat()),
                vx(it.right.toFloat()),vy(it.bottom.toFloat()))
        }
        // Hit-testing flickers between neighbouring nodes while the finger moves; keep the current
        // preview until the new target differs by more than the pad.
        val previous = smartPreview
        val sticky = previous != null && next != null &&
            abs(previous.left - next.left) <= smartPadPixels * vScaleX() &&
            abs(previous.top - next.top) <= smartPadPixels * vScaleY() &&
            abs(previous.right - next.right) <= smartPadPixels * vScaleX() &&
            abs(previous.bottom - next.bottom) <= smartPadPixels * vScaleY()
        if (!sticky) smartPreview = next
        smartPointer = if (hit == null) null else PointF(x, y)
        invalidate()
    }

    /** Screen-space pad for smart hits: enough for glyph ascenders and the OCR crop. */
    private val smartPadPixels: Int
        get() = activeViewport?.let { it.dpToImage(6f, density).toInt() }
            ?: (6 * density * imageWidth / maxOf(1, width)).toInt().coerceAtLeast(2)

    private fun clearSmartPreview() {
        smartPath = emptyList()
        smartIndex = -1
        smartPreview = null
        smartPointer = null
        smartTracking = false
        exploring = false
        invalidate()
    }

    fun refineLastSmartTap() {
        val tap = lastSmartTap ?: return
        lastSmartTap = null
        if (!smartEnabled || active) return
        previewSmart(tap.x, tap.y)
        val path = smartPath
        val index = smartIndex
        clearSmartPreview()
        if (index >= 0) onSmartCommit?.invoke(path, index)
    }

    private fun drawSmartLoupe(canvas: Canvas, point: PointF) {
        val frame = smartFrame ?: return
        val size = (72 * density).coerceAtMost(minOf(width, height) / 2f)
        val gap = 28 * density
        val left = (point.x - size / 2).coerceIn(0f, width - size)
        val top = (if (point.y >= size + gap) point.y - size - gap else point.y + gap)
            .coerceIn(0f, height - size)
        val target = RectF(left, top, left + size, top + size)
        val pixel = toImage(point.x, point.y)
        val radiusX = size / vScaleX().coerceAtLeast(1e-4f) / 6
        val radiusY = size / vScaleY().coerceAtLeast(1e-4f) / 6
        canvas.save()
        canvas.clipRect(target)
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawRect(target, paint)
        val scaleX = size / (2 * radiusX)
        val scaleY = size / (2 * radiusY)
        canvas.drawBitmap(frame, null, RectF(target.centerX() - pixel.x * scaleX,
            target.centerY() - pixel.y * scaleY, target.centerX() + (imageWidth - pixel.x) * scaleX,
            target.centerY() + (imageHeight - pixel.y) * scaleY), null)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * density
        paint.color = 0xff4096ff.toInt()
        canvas.drawRect(target, paint)
        canvas.drawCircle(target.centerX(), target.centerY(), 3 * density, paint)
        canvas.restore()
    }

    /** Draft preview: dim everything outside confirmed∪draft, outline the draft, show vertices. */
    private fun drawConstruction(canvas: Canvas) {
        val draftView = Path()
        val pointer = if (freehandPressed) null else lastPointer
        draftPath(pointer).transform(imageToView(), draftView)
        val lit = Path(draftView)
        confirmedPath?.let { lit.op(it, Path.Op.UNION) }
        paint.style = Paint.Style.FILL; paint.color = 0x80000000.toInt()
        canvas.save(); canvas.clipPath(lit, Region.Op.DIFFERENCE)
        canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint); canvas.restore()
        if (!draftView.isEmpty) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * density; paint.color = 0xff4096ff.toInt()
            canvas.drawPath(draftView, paint)
        }
        if (shape != ScreenshotRegionShape.FREEHAND) for (point in draft) {
            val x = vx(point.x); val y = vy(point.y)
            paint.style = Paint.Style.FILL; paint.color = 0xff4096ff.toInt(); canvas.drawCircle(x,y,4*density,paint)
            paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.strokeWidth = 1.5f*density; canvas.drawCircle(x,y,4*density,paint)
        }
    }

    private var lastPointer: PointF? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (readOnly) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastSmartTap = null
                pressAt = event.downTime; removedLast = false
                if (shape != ScreenshotRegionShape.RECTANGLE) {
                    active = true; moved = false
                    pressAt = event.eventTime
                    val point = toImage(event.x.coerceIn(0f, width.toFloat()), event.y.coerceIn(0f, height.toFloat()))
                    lastPointer = point
                    if (!constructing) { constructing = true; draft.clear(); freehandRawStart = 0 }
                    if (shape == ScreenshotRegionShape.FREEHAND) {
                        draft.clear(); freehandRawStart = 0
                        freehandPressed = true
                        freehandFilter = ScreenshotStrokeGeometry.Filter(geo(point), 0.75, 6.0)
                    }
                    invalidate()
                    return true
                }
                active = true; moved = false
                downX = event.x.coerceIn(0f, width.toFloat()); downY = event.y.coerceIn(0f, height.toFloat())
                before.set(rect); edges = 0
                if (opMode) { rect.set(downX, downY, downX, downY); invalidate(); return true }
                smartTracking = smartEnabled && smartHit != null
                val hit = RectF(rect).apply { inset(-20 * density, -20 * density) }
                if (!rect.isEmpty && hit.contains(downX, downY)) {
                    if (abs(downX - rect.left) <= 20 * density && abs(downX - rect.left) <= abs(downX - rect.right)) edges = edges or 1
                    else if (abs(downX - rect.right) <= 20 * density) edges = edges or 2
                    if (abs(downY - rect.top) <= 20 * density && abs(downY - rect.top) <= abs(downY - rect.bottom)) edges = edges or 4
                    else if (abs(downY - rect.bottom) <= 20 * density) edges = edges or 8
                    if (edges == 0) edges = 16
                } else if (!smartTracking) rect.set(downX, downY, downX, downY)
                // No preview on touch-down: a drag must start as a plain frame. Exploration only
                // begins after the finger rests past the long-press timeout (see ACTION_MOVE), and a
                // tap commits through the tap path. Snapping on down made every drag look shifted.
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                if (!active) return true
                if (smartTracking) {
                    active = false
                    clearSmartPreview()
                    return true
                }
                if (freehandPressed) {
                    finishFreehand(event)
                    return true
                }
                if (constructing) { cancelConstruction(); return true }
                active = false; rect.set(before); invalidate(); if (width > 0 && height > 0) onRegionMoved?.invoke(region())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!active) return true
                val x = event.x.coerceIn(0f, width.toFloat()); val y = event.y.coerceIn(0f, height.toFloat())
                if (shape != ScreenshotRegionShape.RECTANGLE) {
                    val point = toImage(x, y)
                    lastPointer = point
                    if (freehandPressed) freehandFilter?.let { filter ->
                        val out = mutableListOf<ScreenshotStrokeGeometry.GeoPoint>()
                        filter.ingest(geo(point), out)
                        ingestFreehand(out.map { PointF(it.x.toFloat(), it.y.toFloat()) })
                    }
                    invalidate()
                    return true
                }
                val dx = x - downX; val dy = y - downY
                if (smartTracking) {
                    // Once exploration has started it stays exploratory until the finger lifts;
                    // moving during exploration re-targets the preview instead of falling back to a
                    // manual frame. A drag that crosses the touch slop BEFORE the long-press timeout
                    // is manual for the rest of the gesture; a finger that rested past the timeout
                    // explores even when its first move jumps beyond the slop in a single event
                    // (hold-still-then-drag, restored after the gesture rewrite broke it).
                    if (exploring) { previewSmart(x, y); return true }
                    val held = event.eventTime - pressAt >= ViewConfiguration.getLongPressTimeout()
                    if (held) { exploring = true; previewSmart(x, y); return true }
                    if (dx * dx + dy * dy < slop * slop) return true
                    clearSmartPreview()
                    moved = true
                    if (before.isEmpty) { edges = 0; rect.set(downX, downY, downX, downY) }
                }
                if (abs(dx) > slop || abs(dy) > slop) moved = true
                when (edges) {
                    0 -> rect.set(minOf(downX, x), minOf(downY, y), maxOf(downX, x), maxOf(downY, y))
                    16 -> { rect.set(before); rect.offset(dx.coerceIn(-before.left, width - before.right), dy.coerceIn(-before.top, height - before.bottom)) }
                    else -> {
                        if (edges and 1 != 0) rect.left = x.coerceAtMost(rect.right - 1)
                        if (edges and 2 != 0) rect.right = x.coerceAtLeast(rect.left + 1)
                        if (edges and 4 != 0) rect.top = y.coerceAtMost(rect.bottom - 1)
                        if (edges and 8 != 0) rect.bottom = y.coerceAtLeast(rect.top + 1)
                    }
                }
                invalidate()
                if (moved) onRegionMoved?.invoke(region())
            }
            MotionEvent.ACTION_UP -> {
                if (!active) return true
                val x = event.x.coerceIn(0f, width.toFloat()); val y = event.y.coerceIn(0f, height.toFloat())
                if (shape != ScreenshotRegionShape.RECTANGLE) {
                    active = false
                    val point = toImage(x, y)
                    if (freehandPressed) { finishFreehand(event); return true }
                    val held = event.eventTime - pressAt > ViewConfiguration.getLongPressTimeout()
                    if (held) { removeLastVertex(); invalidate(); return true }
                    // A tap fixes one vertex; the second tap of a double tap closes the region.
                    val now = event.eventTime
                    val doubleTap = now - lastUpAt < ViewConfiguration.getDoubleTapTimeout() &&
                        ScreenshotStrokeGeometry.distance(geo(point), geo(PointF(lastUpX, lastUpY))) < 24 * density / vScaleX().coerceAtLeast(1e-4f)
                    lastUpAt = now; lastUpX = point.x; lastUpY = point.y
                    if (doubleTap) {
                        if (draft.isNotEmpty()) draft[draft.size - 1] = point
                        attemptCommit()
                        return true
                    }
                    if (draft.isEmpty() || ScreenshotStrokeGeometry.distance(geo(draft.last()), geo(point)) > 0.01) {
                        if (draft.size < 16384) draft.add(point)
                    }
                    invalidate()
                    return true
                }
                val dx = x - downX; val dy = y - downY
                if (exploring) {
                    // Leaving exploration commits whatever the preview last showed.
                    previewSmart(x, y)
                    val path = smartPath
                    val index = smartIndex
                    clearSmartPreview()
                    active = false
                    if (index >= 0) {
                        if (index < path.lastIndex) smartWindowMode = false
                        onSmartCommit?.invoke(path, index)
                    }
                    return true
                }
                if (smartTracking) {
                    val held = event.eventTime - pressAt >= ViewConfiguration.getLongPressTimeout()
                    if (held || dx * dx + dy * dy < slop * slop) {
                        previewSmart(x, y)
                        val path = smartPath
                        val index = smartIndex
                        clearSmartPreview()
                        active = false
                        val refine = !smartWindowMode
                        if (index >= 0) {
                            if (index < path.lastIndex) smartWindowMode = false
                            onSmartCommit?.invoke(path, index)
                        }
                        if (refine) lastSmartTap = PointF(x, y)
                        return true
                    }
                    clearSmartPreview()
                    moved = true
                }
                if (abs(dx) > slop || abs(dy) > slop) moved = true
                when (edges) {
                    0 -> rect.set(minOf(downX, x), minOf(downY, y), maxOf(downX, x), maxOf(downY, y))
                    16 -> { rect.set(before); rect.offset(dx.coerceIn(-before.left, width - before.right), dy.coerceIn(-before.top, height - before.bottom)) }
                    else -> {
                        if (edges and 1 != 0) rect.left = x.coerceAtMost(rect.right - 1)
                        if (edges and 2 != 0) rect.right = x.coerceAtLeast(rect.left + 1)
                        if (edges and 4 != 0) rect.top = y.coerceAtMost(rect.bottom - 1)
                        if (edges and 8 != 0) rect.bottom = y.coerceAtLeast(rect.top + 1)
                    }
                }
                invalidate()
                if (moved) onRegionMoved?.invoke(region())
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    active = false
                    val picked = region()
                    if (!moved || picked.width() < 2 || picked.height() < 2) {
                        rect.set(before); invalidate()
                        if (opMode) return true
                        // A plain tap must not drop an existing text result.
                        Toast.makeText(context, "请拖动选择文字区域", Toast.LENGTH_SHORT).show()
                    } else if (opMode) onRegionOperation?.invoke(picked)
                    else recognize(picked)
                }
            }
        }
        return true
    }

    /** Freehand release: flush the filter tail, then close like the original's finishRegionDraft. */
    private fun finishFreehand(event: MotionEvent) {
        val x = event.x.coerceIn(0f, width.toFloat()); val y = event.y.coerceIn(0f, height.toFloat())
        val point = toImage(x, y)
        val filter = freehandFilter
        freehandPressed = false
        freehandFilter = null
        if (filter != null) {
            val out = mutableListOf<ScreenshotStrokeGeometry.GeoPoint>()
            filter.ingest(geo(point), out)
            ingestFreehand(out.map { PointF(it.x.toFloat(), it.y.toFloat()) })
            filter.settleEndpoint()?.let { tail ->
                if (draft.isEmpty() || ScreenshotStrokeGeometry.distance(geo(draft.last()), tail) > 1e-9)
                    draft.add(PointF(tail.x.toFloat(), tail.y.toFloat()))
            }
        }
        // The original simplifies the whole stroke once more before accepting it.
        draft = ScreenshotStrokeGeometry.simplify(draft.map(::geo), 0.125)
            .map { PointF(it.x.toFloat(), it.y.toFloat()) }.toMutableList()
        attemptCommit()
    }
}
