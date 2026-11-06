package local.voxfix.debug

import android.content.Context
import android.graphics.*
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/**
 * T16 text contract: `screenshotdefaultstyles.h` defaults, the original per-line background pill
 * (`snow_canvas_text_render.cpp:drawBackground`) and the 6–256 double font size.
 */
internal object ScreenshotTextChecks {
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
    /** The bar is rebuilt by the host after every style change; a test has to do that itself. */
    private fun chip(controls: ScreenshotDrawingControls,label: String): View {
        controls.refresh()
        return find(controls,label) ?: error("缺少控件：$label")
    }
    fun run(context: Context) {
        val image=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val renderer=ScreenshotDrawingRenderer(image) { 1f }
        val memory=ScreenshotToolMemory(context)
        val layer=ScreenshotDrawingLayer(context,image,memory) {}
        val defaults=defaultScreenshotDrawingStyle(ScreenshotDrawingTool.TEXT)
        val red=Color.RED
        fun text(style: ScreenshotDrawingStyle=defaults,value: String="WWWW\nW")=ScreenshotDrawingStroke(ScreenshotDrawingTool.TEXT,
            listOf(ScreenshotDrawingPoint(20f,20f)),style.color,2f,
            style.copy(hasFill=true,fillPattern=ScreenshotFillPattern.SOLID),text=value,textWidth=360f)
        fun render(stroke: ScreenshotDrawingStroke)=image.copy(Bitmap.Config.ARGB_8888,true).also {
            renderer.draw(Canvas(it),listOf(stroke),Rect(0,0,400,300))
        }
        try {
            // screenshotdefaultstyles.h text block.
            check(defaults.fontSize==30f && defaults.color==0xFFF5222D.toInt()) { "文字默认字号或颜色不符" }
            check(defaults.outlineColor==0xFFFFCCC7.toInt() && defaults.outlineWidth==0)
            check(defaults.cornerRadius==6 && defaults.align==0 && defaults.verticalAlign==1)
            check(!defaults.hasFill && (defaults.fillColor ushr 24)==0) { "文字默认填充应为透明" }

            // One pill per laid-out line with 0.32/0.1 line-height padding, not one full-width box.
            val stroke=text(style=defaults.copy(fillColor=red))
            val box=renderer.bounds(stroke)
            val y1=(box.top+box.height()/4).toInt(); val y2=(box.top+box.height()*3/4).toInt()
            val painted=render(stroke)
            try {
                check(painted.getPixel((box.left-6).toInt(),y1)==red && painted.getPixel((box.left-6).toInt(),y2)==red) { "逐行背景缺少外扩内边距" }
                check(painted.getPixel((box.right-20).toInt(),y1)==Color.WHITE) { "首行背景仍按整框铺满" }
                check(painted.getPixel((box.right-20).toInt(),y2)==Color.WHITE) { "次行背景仍按整框铺满" }
                check(painted.getPixel(20,box.bottom.toInt()+20)==Color.WHITE) { "文字背景越出文字范围" }
            } finally { painted.recycle() }

            // Long translated text with no manual newlines wraps at the box width: every laid-out
            // line gets its own pill, every line is actually painted (no truncation), the precise
            // hit follows the laid-out lines, and the document round-trip keeps the text verbatim.
            val longText="这是一段没有任何手动换行的长译文，依靠文字框宽度自动折行，第二句继续写下去以便折出四行以上，末尾保留句号。"
            val longStyle=defaults.copy(hasFill=true,fillColor=0xFFB7EB8F.toInt())
            val wrapped=text(style=longStyle,value=longText)
            val wbox=renderer.bounds(wrapped)
            val single=renderer.bounds(text(style=longStyle,value="行"))
            val lines=kotlin.math.round(wbox.height()/single.height()).toInt()
            check(lines>=4) { "长译文没有按框宽折行：高 ${wbox.height()}px 只够 $lines 行" }
            val paintedLong=render(wrapped)
            fun rowHasText(y:Int):Boolean {
                for(x in wbox.left.toInt()..wbox.right.toInt())
                    if(paintedLong.getPixel(x,y).let { (it ushr 16 and 0xFF)>200 && (it ushr 8 and 0xFF)<120 }) return true
                return false
            }
            try {
                val lineH=wbox.height()/lines
                for(i in 0 until lines) {
                    val y=(wbox.top+(i+0.5)*lineH).toInt()
                    check(paintedLong.getPixel((wbox.left-6).toInt(),y)==longStyle.fillColor) { "长译文第${i+1}行缺少逐行背景" }
                    check(rowHasText(y)) { "长译文第${i+1}行未画出（疑似截断）" }
                }
                check(paintedLong.getPixel(20,(wbox.bottom+20).toInt())==Color.WHITE) { "长译文背景越出末行范围" }
            } finally { paintedLong.recycle() }
            val unfilled=text(value=longText)
            check(renderer.hit(unfilled,ScreenshotDrawingPoint(wbox.left+wbox.width()-2f,wbox.top+single.height()/2),15f)) { "折行整行右端 outline 未命中" }
            check(renderer.hit(unfilled,ScreenshotDrawingPoint(wbox.centerX(),wbox.centerY()),1f,null,false)) { "折行文字 body 未命中" }
            val decodedLong=ScreenshotDocumentCodec.decode(
                ScreenshotDocumentCodec.encode(ScreenshotDocument.full(400,300).copy(objects=listOf(wrapped.copy(id=9002))))
            ).objects.single()
            check(decodedLong.text==longText) { "工程往返丢失长译文" }
            val wrapEvidence=render(wrapped)
            try {
                java.io.File(context.getExternalFilesDir(null),"snow-t16-text-wrap.png").outputStream()
                    .use { wrapEvidence.compress(Bitmap.CompressFormat.PNG,100,it) }
            } finally { wrapEvidence.recycle() }

            // Doubles survive the document and the per-tool memory; the badge keeps its wider bound.
            val fractional=text(style=defaults.copy(fontSize=24.5f)).copy(id=9001)
            val encoded=ScreenshotDocumentCodec.encode(ScreenshotDocument.full(400,300).copy(objects=listOf(fractional)))
            check(ScreenshotDocumentCodec.decode(encoded).objects.single().style.fontSize==24.5f) { "工程丢失字号小数" }
            val legacy=org.json.JSONObject(encoded)
            legacy.getJSONArray("objects").getJSONObject(0).getJSONObject("style").put("fontSize",30)
            check(ScreenshotDocumentCodec.decode(legacy.toString()).objects.single().style.fontSize==30f) { "旧整数字号无法读取" }
            memory.save(ScreenshotDrawingTool.TEXT,defaults.copy(fontSize=24.5f))
            check(memory.style(ScreenshotDrawingTool.TEXT)?.fontSize==24.5f) { "参数记忆丢失字号小数" }
            memory.save(ScreenshotDrawingTool.SERIAL,defaultScreenshotDrawingStyle(ScreenshotDrawingTool.SERIAL).copy(fontSize=300f))
            check(memory.style(ScreenshotDrawingTool.SERIAL)?.fontSize==300f) { "序号字号上限未放宽到512" }
            memory.save(ScreenshotDrawingTool.TEXT,defaults)
            memory.save(ScreenshotDrawingTool.SERIAL,defaultScreenshotDrawingStyle(ScreenshotDrawingTool.SERIAL))

            // Toolbar: readout, wheel step and the typed editor share the same bounds.
            layer.layout(0,0,400,300); layer.region=Rect(0,0,400,300); layer.savedFontUnit=1f; layer.savedPixelUnit=1f
            layer.tool=ScreenshotDrawingTool.TEXT
            layer.applyStyle(defaults)
            var asked=""
            val controls=ScreenshotDrawingControls(context,layer,{ title,_,save -> if(title=="字号") { asked=title; save("24.5") } },false)
            chip(controls,"字号 30").performClick()
            check(layer.style.fontSize==24.5f && asked=="字号") { "字号输入未接入" }
            scroll(chip(controls,"字号 24.5"),1f); check(layer.style.fontSize==25.5f)
            scroll(chip(controls,"字号 25.5"),-1f); check(layer.style.fontSize==24.5f)
            chip(controls,"字号 42").performClick(); check(layer.style.fontSize==42f)
            // The editor rejects out-of-range input; it must not silently write a clamped value.
            val rejecting=ScreenshotDrawingControls(context,layer,{ title,_,save -> if(title=="字号") save("300") },false)
            chip(rejecting,"字号 42").performClick(); check(layer.style.fontSize==42f) { "文字字号接受了超过256的值" }
            layer.applyStyle(layer.style.copy(fontSize=256f))
            scroll(chip(controls,"字号 256"),1f); check(layer.style.fontSize==256f) { "字号滚轮越过256" }

            // The serial badge reaches 512, the text tool stops at 256.
            layer.tool=ScreenshotDrawingTool.SERIAL
            val serialControls=ScreenshotDrawingControls(context,layer,{ title,_,save -> if(title=="字号") save("300") },false)
            chip(serialControls,"字号 24").performClick(); check(layer.style.fontSize==300f) { "序号字号未接受300" }
            scroll(chip(serialControls,"字号 300"),1f); check(layer.style.fontSize==301f) { "序号字号滚轮未生效" }
            layer.tool=ScreenshotDrawingTool.TEXT

            // Keyboard avoidance: `placeControls` shrinks the safe area to
            // `height - max(bars.bottom, imeBottom) - 8dp`; the input and its 取消/保存 bar must stay
            // inside it, and a displaced input switches to black so it stays readable.
            val host=android.widget.FrameLayout(context)
            host.layout(0,0,1080,900)
            val input=ScreenshotAnnotationInput(host,layer,ScreenshotDrawingPoint(40f,760f),null,
                { RectF(40f,760f,240f,800f) },{ RectF(600f,820f,776f,864f) }) {}
            try {
                input.place(24,560)
                val fieldParams=input.field.layoutParams as android.widget.FrameLayout.LayoutParams
                val actionParams=input.actions.layoutParams as android.widget.FrameLayout.LayoutParams
                check(fieldParams.topMargin>=24) { "文字输入落到了安全区上方" }
                check(fieldParams.topMargin+fieldParams.height<=560) { "文字输入落到了键盘下方" }
                check(actionParams.topMargin+actionParams.height<=560) { "保存条落到了键盘下方" }
                check(input.field.currentTextColor==android.graphics.Color.BLACK) { "位移后的文字输入没有改成黑色" }
                // With the keyboard gone the same box may sit lower again: the IME is what pushed it.
                input.place(24,900)
                val relaxed=input.field.layoutParams as android.widget.FrameLayout.LayoutParams
                check(relaxed.topMargin>fieldParams.topMargin) {
                    "键盘收起后文字输入没有回落：${fieldParams.topMargin} -> ${relaxed.topMargin}"
                }
                check(relaxed.topMargin+relaxed.height<=900) { "文字输入超出安全区" }
            } finally { input.close() }

            // Evidence for the record: three lines with the light-yellow fill and 6px corners.
            val evidence=render(text(style=defaults.copy(hasFill=true,fillColor=0xFFFFF1B8.toInt()),
                value="文字默认\n逐行背景\n短"))
            try {
                java.io.File(context.getExternalFilesDir(null),"snow-t16-text.png").outputStream()
                    .use { evidence.compress(Bitmap.CompressFormat.PNG,100,it) }
            } finally { evidence.recycle() }
        } finally { layer.close(); renderer.close(); image.recycle() }
    }
}
