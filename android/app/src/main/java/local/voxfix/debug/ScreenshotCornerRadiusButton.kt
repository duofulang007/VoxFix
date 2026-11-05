package local.voxfix.debug

import local.voxfix.debug.ScreenshotStyleProperty.*
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** Snow Shot's 56x28 icon/value button: click resets to 6, wheel steps within 0..83. */
internal class ScreenshotCornerRadiusButton(context: Context,private val drawing: ScreenshotDrawingLayer,label: String) : View(context) {
    private val density=resources.displayMetrics.density
    private val icon=context.getDrawable(R.drawable.snow_selection_radius)!!.mutate()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize=12*resources.displayMetrics.scaledDensity }
    private var value=drawing.inspectedStyle.cornerRadius
    private var mixed=drawing.isStyleMixed(CORNER_RADIUS)
    private var base=drawing.inspectedStyle
    private var downY=0f
    private var dragging=false
    private var cancelled=false
    private fun show(value: Int) { this.value=value; mixed=false; stateDescription=value.toString(); invalidate() }
    init {
        contentDescription=label; tooltipText="$label：点击重置，滚轮或上下拖动调整"
        isClickable=true; isFocusable=true
        background=GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius=6*density; setStroke(density.toInt().coerceAtLeast(1),0xFF424242.toInt()) }
        stateDescription=if(mixed) "Mixed" else value.toString()
        setOnClickListener { show(6); drawing.applyStyle(drawing.inspectedStyle.copy(cornerRadius=6),CORNER_RADIUS) }
        setOnGenericMotionListener { _,event ->
            val delta=event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if(event.actionMasked==MotionEvent.ACTION_SCROLL && delta.isFinite() && delta!=0f) {
                val next=(drawing.inspectedStyle.cornerRadius+if(delta>0) 1 else -1).coerceIn(0,83)
                show(next); drawing.applyStyle(drawing.inspectedStyle.copy(cornerRadius=next),CORNER_RADIUS); true
            } else false
        }
    }
    override fun onDraw(canvas: Canvas) {
        val color=if(isEnabled) Color.WHITE else 0x73FFFFFF
        paint.color=color
        val start=(width-(18+6)*density-paint.measureText("83"))/2
        icon.setTint(color); icon.setBounds(start.toInt(),((height-18*density)/2).toInt(),(start+18*density).toInt(),((height+18*density)/2).toInt())
        icon.draw(canvas)
        canvas.drawText(if(mixed) "-" else value.toString(),start+24*density,height/2f-(paint.ascent()+paint.descent())/2,paint)
    }
    private fun cancel() {
        cancelled=true
        if(dragging) { dragging=false; drawing.finishStylePreview(false) }
        show(drawing.inspectedStyle.cornerRadius)
        mixed=drawing.isStyleMixed(CORNER_RADIUS); stateDescription=if(mixed) "Mixed" else value.toString()
        parent?.requestDisallowInterceptTouchEvent(false)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!isEnabled) return false
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY=event.y; base=drawing.inspectedStyle; dragging=false; cancelled=false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if(!cancelled) {
                if(!dragging && abs(event.y-downY)>ViewConfiguration.get(context).scaledTouchSlop) { dragging=true; drawing.beginStylePreview() }
                if(dragging) {
                    val next=(base.cornerRadius+((downY-event.y)/(2*density)).toInt()).coerceIn(0,83)
                    show(next); drawing.previewStyle(base.copy(cornerRadius=next),CORNER_RADIUS)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL -> cancel()
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if(!cancelled) {
                    val commit=dragging; dragging=false
                    if(commit) drawing.finishStylePreview(true) else performClick()
                }
            }
        }
        return true
    }
    override fun performClick(): Boolean=super.performClick()
    override fun onDetachedFromWindow() { if(dragging) cancel(); super.onDetachedFromWindow() }
}
