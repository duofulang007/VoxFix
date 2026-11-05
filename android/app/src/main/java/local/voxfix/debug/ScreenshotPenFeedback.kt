package local.voxfix.debug

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Magnifier
import kotlin.math.min
import kotlin.math.roundToInt

/** Phone-only precision feedback; never part of the document renderer or export. */
internal class ScreenshotPenFeedback(private val target: View,private val imageWidth: Int,private val imageHeight: Int) {
    private val density=target.resources.displayMetrics.density
    private var focus: ScreenshotDrawingPoint?=null
    private var finger: ScreenshotDrawingPoint?=null
    private var magnifier: Magnifier?=null
    private val crosshair=Crosshair(density)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE }
    private val update=Runnable { showMagnifier() }

    fun grab(endpoint: ScreenshotDrawingPoint,press: ScreenshotDrawingPoint) {
        clear(); focus=endpoint; finger=press
        target.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
        schedule()
    }
    fun move(point: ScreenshotDrawingPoint,pointer: ScreenshotDrawingPoint) {
        if(focus==null) return
        focus=point; finger=pointer; schedule()
    }
    private fun schedule() { target.removeCallbacks(update); target.postOnAnimation(update) }
    fun clear() {
        target.removeCallbacks(update); magnifier?.dismiss(); magnifier=null; focus=null; finger=null
    }
    fun draw(canvas: Canvas,endpoints: List<ScreenshotDrawingPoint>) {
        val active=focus
        for(point in active?.let(::listOf) ?: endpoints) {
            val radius=(if(active==null) 4f else 7f)*density
            val x=point.x*target.width/imageWidth; val y=point.y*target.height/imageHeight
            paint.color=Color.WHITE; paint.strokeWidth=3.5f*density
            canvas.drawCircle(x,y,radius,paint)
            paint.color=0xFF4096FF.toInt(); paint.strokeWidth=1.5f*density
            canvas.drawCircle(x,y,radius,paint)
        }
    }
    private fun showMagnifier() {
        val point=focus ?: return
        val pointer=finger ?: return
        if(!target.isAttachedToWindow || !target.isShown || target.windowToken==null) return
        val visible=Rect()
        val root=target.rootView
        if(!root.getLocalVisibleRect(visible)) return
        val rootLocation=IntArray(2); val targetLocation=IntArray(2)
        root.getLocationInWindow(rootLocation); target.getLocationInWindow(targetLocation)
        visible.offset(rootLocation[0]-targetLocation[0],rootLocation[1]-targetLocation[1])
        val sx=target.width.toFloat()/imageWidth; val sy=target.height.toFloat()/imageHeight
        val box=lensBounds(ScreenshotDrawingPoint(pointer.x*sx,pointer.y*sy),SelBox.of(visible),density) ?: return
        val width=box.width.roundToInt().coerceAtLeast(1); val height=box.height.roundToInt().coerceAtLeast(1)
        var lens=magnifier
        if(lens==null || lens.width!=width || lens.height!=height) {
            lens?.dismiss()
            lens=Magnifier.Builder(target).setSize(width,height).setInitialZoom(2f)
                .setCornerRadius(8*density).setOverlay(crosshair)
                .setSourceBounds(Magnifier.SOURCE_BOUND_MAX_VISIBLE,Magnifier.SOURCE_BOUND_MAX_VISIBLE,
                    Magnifier.SOURCE_BOUND_MAX_VISIBLE,Magnifier.SOURCE_BOUND_MAX_VISIBLE).build()
            magnifier=lens
        }
        val x=(point.x*sx).coerceIn(0f,target.width.toFloat()); val y=(point.y*sy).coerceIn(0f,target.height.toFloat())
        lens.show(x,y,box.centerX,box.centerY)
        // The native source is clamped at screen edges; keep the reticle on the actual point.
        lens.sourcePosition?.let { source ->
            val location=IntArray(2); target.getLocationInWindow(location)
            crosshair.x=(x+location[0]-source.x)*lens.width/lens.sourceWidth
            crosshair.y=(y+location[1]-source.y)*lens.height/lens.sourceHeight
            crosshair.invalidateSelf()
        }
    }
    companion object {
        fun lensBounds(pointer: ScreenshotDrawingPoint,visible: SelBox,density: Float): SelBox? {
            val margin=8*density; val gap=36*density
            val width=min(112*density,visible.width-margin*2); val height=min(72*density,visible.height-margin*2)
            if(width<1 || height<1) return null
            val left=(pointer.x-width/2).coerceIn(visible.left+margin,visible.right-margin-width)
            val above=pointer.y-gap-height
            val top=(if(above>=visible.top+margin) above else pointer.y+gap)
                .coerceIn(visible.top+margin,visible.bottom-margin-height)
            return SelBox(left,top,left+width,top+height)
        }
    }
    private class Crosshair(private val density: Float): Drawable() {
        var x=0f
        var y=0f
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE }
        override fun draw(canvas: Canvas) {
            for((color,width) in listOf(Color.WHITE to 3f,0xFF4096FF.toInt() to 1f)) {
                paint.color=color; paint.strokeWidth=width*density
                canvas.drawLine(x-10*density,y,x-3*density,y,paint)
                canvas.drawLine(x+3*density,y,x+10*density,y,paint)
                canvas.drawLine(x,y-10*density,x,y-3*density,paint)
                canvas.drawLine(x,y+3*density,x,y+10*density,paint)
            }
        }
        override fun setAlpha(alpha: Int) { paint.alpha=alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter }
        @Deprecated("Drawable API") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
}
