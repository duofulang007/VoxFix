package local.voxfix.debug

enum class ScreenshotDrawingTool { ARROW, RECTANGLE, PEN, FILTER, HIGHLIGHT, SPOTLIGHT, TEXT, SERIAL, ERASER, SELECT, LINE, WATERMARK }
enum class ScreenshotAreaMode { RECTANGLE, PEN }
enum class ScreenshotFilter { MOSAIC, BLUR }
enum class ScreenshotSerialShape { OUTLINED_CIRCLE, SOLID_CIRCLE, OUTLINED_SQUARE, SOLID_SQUARE, CIRCLED_NUMBER, CIRCLE }
enum class ScreenshotStrokePattern { SOLID, DASHED, DOTTED }
enum class ScreenshotFillPattern { SOLID, CROSS_LINE, LINE }
/** Shape tool kinds, in the original radio order (`SnowCanvasRectangleShape`). */
enum class ScreenshotRectangleShape { RECTANGLE, ELLIPSE, DIAMOND }
/**
 * Arrowhead shapes in the original order (`snow-draw-engine-core/src/arrow.rs` `Arrowhead`).
 * The three *Outline* variants are not listed yet: they fill with the document background colour,
 * which a screenshot over an arbitrary photo does not have — see docs/SnowShot-T11-*.md.
 */
enum class ScreenshotArrowhead { NONE, ARROW, BAR, DOT, CIRCLE, CIRCLE_OUTLINE, INDENTED_TRIANGLE, TRIANGLE,
    TRIANGLE_OUTLINE, DIAMOND, DIAMOND_OUTLINE, SQUARE, CROWFOOT_ONE, CROWFOOT_MANY, CROWFOOT_ONE_OR_MANY }
