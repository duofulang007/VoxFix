package local.voxfix.debug

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

object ScreenshotDrawingSmoke {
    fun run(test: Instrumentation) {
        var failure: Throwable? = null
        test.runOnMainSync {
            try {
                val image = Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                var saved: Bitmap? = null
                var savedRequest: ScreenshotSaveRequest? = null
                val translator = object : ScreenshotTranslator {
                    override fun available() = false
                    override fun cancel() {}
                    override fun translate(text: String,pair: TranslatePair,onResult:(String)->Unit,onFailure:(String)->Unit,onUpdate:(String)->Unit) {}
                }
                val overlay = ScreenshotCaptureOverlay(test.targetContext,image,{ request -> saved=request.composite; savedRequest=request },{},{},{},{},translator)
                fun field(name: String) = overlay.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(overlay)
                val root = field("root") as ViewGroup
                fun layout() { repeat(2) {
                    root.forceLayout(); root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
                    root.layout(0,0,1080,2400)
                } }
                fun find(view: View,label: String): View? {
                    if (view.contentDescription == label) return view
                    if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index),label)?.let { return it }
                    return null
                }
                fun click(label: String) { checkNotNull(find(root,label)) { "missing $label" }.performClick(); layout() }
                layout()
                (field("selector") as ScreenTextRegionView).selectRegion(Rect(100,100,980,1100))
                val layer = field("drawing") as ScreenshotDrawingLayer
                fun touch(action: Int,x: Float,y: Float) {
                    val event = MotionEvent.obtain(0,0,action,x,y,0)
                    try { root.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                fun stroke(tool: String,x: Float,y: Float,endX: Float,endY: Float) {
                    click(tool); touch(MotionEvent.ACTION_DOWN,x,y); touch(MotionEvent.ACTION_MOVE,endX,endY); touch(MotionEvent.ACTION_UP,endX,endY)
                }
                stroke("箭头",200f,300f,700f,600f)
                stroke("矩形",750f,800f,350f,400f)
                stroke("画笔",200f,850f,700f,950f)
                check(layer.history.items.map { it.tool } == listOf(ScreenshotDrawingTool.ARROW,ScreenshotDrawingTool.RECTANGLE,ScreenshotDrawingTool.PEN))
                click("撤销"); check(layer.history.items.size == 2)
                click("重做"); check(layer.history.items.size == 3)
                touch(MotionEvent.ACTION_DOWN,200f,1000f); touch(MotionEvent.ACTION_MOVE,700f,1000f); touch(MotionEvent.ACTION_CANCEL,700f,1000f)
                check(layer.history.items.size == 3) { "cancel must discard pending ink" }
                click("绘图颜色 蓝色"); click("线宽 4")
                touch(MotionEvent.ACTION_DOWN,200f,1050f); touch(MotionEvent.ACTION_UP,700f,1050f)
                check(layer.history.items.last().color == 0xFF1677FF.toInt())
                // A mark may start outside the capture box; it is kept as an object and only the box
                // is exported, which is what makes the working area larger than the selection.
                touch(MotionEvent.ACTION_DOWN,20f,20f); touch(MotionEvent.ACTION_MOVE,60f,60f); touch(MotionEvent.ACTION_UP,60f,60f)
                check(layer.history.items.size==5) { "框外起笔被丢弃" }
                check(layer.history.items.last().points.any { it.x<100f }) { "框外笔画被限制在框内" }
                click("画笔")
                check(layer.tool==ScreenshotDrawingTool.SELECT) { "repeated drawing tool must return to object selection" }
                click("画笔")
                layout()
                val styles = field("drawingStyles") as View
                check(styles.translationX >= 0 && styles.translationX+styles.width <= root.width)
                val screenshot = Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(screenshot))
                java.io.File(test.targetContext.cacheDir,"screenshot-drawing-mobile.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) }
                screenshot.recycle()
                click("识别文字")
                overlay.textResult(listOf(ScreenshotTextBlock(160,160,400,220,"示例文字")))
                layout(); click("编辑文字")
                check(layer.visibility == View.INVISIBLE) { "marks must not cover the text editor" }
                click("完成编辑")
                check(layer.visibility == View.VISIBLE && layer.history.items.size == 5)
                click("保存截图")
                val output = checkNotNull(saved)
                check(output.width == 880 && output.height == 1000)
                check(output.getPixel(350,350) != Color.WHITE) { "arrow must be exported" }
                check(output.getPixel(650,400) != Color.WHITE) { "reverse rectangle must be exported" }
                check(output.getPixel(350,800) != Color.WHITE) { "pen must be exported" }
                check(output.getPixel(350,950) == 0xFF1677FF.toInt()) { "selected color must be flattened" }
                // The outside stroke sits above and left of the box; the exported corner must still
                // be the untouched source, which is also where a wrong offset would show up first.
                for (x in 0 until 40) for (y in 0 until 40)
                    check(output.getPixel(x,y)==image.getPixel(x+100,y+100)) { "框外笔画混进了导出图片 ($x,$y)" }
                java.io.File(test.targetContext.cacheDir,"screenshot-drawing-export.png").outputStream().use { output.compress(Bitmap.CompressFormat.PNG,100,it) }
                output.recycle()
                // The save must carry the project, not only flattened pixels.
                val request = checkNotNull(savedRequest) { "保存没有携带工程" }
                check(request.document.canvasWidth==1080 && request.document.canvasHeight==2400) { "工程画布不是整屏" }
                check(request.document.viewport==ScreenshotDocRect(100,100,980,1100)) { "工程视窗不是当次选框：${request.document.viewport}" }
                check(request.document.objects.size==5) { "工程对象数量错误：${request.document.objects.size}" }
                check(request.document.objects.last().points.any { it.x<100f }) { "工程没有保留框外笔画" }
                check(request.document.objects.all { it.id>0 }) { "工程对象缺少编号" }
                check(request.document.text?.blocks?.single()?.text=="示例文字") { "工程没有保存识别文字" }
                check(request.replaceId==null && request.copySourceFromId==null) { "新截图不应顶替已有记录" }
                image.recycle()
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
}
