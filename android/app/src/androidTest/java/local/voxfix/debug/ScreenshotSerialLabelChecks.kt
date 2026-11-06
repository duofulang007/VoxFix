package local.voxfix.debug

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.InputDevice
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

internal object ScreenshotSerialLabelChecks {
    private fun find(view: View,label: String): View? {
        if(view.contentDescription==label) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),label)?.let { return it }
        return null
    }
    private fun scroll(view: View,delta: Float) {
        val pointer=MotionEvent.PointerProperties().apply { id=0; toolType=MotionEvent.TOOL_TYPE_MOUSE }
        val position=MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_VSCROLL,delta) }
        val event=MotionEvent.obtain(0,1,MotionEvent.ACTION_SCROLL,1,arrayOf(pointer),arrayOf(position),0,0,1f,1f,0,0,InputDevice.SOURCE_MOUSE,0)
        try { check(view.dispatchGenericMotionEvent(event)) } finally { event.recycle() }
    }
    fun run(context: Context) {
        val image=Bitmap.createBitmap(800,800,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val memory=ScreenshotToolMemory(context)
        val textDefault=ScreenshotDrawingStyle(color=Color.BLUE,fontSize=54f,fontFamily="serif")
        memory.save(ScreenshotDrawingTool.TEXT,textDefault)
        val layer=ScreenshotDrawingLayer(context,image,memory) {}
        val renderer=ScreenshotDrawingRenderer(image) { 1f }
        fun touch(action: Int,x: Float,y: Float) {
            val event=MotionEvent.obtain(0,1,action,x,y,0)
            try { layer.onTouchEvent(event) } finally { event.recycle() }
        }
        fun drag(x: Float,y: Float,endX: Float,endY: Float) {
            touch(MotionEvent.ACTION_DOWN,x,y); touch(MotionEvent.ACTION_MOVE,endX,endY); touch(MotionEvent.ACTION_UP,endX,endY)
        }
        try {
            layer.layout(0,0,800,800); layer.region=Rect(0,0,800,800); layer.savedFontUnit=1f; layer.savedPixelUnit=1f
            layer.tool=ScreenshotDrawingTool.SERIAL
            var requested: Int?=null
            layer.onTextRequest={ _,index -> requested=index }
            drag(100f,400f,350f,400f)
            val serial=layer.history.items.first(); val label=layer.history.items.last()
            check(requested==1 && serial.boundTextId==label.id)
            check(serial.points.single()==ScreenshotDrawingPoint(100f,400f)) { "序号跑到松手位置" }
            check(label.style.fontFamily=="serif" && label.style.color==Color.BLUE && label.style.fontSize==24f)
            check(abs(renderer.bounds(label).centerX()-350)<.01 && abs(renderer.bounds(label).centerY()-400)<.01)
            layer.undo(); check(layer.history.items.isEmpty()); layer.redo(); check(layer.history.items.size==2)
            layer.beginTextPreview(label.points.first(),1); layer.previewText("说明文字 Note")
            val preview=image.copy(Bitmap.Config.ARGB_8888,true)
            layer.draw(Canvas(preview))
            check((380..430).any { y -> (140..290).any { x -> Color.red(preview.getPixel(x,y))>150 && Color.green(preview.getPixel(x,y))<100 } }) { "文字预览缺少连接线" }
            preview.recycle()
            layer.addText(label.points.first(),"说明文字 Note",1)
            val measured=layer.history.items[1]
            check(measured.textWidth in 20f..240f)
            check(abs(renderer.bounds(measured).centerX()-350)<.01 && abs(renderer.bounds(measured).centerY()-400)<.01)
            check(memory.style(ScreenshotDrawingTool.TEXT)==textDefault) { "序号说明文字污染独立文字默认参数" }
            val encoded=ScreenshotDocumentCodec.encode(ScreenshotDocument.full(800,800).copy(objects=layer.history.items))
            val decoded=ScreenshotDocumentCodec.decode(encoded)
            val before=layer.render(Rect(0,0,800,800),null)!!
            layer.history.load(decoded.objects)
            val after=layer.render(Rect(0,0,800,800),null)!!
            check(before.sameAs(after)) { "保存重开丢失关联文字或连接线" }
            File(context.getExternalFilesDir(null),"snow-t15-serial-labels.png").outputStream().use { before.compress(Bitmap.CompressFormat.PNG,100,it) }
            before.recycle(); after.recycle()
            val broken=JSONObject(encoded)
            broken.getJSONArray("objects").getJSONObject(0).put("boundTextId",Long.MAX_VALUE)
            check(runCatching { ScreenshotDocumentCodec.decode(broken.toString()) }.exceptionOrNull() is ScreenshotDocumentCodec.FormatException)
            broken.getJSONArray("objects").getJSONObject(0).put("boundTextId",label.id.toString())
            check(runCatching { ScreenshotDocumentCodec.decode(broken.toString()) }.exceptionOrNull() is ScreenshotDocumentCodec.FormatException)
            val legacy=JSONObject(encoded)
            for(i in 0..1) legacy.getJSONArray("objects").getJSONObject(i).remove("boundTextId")
            check(ScreenshotDocumentCodec.decode(legacy.toString()).objects.first().boundTextId==0L)
            layer.tool=ScreenshotDrawingTool.SELECT
            drag(100f,400f,130f,440f)
            check(layer.history.items[0].points.first()==ScreenshotDrawingPoint(130f,440f))
            check(layer.history.items[1]==measured && layer.history.items[0].boundTextId==measured.id)
            val count=layer.history.items.size
            layer.editSerialText(); check(layer.history.items.size==count && requested==1)
            layer.history.remove(setOf(1)); check(layer.history.items.single().boundTextId==0L)
            layer.undo(); check(layer.history.items[0].boundTextId==layer.history.items[1].id)
            val previous=layer.history.items
            layer.tool=ScreenshotDrawingTool.SERIAL
            touch(MotionEvent.ACTION_DOWN,300f,100f); touch(MotionEvent.ACTION_MOVE,600f,200f)
            touch(MotionEvent.ACTION_CANCEL,600f,200f); touch(MotionEvent.ACTION_UP,600f,200f)
            check(layer.history.items==previous) { "取消拖动仍创建序号说明" }

            layer.setNumber(Long.MAX_VALUE)
            check(ScreenshotToolMemory(context).startNumber==Long.MAX_VALUE)
            val controls=ScreenshotDrawingControls(context,layer,{ _,_,_-> },false)
            val counter=find(controls,"序号 ${Long.MAX_VALUE}，可滚轮调整")!!
            scroll(counter,1f); check(layer.nextNumber==Long.MAX_VALUE)
            scroll(counter,-1f); check(layer.nextNumber==Long.MAX_VALUE-1)
            layer.setNumber(0); layer.stepNumber(-1); check(layer.nextNumber==0L)
            context.getSharedPreferences("screenshot-tool-memory",Context.MODE_PRIVATE).edit().putInt("start-number",321).commit()
            check(ScreenshotToolMemory(context).startNumber==321L) { "旧Int序号记忆无法读取" }
            context.getSharedPreferences("screenshot-tool-memory",Context.MODE_PRIVATE).edit().putFloat("start-number",321.5f).commit()
            check(ScreenshotToolMemory(context).startNumber==1L) { "非法浮点序号记忆未回退" }
            val huge=serial.copy(number=Long.MAX_VALUE,boundTextId=0L)
            val hugeDoc=ScreenshotDocument.full(800,800).copy(objects=listOf(huge))
            check(ScreenshotDocumentCodec.decode(ScreenshotDocumentCodec.encode(hugeDoc)).objects.single().number==Long.MAX_VALUE)
            scroll(find(controls,"字号 ${ScreenshotFontSize.format(layer.style.fontSize)}")!!,1f)
            check(layer.style.fontSize==serial.style.fontSize+1f)
            layer.applyStyle(layer.style.copy(serialShape=ScreenshotSerialShape.SOLID_CIRCLE,serialGeometryVersion=1))
            val solidControls=ScreenshotDrawingControls(context,layer,{ _,_,_-> },false)
            check(!find(solidControls,"填充色")!!.isEnabled && !find(solidControls,"交叉线填充")!!.isEnabled)
            check(!find(solidControls,"背景填充 浅红")!!.isEnabled)
            layer.applyStyle(layer.style.copy(serialShape=ScreenshotSerialShape.CIRCLE))
            val circleControls=ScreenshotDrawingControls(context,layer,{ _,_,_-> },false)
            check(!find(circleControls,"选择字体")!!.isEnabled && find(circleControls,"字号 ${ScreenshotFontSize.format(layer.style.fontSize)}")!!.isEnabled)
            // Upstream disables (not hides) the number editor for the bare circle; at this point the number is 0.
            val circleNumber=find(circleControls,"序号 0，可滚轮调整")
            check(circleNumber!=null && !circleNumber!!.isEnabled) { "无数字圆的编号输入应禁用可见" }
            find(circleControls,"空心圆")!!.performClick()
            val restoredControls=ScreenshotDrawingControls(context,layer,{ _,_,_-> },false)
            val restoredNumber=find(restoredControls,"序号 0，可滚轮调整")
            check(restoredNumber!=null && restoredNumber!!.isEnabled) { "切回带数字类型后编号输入未恢复" }
            find(circleControls,"绘图颜色 红色")!!.performClick(); check(layer.style.color==0xFFF5222D.toInt())
            find(circleControls,"背景填充 浅蓝")!!.performClick(); check(layer.style.hasFill && layer.style.fillColor==0xFFBAE0FF.toInt())

            var family=""
            val panel=ScreenshotFontPanel(context,"sans-serif") { family=it }
            check(panel.list.count==ScreenshotFontFamilies.names.size)
            panel.search.setText("condensed")
            check(panel.list.count>0)
            val choice=panel.list.adapter.getItem(0).toString()
            panel.list.performItemClick(panel.list.adapter.getView(0,null,panel.list),0,0)
            check(family==choice && family.contains("condensed",ignoreCase=true))
            panel.search.setText("font-no-such-family-094321"); check(panel.list.count==0)
            panel.search.setText(""); check(panel.list.count==ScreenshotFontFamilies.names.size)
            panel.measure(View.MeasureSpec.makeMeasureSpec(735,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.AT_MOST))
            panel.layout(0,0,735,panel.measuredHeight)
            val fonts=Bitmap.createBitmap(735,panel.height,Bitmap.Config.ARGB_8888)
            panel.draw(Canvas(fonts))
            File(context.getExternalFilesDir(null),"snow-t15-font-picker.png").outputStream().use { fonts.compress(Bitmap.CompressFormat.PNG,100,it) }
            fonts.recycle()
        } finally { layer.close(); renderer.close(); image.recycle() }
    }
}