/** Arrow shaft shapes, in the original radio order (`SnowCanvasArrowType`). */
enum class ScreenshotArrowType { STRAIGHT, CURVE, ELBOW }
/** Stored preference; unsupported arrowheads render plain without changing it (`ArrowShaftType`). */
enum class ScreenshotArrowShaftType { PLAIN, TAPERED }
data class ScreenshotDrawingPoint(val x: Float, val y: Float)
data class ScreenshotDrawingStyle(
    val color: Int = 0xFFF5222D.toInt(), val width: Int = 2, val opacity: Float = 1f,
    val area: ScreenshotAreaMode = ScreenshotAreaMode.RECTANGLE,
    val filter: ScreenshotFilter = ScreenshotFilter.MOSAIC, val strength: Int = 12,
    val fontSize: Float = 18f, val bold: Boolean = false, val align: Int = 0,
    val serialShape: ScreenshotSerialShape = ScreenshotSerialShape.OUTLINED_CIRCLE,
    val fontFamily: String = "sans-serif", val verticalAlign: Int = 0,
    val outlineColor: Int = 0xFF000000.toInt(), val outlineWidth: Int = 0,
    val fillColor: Int = 0xFFFFFFFF.toInt(), val hasFill: Boolean = false, val cornerRadius: Int = 0,
    val strokePattern: ScreenshotStrokePattern = ScreenshotStrokePattern.SOLID,
    val fillPattern: ScreenshotFillPattern = ScreenshotFillPattern.SOLID,
    val shape: ScreenshotRectangleShape = ScreenshotRectangleShape.RECTANGLE,
    /** Line tool only: Snow Shot's Straight/Curve radio; the curve is a cubic through the drag path. */
    val curved: Boolean = false,
    /** Arrow shaft: the original starts on Curve and routes Elbow through a midpoint dog-leg. */
    val arrowType: ScreenshotArrowType = ScreenshotArrowType.CURVE,
    /** Arrowhead scale 1.0–3.0 (`normalize_arrow_ratio`), one decimal in the palette. */
    val arrowRatio: Float = 1f,
    /** Stored preference; unsupported arrowheads fall back to the plain shaft when rendering. */
    val arrowShaftType: ScreenshotArrowShaftType = ScreenshotArrowShaftType.PLAIN,
    val startHead: ScreenshotArrowhead = ScreenshotArrowhead.NONE,
    val endHead: ScreenshotArrowhead = ScreenshotArrowhead.ARROW,
    /** 0 preserves existing projects; 1 uses the original serial geometry and contrast text. */
    val serialGeometryVersion: Int = 0
)
data class ScreenshotDrawingStroke(
    val tool: ScreenshotDrawingTool, val points: List<ScreenshotDrawingPoint>, val color: Int, val width: Float,
    val style: ScreenshotDrawingStyle = ScreenshotDrawingStyle(color=color),
    val text: String = "", val number: Long = 1L, val textWidth: Float = 0f, val fontScale: Float = 1f, val textHeight: Float = 0f,
    /** Stable identity across saves. 0 means "not assigned yet"; the history stamps it once. */
    val id: Long = 0L,
    /**
     * Geometry version marker. False keeps the stored touch polyline (every document written before
     * the pen smoothing port), true means the points are stabilized/simplified vertices that must be
     * drawn through centripetal Catmull-Rom. Reopening an old project must not change its outline.
     */
    val smooth: Boolean = false,
    /** Only meaningful for [smooth] strokes: the recorded closure decision, not a render-time guess. */
    val closedPath: Boolean = false,
    /** SERIAL -> independent TEXT object; 0 means no attached label (including legacy projects). */
    val boundTextId: Long = 0L,
    /** Radians around the unrotated object's centre; absent in legacy projects. */
    val rotation: Float = 0f,
    /** Fixed elbow segments use the index of their end vertex. */
    val fixedElbowSegments: Set<Int> = emptySet(),
    /**
     * T22 arrow binding: the element each endpoint is pinned to, with the ratio inside it.
     * Null means a free endpoint, which is also what every project written before T22 has.
     */
    val startBinding: ScreenshotFixedPointBinding? = null,
    val endBinding: ScreenshotFixedPointBinding? = null,
    val autoFilterCategory: Int? = null
) {
    // Identity is not appearance: two independently built strokes that draw the same are equal, so
    // existing comparisons keep working while the document still tracks each object separately.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenshotDrawingStroke) return false
        return tool == other.tool && points == other.points && color == other.color && width == other.width &&
            style == other.style && text == other.text && number == other.number && textWidth == other.textWidth &&
            fontScale == other.fontScale && textHeight == other.textHeight &&
            smooth == other.smooth && closedPath == other.closedPath && boundTextId == other.boundTextId &&
            rotation == other.rotation && fixedElbowSegments == other.fixedElbowSegments &&
            startBinding == other.startBinding && endBinding == other.endBinding &&
            autoFilterCategory == other.autoFilterCategory
    }
    override fun hashCode(): Int {
        var result = tool.hashCode()
        result = 31 * result + points.hashCode()
        result = 31 * result + color
        result = 31 * result + width.hashCode()
        result = 31 * result + style.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + number.hashCode()
        result = 31 * result + textWidth.hashCode()
        result = 31 * result + fontScale.hashCode()
        result = 31 * result + textHeight.hashCode()
        result = 31 * result + smooth.hashCode()
        result = 31 * result + closedPath.hashCode()
        result = 31 * result + boundTextId.hashCode()
        result = 31 * result + rotation.hashCode()
        result = 31 * result + fixedElbowSegments.hashCode()
        result = 31 * result + (startBinding?.hashCode() ?: 0)
        result = 31 * result + (endBinding?.hashCode() ?: 0)
        return 31 * result + (autoFilterCategory ?: 0)
    }
}

/** Object identities only have to be unique inside one document, and must survive a restart. */
internal object ScreenshotIds {
    private val counter = java.util.concurrent.atomic.AtomicLong(0)
    fun next(): Long = counter.incrementAndGet()
    fun above(value: Long) {
        while (true) {
            val current = counter.get()
            if (current >= value || counter.compareAndSet(current, value)) return
        }
    }
}

fun defaultScreenshotDrawingStyle(tool: ScreenshotDrawingTool?)=when(tool) {
    // Snow Shot's highlight row has no opacity role: transparency rides on the colour's alpha.
    ScreenshotDrawingTool.HIGHLIGHT -> ScreenshotDrawingStyle(color=0x59FADB14,width=12,area=ScreenshotAreaMode.PEN)
    ScreenshotDrawingTool.SPOTLIGHT -> ScreenshotDrawingStyle(color=0xFF000000.toInt(),opacity=.64f)
    ScreenshotDrawingTool.SERIAL -> ScreenshotDrawingStyle(color=0xFFF5222D.toInt(),fontSize=24f,serialGeometryVersion=1)
    // `screenshotdefaultstyles.h` text block: red, 30px, light-red hairline stroke, 6px corners,
    // left/centre alignment and a transparent fill — enabling the fill alone stays invisible until
    // a fill colour is picked, exactly like the original.
    ScreenshotDrawingTool.TEXT -> ScreenshotDrawingStyle(color=0xFFF5222D.toInt(),fontSize=30f,
        outlineColor=0xFFFFCCC7.toInt(),cornerRadius=6,verticalAlign=1,fillColor=0x00FFFFFF)
    ScreenshotDrawingTool.RECTANGLE -> ScreenshotDrawingStyle(color=0xFFF5222D.toInt(),cornerRadius=6)
    ScreenshotDrawingTool.FILTER -> ScreenshotDrawingStyle(width=24)
    // The original Line style row starts on Curve (`lineTypeConfig.initialId = 1`).
    ScreenshotDrawingTool.LINE -> ScreenshotDrawingStyle(curved=true)
    // `defaults.arrow = defaults.rectangle`, then start None / end Arrow / shaft Curve.
    ScreenshotDrawingTool.ARROW -> ScreenshotDrawingStyle(color=0xFFF5222D.toInt(),arrowType=ScreenshotArrowType.CURVE)
    else -> ScreenshotDrawingStyle()
}

