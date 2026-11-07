package local.voxfix.debug

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises native rendering, object gestures, migration and offline scanning on ARM64. */
object ScreenshotSelectedToolsSmoke {
    fun run(test: Instrumentation): String {
        var error: Throwable?=null
        test.runOnMainSync { try { drawing(test.targetContext); layouts(test.targetContext); share(test.targetContext); pinPresentation(test.targetContext) } catch(e: Throwable) { error=e } }
        error?.let { throw it }
        scan(test,qr(),"VOXFIX_OFFLINE_QR_20261104")
        scan(test,ean(),"5901234123457")
        return "SCREENSHOT_SELECTED_TOOLS_PASS: effects,text,serial,select-move-resize-style,selection-order,selection-align,selection-copy,erase,undo-redo,section-migration,editor-space,share-uri,frameless-pin,offline-QR-EAN13"
    }
    private fun pinPresentation(context: Context) {
        val clazz=Class.forName("local.voxfix.debug.ScreenshotPins\$Pin")
        for(size in listOf(600 to 400,400 to 800)) {
            val image=Bitmap.createBitmap(size.first,size.second,Bitmap.Config.ARGB_8888)
            val pin=clazz.getDeclaredConstructor(Context::class.java,Bitmap::class.java,Rect::class.java).apply { isAccessible=true }.newInstance(context,image,Rect(100,300,100+size.first,300+size.second))
            fun field(name: String)=clazz.getDeclaredField(name).apply { isAccessible=true }.get(pin)
            fun invoke(name: String)=clazz.getDeclaredMethod(name).apply { isAccessible=true }.invoke(pin)
            try {
                clazz.getDeclaredMethod("resize",Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(pin,500)
                val params=field("params") as android.view.WindowManager.LayoutParams
                check(kotlin.math.abs(params.height-params.width.toFloat()*size.second/size.first)<=1) { "pin must not reserve a title bar" }
                val picture=field("picture") as View
                check((picture.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin==0)
                val close=field("closeButton") as View
                check(close.visibility==View.GONE)
                picture.performClick(); check(close.visibility==View.VISIBLE)
                picture.performClick(); check(close.visibility==View.GONE)
                fun set(name: String,value: Int) { clazz.getDeclaredField(name).apply { isAccessible=true }.setInt(pin,value) }
                val initialHeight=(500f*size.second/size.first).toInt()
                for(edges in listOf(1,2,4,8,5,6,9,10)) {
                    set("startX",100); set("startY",300); set("startWidth",500); set("startHeight",initialHeight); set("edges",edges)
                    clazz.getDeclaredMethod("resizeFromDrag",Float::class.javaPrimitiveType,Float::class.javaPrimitiveType).apply { isAccessible=true }.invoke(pin,40f,60f)
                    check(kotlin.math.abs(params.height-params.width.toFloat()*size.second/size.first)<=1)
                    check(if(edges and 1!=0) params.x+params.width==600 else params.x==100) { "wrong horizontal anchor: $edges" }
                    check(if(edges and 4!=0) params.y+params.height==300+initialHeight else params.y==300) { "wrong vertical anchor: $edges" }
                }
                set("edges",0)
                clazz.getDeclaredMethod("resize",Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(pin,500)
                params.x=250; params.y=300
                val root=field("root") as View
                root.measure(View.MeasureSpec.makeMeasureSpec(params.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(params.height,View.MeasureSpec.EXACTLY))
                root.layout(0,0,params.width,params.height)
                val time=android.os.SystemClock.uptimeMillis(); var step=0
                fun pointers(action: Int,xs: List<Float>) {
                    val props=Array(xs.size) { index -> MotionEvent.PointerProperties().apply { id=index; toolType=MotionEvent.TOOL_TYPE_FINGER } }
                    val coords=Array(xs.size) { index -> MotionEvent.PointerCoords().apply { x=xs[index]; y=100f; pressure=1f; this.size=1f } }
                    val event=MotionEvent.obtain(time,time+step++*20,action,xs.size,props,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
                    try { picture.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                pointers(MotionEvent.ACTION_DOWN,listOf(150f))
                pointers(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(150f,350f))
                val configuration=android.view.ViewConfiguration.get(context)
                val threshold=maxOf(240f,configuration.scaledMinimumScalingSpan+configuration.scaledTouchSlop*2f)
                for(span in listOf(threshold,threshold+100,threshold+200)) {
                    val focus=500f-params.x
                    pointers(MotionEvent.ACTION_MOVE,listOf(focus-span/2,focus+span/2))
                }
                check(params.width>500) {
                    val detector=field("scale") as android.view.ScaleGestureDetector
                    "pinch did not enlarge the pin: minimum=${configuration.scaledMinimumScalingSpan}, span=${detector.currentSpan}, active=${detector.isInProgress}"
                }
                check(kotlin.math.abs(params.x+params.width/2f-500f)<=2) { "pinch focal point drifted" }
                val beforeX=params.x; val beforeWidth=params.width
                val span=threshold+200
                val remaining=500f-params.x-span/2
                pointers(MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(remaining,remaining+span))
                pointers(MotionEvent.ACTION_MOVE,listOf(remaining+10))
                pointers(MotionEvent.ACTION_UP,listOf(remaining+10))
                check(params.width==beforeWidth && params.x==beforeX+10) { "pinch to single-finger handoff jumped" }
            } finally { invoke("close"); check(image.isRecycled) }
        }
    }
    private fun drawing(context: Context) {
        val source=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888)
        // 彩色条纹底：滤镜断言横跨 130..270 取一行像素，纯黑白底会让灰度在黑色行上「没有变化」，
        // 那验证的是底色而不是滤镜。高亮用 MULTIPLY 混合（黄×橙仍是橙），所以两色都必须带非零蓝分量。
        val pixels=IntArray(600*900) { index ->
            if((index%600/3)%2==0) Color.rgb(30,144,255) else Color.rgb(200,40,200)
        }
        source.setPixels(pixels,0,600,0,0,600,900)
        val layer=ScreenshotDrawingLayer(context,source) {}
        layer.layout(0,0,600,900); layer.region=Rect(0,0,600,900)
        fun touch(action: Int,x: Float,y: Float,meta: Int=0) {
            val event=MotionEvent.obtain(1,2,action,x,y,meta)
            try { check(layer.onTouchEvent(event)) } finally { event.recycle() }
        }
        fun drag(x: Float,y: Float,ex: Float,ey: Float) {
            touch(0,x,y); touch(2,ex,ey); touch(1,ex,ey)
        }
        try {
            layer.tool=ScreenshotDrawingTool.RECTANGLE; drag(80f,100f,240f,260f)
            layer.tool=ScreenshotDrawingTool.SELECT; drag(80f,180f,130f,230f)
            check(layer.selected?.points?.first()==ScreenshotDrawingPoint(130f,150f)) { "selection move failed" }
            val before=layer.selected!!
            val renderer=ScreenshotDrawingRenderer(source) { context.resources.displayMetrics.scaledDensity }
            val box=renderer.bounds(before)
            drag(box.right,box.bottom,box.right+60,box.bottom+60)
            check(layer.selected!!.points.last().x>before.points.last().x+40) { "resize handle failed" }
            layer.applyStyle(layer.inspectedStyle.copy(color=Color.BLUE))
            check(layer.selected!!.color==Color.BLUE)
            layer.undo(); check(layer.history.items.single().color!=Color.BLUE)
            layer.redo(); check(layer.history.items.single().color==Color.BLUE)
            val erasedBox=renderer.bounds(layer.history.items.single())
            layer.tool=ScreenshotDrawingTool.ERASER; drag(erasedBox.left,erasedBox.centerY(),erasedBox.left+5,erasedBox.centerY()+5)
            check(layer.history.items.isEmpty()); layer.undo(); check(layer.history.items.size==1)
            layer.history.remove(setOf(0))
            layer.tool=ScreenshotDrawingTool.PEN; drag(300f,200f,300f,300f)
            layer.tool=ScreenshotDrawingTool.ERASER; drag(100f,250f,500f,250f)
            check(layer.history.items.isEmpty()) { "fast eraser must sample the entire gesture segment" }
            layer.undo(); check(layer.history.items.size==1); layer.history.remove(setOf(0))
            layer.tool=ScreenshotDrawingTool.TEXT
            var requested=false
            layer.onTextRequest={point,index -> requested=true; layer.addText(point,"文字标注\n换行",index) }
            drag(100f,300f,100f,300f)
            check(requested && layer.history.items.single().text.contains("换行"))
            layer.tool=ScreenshotDrawingTool.SERIAL; drag(200f,400f,200f,400f); drag(300f,400f,300f,400f)
            check(layer.history.items.takeLast(2).map { it.number }==listOf(1L,2L))
            for(shape in ScreenshotSerialShape.values()) {
                layer.applyStyle(layer.inspectedStyle.copy(serialShape=shape)); drag(400f,400f,400f,400f)
                val output=layer.render(layer.region!!,null)!!; check(!output.isRecycled); output.recycle()
            }
            layer.history.remove(layer.history.items.indices.toSet())
            layer.tool=ScreenshotDrawingTool.HIGHLIGHT
            layer.applyStyle(layer.inspectedStyle.copy(area=ScreenshotAreaMode.RECTANGLE,color=Color.YELLOW,opacity=.5f))
            drag(50f,50f,250f,250f)
            var output=layer.render(layer.region!!,null)!!
            check(output.getPixel(100,100)!=source.getPixel(100,100)) {
                "highlight did not tint inside: ${Integer.toHexString(output.getPixel(100,100))} vs ${Integer.toHexString(source.getPixel(100,100))}"
            }
            check(output.getPixel(500,500)==source.getPixel(500,500)) {
                "highlight leaked outside: ${Integer.toHexString(output.getPixel(500,500))} vs ${Integer.toHexString(source.getPixel(500,500))}"
            }; output.recycle()
            layer.history.remove(layer.history.items.indices.toSet())
            layer.tool=ScreenshotDrawingTool.SPOTLIGHT; drag(100f,100f,300f,300f)
            output=layer.render(layer.region!!,null)!!
            check(output.getPixel(100,200)==source.getPixel(100,200)) { "focus area darkened" }
            check(output.getPixel(400,500)!=source.getPixel(400,500)) { "outside focus is not darkened" }; output.recycle()
            layer.history.remove(layer.history.items.indices.toSet())
            layer.tool=ScreenshotDrawingTool.FILTER
            for(filter in ScreenshotFilter.values()) {
                layer.applyStyle(layer.inspectedStyle.copy(filter=filter,area=ScreenshotAreaMode.RECTANGLE,strength=0.27f))
                drag(100f,100f,300f,300f); output=layer.render(layer.region!!,null)!!
                var changed=0
                for(x in 130..270) if(output.getPixel(x,200)!=source.getPixel(x,200)) changed++
                check(changed>40) { "$filter did not filter actual pixels: $changed (strength=${layer.style.strength}, area=${layer.style.area}, objects=${layer.history.items.size}, filter=${layer.style.filter})" }
                check(output.getPixel(500,500)==source.getPixel(500,500)); output.recycle(); layer.undo()
            }
            check(source.getPixel(100,100)==pixels[60100]) { "source screenshot changed" }
            layer.history.remove(layer.history.items.indices.toSet())
            layer.tool=ScreenshotDrawingTool.PEN; layer.applyStyle(layer.inspectedStyle.copy(color=Color.RED,width=12))
            drag(150f,500f,150f,500f); output=layer.render(layer.region!!,null)!!
            check(output.getPixel(150,500)==Color.RED) { "pen tap must create a dot" }; output.recycle()
            layer.undo(); layer.tool=ScreenshotDrawingTool.FILTER
            layer.applyStyle(layer.inspectedStyle.copy(area=ScreenshotAreaMode.PEN,width=24,filter=ScreenshotFilter.BLUR))
            drag(150f,500f,150f,500f); output=layer.render(layer.region!!,null)!!
            check(output.getPixel(150,500)!=source.getPixel(150,500)) { "filter tap must redact pixels" }; output.recycle()
            val points = listOf(ScreenshotDrawingPoint(320f,500f),ScreenshotDrawingPoint(470f,660f))
            val bottom = before.copy(points=points, color=Color.RED,
                style=before.style.copy(fillColor=Color.RED, hasFill=true))
            val top = bottom.copy(id=0, color=Color.BLUE,style=bottom.style.copy(fillColor=Color.BLUE))
            layer.history.load(listOf(bottom,top))
            layer.tool=ScreenshotDrawingTool.SELECT
            fun actions(): ViewGroup {
                val panel = ScreenshotDrawingControls(context,layer,{ _,_,_ -> })
                    .getChildAt(0) as ScreenshotSelectionActionControls
                return panel.getChildAt(0) as ViewGroup
            }
            val disabled = actions()
            // `updateSelectionActionAvailability`: everything but Reset (and the draw template) is
            // disabled while nothing is selected.
            check((0 until disabled.childCount).none {
                val child=disabled.getChildAt(it)
                child.isClickable && child.isEnabled && child.contentDescription!="重置画布"
            })
            drag(320f,570f,320f,570f)
            val topId = layer.history.items.last().id
            check(layer.selected?.id==topId)
            val order = actions()
            check(order.getChildAt(0).performClick())
            check(layer.history.items.first().id==topId && layer.selected?.id==topId)
            output=layer.render(layer.region!!,null)!!
            check(output.getPixel(400,580)==Color.RED); output.recycle()
            layer.undo(); check(layer.history.items.last().id==topId && !layer.history.canUndo)
            layer.redo(); check(layer.history.items.first().id==topId)
            drag(320f,570f,320f,570f)
            val original = layer.selected!!
            val copy = actions().let { panel -> (0 until panel.childCount).map(panel::getChildAt)
                .single { it.contentDescription == "复制选中对象" } }
            check(copy.contentDescription == "复制选中对象" && copy.performClick())
            check(layer.selected?.id != original.id && layer.selected?.points?.first() == ScreenshotDrawingPoint(332f,512f))
            layer.undo(); check(layer.history.items.size == 2)
            val left = top.copy(points=listOf(ScreenshotDrawingPoint(100f,300f),ScreenshotDrawingPoint(240f,440f)))
            layer.history.load(listOf(bottom,left))
            drag(320f,570f,320f,570f)
            touch(MotionEvent.ACTION_DOWN,100f,370f,android.view.KeyEvent.META_SHIFT_ON)
            touch(MotionEvent.ACTION_UP,100f,370f,android.view.KeyEvent.META_SHIFT_ON)
            check(layer.selectedIndices.size==2)
            val alignment = actions()
            val alignLeft = (0 until alignment.childCount).map(alignment::getChildAt).single { it.contentDescription == "左对齐" }
            check(alignLeft.isEnabled && alignLeft.performClick())
            check(layer.history.items.all { it.points.first().x==100f } && layer.selectedIndices.size==2)
            layer.undo(); check(layer.history.items.first().points == bottom.points && !layer.history.canUndo)
            renderer.close()
        } finally { layer.close(); source.recycle() }
    }
    private fun layouts(base: Context) {
        val name="screenshot-selected-test"
        val context=object : ContextWrapper(base) {
            override fun getSharedPreferences(key: String,mode: Int)=base.getSharedPreferences(name,mode)
        }
        val prefs=base.getSharedPreferences(name,Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val legacy=ScreenshotToolbarLayout.defaults().move(ScreenshotAction.SHARE,ScreenshotAction.FULL_SCREEN).hide(ScreenshotAction.TEXT).hide(ScreenshotAction.PIN)
            prefs.edit().putString(ScreenshotToolbarEditor.KEY,legacy.encode()).commit()
            val drawing=ScreenshotToolbarPreferences.load(context,ScreenshotToolbarSection.DRAWING)
            val actions=ScreenshotToolbarPreferences.load(context,ScreenshotToolbarSection.ACTIONS)
            check(ScreenshotAction.TEXT in drawing.hidden && ScreenshotAction.PIN in actions.hidden)
            val reordered=drawing.move(ScreenshotAction.ERASER,ScreenshotAction.SELECT)
            ScreenshotToolbarPreferences.save(context,ScreenshotToolbarSection.DRAWING,reordered)
            check(!prefs.contains(ScreenshotToolbarEditor.KEY))
            check(ScreenshotToolbarPreferences.load(context,ScreenshotToolbarSection.ACTIONS)==actions)
            ScreenshotToolbarPreferences.save(context,ScreenshotToolbarSection.ACTIONS,actions.hide(ScreenshotAction.SHARE))
            check(ScreenshotToolbarPreferences.load(context,ScreenshotToolbarSection.DRAWING)==reordered)
            for(section in ScreenshotToolbarSection.values()) for(width in listOf(320,360,411,640)) {
                val editor=ScreenshotToolbarEditor(context,section)
                val density=base.resources.displayMetrics.density
                val w=(width*density).toInt(); val h=(650*density).toInt()
                editor.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY)); editor.layout(0,0,w,h)
                val all=mutableListOf<View>()
                fun visit(view: View) { all.add(view); if(view is ViewGroup) for(i in 0 until view.childCount) visit(view.getChildAt(i)) }
                visit(editor)
                val icons=all.filter { it.tag is ScreenshotAction }
                check(icons.isNotEmpty())
                check(icons.all { it.left>=0 && it.right<=w }) { "$section editor icon overflow at $width" }
                // The main row begins near the top instead of anchoring to the bottom of an empty canvas.
                val positions=icons.map { view -> var y=view.top; var p=view.parent; while(p is View && p!==editor) { y+=p.top; p=p.parent }; y }
                check(positions.min()<220*density) { "$section unused upper workspace" }
            }
        } finally { prefs.edit().clear().commit() }
    }
    private fun share(context: Context) {
        val bitmap=Bitmap.createBitmap(50,40,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val uri=try { ScreenshotShare.write(context,bitmap) } finally { bitmap.recycle() }
        val resolver=context.contentResolver
        try {
            check(resolver.getType(uri)=="image/png")
            resolver.openInputStream(uri)!!.use { val image=BitmapFactory.decodeStream(it); check(image.width==50 && image.height==40); image.recycle() }
            val intent=ScreenshotShare.intent(context,uri)
            check(intent.action==Intent.ACTION_SEND && intent.clipData!!.getItemAt(0).uri==uri)
            check(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0 && intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION==0)
            check(runCatching { resolver.openFileDescriptor(uri,"w")?.close() }.isFailure)
            check(runCatching { resolver.openFileDescriptor(Uri.parse("content://${context.packageName}.screenshots/exports/..%2Fprivate.png"),"r")?.close() }.isFailure)
        } finally { java.io.File(context.cacheDir,"screenshot-exports/${uri.lastPathSegment}").delete() }
    }
    private fun scan(test: Instrumentation,bitmap: Bitmap,expected: String) {
        val done=CountDownLatch(1); var values=listOf<String>(); var error=""
        test.runOnMainSync { ScreenshotBarcode.scanText(bitmap,{ values=it; done.countDown() },{ error=it; done.countDown() }) }
        check(done.await(20,TimeUnit.SECONDS)) { "offline scanner timed out" }
        check(expected in values) { "barcode $expected not recognized: $values $error" }
    }
    private fun qr(): Bitmap {
        val matrix=listOf(
            "1111111000100111101111111","1000001000111111001000001","1011101010011010001011101","1011101011011101101011101","1011101011101011101011101",
            "1000001010101101101000001","1111111010101010101111111","0000000011111001000000000","1011111000001101101111100","1011000100000011101101111",
            "1001011100110001111011011","0111110011011000100100010","1101111010101100111001010","1000110000101011101100001","1001111010101101110111111",
            "1010100111000001101110000","1011001100100101111111011","0000000011111011100011111","1111111000101111101010011","1000001010101000100010000",
            "1011101010000101111110000","1011101010111011111001111","1011101010100000101101001","1000001001101011000110001","1111111010100100011110011")
        return Bitmap.createBitmap(264,264,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE); val canvas=Canvas(this); val paint=Paint().apply { color=Color.BLACK }
            matrix.forEachIndexed { y,line -> line.forEachIndexed { x,char -> if(char=='1') canvas.drawRect((x+4)*8f,(y+4)*8f,(x+5)*8f,(y+5)*8f,paint) } }
        }
    }
    private fun ean(): Bitmap {
        val codes=listOf("0001101","0011001","0010011","0111101","0100011","0110001","0101111","0111011","0110111","0001011")
        fun invert(code: String)=code.map { if(it=='0') '1' else '0' }.joinToString("")
        val digits="5901234123457"; val parity="LGGLLG"
        val bars="101"+(1..6).joinToString("") { i -> codes[digits[i]-'0'].let { if(parity[i-1]=='G') invert(it).reversed() else it } }+"01010"+
            (7..12).joinToString("") { invert(codes[digits[it]-'0']) }+"101"
        return Bitmap.createBitmap(480,200,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE); val canvas=Canvas(this); val paint=Paint().apply { color=Color.BLACK }
            bars.forEachIndexed { x,char -> if(char=='1') canvas.drawRect(40+x*4f,20f,44+x*4f,180f,paint) }
        }
    }
}
