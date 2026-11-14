package local.voxfix.debug

import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView

/**
 * 「布局之后」恢复滚动位置的唯一实现。
 *
 * 为什么不能用 `post { scrollTo(...) }`：post 只排进消息队列，此刻视图往往尚未
 * measure/layout；`scrollTo` 会把目标夹进 `[0, child.extent - viewport.extent]`，
 * 布局前两者都是 0，恢复值被静默夹成 0——这正是「点一下就回到最前面」的直接原因
 * （改造前 StyleRow / ToolbarPanel / ToolbarEditor / Overlay / PinEditor 五处同病）。
 *
 * 做法：挂 `OnLayoutChangeListener`，extent 有效后应用一次并自摘；调用时已布局好
 * 就立即生效。clamp/needsApply 为纯函数，JVM 可测。
 */
internal object ScreenshotScrollMemory {
    /**
     * 把 [value] 等到 [view] 下一次有效布局再应用（整页重建 / 面板重建场景）。
     * [horizontal]=true 恢复 scrollX，false 恢复 scrollY。
     */
    fun applyAfterLayout(view: View,value: Int,horizontal: Boolean) {
        if(value<=0) return
        if(!isScroller(view)) return
        val memory=Memory(view,horizontal,value)
        memory.attach()
        memory.tryApply()
    }

    // —— 纯函数（JVM 可测）——

    /** 布局信息无效时返回 null（=「还不能应用」）；有效时把目标夹进合法滚动范围。 */
    internal fun clamp(scroll: Int,viewportExtent: Int,childExtent: Int): Int? {
        if(viewportExtent<=0 || childExtent<=0) return null
        return scroll.coerceIn(0,(childExtent-viewportExtent).coerceAtLeast(0))
    }

    /** 恢复是否仍必要：目标无效或与当前一致都不必动。 */
    internal fun needsApply(current: Int,target: Int?): Boolean=target!=null && target!=current

    private fun isScroller(view: View)=view is HorizontalScrollView || view is ScrollView

    private class Memory(private val view: View,private val horizontal: Boolean,var pending: Int) {
        private val listener=View.OnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> tryApply() }

        fun attach() { view.addOnLayoutChangeListener(listener) }

        fun detach() { view.removeOnLayoutChangeListener(listener) }

        fun tryApply() {
            val scroller=scroller() ?: return
            val child=(view as? ViewGroup)?.getChildAt(0) ?: return   // 内容还没挂上：等下一次布局
            val viewport=if(horizontal) scroller.width-scroller.paddingLeft-scroller.paddingRight
                else scroller.height-scroller.paddingTop-scroller.paddingBottom
            val extent=if(horizontal) child.width else child.height
            val target=clamp(pending,viewport,extent) ?: return   // 布局未就绪：等下一次回调
            if(horizontal) scroller.scrollTo(target,scroller.scrollY)
            else scroller.scrollTo(scroller.scrollX,target)
            pending=-1
            detach()
        }

        private fun scroller(): View?=view.takeIf { isScroller(it) }
    }
}
