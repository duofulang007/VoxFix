package local.voxfix.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
        val fresh=Bitmap.createBitmap(image.width,image.height,Bitmap.Config.ARGB_8888)
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
            // A12：整条选区栏（读数 + 两行按钮）一起收起，主栏保持可见（取消始终可达），
            // 原位留一颗可见的恢复胶囊；恢复后读数与选区都不变。
            checkNotNull(find(root,"隐藏选择工具栏")).performClick()
            check(status.visibility == View.GONE) { "隐藏后读数仍然可见" }
            check((field(overlay,"toolbar") as View).visibility == View.VISIBLE) { "隐藏把主栏也收掉了" }
            check((field(overlay,"selectActionsScroll") as View).visibility == View.GONE) { "故选栏没有整条收起" }
            val restore = checkNotNull(find(root,"恢复选区栏")) { "整条收起后没有恢复入口" }
            check(restore.visibility == View.VISIBLE) { "恢复入口不可见" }
            restore.performClick()
            check(status.visibility == View.VISIBLE && status.text.toString()=="选区 600 × 500 px") { "恢复后读数异常" }
            check((field(overlay,"selectActionsScroll") as View).visibility == View.VISIBLE) { "恢复后选区栏没回来" }
            check(restore.visibility == View.GONE) { "恢复后入口没有收起" }
            check(field(overlay,"picked") == picked) { "隐藏/恢复改动了选区" }
            // A09：二维码标记——中心按识别框质心换算，只在「编辑选区 + 无绘图工具」时显示，
            // 命中半径按上游 12 逻辑像素；这颗按钮是显隐开关而不是重新扫描。
            val markers = field(overlay,"qrMarkers") as ScreenshotQrMarkers
            val mapped = ScreenshotQrMarkers.centersFor(
                listOf(ScreenshotBarcodeHit("https://example.com", Rect(10,20,30,40))),
                checkNotNull(picked),1080,2400,1080,2400)
            check(mapped.size==1 && Math.round(mapped[0].x)==picked.left+20 && Math.round(mapped[0].y)==picked.top+30) {
                "坐标换算不对：$mapped"
            }
            overlay.javaClass.getDeclaredField("qrHits").apply { isAccessible=true }
                .set(overlay,listOf(ScreenshotBarcodeHit("https://example.com", Rect(10,20,30,40))))
            overlay.javaClass.getDeclaredField("qrVisible").apply { isAccessible=true }.set(overlay,true)
            overlay.javaClass.getDeclaredMethod("sync").apply { isAccessible=true }.invoke(overlay)
            root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            root.layout(0,0,1080,2400)
            check(markers.visibility==View.VISIBLE && markers.markerCount()==1) {
                "标记未显示：${markers.visibility}/${markers.markerCount()}"
            }
            val center = checkNotNull(markers.centerAt(0))
            check(markers.hitIndex(center.x,center.y)==0) { "标记命中失败" }
            check(markers.hitIndex(center.x+60*context.resources.displayMetrics.density,center.y)==-1) { "标记外误命中" }
            checkNotNull(find(root,"隐藏二维码标记")) { "开关标签没有跟随可见性" }
            checkNotNull(find(root,"隐藏二维码标记")).performClick()
            check(markers.visibility==View.GONE && field(overlay,"qrVisible")==false) { "关闭后标记仍在" }
            checkNotNull(find(root,"显示二维码标记"))
            checkRecapture(overlay,root,image,fresh,checkNotNull(picked))
        } finally {
            overlay.dismiss(context.getSystemService(android.view.WindowManager::class.java))
            image.recycle()
            fresh.recycle()
        }
    }

    private fun checkRecapture(overlay: ScreenshotCaptureOverlay, root: ViewGroup,
                               original: Bitmap, fresh: Bitmap, picked: Rect) {
        val drawing = field(overlay,"drawing") as ScreenshotDrawingLayer
        original.eraseColor(Color.RED)
        val box = listOf(ScreenshotDrawingPoint(320f,420f),ScreenshotDrawingPoint(450f,550f))
        drawing.history.add(ScreenshotDrawingStroke(ScreenshotDrawingTool.FILTER,box,Color.BLACK,4f))
        val before = drawing.history.items
        fun renderColor(): Int {
            val rendered = checkNotNull(drawing.render(picked,null))
            return try { rendered.getPixel(50,50) } finally { rendered.recycle() }
        }
        check(renderColor() == Color.RED)
        var requests = 0
        overlay.onRecaptureRequested = { requests++; check(overlay.hideForRecapture()) }
        overlay.javaClass.getDeclaredMethod("sync").apply { isAccessible=true }.invoke(overlay)
        val button = checkNotNull(find(root,"重新截图"))
        check(button.isEnabled)
        button.performClick()
        check(requests == 1 && root.visibility == View.INVISIBLE)
        overlay.restoreAfterRecapture("测试失败")
        check(root.visibility == View.VISIBLE && field(overlay,"picked") == picked)
        check(drawing.history.items == before && renderColor() == Color.RED)

        overlay.textResult(listOf(ScreenshotTextBlock(310,410,440,445,"原识别结果")))
        val text = field(overlay,"textLayer") as ScreenshotTextLayer
        (text.getChildAt(0) as android.widget.EditText).setText("手动修改的文字")
        check(text.canUndo)
        val move = checkNotNull(find(root,"编辑选区"))
        move.performClick()
        move.performClick()

        val session = ScreenshotSession(original)
        val wrongSize = Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        try {
            check(runCatching { session.replaceFrame(wrongSize) }.isFailure)
            check(session.image === original)
        } finally { wrongSize.recycle() }
        fresh.eraseColor(Color.BLUE)
        button.performClick()
        check(requests == 2 && root.visibility == View.INVISIBLE)
        overlay.replaceCaptureSource(fresh,null)
        check(session.replaceFrame(fresh) === original && session.image === fresh)
        overlay.restoreAfterRecapture(null)
        check(root.visibility == View.VISIBLE && field(overlay,"picked") == picked)
        check(field(overlay,"textLayer") == null)
        check(overlay.retainedResult()?.value?.contains("手动修改的文字") == true)
        check(checkNotNull(find(root,"上次文字草稿")).visibility == View.VISIBLE)
        check(drawing.history.items == before && renderColor() == Color.BLUE)
        drawing.undo()
        check(drawing.history.items.isEmpty())
        drawing.redo()
        check(drawing.history.items == before && renderColor() == Color.BLUE)
    }
}
