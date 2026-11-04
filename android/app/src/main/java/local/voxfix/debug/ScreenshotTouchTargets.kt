package local.voxfix.debug

import android.graphics.Rect
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup

/** Extends compact icons into free padding only; normal child gestures keep their own dispatch. */
internal object ScreenshotTouchTargets {
    fun install(parent: ViewGroup) {
        parent.touchDelegate=object : TouchDelegate(Rect(),parent) {
            private var target: View?=null
            private var downX=0f; private var downY=0f
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if(event.actionMasked==MotionEvent.ACTION_DOWN) {
                    downX=event.x; downY=event.y
                    val candidates=mutableListOf<Pair<View,Rect>>()
                    fun visit(group: ViewGroup) {
                        for(i in 0 until group.childCount) {
                            val child=group.getChildAt(i)
                            if(child.visibility!=View.VISIBLE || !child.isEnabled) continue
                            if(child.isClickable) {
                                val global=Rect()
                                if(child.getGlobalVisibleRect(global)) {
                                    val location=IntArray(2); parent.getLocationOnScreen(location); global.offset(-location[0],-location[1])
                                    val margin=(6*parent.resources.displayMetrics.density).toInt()
                                    val expanded=Rect(global).apply { inset(-margin,-margin) }
                                    if(expanded.contains(event.x.toInt(),event.y.toInt())) candidates.add(child to global)
                                }
                            } else if(child is ViewGroup) visit(child)
                        }
                    }
                    visit(parent)
                    target=candidates.minByOrNull { (_,r) -> kotlin.math.abs(event.x-r.exactCenterX())+kotlin.math.abs(event.y-r.exactCenterY()) }?.first
                }
                val child=target ?: return false
                val copy=MotionEvent.obtain(event)
                val slop=android.view.ViewConfiguration.get(parent.context).scaledTouchSlop
                if(event.actionMasked==MotionEvent.ACTION_MOVE && kotlin.math.hypot(event.x-downX,event.y-downY)>slop) {
                    copy.action=MotionEvent.ACTION_CANCEL; target=null
                }
                copy.setLocation(child.width/2f,child.height/2f)
                val handled=try { child.dispatchTouchEvent(copy) } finally { copy.recycle() }
                if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) target=null
                return handled
            }
        }
    }
}
