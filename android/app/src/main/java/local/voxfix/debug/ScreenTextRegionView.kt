package local.voxfix.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Toast
import kotlin.math.abs

/** A reselected region can be resized or moved; an ordinary new drag still recognizes on release. */
internal class ScreenTextRegionView(
    context: Context,
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val previous: Rect?,
    private val recognize: (Rect) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
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
    private var pendingSelection: Rect? = null
    fun selectRegion(region: Rect) {
        if (width == 0 || height == 0) { pendingSelection = Rect(region); return }
        rect.set(region.left * width.toFloat() / imageWidth,region.top * height.toFloat() / imageHeight,
            region.right * width.toFloat() / imageWidth,region.bottom * height.toFloat() / imageHeight)
        invalidate(); onRegionMoved?.invoke(Rect(region))
    }
    private fun region(): Rect {
        var left = (rect.left * imageWidth / width).toInt()
        var top = (rect.top * imageHeight / height).toInt()
        if (edges == 16) {
            // Rounding two moving edges independently changes the crop by one pixel.
            val fixedWidth = ((before.right * imageWidth / width).toInt() - (before.left * imageWidth / width).toInt()).coerceIn(1,imageWidth)
            val fixedHeight = ((before.bottom * imageHeight / height).toInt() - (before.top * imageHeight / height).toInt()).coerceIn(1,imageHeight)
            left = if (rect.right >= width) imageWidth - fixedWidth else left.coerceIn(0,imageWidth-fixedWidth)
            top = if (rect.bottom >= height) imageHeight - fixedHeight else top.coerceIn(0,imageHeight-fixedHeight)
            return Rect(left,top,left+fixedWidth,top+fixedHeight)
        }
        return Rect(left,top,(rect.right * imageWidth / width).toInt(),(rect.bottom * imageHeight / height).toInt())
    }

    init { contentDescription = "拖动框选文字；已有选框可拖动边缘调整或在框内移动，松手识别" }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pendingSelection?.let { pendingSelection = null; selectRegion(it); return }
        if (oldw == 0 && previous != null) rect.set(previous.left * w.toFloat() / imageWidth,
            previous.top * h.toFloat() / imageHeight, previous.right * w.toFloat() / imageWidth,
            previous.bottom * h.toFloat() / imageHeight)
    }
    override fun onDraw(canvas: Canvas) {
        paint.style = Paint.Style.FILL; paint.color = 0x80000000.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, paint)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), paint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, paint)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, paint)
        if (rect.isEmpty) return
        val blue = 0xff4096ff.toInt()
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * density; paint.color = blue
        canvas.drawRect(rect, paint)
        val points = mutableListOf<Pair<Float,Float>>()
        val side = minOf(rect.width(),rect.height())
        if (side > 32 * density) for (x in listOf(rect.left,rect.right)) for (y in listOf(rect.top,rect.bottom)) points.add(x to y)
        if (side > 64 * density) points.addAll(listOf(rect.centerX() to rect.top,rect.right to rect.centerY(),rect.centerX() to rect.bottom,rect.left to rect.centerY()))
        for ((x,y) in points) {
            paint.style = Paint.Style.FILL; paint.color = blue; canvas.drawCircle(x,y,4*density,paint)
            paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.strokeWidth = 1.5f*density; canvas.drawCircle(x,y,4*density,paint)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = true; moved = false
                downX = event.x.coerceIn(0f, width.toFloat()); downY = event.y.coerceIn(0f, height.toFloat())
                before.set(rect); edges = 0
                val hit = RectF(rect).apply { inset(-20 * density, -20 * density) }
                if (!rect.isEmpty && hit.contains(downX, downY)) {
                    if (abs(downX - rect.left) <= 20 * density && abs(downX - rect.left) <= abs(downX - rect.right)) edges = edges or 1
                    else if (abs(downX - rect.right) <= 20 * density) edges = edges or 2
                    if (abs(downY - rect.top) <= 20 * density && abs(downY - rect.top) <= abs(downY - rect.bottom)) edges = edges or 4
                    else if (abs(downY - rect.bottom) <= 20 * density) edges = edges or 8
                    if (edges == 0) edges = 16
                } else rect.set(downX, downY, downX, downY)
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                if (active) { active = false; rect.set(before); invalidate(); if (width > 0 && height > 0) onRegionMoved?.invoke(region()) }
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (!active) return true
                val x = event.x.coerceIn(0f, width.toFloat()); val y = event.y.coerceIn(0f, height.toFloat())
                val dx = x - downX; val dy = y - downY
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
                        // A plain tap must not drop an existing text result.
                        Toast.makeText(context, "请拖动选择文字区域", Toast.LENGTH_SHORT).show()
                    } else recognize(picked)
                }
            }
        }
        return true
    }
}
