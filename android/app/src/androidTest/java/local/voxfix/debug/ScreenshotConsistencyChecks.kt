package local.voxfix.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.json.JSONObject

/** Regression checks for continuing edits, rather than only adding new marks. */
internal object ScreenshotConsistencyChecks {
    fun run(context: Context) {
        val failures=mutableListOf<String>()
        fun checkCase(name: String,run:()->Unit) { runCatching(run).onFailure { failures.add("$name: ${it.message}") } }
        checkCase("earlier-text") {
            val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888)
            val layer=ScreenshotDrawingLayer(context,image) {}
            try {
                layer.layout(0,0,400,600)
                layer.addText(ScreenshotDrawingPoint(20f,30f),"第一条")
                layer.addText(ScreenshotDrawingPoint(30f,250f),"第二条")
                val id=layer.history.items.first().id
                layer.addText(ScreenshotDrawingPoint(20f,30f),"修改第一条",0)
                check(layer.selected?.id==id) { "编辑旧文字后选中了其他对象" }
                layer.deleteSelected()
                check(layer.history.items.single().text=="第二条") { "删除落在错误的对象" }
            } finally { layer.close(); image.recycle() }
        }
        checkCase("toolbar-close") {
            val panel=ScreenshotToolbarPanel(context)
            val close=View(context)
            val items=(1..5).map { View(context) to false }
            val density=context.resources.displayMetrics.density
            panel.setTools(items,close,(400*density).toInt(),(800*density).toInt())
            panel.measure(View.MeasureSpec.makeMeasureSpec(panel.packedWidth,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(panel.packedHeight,View.MeasureSpec.EXACTLY))
            panel.layout(0,0,panel.measuredWidth,panel.measuredHeight)
            val last=items.last().first
            val box=Rect(0,0,last.width,last.height)
            panel.offsetDescendantRectToMyCoords(last,box)
            check(box.right<=close.left) { "末尾工具与关闭按钮重叠：${box.right}>${close.left}" }
        }
        checkCase("region-roundtrip") {
            val json=JSONObject(ScreenshotDocumentCodec.encode(ScreenshotDocument.full(400,600)))
            json.put("regions",org.json.JSONArray("[[0,0,400,200],[0,400,400,600]]"))
            val restored=ScreenshotDocumentCodec.decode(json.toString())
            check(JSONObject(ScreenshotDocumentCodec.encode(restored)).optJSONArray("regions")?.length()==2) {
                "保存重开丢失选区形状"
            }
        }
        checkCase("pin-crop") {
            val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val document=ScreenshotDocument(400,600,ScreenshotDocRect(50,100,250,400),text=
                ScreenshotDocumentText(listOf(ScreenshotTextBlock(60,120,180,160,"已编辑文字"))),drawingUnit=2f,fontUnit=2f)
            var result: ScreenshotPinResult?=null
            val editor=ScreenshotPinEditor(context,image,Rect(100,200,300,500),document=document) { result=it }
            try {
                val root=editor.javaClass.getDeclaredField("root").apply { isAccessible=true }.get(editor) as ViewGroup
                repeat(2) {
                    root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
                    root.layout(0,0,1080,2400)
                }
                fun find(view: View): View? {
                    if(view.contentDescription=="完成") return view
                    if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                    return null
                }
                checkNotNull(find(root)).performClick()
                val saved=checkNotNull(result)
                check(saved.bitmap.width==200 && saved.bitmap.height==300) { "二次编辑导出整张底图，丢失裁剪视窗" }
                check(saved.document.text==document.text) { "贴图丢失文字层" }
                var ink=0
                for(x in 10 until 130) for(y in 20 until 60) if(saved.bitmap.getPixel(x,y)!=Color.WHITE) ink++
                check(ink>0) { "贴图二次完成没有合成识别文字" }
                check(saved.document.fontUnit==2f && saved.document.drawingUnit==2f) { "贴图改变了工程渲染比例" }
                saved.bitmap.recycle()
            } finally { editor.close(); image.recycle() }
        }
        checkCase("font-roundtrip") {
            val doc=ScreenshotDocument.full(400,600).copy(drawingUnit=2f,fontUnit=2.5f)
            check(ScreenshotDocumentCodec.decode(ScreenshotDocumentCodec.encode(doc))==doc) { "重开丢失绘制比例" }
            val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888)
            val layer=ScreenshotDrawingLayer(context,image) {}
            try {
                layer.savedFontUnit=doc.fontUnit; layer.savedPixelUnit=doc.drawingUnit
                layer.layout(0,0,400,600)
                val before=layer.fontUnit
                layer.layout(0,0,200,300)
                check(layer.fontUnit==before && layer.pixelUnit==2f) { "缩放窗口改变了已保存字号" }
                layer.region=Rect(0,0,400,600)
                layer.history.add(ScreenshotDrawingStroke(ScreenshotDrawingTool.RECTANGLE,
                    listOf(ScreenshotDrawingPoint(100f,100f),ScreenshotDrawingPoint(200f,200f)),Color.RED,4f))
                layer.tool=ScreenshotDrawingTool.SELECT
                val touch=android.view.MotionEvent.obtain(1,2,android.view.MotionEvent.ACTION_DOWN,40f,75f,0)
                try { layer.onTouchEvent(touch) } finally { touch.recycle() }
                check(layer.selected!=null) { "缩小贴图后手指命中容差被缩小" }
            } finally { layer.close(); image.recycle() }
        }
        checkCase("reopened-overlay") {
            val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val doc=ScreenshotDocument.full(400,600).copy(text=ScreenshotDocumentText(listOf(ScreenshotTextBlock(30,40,250,90,"保留识别文字"))),
                regions=listOf(ScreenshotDocRect(0,0,400,200),ScreenshotDocRect(0,400,400,600)),drawingUnit=2f,fontUnit=2f)
            val translator=object : ScreenshotTranslator {
                override fun available()=false
                override fun cancel() {}
                override fun translate(text:String,pair:TranslatePair,onResult:(String)->Unit,onFailure:(String)->Unit,onUpdate:(String)->Unit) {}
            }
            var saved: ScreenshotSaveRequest?=null
            val overlay=ScreenshotCaptureOverlay(context,image,{saved=it},{},{},{},{},translator,project=ScreenshotProject(doc,"a".repeat(32),1,null))
            fun field(name:String)=overlay.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(overlay)
            val root=field("root") as ViewGroup
            try {
                repeat(2) {
                    root.measure(View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.EXACTLY))
                    root.layout(0,0,800,1200)
                }
                overlay.javaClass.getDeclaredMethod("selectDrawing",ScreenshotDrawingTool::class.java).apply { isAccessible=true }.invoke(overlay,ScreenshotDrawingTool.ARROW)
                check((field("resultLayer") as View).visibility==View.VISIBLE) { "切绘图工具丢失识别文字显示" }
                overlay.javaClass.getDeclaredMethod("dragToolbar",Float::class.javaPrimitiveType,Float::class.javaPrimitiveType)
                    .apply { isAccessible=true }.invoke(overlay,0f,3000f)
                val bar=field("toolbar") as View
                val details=field("secondaryPanel") as View
                check(details.translationY<bar.translationY) { "工具栏拖到底部后详细操作不可达" }
                overlay.javaClass.getDeclaredMethod("beginSave").apply { isAccessible=true }.invoke(overlay)
                val request=checkNotNull(saved)
                check(request.document.regions==doc.regions && request.document.fontUnit==2f) { "重开再保存丢失形状或比例" }
                val output=checkNotNull(request.composite)
                check(Color.alpha(output.getPixel(200,300))==0) { "重开再保存填平了选区空洞" }
                var ink=0
                for(x in 30 until 250) for(y in 40 until 90) if(output.getPixel(x,y)!=Color.WHITE) ink++
                check(ink>0) { "切换绘图后保存漏掉识别文字" }
            } finally {
                saved?.composite?.recycle()
                overlay.dismiss(context.getSystemService(android.view.WindowManager::class.java)); image.recycle()
            }
        }
        checkCase("group-tap") {
            var clicks=0; var menus=0
            val button=View(context).apply { setOnClickListener { clicks++ } }
            val group=ScreenshotToolGroupView(context,button,"工具组") { menus++ }
            group.layout(0,0,100,100)
            val down=android.view.MotionEvent.obtain(1,1,android.view.MotionEvent.ACTION_DOWN,98f,98f,0)
            val up=android.view.MotionEvent.obtain(1,10,android.view.MotionEvent.ACTION_UP,98f,98f,0)
            try { group.dispatchTouchEvent(down); group.dispatchTouchEvent(up) } finally { down.recycle(); up.recycle() }
            check(clicks==1 && menus==0) { "普通点击误入没有角标的隐藏菜单" }
            group.performLongClick()
            check(menus==1) { "工具组长按入口失效" }
        }
        checkCase("pixel-icons") {
            fun render(id:Int): Bitmap {
                val bitmap=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888)
                val drawable=checkNotNull(context.getDrawable(id))
                drawable.setBounds(0,0,96,96); drawable.draw(android.graphics.Canvas(bitmap))
                return bitmap
            }
            val logical=render(R.drawable.snow_pixel_logical)
            val physical=render(R.drawable.snow_pixel_physical)
            val filter=render(ScreenshotToolbarEditor.icon(ScreenshotAction.FILTER))
            val spotlight=render(ScreenshotToolbarEditor.icon(ScreenshotAction.SPOTLIGHT))
            try {
                check(Color.alpha(logical.getPixel(16,48))>200 && Color.alpha(logical.getPixel(48,48))==0) { "逻辑像素图标不是空心方框" }
                check(Color.alpha(physical.getPixel(48,48))>200 && Color.alpha(physical.getPixel(30,30))==0) { "物理像素图标不是四格" }
                check(Color.alpha(filter.getPixel(48,48))>200 && Color.alpha(spotlight.getPixel(48,48))==0) { "滤镜与聚光灯图标未区分" }
                check(ScreenshotToolbarEditor.icon(ScreenshotAction.LINE)==R.drawable.snow_line &&
                    ScreenshotToolbarEditor.icon(ScreenshotAction.WATERMARK)==R.drawable.snow_watermark) { "直线或水印图标绑定错误" }
            } finally { logical.recycle(); physical.recycle(); filter.recycle(); spotlight.recycle() }
        }
        check(failures.isEmpty()) { failures.joinToString("; ") }
    }
}
