package local.voxfix.debug

enum class ScreenshotDrawingTool { ARROW, RECTANGLE, PEN, FILTER, HIGHLIGHT, SPOTLIGHT, TEXT, SERIAL, ERASER, SELECT }
enum class ScreenshotAreaMode { RECTANGLE, PEN }
enum class ScreenshotFilter { MOSAIC, BLUR }
enum class ScreenshotSerialShape { OUTLINED_CIRCLE, SOLID_CIRCLE, OUTLINED_SQUARE, SOLID_SQUARE, CIRCLED_NUMBER }
data class ScreenshotDrawingPoint(val x: Float, val y: Float)
data class ScreenshotDrawingStyle(
    val color: Int = 0xFFFF4D4F.toInt(), val width: Int = 2, val opacity: Float = 1f,
    val area: ScreenshotAreaMode = ScreenshotAreaMode.RECTANGLE,
    val filter: ScreenshotFilter = ScreenshotFilter.MOSAIC, val strength: Int = 12,
    val fontSize: Int = 18, val bold: Boolean = false, val align: Int = 0,
    val serialShape: ScreenshotSerialShape = ScreenshotSerialShape.OUTLINED_CIRCLE,
    val fontFamily: String = "sans-serif", val verticalAlign: Int = 0,
    val outlineColor: Int = 0xFF000000.toInt(), val outlineWidth: Int = 0,
    val fillColor: Int = 0xFFFFFFFF.toInt(), val hasFill: Boolean = false, val cornerRadius: Int = 0
)
data class ScreenshotDrawingStroke(
    val tool: ScreenshotDrawingTool, val points: List<ScreenshotDrawingPoint>, val color: Int, val width: Float,
    val style: ScreenshotDrawingStyle = ScreenshotDrawingStyle(color=color),
    val text: String = "", val number: Int = 1, val textWidth: Float = 0f, val fontScale: Float = 1f, val textHeight: Float = 0f,
    /** Stable identity across saves. 0 means "not assigned yet"; the history stamps it once. */
    val id: Long = 0L
) {
    // Identity is not appearance: two independently built strokes that draw the same are equal, so
    // existing comparisons keep working while the document still tracks each object separately.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenshotDrawingStroke) return false
        return tool == other.tool && points == other.points && color == other.color && width == other.width &&
            style == other.style && text == other.text && number == other.number && textWidth == other.textWidth &&
            fontScale == other.fontScale && textHeight == other.textHeight
    }
    override fun hashCode(): Int {
        var result = tool.hashCode()
        result = 31 * result + points.hashCode()
        result = 31 * result + color
        result = 31 * result + width.hashCode()
        result = 31 * result + style.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + number
        result = 31 * result + textWidth.hashCode()
        result = 31 * result + fontScale.hashCode()
        result = 31 * result + textHeight.hashCode()
        return result
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
    ScreenshotDrawingTool.HIGHLIGHT -> ScreenshotDrawingStyle(color=0xFFFADB14.toInt(),width=12,opacity=.35f,area=ScreenshotAreaMode.PEN)
    ScreenshotDrawingTool.SPOTLIGHT -> ScreenshotDrawingStyle(color=0xFF000000.toInt(),opacity=.6f)
    ScreenshotDrawingTool.FILTER -> ScreenshotDrawingStyle(width=24)
    else -> ScreenshotDrawingStyle()
}

/** Immutable snapshots make deletion, transforms and style changes undoable alongside new marks. */
class ScreenshotDrawing {
    private val snapshots = mutableListOf<List<ScreenshotDrawingStroke>>(emptyList())
    private var cursor = 0
    val items: List<ScreenshotDrawingStroke> get() = snapshots[cursor]
    val canUndo get() = cursor > 0
    val canRedo get() = cursor < snapshots.lastIndex
    private fun stable(stroke: ScreenshotDrawingStroke): ScreenshotDrawingStroke {
        val points = stroke.points.toList()
        if (stroke.id == 0L) return stroke.copy(points = points, id = ScreenshotIds.next())
        ScreenshotIds.above(stroke.id)
        return stroke.copy(points = points)
    }
    private fun commit(value: List<ScreenshotDrawingStroke>) {
        if (value == items) return
        while (snapshots.lastIndex > cursor) snapshots.removeAt(snapshots.lastIndex)
        snapshots.add(value); cursor++
    }
    /** Replace the whole stack, e.g. when a saved project is reopened. Undo starts empty. */
    fun load(value: List<ScreenshotDrawingStroke>) {
        val stable = value.map(::stable)
        snapshots.clear(); snapshots.add(stable); cursor = 0
    }
    fun add(stroke: ScreenshotDrawingStroke) {
        if (stroke.points.isNotEmpty()) commit(items+stable(stroke))
    }
    fun replace(index: Int, stroke: ScreenshotDrawingStroke) {
        if (index in items.indices) {
            // A transform derived from a stroke that never entered the history has no id of its own;
            // it edits the object already at this slot and must keep that object's identity.
            val existing = items[index]
            val next = if (stroke.id == 0L) stroke.copy(id = existing.id) else stroke
            commit(items.mapIndexed { i,item -> if (i == index) stable(next) else item })
        }
    }
    fun remove(indices: Set<Int>) { if (indices.isNotEmpty()) commit(items.filterIndexed { index,_ -> index !in indices }) }
    fun undo() { if (canUndo) cursor-- }
    fun redo() { if (canRedo) cursor++ }
}

fun ScreenshotDrawingStroke.transformed(fromLeft: Float, fromTop: Float, toLeft: Float, toTop: Float,
    scaleX: Float = 1f, scaleY: Float = 1f): ScreenshotDrawingStroke = copy(
    points=points.map { ScreenshotDrawingPoint(toLeft+(it.x-fromLeft)*scaleX,toTop+(it.y-fromTop)*scaleY) },
    width=width*minOf(scaleX,scaleY),textWidth=textWidth*scaleX,fontScale=fontScale*scaleY,textHeight=textHeight*scaleY)
