package local.voxfix.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Region

internal enum class ScreenshotRegionOp { ADD, SUBTRACT }

/**
 * Capture area made of axis-aligned rectangles, so "add region" and "subtract region" keep a real
 * shape instead of collapsing to one box. [bounds] is what OCR, drawing and the saved viewport use;
 * the shape itself only changes what pixels are exported.
 */
internal class ScreenshotRegion(parts: List<Rect>) {
    val rects: List<Rect> = parts.map { Rect(it) }.filter { it.width() > 0 && it.height() > 0 }
    val bounds: Rect = Rect().also { box -> rects.forEach { box.union(it) } }
    val isRectangle: Boolean get() = rects.size == 1
    val isEmpty: Boolean get() = rects.isEmpty()

    fun united(other: Rect): ScreenshotRegion {
        if (other.width() <= 0 || other.height() <= 0) return this
        return ScreenshotRegion(rects + Rect(other))
    }

    fun subtracted(other: Rect): ScreenshotRegion {
        if (other.width() <= 0 || other.height() <= 0) return this
        return ScreenshotRegion(rects.flatMap { part -> minus(part, other) })
    }

    /** The part of [bounds] that is not covered by the region; used to clear exported pixels. */
    fun outsidePath(): Path {
        val whole = Path().apply { addRect(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat(),Path.Direction.CW) }
        val holes = Path().apply { rects.forEach { addRect(it.left.toFloat(),it.top.toFloat(),it.right.toFloat(),it.bottom.toFloat(),Path.Direction.CW) } }
        return Path().apply { op(whole, holes, Path.Op.DIFFERENCE) }
    }

    /** Clears everything outside the region so the exported bitmap keeps only the chosen parts. */
    fun mask(bitmap: Bitmap,viewport: Rect = bounds): Bitmap {
        if (isRectangle && rects.single().contains(viewport)) return bitmap
        val canvas = Canvas(bitmap)
        val path = Path().apply {
            addRect(viewport.left.toFloat(),viewport.top.toFloat(),viewport.right.toFloat(),viewport.bottom.toFloat(),Path.Direction.CW)
            val inside=Path().apply { rects.forEach { addRect(it.left.toFloat(),it.top.toFloat(),it.right.toFloat(),it.bottom.toFloat(),Path.Direction.CW) } }
            op(inside,Path.Op.DIFFERENCE)
            offset(-viewport.left.toFloat(),-viewport.top.toFloat())
        }
        val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR); color = Color.TRANSPARENT }
        canvas.drawPath(path, paint)
        return bitmap
    }

    private fun minus(part: Rect, cut: Rect): List<Rect> {
        val overlap = Rect(part)
        if (!overlap.intersect(cut)) return listOf(part)
        val pieces = mutableListOf<Rect>()
        if (overlap.top > part.top) pieces.add(Rect(part.left, part.top, part.right, overlap.top))
        if (overlap.bottom < part.bottom) pieces.add(Rect(part.left, overlap.bottom, part.right, part.bottom))
        if (overlap.left > part.left) pieces.add(Rect(part.left, overlap.top, overlap.left, overlap.bottom))
        if (overlap.right < part.right) pieces.add(Rect(overlap.right, overlap.top, part.right, overlap.bottom))
        return pieces.filter { it.width() > 0 && it.height() > 0 }
    }
}