/** Immutable snapshots make deletion, transforms and style changes undoable alongside new marks. */
class ScreenshotDrawing {
    internal enum class Order { BACK, BACKWARD, FORWARD, FRONT }
    /** Watermark rides the same stack: one committed change is one undo step (original watermark edit). */
    private class Snapshot(val objects: List<ScreenshotDrawingStroke>, val watermark: ScreenshotWatermark)
    private val snapshots = mutableListOf(Snapshot(emptyList(), ScreenshotWatermark()))
    private var cursor = 0
    val items: List<ScreenshotDrawingStroke> get() = snapshots[cursor].objects
    var watermark: ScreenshotWatermark
        get() = snapshots[cursor].watermark
        internal set(value) { commit(snapshots[cursor].objects, value) }
    val canUndo get() = cursor > 0
    val canRedo get() = cursor < snapshots.lastIndex
    private fun stable(stroke: ScreenshotDrawingStroke): ScreenshotDrawingStroke {
        val points = stroke.points.toList()
        if (stroke.id == 0L) return stroke.copy(points = points, id = ScreenshotIds.next())
        ScreenshotIds.above(stroke.id)
        return stroke.copy(points = points)
    }
    private fun commit(objects: List<ScreenshotDrawingStroke>, watermark: ScreenshotWatermark) {
        val current = snapshots[cursor]
        if (current.watermark == watermark && current.objects == objects &&
            current.objects.indices.all { current.objects[it].id == objects[it].id }) return
        while (snapshots.lastIndex > cursor) snapshots.removeAt(snapshots.lastIndex)
        snapshots.add(Snapshot(objects, watermark)); cursor++
    }
    /** Replace the whole stack, e.g. when a saved project is reopened. Undo starts empty. */
    fun load(objects: List<ScreenshotDrawingStroke>, watermark: ScreenshotWatermark = ScreenshotWatermark()) {
        val stable = objects.map(::stable)
        snapshots.clear(); snapshots.add(Snapshot(stable, watermark.normalized())); cursor = 0
    }
    /** `resetCanvasRequested`: clear every annotation and the watermark as one undoable step. */
    fun reset(watermark: ScreenshotWatermark = ScreenshotWatermark()) = commit(emptyList(), watermark)
    fun add(stroke: ScreenshotDrawingStroke) {
        if (stroke.points.isNotEmpty() && !stroke.isDegenerate()) commit(items+stable(stroke), watermark)
    }
    internal fun fillAutoRegions(regions: List<ScreenshotAutoFilter.Region>, style: ScreenshotDrawingStyle,
                                 pixelUnit: Float, toggle: Boolean) {
        if (regions.isEmpty()) return
        val (previous, other) = items.partition { it.autoFilterCategory != null }
        fun key(stroke: ScreenshotDrawingStroke) = ScreenshotAutoFilter.Region(
            ScreenshotDocRect(stroke.points.first().x.toInt(), stroke.points.first().y.toInt(),
                stroke.points.last().x.toInt(), stroke.points.last().y.toInt()), stroke.autoFilterCategory!!
        )
        val fills = previous.associateByTo(linkedMapOf(), ::key)
        val rectangleStyle = style.copy(area = ScreenshotAreaMode.RECTANGLE)
        for (region in regions) {
            val existing = fills[region]
            if (toggle && existing?.style?.filter == style.filter) fills.remove(region)
            else {
                val bounds = region.bounds
                val points = listOf(ScreenshotDrawingPoint(bounds.left.toFloat(), bounds.top.toFloat()),
                    ScreenshotDrawingPoint(bounds.right.toFloat(), bounds.bottom.toFloat()))
                fills[region] = stable(ScreenshotDrawingStroke(ScreenshotDrawingTool.FILTER, points, style.color,
                    style.width * pixelUnit, rectangleStyle, id = existing?.id ?: 0,
                    autoFilterCategory = region.category))
            }
        }
        commit(fills.values.toList() + other, watermark)
    }
    /** Creating a badge with its label is one gesture and one undo step. */
    fun addSerialWithText(serial: ScreenshotDrawingStroke,text: ScreenshotDrawingStroke) {
        require(serial.tool==ScreenshotDrawingTool.SERIAL && text.tool==ScreenshotDrawingTool.TEXT)
        val label=stable(text); val badge=stable(serial).copy(boundTextId=label.id)
        commit(items+listOf(badge,label), watermark)
    }
    /** Reuse an existing label; repeated Create Text must never duplicate it. */
    fun attachSerialText(serialId: Long,text: ScreenshotDrawingStroke): Int? {
        val index=items.indexOfFirst { it.id==serialId && it.tool==ScreenshotDrawingTool.SERIAL }
        if(index<0) return null
        val serial=items[index]
        items.indexOfFirst { it.id==serial.boundTextId && it.tool==ScreenshotDrawingTool.TEXT }.takeIf { it>=0 }?.let { return it }
        require(text.tool==ScreenshotDrawingTool.TEXT)
        val label=stable(text)
        commit(items.mapIndexed { i,item -> if(i==index) item.copy(boundTextId=label.id) else item }+label, watermark)
        return items.lastIndex
    }
    fun replace(index: Int, stroke: ScreenshotDrawingStroke) {
        if (index in items.indices) {
            // A transform derived from a stroke that never entered the history has no id of its own;
            // it edits the object already at this slot and must keep that object's identity.
            val existing = items[index]
            val next = if (stroke.id == 0L) stroke.copy(id = existing.id) else stroke
            commit(items.mapIndexed { i,item -> if (i == index) stable(next) else item }, watermark)
        }
    }
    fun remove(indices: Set<Int>) {
        if(indices.isEmpty()) return
        val removed=indices.mapNotNull { items.getOrNull(it)?.id }.toSet()
        commit(items.filterIndexed { index,_ -> index !in indices }.map {
            var next=it
            if(next.boundTextId in removed) next=next.copy(boundTextId=0L)
            // An arrow must not keep pointing at an object that no longer exists.
            val start=next.startBinding
            val end=next.endBinding
            if((start!=null && start.elementId in removed) || (end!=null && end.elementId in removed))
                next=next.copy(
                    startBinding=if(start!=null && start.elementId in removed) null else start,
                    endBinding=if(end!=null && end.elementId in removed) null else end)
            next
        }, watermark)
    }
    /** One selection gesture is one transaction, including every selected object. */
    fun replaceAll(changes: Map<Int, ScreenshotDrawingStroke>) {
        if(changes.isEmpty()) return
        commit(items.mapIndexed { index,item -> changes[index]?.let { stable(it.copy(id=item.id)) } ?: item },watermark)
    }
    /** Stable paint order from document_ops.rs::reorder_selected. */
    internal fun reorder(indices: Set<Int>, action: Order): Boolean {
        if (indices.isEmpty()) return false
        val selected = expandedSelection(indices)
        val order = items.indices.toMutableList()
        when (action) {
            Order.BACK -> order.sortBy { it !in selected }
            Order.BACKWARD -> {
                for (i in 1 until order.size) {
                    if (order[i] in selected && order[i - 1] !in selected) java.util.Collections.swap(order, i, i - 1)
                }
            }
            Order.FORWARD -> {
                for (i in order.lastIndex - 1 downTo 0) {
                    if (order[i] in selected && order[i + 1] !in selected) java.util.Collections.swap(order, i, i + 1)
                }
            }
            Order.FRONT -> order.sortBy { it in selected }
        }
        if (order.indices.all { order[it] == it }) return false
        commit(order.map { items[it] }, watermark)
        return true
    }
    private fun expandedSelection(indices: Set<Int>): Set<Int> {
        val byId = items.withIndex().associate { (index, stroke) -> stroke.id to index }
        return buildSet {
            for (index in indices) {
                add(index)
                byId[items[index].boundTextId]?.let(::add)
            }
        }
    }
    internal fun templateObjects(indices: Set<Int>): List<ScreenshotDrawingStroke> {
        val strokes = expandedSelection(indices).sorted().map { items[it] }
        val ids = strokes.mapTo(hashSetOf()) { it.id }
        return strokes.map { stroke ->
            stroke.copy(startBinding = stroke.startBinding?.takeIf { it.elementId in ids },
                endBinding = stroke.endBinding?.takeIf { it.elementId in ids }, autoFilterCategory = null)
        }
    }

