package local.voxfix.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import org.json.JSONArray

internal enum class ScreenshotRegionOp { ADD, SUBTRACT }

/**
 * Capture-area shape family, mirroring the original `ScreenshotRegionType`
 * (`screenshotregiongeometry.h`): the order Rectangle → Polyline → Curve → Freehand is fixed and
 * the ids are the persisted form.
 */
internal enum class ScreenshotRegionShape(val id: String) {
    RECTANGLE("rectangle"), POLYLINE("polyline"), CURVE("curve"), FREEHAND("freehand");
    companion object {
        fun fromId(value: String?): ScreenshotRegionShape =
            entries.firstOrNull { it.id == value } ?: RECTANGLE
    }
}

/**
 * One operand of the capture area, mirroring `ScreenshotRegionGeometry::Operand`: construction
 * vertices plus the boolean operation applied against everything before it. Vertices are kept
 * instead of a compiled Path so the shape survives persistence and can be rebuilt identically;
 * derived contours use the shared centripetal Catmull-Rom for curved shapes, exactly like the
 * original builds its draft path.
 */
internal class ScreenshotRegionOperand(
    val vertices: List<PointF>,
    val op: Op,
    val shape: ScreenshotRegionShape
) {
    internal enum class Op { ADD, SUBTRACT, INTERSECT }

    fun path(): Path {
        val out = Path()
        when (shape) {
            ScreenshotRegionShape.RECTANGLE -> {
                val box = bounds()
                out.addRect(box.left, box.top, box.right, box.bottom, Path.Direction.CW)
            }
            ScreenshotRegionShape.POLYLINE -> if (vertices.isNotEmpty()) {
                out.moveTo(vertices[0].x, vertices[0].y)
                for (index in 1 until vertices.size) out.lineTo(vertices[index].x, vertices[index].y)
                if (vertices.size >= 3) out.close()
            }
            ScreenshotRegionShape.CURVE, ScreenshotRegionShape.FREEHAND ->
                ScreenshotStrokeGeometry.buildPath(
                    vertices.map { ScreenshotStrokeGeometry.GeoPoint(it.x.toDouble(), it.y.toDouble()) },
                    closed = true, out)
        }
        // The original fills custom draft paths with OddEvenFill; self-intersections punch holes.
        out.fillType = Path.FillType.EVEN_ODD
        return out
    }

    fun bounds(): RectF {
        val box = RectF(vertices.first().x, vertices.first().y, vertices.first().x, vertices.first().y)
        for (point in vertices) {
            box.left = minOf(box.left, point.x); box.top = minOf(box.top, point.y)
            box.right = maxOf(box.right, point.x); box.bottom = maxOf(box.bottom, point.y)
        }
        return box
    }

    fun copy(op: Op): ScreenshotRegionOperand = ScreenshotRegionOperand(vertices.toList(), op, shape)
}

/**
 * Capture area built from boolean operands, mirroring `ScreenshotRegionGeometry`. A pure
 * rectangle chain keeps exact integer rects ([rects]); any custom shape flattens through
 * [path]. [bounds] is what OCR, drawing and the saved viewport use; the shape itself only
 * changes what pixels are exported.
 */
