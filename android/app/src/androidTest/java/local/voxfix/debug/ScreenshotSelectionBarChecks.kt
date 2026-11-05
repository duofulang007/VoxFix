package local.voxfix.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * A10/A11: the selection readout reports the crop in physical pixels and, on tap, the same crop in
 * density-independent dp. Switching units never changes the captured rectangle.
 */
internal object ScreenshotSelectionBarChecks {
    private fun field(target: Any,name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun find(view: View,label: String): View? {
        if(view.contentDescription==label) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),label)?.let { return it }
        return null
    }
    private class Translator : ScreenshotTranslator {
        override fun translate(text: String,pair: TranslatePair,onResult:(String)->Unit,onFailure:(String)->Unit,onUpdate:(String)->Unit) {}
        override fun cancel() {}
        override fun available()=true
    }
    fun run(context: Context) {
        val image=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888)
        val overlay=ScreenshotCaptureOverlay(context,image,{},{},{},{},{},Translator())
        try {
            val root=field(overlay,"root") as ViewGroup
            (field(overlay,"selector") as ScreenTextRegionView).selectRegion(Rect(300,400,900,900))
            overlay.javaClass.getDeclaredMethod("sync").apply { isAccessible=true }.invoke(overlay)
            root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            root.layout(0,0,1080,2400)
            find(root,"编辑选区")?.performClick()
            val status=field(overlay,"status") as TextView
            check(status.visibility==View.VISIBLE) { "选区大小没有显示" }
            check(status.text.toString()=="选区 600 × 500 px") { "物理像素读数不对：${status.text}" }
            val density=context.resources.displayMetrics.density
            val logical="选区 ${Math.round(600/density)} × ${Math.round(500/density)} dp"
            status.performClick()
            check(status.text.toString()==logical) { "逻辑像素读数不对：${status.text} / $logical" }
            // The captured rectangle is unaffected by the readout unit.
            val picked=field(overlay,"picked") as Rect?
            check(picked!=null && picked.width()==600 && picked.height()==500) { "切换单位改变了选区：$picked" }
            status.performClick()
            check(status.text.toString()=="选区 600 × 500 px") { "无法切回物理像素：${status.text}" }
        } finally {
            overlay.dismiss(context.getSystemService(android.view.WindowManager::class.java))
            image.recycle()
        }
    }
}
