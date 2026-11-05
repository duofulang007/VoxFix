package local.voxfix.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar

/**
 * `createSelectionActionFamily`: the selection row carries an opacity slider (0–100, ±5 per wheel
 * notch, Mixed readout), a delete action next to copy, and a reset that stays available with an
 * empty selection. Reset is one undo step, like every other canvas edit.
 */
internal object ScreenshotSelectionActionsChecks {
    private fun find(view: View,label: String): View? {
        if(view.contentDescription==label) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),label)?.let { return it }
        return null
    }
    fun run(context: Context) {
        val image=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val layer=ScreenshotDrawingLayer(context,image) {}
        fun p(x: Float,y: Float)=ScreenshotDrawingPoint(x,y)
        fun rect(x: Float)=ScreenshotDrawingStroke(ScreenshotDrawingTool.RECTANGLE,listOf(p(x,100f),p(x+100f,200f)),
            Color.RED,4f,ScreenshotDrawingStyle(color=Color.RED),id=x.toLong())
        fun touch(action: Int,x: Float,y: Float) {
            val event=MotionEvent.obtain(0,1,action,x,y,0)
            try { layer.onTouchEvent(event) } finally { event.recycle() }
        }
        try {
            val a=rect(100f); val b=rect(300f)
            layer.layout(0,0,600,900); layer.region=Rect(0,0,600,900)
            layer.history.load(listOf(a,b)); layer.tool=ScreenshotDrawingTool.SELECT
            // Marquee both objects so the selection row is shown with two members.
            touch(MotionEvent.ACTION_DOWN,50f,50f)
            touch(MotionEvent.ACTION_UP,450f,250f)
            check(layer.selectedIndices==setOf(0,1)) { "框选没有选中两个对象：${layer.selectedIndices}" }

            ScreenshotSelectionActionControls(context,layer).also { bar ->
                bar.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(120,View.MeasureSpec.AT_MOST))
                bar.layout(0,0,1080,bar.measuredHeight)
                val slider=find(bar,"选区透明度") as? SeekBar ?: error("选择行缺少透明度滑杆")
                check(slider.isEnabled) { "有选中对象时透明度不可用" }
                slider.progress=40
                check(layer.history.items.all { kotlin.math.abs(it.style.opacity-0.4f)<0.01f }) {
                    "透明度没有作用到所有选中对象：${layer.history.items.map { it.style.opacity }}"
                }
                check(layer.history.canUndo) { "透明度改动没有进历史" }

                find(bar,"删除选中对象")?.performClick() ?: error("选择行缺少删除")
                check(layer.history.items.isEmpty()) { "删除选中对象没有生效" }

                // Reset works with nothing selected and is undone in one step.
                ScreenshotSelectionActionControls(context,layer).also { emptyBar ->
                    emptyBar.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(120,View.MeasureSpec.AT_MOST))
                    emptyBar.layout(0,0,1080,emptyBar.measuredHeight)
                    val reset=find(emptyBar,"重置画布") ?: error("选择行缺少重置画布")
                    check(reset.isEnabled) { "空选择时重置画布不该禁用" }
                    layer.history.load(listOf(a,b))
                    reset.performClick()
                    check(layer.history.items.isEmpty()) { "重置画布没有清空标注" }
                    layer.history.undo()
                    check(layer.history.items.size==2) { "重置画布不能一步撤销：${layer.history.items.size}" }
                }
            }
            touch(MotionEvent.ACTION_DOWN,50f,50f)
            touch(MotionEvent.ACTION_UP,450f,250f)
            val source = checkNotNull(layer.selectedTemplate())
            val prefsName = "screenshot-draw-template-check-${java.util.UUID.randomUUID()}"
            val isolated = object : android.content.ContextWrapper(context) {
                override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(prefsName, mode)
            }
            try {
                val store = ScreenshotDrawTemplateStore(isolated)
                store.save("测试模板", source)
                check(store.names() == listOf("测试模板"))
                val reopened = ScreenshotDrawTemplateStore(isolated).load("测试模板")
                check(reopened == source)
                check(layer.insertTemplate(reopened))
                check(layer.selectedIndices == setOf(2,3))
                val copies = layer.history.items.takeLast(2)
                check(copies[0].points.first().x == 150f && copies[0].points.first().y == 400f)
                check(copies[1].points.last().x == 450f && copies[1].points.last().y == 500f)
                layer.undo()
                check(layer.history.items == listOf(a,b))
                store.delete("测试模板")
                check(store.names().isEmpty())
            } finally { context.deleteSharedPreferences(prefsName) }
        } finally { layer.close(); image.recycle() }
    }
}
