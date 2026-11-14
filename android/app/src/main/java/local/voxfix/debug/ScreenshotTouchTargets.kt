package local.voxfix.debug

import android.graphics.Rect
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar

/**
 * 把紧凑图标的热区撑到安卓最小触摸目标（48dp），**不改任何可视尺寸**。
 *
 * 改造前是固定外扩 6dp：32dp 的格只有 44dp 命中，28dp 的格只有 40dp，都低于 48dp。
 * 现在按子 View 实际尺寸补足差额；命中归属仍走「离触点最近的格中心」，
 * 相邻格在间距中线上自然分区，不会互相抢触摸。
 */
internal object ScreenshotTouchTargets {
    fun install(parent: ViewGroup, minTouchDp: Int = ScreenshotToolbarMetrics.TOUCH_MIN) {
        val minPx=(minTouchDp*parent.resources.displayMetrics.density).toInt()
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
                            // 含滑杆的子树整体跳过：滑杆走原生触摸管线（bindUninterruptedDrag），
                            // 读数区触摸不得被重定向到最近的 28dp 格造成误触。
                            if(containsSeekBar(child)) continue
                            if(child.visibility!=View.VISIBLE || !child.isEnabled) continue
                            if(child.isClickable) {
                                val global=Rect()
                                if(child.getGlobalVisibleRect(global)) {
                                    val location=IntArray(2); parent.getLocationOnScreen(location); global.offset(-location[0],-location[1])
                                    val margin=((minPx-minOf(child.width,child.height))/2).coerceAtLeast(0)
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

    /** 子树里藏了 SeekBar 就整棵跳过（滑杆块必须保持原生拖动语义）。 */
    private fun containsSeekBar(view: View): Boolean {
        if(view is SeekBar) return true
        if(view is ViewGroup) {
            for(i in 0 until view.childCount) if(containsSeekBar(view.getChildAt(i))) return true
        }
        return false
    }
}