internal class ScreenshotRegion private constructor(
    val operands: List<ScreenshotRegionOperand>
) {
    companion object {
        fun ofRects(parts: List<Rect>): ScreenshotRegion = ScreenshotRegion(parts.map { part ->
            ScreenshotRegionOperand(
                listOf(PointF(part.left.toFloat(), part.top.toFloat()),
                    PointF(part.right.toFloat(), part.top.toFloat()),
                    PointF(part.right.toFloat(), part.bottom.toFloat()),
                    PointF(part.left.toFloat(), part.bottom.toFloat())),
                ScreenshotRegionOperand.Op.ADD, ScreenshotRegionShape.RECTANGLE)
        })

        /** A freshly drawn custom shape becomes the first operand (always an add). */
        fun ofShape(vertices: List<PointF>, shape: ScreenshotRegionShape): ScreenshotRegion =
            ScreenshotRegion(listOf(ScreenshotRegionOperand(
                vertices.toList(), ScreenshotRegionOperand.Op.ADD, shape)))

        fun fromJson(value: JSONArray?): ScreenshotRegion? {
            if (value == null || value.length() == 0 || value.length() > 4096) return null
            val operands = mutableListOf<ScreenshotRegionOperand>()
            for (index in 0 until value.length()) {
                val item = value.optJSONArray(index) ?: return null
                if (item.length() < 3) return null
                val op = item.optInt(0, -1)
                if (op !in 0..2 || (operands.isEmpty() && op != 0)) return null
                val shape = ScreenshotRegionShape.fromId(item.optString(1))
                val points = item.optJSONArray(2) ?: return null
                if (points.length() !in 3..65532) return null
                val vertices = mutableListOf<PointF>()
                for (p in 0 until points.length()) {
                    val pair = points.optJSONArray(p) ?: return null
                    if (pair.length() != 2) return null
                    val x = pair.optDouble(0); val y = pair.optDouble(1)
                    if (!x.isFinite() || !y.isFinite() || kotlin.math.abs(x) > 1e7 || kotlin.math.abs(y) > 1e7) return null
                    vertices.add(PointF(x.toFloat(), y.toFloat()))
                }
                operands.add(ScreenshotRegionOperand(vertices,
                    ScreenshotRegionOperand.Op.entries[op], shape))
            }
            return ScreenshotRegion(operands)
        }
    }

    val bounds: Rect = Rect().also { box ->
        val flat = RectF()
        operands.forEach { operand ->
            if (operand === operands.first()) flat.set(operand.bounds())
            else when (operand.op) {
                ScreenshotRegionOperand.Op.ADD -> flat.union(operand.bounds())
                ScreenshotRegionOperand.Op.SUBTRACT -> {}
                ScreenshotRegionOperand.Op.INTERSECT -> flat.setIntersect(flat, operand.bounds())
            }
        }
        box.set(flat.left.toInt(), flat.top.toInt(), flat.right.toInt(), flat.bottom.toInt())
    }

    val isRectangle: Boolean
        get() = operands.size == 1 && operands[0].shape == ScreenshotRegionShape.RECTANGLE
    val isEmpty: Boolean get() = bounds.isEmpty || path().isEmpty
    val isCustom: Boolean get() = operands.any { it.shape != ScreenshotRegionShape.RECTANGLE }

    /** Exact rects of the rectangle chain; empty once a custom shape took part. */
    val rects: List<Rect>
        get() = if (isCustom) emptyList() else operands.map { operand ->
            val box = operand.bounds()
            Rect(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())
        }

    fun path(): Path {
        var result = Path()
        operands.forEachIndexed { index, operand ->
            val next = operand.path()
            if (index == 0) result = next
            else when (operand.op) {
                ScreenshotRegionOperand.Op.ADD -> result.op(next, Path.Op.UNION)
                ScreenshotRegionOperand.Op.SUBTRACT -> result.op(next, Path.Op.DIFFERENCE)
                ScreenshotRegionOperand.Op.INTERSECT -> result.op(next, Path.Op.INTERSECT)
            }
        }
        return result
    }

    /** Applies the user's add/subtract drag, mirroring `combined()`; no-op drags return this. */
    fun combined(vertices: List<PointF>, shape: ScreenshotRegionShape, op: ScreenshotRegionOp): ScreenshotRegion {
        if (vertices.size < 3) return this
        val fresh = ScreenshotRegionOperand(vertices, when (op) {
            ScreenshotRegionOp.ADD -> ScreenshotRegionOperand.Op.ADD
            ScreenshotRegionOp.SUBTRACT -> ScreenshotRegionOperand.Op.SUBTRACT
        }, shape)
        val freshBox = fresh.bounds()
        // The original skips drags that change nothing: a subtract disjoint from the region.
        if (op == ScreenshotRegionOp.SUBTRACT && !RectF(bounds).intersects(
                freshBox.left, freshBox.top, freshBox.right, freshBox.bottom)) return this
        val after = path().apply {
            op(fresh.path(), when (op) {
                ScreenshotRegionOp.ADD -> Path.Op.UNION
                ScreenshotRegionOp.SUBTRACT -> Path.Op.DIFFERENCE
            })
        }
        if (after.isEmpty) return ScreenshotRegion(emptyList())
        return ScreenshotRegion(operands + fresh)
    }

    /** The part of [bounds] that is not covered by the region; used to clear exported pixels. */
    fun outsidePath(): Path {
        val whole = Path().apply {
            addRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(),
                bounds.bottom.toFloat(), Path.Direction.CW)
        }
        return Path().apply { op(whole, path(), Path.Op.DIFFERENCE) }
    }

    /** Clears everything outside the region so the exported bitmap keeps only the chosen parts. */
    fun mask(bitmap: Bitmap, viewport: Rect = bounds): Bitmap {
        if (isRectangle && !isCustom) {
            val single = rects.single()
            if (single.contains(viewport)) return bitmap
        }
        val inside = path()
        val clear = Path().apply {
            addRect(viewport.left.toFloat(), viewport.top.toFloat(), viewport.right.toFloat(),
                viewport.bottom.toFloat(), Path.Direction.CW)
            op(inside, Path.Op.DIFFERENCE)
            offset(-viewport.left.toFloat(), -viewport.top.toFloat())
        }
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR); color = Color.TRANSPARENT }
        canvas.drawPath(clear, paint)
        return bitmap
    }

    fun toJson(): JSONArray = JSONArray().apply {
        operands.forEach { operand ->
            put(JSONArray().apply {
                put(operand.op.ordinal)
                put(operand.shape.id)
                put(JSONArray().apply {
                    operand.vertices.forEach { point ->
                        put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble()))
                    }
                })
            })
        }
    }
}