    internal fun insertTemplate(strokes: List<ScreenshotDrawingStroke>, selectedIds: Set<Long>,
                                dx: Float, dy: Float): Set<Int> {
        if (strokes.isEmpty() || items.size + strokes.size > ScreenshotDocumentCodec.MAX_OBJECTS ||
            items.sumOf { it.points.size } + strokes.sumOf { it.points.size } > ScreenshotDocumentCodec.MAX_POINTS) return emptySet()
        val ids = strokes.associate { it.id to ScreenshotIds.next() }
        val copies = strokes.map { stroke ->
            val start = stroke.startBinding?.let { binding -> ids[binding.elementId]?.let { binding.copy(elementId = it) } }
            val end = stroke.endBinding?.let { binding -> ids[binding.elementId]?.let { binding.copy(elementId = it) } }
            stroke.transformed(0f, 0f, dx, dy).copy(id = ids.getValue(stroke.id),
                boundTextId = ids[stroke.boundTextId] ?: 0, startBinding = start, endBinding = end, autoFilterCategory = null)
        }
        val selected = strokes.indices.filterTo(linkedSetOf()) { strokes[it].id in selectedIds }
            .mapTo(linkedSetOf()) { items.size + it }
        if (selected.isEmpty()) return emptySet()
        commit(items + copies, watermark)
        return selected
    }
    internal fun duplicate(indices: Set<Int>): Set<Int> {
        val expanded = expandedSelection(indices)
        if (expanded.isEmpty() || items.size + expanded.size > ScreenshotDocumentCodec.MAX_OBJECTS) return emptySet()
        if (items.sumOf { it.points.size } + expanded.sumOf { items[it].points.size } > ScreenshotDocumentCodec.MAX_POINTS) {
            return emptySet()
        }
        val ids = expanded.associate { items[it].id to ScreenshotIds.next() }
        val copies = expanded.map { index ->
            val stroke = items[index]
            // SnowCanvasWidget::duplicateSelected uses a 12 × 12 canvas offset.
            stroke.transformed(0f, 0f, 12f, 12f).copy(id = ids.getValue(stroke.id), boundTextId = ids[stroke.boundTextId] ?: 0L)
        }
        val nextSelection = expanded.withIndex().filter { it.value in indices }.mapTo(linkedSetOf()) { items.size + it.index }
        commit(items + copies, watermark)
        return nextSelection
    }
    /** Spotlight colour/opacity is one canvas setting shared by all cutouts in Snow Shot. */
    fun restyleSpotlights(style: ScreenshotDrawingStyle) {
        commit(items.map { if(it.tool==ScreenshotDrawingTool.SPOTLIGHT)
            it.copy(color=style.color,style=it.style.copy(color=style.color,opacity=style.opacity)) else it }, watermark)
    }
    fun undo() { if (canUndo) cursor-- }
    fun redo() { if (canRedo) cursor++ }
}

/**
 * `arrow_is_degenerate`: fewer than two vertices, or no segment longer than 1e-6, is an object the
 * original refuses to create. A tap with the arrow or line tool therefore leaves nothing behind,
 * while a one-pixel drag is still a real arrow.
 */
fun ScreenshotDrawingStroke.isDegenerate(): Boolean {
    if (tool != ScreenshotDrawingTool.ARROW && tool != ScreenshotDrawingTool.LINE) return false
    if (points.size < 2) return true
    for (index in 1 until points.size) {
        val previous = points[index - 1]
        val current = points[index]
        if (kotlin.math.hypot(current.x - previous.x, current.y - previous.y) > 1e-6f) return false
    }
    return true
}

fun ScreenshotDrawingStroke.transformed(fromLeft: Float, fromTop: Float, toLeft: Float, toTop: Float,
    scaleX: Float = 1f, scaleY: Float = 1f): ScreenshotDrawingStroke = copy(
    points=points.map { ScreenshotDrawingPoint(toLeft+(it.x-fromLeft)*scaleX,toTop+(it.y-fromTop)*scaleY) },
    width=width*minOf(scaleX,scaleY),textWidth=textWidth*scaleX,fontScale=fontScale*scaleY,textHeight=textHeight*scaleY)
