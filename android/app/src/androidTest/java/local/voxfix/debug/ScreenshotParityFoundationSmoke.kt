package local.voxfix.debug

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.*
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.sin

/** C00/T01/T08 source-contract checks; all pictures/preferences belong to this test. */
object ScreenshotParityFoundationSmoke {
    private class Translator : ScreenshotTranslator {
        var cancelled=0
        override fun translate(text: String,pair: TranslatePair,onResult:(String)->Unit,onFailure:(String)->Unit,onUpdate:(String)->Unit) {}
        override fun cancel() { cancelled++ }
        override fun available()=true
    }
    private fun field(target: Any,name: String): Any?=target.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(target)
    private fun find(view: View,label: String): View? {
        if(view.contentDescription==label) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),label)?.let { return it }
        return null
    }
    /** Option cells keep a "起点"/"终点" + label description; the trigger reads "…箭头样式". */
    private fun options(view: View): Int {
        var count=0
        if(view is ViewGroup) for(i in 0 until view.childCount) count+=options(view.getChildAt(i))
        val label=view.contentDescription?.toString()
        if(label!=null && !label.endsWith("箭头样式") && (label.startsWith("起点") || label.startsWith("终点"))) count++
        return count
    }
    private fun layout(view: View,width: Int=1080,height: Int=2400) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height)
    }
    fun run(test: Instrumentation): String {
        var failure: Throwable?=null
        test.runOnMainSync {
            val context=object : ContextWrapper(test.targetContext) {
                override fun getSharedPreferences(name: String,mode: Int)=super.getSharedPreferences("parity-foundation-$name",mode)
            }
            val prefs=context.getSharedPreferences("screenshot-tool-memory",Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
            try { cancel(context); pen(context); geometry(context); shapes(context); lines(context); arrows(context); eraser(context); highlights(context); ScreenshotSpotlightChecks.run(context); ScreenshotHatchChecks.run(); ScreenshotSerialChecks.run(context); ScreenshotSerialLabelChecks.run(context); ScreenshotCornerChecks.run(context); ScreenshotTextChecks.run(context); ScreenshotWatermarkChecks.run(context); ScreenshotSelectionBarChecks.run(context); ScreenshotRegionChecks.run(context); ScreenshotObjectSelectionChecks.run(context); ScreenshotPenFeedbackChecks.run(context) } catch(error: Throwable) { failure=error }
            finally { prefs.edit().clear().commit() }
        }
        failure?.let { throw it }
        return "SCREENSHOT_PARITY_FOUNDATION_PASS: cancel-states,idempotence,pen-styles,export,history,legacy,document,memory,layout,geometry,shape-kinds,shape-fill,shape-stroke,shape-hit,shape-document,shape-ui,line-straight,line-curve,line-hit,line-document,line-ui,arrow-heads,arrow-outline,arrow-hit,arrow-document,arrow-ui,arrow-shaft,arrow-ratio,arrow-gesture,eraser-sweep,eraser-preview,eraser-reach,eraser-undo,highlight-default,highlight-render,highlight-alpha,highlight-ui,spotlight-default,spotlight-alpha,spotlight-union,spotlight-global,spotlight-history,spotlight-document,spotlight-ui,hatch-4x,hatch-origin,hatch-alpha,hatch-cleanup,serial-geometry,serial-circle,serial-contrast,serial-hit,serial-legacy,serial-document,serial-ui,serial-label-create,serial-label-preview,serial-label-history,serial-label-document,serial-label-cancel,serial-label-move,font-search,font-list,serial-int64,serial-wheel,serial-disabled,text-default,text-line-background,text-size,text-document,watermark-default,watermark-tile,watermark-opacity,watermark-gap,watermark-layer,watermark-ui,watermark-document,selection-units,selection-unit-toggle,region-model,region-mask,region-gesture,object-marquee,object-group-move,object-group-rotate,object-cancel,object-rotate-export,object-rotate-document,object-linear-points,pen-continue,pen-touch-feedback"
    }
    private fun cancel(context: Context) {
        for(mode in listOf("empty","drawing","busy","editing","translating")) {
            val image=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888)
            val translator=Translator(); var exits=0; var saves=0
            val overlay=ScreenshotCaptureOverlay(context,image,{ saves++ },{},{},{ exits++ },{},translator)
            try {
                val root=field(overlay,"root") as ViewGroup
                if(mode!="empty") (field(overlay,"selector") as ScreenTextRegionView).selectRegion(Rect(30,30,900,700))
                if(mode=="drawing") (field(overlay,"drawing") as ScreenshotDrawingLayer).tool=ScreenshotDrawingTool.PEN
                if(mode=="busy") overlay.javaClass.getDeclaredField("busy").apply { isAccessible=true }.setBoolean(overlay,true)
                if(mode in listOf("editing","translating")) overlay.javaClass.getDeclaredField(mode).apply { isAccessible=true }.setBoolean(overlay,true)
                overlay.javaClass.getDeclaredMethod("sync").apply { isAccessible=true }.invoke(overlay)
                layout(root)
                val close=find(root,"取消截图") as ImageView
                check(close.isEnabled && close.visibility==View.VISIBLE) { "关闭不可达：$mode" }
                val density=context.resources.displayMetrics.density
                check(close.width==(32*density).toInt() && close.height==(32*density).toInt())
                check(close.paddingLeft==(4*density).toInt()) { "关闭图标不是24dp" }
                close.performClick(); close.performClick()
                check(exits==1 && translator.cancelled==1 && saves==0) { "关闭重复执行或触发保存：$mode" }
                find(root,"保存截图")?.performClick(); check(saves==0)
            } finally { overlay.dismiss(context.getSystemService(WindowManager::class.java)); image.recycle() }
        }
    }
    private fun pen(context: Context) {
        val image=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val memory=ScreenshotToolMemory(context)
        val drawing=ScreenshotDrawingLayer(context,image,memory) {}
        try {
            drawing.region=Rect(0,0,320,320); drawing.layout(0,0,320,320); drawing.tool=ScreenshotDrawingTool.PEN
            val style=ScreenshotDrawingStyle(color=Color.RED,width=4,strokePattern=ScreenshotStrokePattern.DASHED,
                hasFill=true,fillColor=Color.YELLOW,fillPattern=ScreenshotFillPattern.CROSS_LINE)
            drawing.applyStyle(style)
            check(ScreenshotToolMemory(context).style(ScreenshotDrawingTool.PEN)==style) { "画笔样式未记忆" }
            check(memory.style(ScreenshotDrawingTool.RECTANGLE)==null) { "污染矩形参数" }
            val stroke=ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN,listOf(ScreenshotDrawingPoint(30f,40f),ScreenshotDrawingPoint(290f,40f)),Color.RED,4f,style,id=8801)
            val renderer=ScreenshotDrawingRenderer(image) { 1f }
            fun render(value: ScreenshotDrawingStroke): Bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE); renderer.draw(Canvas(it),listOf(value),Rect(0,0,320,320))
            }
            try {
                val dash=render(stroke)
                check((35..285).count { dash.getPixel(it,40)==Color.RED }>40)
                check((35..285).count { dash.getPixel(it,40)==Color.WHITE }>20) { "虚线没有间隔" }; dash.recycle()
                val dot=render(stroke.copy(style=style.copy(strokePattern=ScreenshotStrokePattern.DOTTED)))
                check((35..285).any { dot.getPixel(it,40)==Color.RED } && (35..285).any { dot.getPixel(it,40)==Color.WHITE }); dot.recycle()
                val closed=stroke.copy(points=listOf(ScreenshotDrawingPoint(40f,80f),ScreenshotDrawingPoint(280f,80f),ScreenshotDrawingPoint(280f,280f),ScreenshotDrawingPoint(40f,280f),ScreenshotDrawingPoint(40f,80f)))
                for(pattern in ScreenshotFillPattern.values()) {
                    val result=render(closed.copy(style=style.copy(fillPattern=pattern)))
                    val interior=(100..200).flatMap { y -> (100..200).map { x -> result.getPixel(x,y) } }
                    check(interior.any { it==Color.YELLOW }) { "填充无效 $pattern" }
                    if(pattern!=ScreenshotFillPattern.SOLID) check(interior.any { it==Color.WHITE })
                    check(result.getPixel(10,150)==Color.WHITE) { "填充越界" }; result.recycle()
                }
                check(renderer.hit(closed,ScreenshotDrawingPoint(160f,160f),2f)) { "填充内部不能选中" }
                val noStroke=render(stroke.copy(color=Color.TRANSPARENT)); check(noStroke.getPixel(40,40)==Color.WHITE); noStroke.recycle()
            } finally { renderer.close() }
            drawing.history.add(stroke)
            val document=ScreenshotDocument.full(320,320).copy(objects=drawing.history.items)
            val encoded=ScreenshotDocumentCodec.encode(document)
            check(ScreenshotDocumentCodec.decode(encoded)==document) { "工程丢失画笔样式" }
            val old=JSONObject(encoded); val oldStyle=old.getJSONArray("objects").getJSONObject(0).getJSONObject("style")
            oldStyle.remove("strokePattern"); oldStyle.remove("fillPattern")
            val legacy=ScreenshotDocumentCodec.decode(old.toString()).objects.single().style
            check(legacy.strokePattern==ScreenshotStrokePattern.SOLID && legacy.fillPattern==ScreenshotFillPattern.SOLID)
            drawing.undo(); check(drawing.history.items.isEmpty()); drawing.redo(); check(drawing.history.items.single()==stroke)
            val controls=ScreenshotPenControls(context,drawing)
            layout(controls,840,360)
            val snapshot=Bitmap.createBitmap(840,360,Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF1C1C1E.toInt()) }
            controls.draw(Canvas(snapshot))
            java.io.File(context.cacheDir,"screenshot-parity-pen-controls.png").outputStream().use { snapshot.compress(Bitmap.CompressFormat.PNG,100,it) }
            snapshot.recycle()
            check(find(controls,"线宽 8")!!.width==(28*context.resources.displayMetrics.density).toInt()) { "画笔控件尺寸偏离28dp" }
            find(controls,"线宽 8")!!.performClick(); check(drawing.style.width==8)
            find(controls,"无填充")!!.performClick(); check(!drawing.style.hasFill)
            find(controls,"交叉线填充")!!.performClick(); check(drawing.style.fillPattern==ScreenshotFillPattern.CROSS_LINE)
            drawing.tool=ScreenshotDrawingTool.RECTANGLE; check(drawing.style.strokePattern==ScreenshotStrokePattern.SOLID)
        } finally { drawing.close(); image.recycle() }
    }
    /**
     * T08 geometry: a pen stroke records stabilized vertices plus its own closure decision, so the
     * same points may only render as a curve when the marker says so, closure must survive a save,
     * and a document written before the port must keep the legacy polyline.
     */
    private fun geometry(context: Context) {
        val image=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val renderer=ScreenshotDrawingRenderer(image) { 1f }
        fun render(stroke: ScreenshotDrawingStroke): Bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE); renderer.draw(Canvas(it),listOf(stroke),Rect(0,0,320,320))
        }
        fun built(path: List<Pair<Float,Float>>,width: Float): PenStrokeBuilder.Result {
            val builder=PenStrokeBuilder(ScreenshotDrawingPoint(path.first().first,path.first().second),width)
            path.drop(1).forEach { builder.append(ScreenshotDrawingPoint(it.first,it.second)) }
            return builder.finish()
        }
        try {
            val bend=built(listOf(40f to 280f,100f to 120f,160f to 100f,220f to 120f,280f to 280f),6f)
            check(!bend.closed) { "开口笔画被误判闭合" }
            check(bend.points.last()==ScreenshotDrawingPoint(280f,280f)) { "笔画终点没有落在抬手位置：${bend.points.last()}" }
            val smooth=ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN,bend.points,Color.RED,6f,
                ScreenshotDrawingStyle(color=Color.RED,width=6),smooth=true)
            val polyline=smooth.copy(smooth=false)
            val curved=render(smooth); val straight=render(polyline)
            var differing=0
            for(y in 0 until 320) for(x in 0 until 320) if(curved.getPixel(x,y)!=straight.getPixel(x,y)) differing++
            check(differing>30) { "平滑几何没有生效，差异像素 $differing" }
            val straight2=straight
            // Before/after on the same hand-like input: raw pointer samples versus the pipeline.
            val raw=(0..120).map { step ->
                val x=40f+step*2f
                val curve=240f-140f*sin(step/120f*PI).toFloat()
                ScreenshotDrawingPoint(x,curve+(if(step%2==0) 0.9f else -0.9f))
            }
            val rawStroke=ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN,raw,Color.RED,6f,ScreenshotDrawingStyle(color=Color.RED,width=6))
            val jitterBuilder=PenStrokeBuilder(raw.first(),6f)
            raw.drop(1).forEach { jitterBuilder.append(it) }
            val jitter=jitterBuilder.finish()
            check(jitter.points.size<raw.size) { "稳定后的取样没有减少：${jitter.points.size}/${raw.size}" }
            val piped=ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN,jitter.points,Color.RED,6f,
                ScreenshotDrawingStyle(color=Color.RED,width=6),smooth=true)
            val sheet=Bitmap.createBitmap(640,300,Bitmap.Config.ARGB_8888)
            val sheetCanvas=Canvas(sheet).apply { drawColor(Color.WHITE) }
            val before=render(rawStroke); val after=render(piped)
            sheetCanvas.drawBitmap(before,0f,0f,null); sheetCanvas.drawBitmap(after,320f,0f,null)
            val label=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xFF444444.toInt(); textSize=13f }
            sheetCanvas.drawLine(320f,0f,320f,300f,Paint().apply { color=0xFFDDDDDD.toInt() })
            sheetCanvas.drawText("raw pointer polyline",10f,18f,label)
            sheetCanvas.drawText("stabilized + Catmull-Rom",330f,18f,label)
            java.io.File(context.cacheDir,"screenshot-pen-geometry.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG,100,it) }
            sheet.recycle(); before.recycle(); after.recycle()
            curved.recycle(); straight2.recycle()

            val loop=built(listOf(60f to 60f,240f to 60f,240f to 240f,60f to 240f,60f to 64f),4f)
            check(loop.closed) { "回到起点没有闭合" }
            val filled=ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN,loop.points,Color.RED,4f,
                ScreenshotDrawingStyle(color=Color.RED,width=4,hasFill=true,fillColor=Color.YELLOW),
                smooth=true,closedPath=true)
            val filledBitmap=render(filled)
            check(filledBitmap.getPixel(150,150)==Color.YELLOW) { "闭合平滑笔画没有填充" }
            check(filledBitmap.getPixel(10,150)==Color.WHITE) { "填充越界" }
            filledBitmap.recycle()

            val document=ScreenshotDocument.full(320,320).copy(objects=listOf(filled.copy(id=9901)))
            val encoded=ScreenshotDocumentCodec.encode(document)
            check(ScreenshotDocumentCodec.decode(encoded)==document) { "平滑笔画工程往返不一致" }
            val restored=ScreenshotDocumentCodec.decode(encoded).objects.single()
            check(restored.smooth && restored.closedPath) { "工程丢失平滑/闭合标记" }
            val old=JSONObject(encoded); val row=old.getJSONArray("objects").getJSONObject(0)
            row.remove("smooth"); row.remove("closedPath")
            val legacy=ScreenshotDocumentCodec.decode(old.toString()).objects.single()
            check(!legacy.smooth && !legacy.closedPath) { "旧工程被误读为平滑笔画" }
        } finally { renderer.close(); image.recycle() }
    }

    /**
     * T09: the shape tool is a three-way kind radio (Rectangle/Ellipse/Diamond) with its own fill and
     * stroke, exactly as `SnowCanvasRectangleShape` and the Shape style row define it. Every kind is
     * inscribed in the same drag box, so only the covered pixels may differ.
     */
    private fun shapes(context: Context) {
        val image=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888)
        val renderer=ScreenshotDrawingRenderer(image) { 1f }
        fun render(value: ScreenshotDrawingStroke): Bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE); renderer.draw(Canvas(it),listOf(value),Rect(0,0,320,320))
        }
        fun shape(kind: ScreenshotRectangleShape,style: ScreenshotDrawingStyle)=ScreenshotDrawingStroke(
            ScreenshotDrawingTool.RECTANGLE,listOf(ScreenshotDrawingPoint(60f,60f),ScreenshotDrawingPoint(260f,260f)),
            style.color,style.width.toFloat(),style.copy(shape=kind))
        try {
            val filled=ScreenshotDrawingStyle(color=Color.TRANSPARENT,width=4,hasFill=true,fillColor=Color.YELLOW)
            for(kind in ScreenshotRectangleShape.values()) {
                val bitmap=render(shape(kind,filled))
                check(bitmap.getPixel(160,160)==Color.YELLOW) { "$kind 内部没有填充" }
                check(bitmap.getPixel(20,20)==Color.WHITE) { "$kind 填充越界" }
                // The drag box corner separates the three kinds: only the rectangle covers it.
                val corner=bitmap.getPixel(65,65)
                check((corner==Color.YELLOW)==(kind==ScreenshotRectangleShape.RECTANGLE)) { "$kind 的角落像素不对：$corner" }
                bitmap.recycle()
            }
            val dashed=ScreenshotDrawingStyle(color=Color.RED,width=4,strokePattern=ScreenshotStrokePattern.DASHED)
            val dashedBitmap=render(shape(ScreenshotRectangleShape.RECTANGLE,dashed))
            check((60..260).count { dashedBitmap.getPixel(it,60)==Color.RED }>20) { "虚线矩形没有描边" }
            check((60..260).count { dashedBitmap.getPixel(it,60)==Color.WHITE }>20) { "虚线矩形没有间隔" }
            dashedBitmap.recycle()

            val solid=ScreenshotDrawingStyle(color=Color.RED,width=4)
            for(kind in ScreenshotRectangleShape.values()) {
                val value=shape(kind,solid)
                check(renderer.hit(value,ScreenshotDrawingPoint(160f,160f),2f,null,false)) { "$kind 内部不能选中" }
            }
            val ellipse=shape(ScreenshotRectangleShape.ELLIPSE,solid)
            check(!renderer.hit(ellipse,ScreenshotDrawingPoint(65f,65f),1f,null,false)) { "椭圆角外仍被命中" }
            check(renderer.hit(ellipse,ScreenshotDrawingPoint(160f,60f),1f,null,true)) { "椭圆上边线不能命中" }
            val rectangle=shape(ScreenshotRectangleShape.RECTANGLE,solid)
            check(renderer.hit(rectangle,ScreenshotDrawingPoint(65f,65f),1f,null,false)) { "矩形内部不能命中" }
            check(renderer.hit(rectangle,ScreenshotDrawingPoint(60f,160f),1f,null,true)) { "矩形左边线不能命中" }

            val document=ScreenshotDocument.full(320,320).copy(objects=listOf(
                shape(ScreenshotRectangleShape.DIAMOND,solid).copy(id=9101)))
            val restored=ScreenshotDocumentCodec.decode(ScreenshotDocumentCodec.encode(document)).objects.single()
            check(restored.style.shape==ScreenshotRectangleShape.DIAMOND) { "工程丢失形状种类" }

            // The style row itself: kind radio first, then stroke, width and fill groups.
            val layer=ScreenshotDrawingLayer(context,image) {}
            layer.layout(0,0,320,320); layer.region=Rect(0,0,320,320); layer.tool=ScreenshotDrawingTool.RECTANGLE
            val controls=ScreenshotShapeControls(context,layer)
            controls.measure(View.MeasureSpec.makeMeasureSpec(840,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.AT_MOST))
            controls.layout(0,0,840,controls.measuredHeight)
            for(label in listOf("矩形","椭圆","菱形","描边实线","描边虚线","描边点线","无填充","实心填充","交叉线填充","线条填充"))
                check(find(controls,label)!=null) { "形状样式缺少 $label" }
            check(find(controls,"矩形")!!.isSelected) { "当前形状没有选中态" }
            find(controls,"菱形")!!.performClick()
            check(layer.style.shape==ScreenshotRectangleShape.DIAMOND) { "形状单选没有改到样式" }
            find(controls,"交叉线填充")!!.performClick()
            check(layer.style.hasFill && layer.style.fillPattern==ScreenshotFillPattern.CROSS_LINE) { "填充样式没有改到样式" }
            find(controls,"无填充")!!.performClick()
            check(!layer.style.hasFill) { "无填充没有关掉填充" }
            find(controls,"描边点线")!!.performClick()
            check(layer.style.strokePattern==ScreenshotStrokePattern.DOTTED) { "线型没有改到样式" }
            layer.close()

            // One sheet per kind, same box, so the difference is only the outline.
            val sheet=Bitmap.createBitmap(960,320,Bitmap.Config.ARGB_8888)
            val sheetCanvas=Canvas(sheet).apply { drawColor(Color.WHITE) }
            val label=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xFF444444.toInt(); textSize=13f }
            ScreenshotRectangleShape.values().forEachIndexed { index,kind ->
                val shown=ScreenshotDrawingStyle(color=0xFF185FA5.toInt(),width=4,hasFill=true,fillColor=0xFFE6F1FB.toInt())
                val bitmap=render(shape(kind,shown))
                sheetCanvas.drawBitmap(bitmap,index*320f,0f,null)
                sheetCanvas.drawText(listOf("Rectangle","Ellipse","Diamond")[index],index*320f+12f,20f,label)
                if(index>0) sheetCanvas.drawLine(index*320f,0f,index*320f,320f,Paint().apply { color=0xFFDDDDDD.toInt() })
                bitmap.recycle()
            }
            java.io.File(context.cacheDir,"screenshot-shape-kinds.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG,100,it) }
            sheet.recycle()
        } finally { renderer.close(); image.recycle() }
    }

    /**
     * T10: the Line tool is the arrow body without heads, with a Straight/Curve radio that starts on
     * Curve. Curve uses the original cubic chain over the drag points (`curve_bezier_segments`,
     * tension 1, open endpoints), so the same points must draw differently from the polyline.
     */
    private fun lines(context: Context) {
        val image=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888)
        val renderer=ScreenshotDrawingRenderer(image) { 1f }
        fun render(value: ScreenshotDrawingStroke): Bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE); renderer.draw(Canvas(it),listOf(value),Rect(0,0,320,320))
        }
        fun line(points: List<ScreenshotDrawingPoint>,style: ScreenshotDrawingStyle)=ScreenshotDrawingStroke(
            ScreenshotDrawingTool.LINE,points,style.color,style.width.toFloat(),style)
        try {
            check(defaultScreenshotDrawingStyle(ScreenshotDrawingTool.LINE).curved) { "直线工具默认不是曲线" }
            val straightStyle=ScreenshotDrawingStyle(color=Color.RED,width=4,curved=false)
            val straight=line(listOf(ScreenshotDrawingPoint(60f,160f),ScreenshotDrawingPoint(260f,160f)),straightStyle)
            val straightBitmap=render(straight)
            check(straightBitmap.getPixel(160,160)==Color.RED) { "直线没有画在两点之间" }
            check(straightBitmap.getPixel(160,200)==Color.WHITE) { "直线溢出到线外" }
            straightBitmap.recycle()

            val bend=listOf(ScreenshotDrawingPoint(60f,240f),ScreenshotDrawingPoint(160f,120f),ScreenshotDrawingPoint(260f,240f))
            val curvedStyle=ScreenshotDrawingStyle(color=Color.RED,width=4,curved=true)
            val curved=render(line(bend,curvedStyle)); val polyline=render(line(bend,curvedStyle.copy(curved=false)))
            var differing=0
            for(y in 0 until 320) for(x in 0 until 320) if(curved.getPixel(x,y)!=polyline.getPixel(x,y)) differing++
            check(differing>20) { "曲线与折线没有差别：$differing" }
            check(curved.getPixel(160,120)==Color.RED) { "曲线没有经过中间点" }
            curved.recycle(); polyline.recycle()

            check(renderer.hit(straight,ScreenshotDrawingPoint(160f,160f),2f)) { "直线中点不能命中" }
            check(!renderer.hit(straight,ScreenshotDrawingPoint(160f,205f),2f)) { "直线远处仍被命中" }
            check(renderer.hit(line(bend,curvedStyle),ScreenshotDrawingPoint(160f,120f),3f,null,false)) { "曲线中间点不能命中" }

            val document=ScreenshotDocument.full(320,320).copy(objects=listOf(straight.copy(id=9201)))
            val restored=ScreenshotDocumentCodec.decode(ScreenshotDocumentCodec.encode(document)).objects.single()
            check(!restored.style.curved) { "工程丢失直线/曲线档位" }
            val curvedDocument=ScreenshotDocument.full(320,320).copy(objects=listOf(line(bend,curvedStyle).copy(id=9202)))
            val curvedRestored=ScreenshotDocumentCodec.decode(ScreenshotDocumentCodec.encode(curvedDocument)).objects.single()
            check(curvedRestored.style.curved && curvedRestored.points.size==3) { "工程丢失曲线点集" }

            val layer=ScreenshotDrawingLayer(context,image) {}
            layer.layout(0,0,320,320); layer.region=Rect(0,0,320,320); layer.tool=ScreenshotDrawingTool.LINE
            val controls=ScreenshotLineControls(context,layer)
            controls.measure(View.MeasureSpec.makeMeasureSpec(840,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.AT_MOST))
            controls.layout(0,0,840,controls.measuredHeight)
            check(find(controls,"直线")!=null && find(controls,"曲线")!=null) { "直线样式缺少直线/曲线档位" }
            find(controls,"直线")!!.performClick()
            check(!layer.style.curved) { "切直线没有改到样式" }
            find(controls,"曲线")!!.performClick()
            check(layer.style.curved) { "切曲线没有改到样式" }
            find(controls,"线宽 8")!!.performClick()
            check(layer.style.width==8) { "线宽没有改到样式" }
            find(controls,"描边虚线")!!.performClick()
            check(layer.style.strokePattern==ScreenshotStrokePattern.DASHED) { "线型没有改到样式" }
            layer.close()

            val sheet=Bitmap.createBitmap(640,320,Bitmap.Config.ARGB_8888)
            val sheetCanvas=Canvas(sheet).apply { drawColor(Color.WHITE) }
            val label=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xFF444444.toInt(); textSize=13f }
            val plain=ScreenshotDrawingStyle(color=0xFF185FA5.toInt(),width=4,curved=false)
            val shown=render(line(bend,plain)); sheetCanvas.drawBitmap(shown,0f,0f,null); shown.recycle()
            val bent=render(line(bend,plain.copy(curved=true))); sheetCanvas.drawBitmap(bent,320f,0f,null); bent.recycle()
            sheetCanvas.drawLine(320f,0f,320f,320f,Paint().apply { color=0xFFDDDDDD.toInt() })
            sheetCanvas.drawText("Straight (same 3 points)",10f,18f,label)
            sheetCanvas.drawText("Curve",330f,18f,label)
            java.io.File(context.cacheDir,"screenshot-line-kinds.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG,100,it) }
            sheet.recycle()
        } finally { renderer.close(); image.recycle() }
    }

    /**
     * T11: the arrow head is a shape set, not one hard-coded chevron. Sizes and angles come from
     * `get_arrowhead_size` / `get_arrowhead_angle`; start and end are chosen independently.
     */
    private fun arrows(context: Context) {
        val image=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888)
        val renderer=ScreenshotDrawingRenderer(image) { 1f }
        fun render(value: ScreenshotDrawingStroke): Bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE); renderer.draw(Canvas(it),listOf(value),Rect(0,0,320,320))
        }
        fun arrow(start: ScreenshotArrowhead,end: ScreenshotArrowhead)=ScreenshotDrawingStroke(
            ScreenshotDrawingTool.ARROW,listOf(ScreenshotDrawingPoint(60f,160f),ScreenshotDrawingPoint(260f,160f)),
            Color.RED,4f,ScreenshotDrawingStyle(color=Color.RED,width=4,startHead=start,endHead=end))
        fun coverage(bitmap: Bitmap,box: Rect): Int {
            var count=0
            for(y in box.top until box.bottom) for(x in box.left until box.right) if(bitmap.getPixel(x,y)!=Color.WHITE) count++
            return count
        }
        try {
            val defaults=defaultScreenshotDrawingStyle(ScreenshotDrawingTool.ARROW)
            check(defaults.endHead==ScreenshotArrowhead.ARROW && defaults.startHead==ScreenshotArrowhead.NONE) { "箭头默认首尾不对" }
            check(defaults.arrowType==ScreenshotArrowType.CURVE && defaults.arrowRatio==1f) { "箭头默认箭身或比例不对" }
            check(defaults.color==0xFFF5222D.toInt()) { "箭头默认描边色不是原版红" }
            // Elbow: a mid-point dog-leg across the longer axis; a level drag stays one segment.
            val routed=ScreenshotArrowRendering.elbowPoints(ScreenshotDrawingPoint(60f,260f),ScreenshotDrawingPoint(260f,60f))
            check(routed.size==4 && routed[1]==ScreenshotDrawingPoint(160f,260f) && routed[2]==ScreenshotDrawingPoint(160f,60f)) { "折线箭身没有走中点折线" }
            check(ScreenshotArrowRendering.elbowPoints(ScreenshotDrawingPoint(60f,60f),ScreenshotDrawingPoint(260f,60f)).size==2) { "水平拖动不该折线" }
            fun slanted(type: ScreenshotArrowType,ratio: Float=1f)=ScreenshotDrawingStroke(ScreenshotDrawingTool.ARROW,
                if(type==ScreenshotArrowType.ELBOW) routed else listOf(ScreenshotDrawingPoint(60f,260f),ScreenshotDrawingPoint(260f,60f)),
                Color.RED,4f,ScreenshotDrawingStyle(color=Color.RED,width=4,arrowType=type,arrowRatio=ratio,
                    startHead=ScreenshotArrowhead.NONE,endHead=ScreenshotArrowhead.NONE))
            val straightShaft=render(slanted(ScreenshotArrowType.STRAIGHT))
            val elbowShaft=render(slanted(ScreenshotArrowType.ELBOW))
            try {
                check(straightShaft.getPixel(160,80)==Color.WHITE) { "直线箭身穿过了折线拐角" }
                check(elbowShaft.getPixel(160,80)==Color.RED) { "折线箭身没有画出竖直段" }
                check(elbowShaft.getPixel(160,258)==Color.RED) { "折线箭身没有画出水平段" }
            } finally { straightShaft.recycle(); elbowShaft.recycle() }

            // Ratio multiplies the head size (1.0–3.0, one decimal in the palette).
            val shaftPoints=listOf(ScreenshotDrawingPoint(60f,160f),ScreenshotDrawingPoint(260f,160f))
            val shoulderOne=ScreenshotArrowRendering.boundsPoints(shaftPoints,ScreenshotArrowhead.ARROW,false,4f,1f)
            val shoulderTwo=ScreenshotArrowRendering.boundsPoints(shaftPoints,ScreenshotArrowhead.ARROW,false,4f,2f)
            val spreadOne=kotlin.math.hypot(shoulderOne[0].x-shoulderOne[1].x,shoulderOne[0].y-shoulderOne[1].y)
            val spreadTwo=kotlin.math.hypot(shoulderTwo[0].x-shoulderTwo[1].x,shoulderTwo[0].y-shoulderTwo[1].y)
            check(spreadTwo>spreadOne*1.7f) { "箭头比例没有放大肩宽：$spreadOne -> $spreadTwo" }
            val ratioOne=render(arrow(ScreenshotArrowhead.NONE,ScreenshotArrowhead.ARROW))
            val ratioTwo=render(ScreenshotDrawingStroke(ScreenshotDrawingTool.ARROW,shaftPoints,Color.RED,4f,
                ScreenshotDrawingStyle(color=Color.RED,width=4,endHead=ScreenshotArrowhead.ARROW,arrowRatio=2f)))
            try {
                val headBox=Rect(150,100,262,220)
                check(coverage(ratioTwo,headBox)>coverage(ratioOne,headBox)+150) { "箭头比例没有放大头部：${coverage(ratioOne,headBox)} -> ${coverage(ratioTwo,headBox)}" }
            } finally { ratioOne.recycle(); ratioTwo.recycle() }
            val bare=render(arrow(ScreenshotArrowhead.NONE,ScreenshotArrowhead.NONE))
            val endHead=render(arrow(ScreenshotArrowhead.NONE,ScreenshotArrowhead.ARROW))
            val startHead=render(arrow(ScreenshotArrowhead.TRIANGLE,ScreenshotArrowhead.NONE))
            val dot=render(arrow(ScreenshotArrowhead.NONE,ScreenshotArrowhead.DOT))
            val endBox=Rect(210,130,262,190); val startBox=Rect(58,130,110,190)
            check(coverage(endHead,endBox)>coverage(bare,endBox)+40) { "终点箭头没有画出来" }
            check(coverage(startHead,startBox)>coverage(bare,startBox)+40) { "起点箭头没有画出来" }
            check(dot.getPixel(260,172)==Color.RED && bare.getPixel(260,172)==Color.WHITE) { "圆点头部尺寸不对" }
            endHead.recycle(); startHead.recycle(); dot.recycle()

            // Two heads on one short segment: each is capped at half the segment (a quarter for the
            // diamond family), so they meet at the midpoint at most (`short_arrow_endpoint_sizes...`).
            val shortPoints=listOf(ScreenshotDrawingPoint(60f,160f),ScreenshotDrawingPoint(80f,160f))
            for(head in ScreenshotArrowGlyphs.PANEL_HEADS.drop(1)) {
                val startShoulder=ScreenshotArrowRendering.boundsPoints(shortPoints,head,true,4f,1f)
                val endShoulder=ScreenshotArrowRendering.boundsPoints(shortPoints,head,false,4f,1f)
                check(startShoulder.maxOf { it.x }<=70.01f) { "$head 起点头越过了半段" }
                check(endShoulder.minOf { it.x }>=69.99f) { "$head 终点头越过了半段" }
            }

            check(renderer.hit(arrow(ScreenshotArrowhead.NONE,ScreenshotArrowhead.DOT),ScreenshotDrawingPoint(260f,172f),1f)) { "圆点头部不能命中" }
            check(!renderer.hit(arrow(ScreenshotArrowhead.NONE,ScreenshotArrowhead.NONE),ScreenshotDrawingPoint(260f,172f),1f)) { "无箭头时仍命中头部" }
            bare.recycle()

            // Hollow heads punch through to the screenshot; the original fills with the document
            // background colour, which a screenshot does not have.
            val scene=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            val sceneRenderer=ScreenshotDrawingRenderer(scene) { 1f }
            fun onScene(head: ScreenshotArrowhead): Bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).also { out ->
                out.eraseColor(Color.GREEN)
                sceneRenderer.draw(Canvas(out),listOf(arrow(ScreenshotArrowhead.NONE,head)),Rect(0,0,320,320))
            }
            try {
                val hollow=onScene(ScreenshotArrowhead.TRIANGLE_OUTLINE)
                check(hollow.getPixel(240,160)==Color.GREEN) { "空心三角没有挖空：${hollow.getPixel(240,160)}" }
                var outlinePixels=0
                for(y in 130 until 190) for(x in 210 until 262) if(hollow.getPixel(x,y)==Color.RED) outlinePixels++
                check(outlinePixels>20) { "空心三角没有描边：$outlinePixels" }
                hollow.recycle()
                val solid=onScene(ScreenshotArrowhead.TRIANGLE)
                check(solid.getPixel(240,160)==Color.RED) { "实心三角内部不是描边色" }
                solid.recycle()
                val hollowDiamond=onScene(ScreenshotArrowhead.DIAMOND_OUTLINE)
                check(hollowDiamond.getPixel(250,160)!=Color.RED || hollowDiamond.getPixel(240,160)==Color.GREEN) { "空心菱形没有挖空" }
                hollowDiamond.recycle()
            } finally { sceneRenderer.close(); scene.recycle() }

            val document=ScreenshotDocument.full(320,320).copy(objects=listOf(
                arrow(ScreenshotArrowhead.CROWFOOT_MANY,ScreenshotArrowhead.TRIANGLE).copy(id=9301)))
            val restored=ScreenshotDocumentCodec.decode(ScreenshotDocumentCodec.encode(document)).objects.single()
            check(restored.style.startHead==ScreenshotArrowhead.CROWFOOT_MANY &&
                restored.style.endHead==ScreenshotArrowhead.TRIANGLE) { "工程丢失箭头首尾" }

            val layer=ScreenshotDrawingLayer(context,image) {}
            layer.layout(0,0,320,320); layer.region=Rect(0,0,320,320); layer.tool=ScreenshotDrawingTool.ARROW
            val controls=ScreenshotArrowControls(context,layer)
            controls.measure(View.MeasureSpec.makeMeasureSpec(840,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.AT_MOST))
            controls.layout(0,0,840,controls.measuredHeight)
            for(label in listOf("起点箭头样式","终点箭头样式"))
                check(find(controls,label)!=null) { "箭头样式缺少 $label" }
            check(find(controls,"终点三角")==null) { "箭头选项应只在弹层里，不该铺在参数行" }
            // A detached row cannot show a popup, but the 14-option grid and its wiring must exist.
            find(controls,"终点箭头样式")!!.performClick()
            val heads=controls.pickerPanel ?: error("箭头选项面板没有生成")
            for(label in listOf("终点无","终点箭头","终点三角","终点菱形","终点多叉","终点双叉","终点凹三角"))
                check(find(heads,label)!=null) { "箭头样式缺少 $label" }
            check(options(heads)==14) { "箭头选项数不是原版的 14 档：${options(heads)}" }
            check(find(heads,"终点方块")==null) { "方块不在原版面板里" }
            find(heads,"终点三角")!!.performClick()
            check(layer.style.endHead==ScreenshotArrowhead.TRIANGLE) { "终点箭头没有改到样式" }
            find(controls,"起点箭头样式")!!.performClick()
            val starts=controls.pickerPanel ?: error("起点箭头选项面板没有生成")
            find(starts,"起点菱形")!!.performClick()
            check(layer.style.startHead==ScreenshotArrowhead.DIAMOND) { "起点箭头没有改到样式" }
            find(starts,"起点无")!!.performClick()
            check(layer.style.startHead==ScreenshotArrowhead.NONE) { "起点无箭头没有改到样式" }

            // Evidence sheet: the parameter row on the left, the 4x4 head grid underneath.
            run {
                val width=840
                fun sheet(view: View): Bitmap {
                    view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                    view.layout(0,0,width,view.measuredHeight)
                    return Bitmap.createBitmap(width,view.measuredHeight,Bitmap.Config.ARGB_8888).also {
                        val canvas=Canvas(it); canvas.drawColor(0xFF1F1F1F.toInt()); view.draw(canvas)
                    }
                }
                val row=sheet(ScreenshotArrowControls(context,layer))
                val grid=sheet(starts)
                try {
                    val out=Bitmap.createBitmap(width,row.height+8+grid.height,Bitmap.Config.ARGB_8888)
                    val canvas=Canvas(out); canvas.drawColor(0xFF1F1F1F.toInt())
                    canvas.drawBitmap(row,0f,0f,null); canvas.drawBitmap(grid,0f,row.height+8f,null)
                    java.io.File(context.cacheDir,"screenshot-parity-arrow-icons.png").outputStream().use {
                        out.compress(Bitmap.CompressFormat.PNG,100,it)
                    }
                    out.recycle()
                } finally { row.recycle(); grid.recycle() }
            }

            // Shaft radio plus the ratio button (wheel in tenths, click back to 1.0).
            check(find(controls,"箭身曲线")!=null && find(controls,"箭身折线")!=null && find(controls,"箭身直线")!=null) { "箭头样式缺少箭身选项" }
            find(controls,"箭身折线")!!.performClick()
            check(layer.style.arrowType==ScreenshotArrowType.ELBOW) { "箭身单选没有改到样式" }
            fun wheel(target: View,delta: Float) {
                val pointer=MotionEvent.PointerProperties().apply { id=0; toolType=MotionEvent.TOOL_TYPE_MOUSE }
                val position=MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_VSCROLL,delta) }
                val event=MotionEvent.obtain(0,1,MotionEvent.ACTION_SCROLL,1,arrayOf(pointer),arrayOf(position),0,0,1f,1f,0,0,InputDevice.SOURCE_MOUSE,0)
                try { check(target.dispatchGenericMotionEvent(event)) } finally { event.recycle() }
            }
            // The bar is rebuilt by the host after a style change, so every step needs fresh controls.
            fun arrowRow()=ScreenshotArrowControls(context,layer).also {
                it.measure(View.MeasureSpec.makeMeasureSpec(840,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.AT_MOST))
                it.layout(0,0,840,it.measuredHeight)
            }
            wheel(find(arrowRow(),"比例 1.0")!!,1f); check(layer.style.arrowRatio==1.1f) { "比例滚轮没有按 0.1 步进" }
            wheel(find(arrowRow(),"比例 1.1")!!,-1f); check(layer.style.arrowRatio==1f)
            layer.applyStyle(layer.style.copy(arrowRatio=3f))
            wheel(find(arrowRow(),"比例 3.0")!!,1f)
            check(layer.style.arrowRatio==3f) { "比例越过 3.0 上限" }
            find(arrowRow(),"比例 3.0")!!.performClick()
            check(layer.style.arrowRatio==1f) { "点击比例没有回到 1.0" }
            layer.close()

            // Gesture: the shaft points are committed per type, not just on the two drag ends.
            val gestureLayer=ScreenshotDrawingLayer(context,image) {}
            gestureLayer.layout(0,0,320,320); gestureLayer.region=Rect(0,0,320,320); gestureLayer.tool=ScreenshotDrawingTool.ARROW
            fun touch(action: Int,x: Float,y: Float) {
                val event=MotionEvent.obtain(0,1,action,x,y,0)
                try { gestureLayer.onTouchEvent(event) } finally { event.recycle() }
            }
            fun drag(type: ScreenshotArrowType,vararg path: Pair<Float,Float>) {
                gestureLayer.applyStyle(defaultScreenshotDrawingStyle(ScreenshotDrawingTool.ARROW).copy(
                    arrowType=type,startHead=ScreenshotArrowhead.NONE,endHead=ScreenshotArrowhead.NONE))
                touch(MotionEvent.ACTION_DOWN,path.first().first,path.first().second)
                path.drop(1).forEach { touch(MotionEvent.ACTION_MOVE,it.first,it.second) }
                touch(MotionEvent.ACTION_UP,path.last().first,path.last().second)
            }
            try {
                drag(ScreenshotArrowType.ELBOW,60f to 260f,160f to 160f,260f to 60f)
                val elbow=gestureLayer.history.items.last()
                check(elbow.points.size==4 && elbow.points[1]==ScreenshotDrawingPoint(160f,260f)) { "折线拖动没有落成折线点" }
                check(elbow.style.arrowType==ScreenshotArrowType.ELBOW)
                drag(ScreenshotArrowType.STRAIGHT,60f to 120f,160f to 120f,260f to 120f)
                check(gestureLayer.history.items.last().points.size==2) { "直线箭身不该保留中间采样" }
                drag(ScreenshotArrowType.CURVE,60f to 220f,120f to 180f,180f to 140f,260f to 60f)
                val curve=gestureLayer.history.items.last()
                check(curve.points.size>2) { "曲线箭身没有采样路径" }
                // The drag samples per 1.5 unit; the stored vertices must collapse to the set the head
                // sizes against, otherwise a curve arrow loses its head to the sampling step.
                check(curve.points.size<12) { "曲线箭身没有折成顶点集：${curve.points.size}" }
                val headCurve=curve.copy(style=curve.style.copy(startHead=ScreenshotArrowhead.NONE,endHead=ScreenshotArrowhead.ARROW))
                val bareCurve=curve.copy(style=curve.style.copy(startHead=ScreenshotArrowhead.NONE,endHead=ScreenshotArrowhead.NONE))
                val paintedHead=render(headCurve); val bareShaft=render(bareCurve)
                try {
                    val curveEndBox=Rect(228,44,268,96)
                    check(coverage(paintedHead,curveEndBox)>coverage(bareShaft,curveEndBox)+40) {
                        "曲线箭头没有画出头部：${coverage(bareShaft,curveEndBox)} -> ${coverage(paintedHead,curveEndBox)}"
                    }
                } finally { paintedHead.recycle(); bareShaft.recycle() }
            } finally { gestureLayer.close() }

            // The shaft choice and ratio survive a project round-trip; older projects stay on Curve 1.0.
            val arrowDoc=ScreenshotDocument.full(320,320).copy(objects=listOf(slanted(ScreenshotArrowType.ELBOW,2.5f).copy(id=9350)))
            val restoredArrow=ScreenshotDocumentCodec.decode(ScreenshotDocumentCodec.encode(arrowDoc)).objects.single()
            check(restoredArrow.style.arrowType==ScreenshotArrowType.ELBOW && restoredArrow.style.arrowRatio==2.5f) { "工程丢失箭身或比例" }
            val legacyArrow=JSONObject(ScreenshotDocumentCodec.encode(arrowDoc))
            val styleRow=legacyArrow.getJSONArray("objects").getJSONObject(0).getJSONObject("style")
            styleRow.remove("arrowType"); styleRow.remove("arrowRatio")
            val oldArrow=ScreenshotDocumentCodec.decode(legacyArrow.toString()).objects.single()
            check(oldArrow.style.arrowType==ScreenshotArrowType.CURVE && oldArrow.style.arrowRatio==1f) { "旧工程箭头被改成新箭身" }

            // Tapered shaft (`arrow_shaft.rs`): width ramps toward the primary head, unsupported
            // heads fall back to the plain shaft, TriangleOutline punches, dashed builds segments.
            val taperedPlain=render(arrow(ScreenshotArrowhead.NONE,ScreenshotArrowhead.ARROW))
            val taperedArrow=render(ScreenshotDrawingStroke(ScreenshotDrawingTool.ARROW,
                listOf(ScreenshotDrawingPoint(60f,160f),ScreenshotDrawingPoint(260f,160f)),Color.RED,4f,
                ScreenshotDrawingStyle(color=Color.RED,width=4,endHead=ScreenshotArrowhead.ARROW,
                    arrowShaftType=ScreenshotArrowShaftType.TAPERED)))
            try {
                check(!taperedArrow.sameAs(taperedPlain)) { "锥形轴没有改变渲染" }
                val midBox=Rect(100,120,200,200)
                check(coverage(taperedArrow,midBox)>coverage(taperedPlain,midBox)) { "锥形轴中段没有变宽" }
            } finally { taperedPlain.recycle(); taperedArrow.recycle() }
            val fallback=render(ScreenshotDrawingStroke(ScreenshotDrawingTool.ARROW,
                listOf(ScreenshotDrawingPoint(60f,160f),ScreenshotDrawingPoint(260f,160f)),Color.RED,4f,
                ScreenshotDrawingStyle(color=Color.RED,width=4,endHead=ScreenshotArrowhead.DOT,
                    arrowShaftType=ScreenshotArrowShaftType.TAPERED)))
            val plainDot=render(arrow(ScreenshotArrowhead.NONE,ScreenshotArrowhead.DOT))
            try { check(fallback.sameAs(plainDot)) { "不支持的头部没有回退普通轴" } } finally { fallback.recycle(); plainDot.recycle() }
            // Hollow (TriangleOutline) primary punches through even when tapered.
            val taperScene=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            val taperRenderer=ScreenshotDrawingRenderer(taperScene) { 1f }
            try {
                val hollowTapered=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).also { out ->
                    out.eraseColor(Color.GREEN)
                    taperRenderer.draw(Canvas(out),listOf(ScreenshotDrawingStroke(ScreenshotDrawingTool.ARROW,
                        listOf(ScreenshotDrawingPoint(60f,160f),ScreenshotDrawingPoint(260f,160f)),Color.RED,4f,
                        ScreenshotDrawingStyle(color=Color.RED,width=4,endHead=ScreenshotArrowhead.TRIANGLE_OUTLINE,
                            arrowShaftType=ScreenshotArrowShaftType.TAPERED))),Rect(0,0,320,320))
                }
                try { check(hollowTapered.getPixel(240,160)==Color.GREEN) { "锥形空心三角没有挖空" } } finally { hollowTapered.recycle() }
            } finally { taperRenderer.close(); taperScene.recycle() }

            // One cell per head shape, both ends, so the set can be eyeballed against the original.
            val sheet=Bitmap.createBitmap(960,360,Bitmap.Config.ARGB_8888)
            val sheetCanvas=Canvas(sheet).apply { drawColor(Color.WHITE) }
            val label=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xFF444444.toInt(); textSize=12f }
            ScreenshotArrowhead.values().forEachIndexed { index,head ->
                val cellLeft=(index%4)*240; val cellTop=(index/4)*120
                val cellBitmap=Bitmap.createBitmap(240,120,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                val cellRenderer=ScreenshotDrawingRenderer(cellBitmap) { 1f }
                val style=ScreenshotDrawingStyle(color=0xFF185FA5.toInt(),width=4,startHead=head,endHead=head)
                val stroke=ScreenshotDrawingStroke(ScreenshotDrawingTool.ARROW,
                    listOf(ScreenshotDrawingPoint(30f,80f),ScreenshotDrawingPoint(210f,80f)),style.color,4f,style)
                cellRenderer.draw(Canvas(cellBitmap),listOf(stroke),Rect(0,0,240,120))
                cellRenderer.close()
                sheetCanvas.drawBitmap(cellBitmap,cellLeft.toFloat(),cellTop.toFloat(),null)
                cellBitmap.recycle()
            }
            ScreenshotArrowhead.values().forEachIndexed { index,head ->
                sheetCanvas.drawText(head.name,(index%4)*240f+8f,(index/4)*120f+20f,label)
            }
            java.io.File(context.cacheDir,"screenshot-arrow-heads.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG,100,it) }
            sheet.recycle()

            // One cell per shaft type: Straight / Curve / Elbow over the same drag.
            val shaftSheet=Bitmap.createBitmap(1200,240,Bitmap.Config.ARGB_8888)
            val shaftCanvas=Canvas(shaftSheet).apply { drawColor(Color.WHITE) }
            ScreenshotArrowType.values().forEachIndexed { index,type ->
                val cell=Bitmap.createBitmap(240,240,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                val cellRenderer=ScreenshotDrawingRenderer(cell) { 1f }
                val points=if(type==ScreenshotArrowType.ELBOW)
                    ScreenshotArrowRendering.elbowPoints(ScreenshotDrawingPoint(30f,210f),ScreenshotDrawingPoint(210f,40f))
                    else listOf(ScreenshotDrawingPoint(30f,210f),ScreenshotDrawingPoint(120f,150f),ScreenshotDrawingPoint(210f,40f))
                val shaftStroke=ScreenshotDrawingStroke(ScreenshotDrawingTool.ARROW,points,
                    0xFF185FA5.toInt(),4f,ScreenshotDrawingStyle(color=0xFF185FA5.toInt(),width=4,arrowType=type))
                cellRenderer.draw(Canvas(cell),listOf(shaftStroke),Rect(0,0,240,240))
                cellRenderer.close()
                shaftCanvas.drawBitmap(cell,index*240f,0f,null)
                cell.recycle()
            }
            ScreenshotArrowType.values().forEachIndexed { index,type ->
                shaftCanvas.drawText(type.name,index*240f+8f,20f,label)
            }
            val taperCell=Bitmap.createBitmap(240,240,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val sheetTaperRenderer=ScreenshotDrawingRenderer(taperCell) { 1f }
            val taperStroke=ScreenshotDrawingStroke(ScreenshotDrawingTool.ARROW,
                listOf(ScreenshotDrawingPoint(30f,210f),ScreenshotDrawingPoint(210f,40f)),
                0xFF185FA5.toInt(),4f,ScreenshotDrawingStyle(color=0xFF185FA5.toInt(),width=4,
                    endHead=ScreenshotArrowhead.ARROW,arrowShaftType=ScreenshotArrowShaftType.TAPERED))
            sheetTaperRenderer.draw(Canvas(taperCell),listOf(taperStroke),Rect(0,0,240,240))
            sheetTaperRenderer.close()
            shaftCanvas.drawBitmap(taperCell,720f,0f,null); taperCell.recycle()
            shaftCanvas.drawText("TAPERED",728f,20f,label)
            java.io.File(context.cacheDir,"screenshot-arrow-shafts.png").outputStream().use { shaftSheet.compress(Bitmap.CompressFormat.PNG,100,it) }
            shaftSheet.recycle()
        } finally { renderer.close(); image.recycle() }
    }

    /**
     * T12: the eraser has no parameter row in the original — only an 8px viewport reach, half-radius
     * sampling, a half-opacity preview while the finger is down and one atomic commit on lift.
     */
    private fun eraser(context: Context) {
        val image=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val layer=ScreenshotDrawingLayer(context,image) {}
        layer.region=Rect(0,0,320,320); layer.layout(0,0,320,320)
        fun touch(action: Int,x: Float,y: Float) {
            val event=MotionEvent.obtain(1,2,action,x,y,0)
            try { check(layer.onTouchEvent(event)) { "手势未被消费：$action" } } finally { event.recycle() }
        }
        fun drag(x: Float,y: Float,toX: Float,toY: Float) {
            touch(MotionEvent.ACTION_DOWN,x,y); touch(MotionEvent.ACTION_MOVE,toX,toY); touch(MotionEvent.ACTION_UP,toX,toY)
        }
        fun undoDepth(): Int {
            var steps=0
            while(layer.history.canUndo) { layer.history.undo(); steps++ }
            repeat(steps) { layer.history.redo() }
            return steps
        }
        try {
            layer.tool=ScreenshotDrawingTool.RECTANGLE
            drag(70f,140f,130f,200f); drag(150f,140f,210f,200f); drag(230f,140f,290f,200f)
            check(layer.history.items.size==3) { "三个矩形没有落笔" }
            check(undoDepth()==3) { "三个矩形不是三条历史" }

            layer.tool=ScreenshotDrawingTool.ERASER
            touch(MotionEvent.ACTION_DOWN,60f,170f)
            touch(MotionEvent.ACTION_MOVE,160f,170f)
            // Pending erasures must still be visible, dimmed, until the finger lifts.
            val preview=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            layer.draw(Canvas(preview))
            val pendingPixel=preview.getPixel(100,170)
            preview.recycle()
            check(layer.history.items.size==3) { "拖动中就删了对象" }
            check(pendingPixel!=Color.RED) { "待擦除对象没有半透明预览" }
            touch(MotionEvent.ACTION_MOVE,300f,170f)
            touch(MotionEvent.ACTION_UP,300f,170f)
            check(layer.history.items.isEmpty()) { "一次划过没有擦掉全部三个对象" }
            check(undoDepth()==4) { "整体擦除应为一条历史：${undoDepth()}" }
            layer.undo()
            check(layer.history.items.size==3) { "撤销没有恢复被擦掉的对象" }

            // Reach is 8px: a rectangle 90px above the sweep must survive it.
            layer.tool=ScreenshotDrawingTool.RECTANGLE
            drag(120f,40f,180f,80f)
            val farId=layer.history.items.last().id
            layer.tool=ScreenshotDrawingTool.ERASER
            drag(60f,170f,300f,170f)
            check(layer.history.items.any { it.id==farId }) { "橡皮擦超出了 8px 半径" }
            check(layer.history.items.none { it.tool==ScreenshotDrawingTool.RECTANGLE && it.id!=farId }) { "半径内的对象没有被擦掉" }
        } finally { layer.close(); image.recycle() }
    }

    /**
     * T13: highlight is one toolbar slot with a mode radio (pen / rectangle), a colour and either a
     * border (rectangle) or a brush width (pen). The original has no opacity role — transparency is
     * the colour's alpha — so the phone must not expose a separate slider.
     */
    private fun highlights(context: Context) {
        val image=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF303030.toInt()) }
        val renderer=ScreenshotDrawingRenderer(image) { 1f }
        fun render(value: ScreenshotDrawingStroke): Bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).also {
            it.eraseColor(0xFF303030.toInt()); renderer.draw(Canvas(it),listOf(value),Rect(0,0,320,320))
        }
        fun highlight(style: ScreenshotDrawingStyle,color: Int)=ScreenshotDrawingStroke(
            ScreenshotDrawingTool.HIGHLIGHT,listOf(ScreenshotDrawingPoint(60f,60f),ScreenshotDrawingPoint(260f,260f)),
            color,style.width.toFloat(),style)
        try {
            val defaults=defaultScreenshotDrawingStyle(ScreenshotDrawingTool.HIGHLIGHT)
            check(defaults.opacity==1f) { "高亮不该再有独立透明度" }
            check(Color.alpha(defaults.color) in 80..100) { "高亮默认色没有带 35% alpha：${Color.alpha(defaults.color)}" }

            val rectStyle=ScreenshotDrawingStyle(color=defaults.color,width=12,area=ScreenshotAreaMode.RECTANGLE)
            val rect=render(highlight(rectStyle,defaults.color))
            val inside=rect.getPixel(160,160)
            check(inside!=0xFF303030.toInt()) { "矩形高亮没有覆盖底色" }
            check(Color.red(inside)>Color.blue(inside)) { "矩形高亮不是黄色叠加：$inside" }
            check(rect.getPixel(20,20)==0xFF303030.toInt()) { "矩形高亮越界" }
            rect.recycle()

            // The old document encoded 35% as opacity; the same picture must come out of colour alpha.
            val legacy=render(highlight(rectStyle.copy(color=0xFFFADB14.toInt(),opacity=0.35f),0xFFFADB14.toInt()))
            val modern=render(highlight(rectStyle,defaults.color))
            check(legacy.getPixel(160,160)==modern.getPixel(160,160)) { "旧透明度与新 alpha 渲染不一致" }
            legacy.recycle(); modern.recycle()

            // The whole highlight blends with MULTIPLY, so the border reads as a band that differs
            // from the fill rather than as the raw stroke colour.
            val bordered=render(highlight(rectStyle.copy(outlineWidth=4,outlineColor=Color.RED),defaults.color))
            val plain=render(highlight(rectStyle,defaults.color))
            var borderBand=0
            for(x in 70..250) if(bordered.getPixel(x,60)!=bordered.getPixel(x,70)) borderBand++
            check(borderBand>80) { "矩形高亮边框没有画出来：$borderBand" }
            var plainBand=0
            for(x in 70..250) if(plain.getPixel(x,60)!=plain.getPixel(x,70)) plainBand++
            check(plainBand<20) { "无边框时边缘也出现了带：$plainBand" }
            bordered.recycle(); plain.recycle()

            val penStyle=ScreenshotDrawingStyle(color=defaults.color,width=24,area=ScreenshotAreaMode.PEN)
            val pen=render(highlight(penStyle,defaults.color))
            var painted=0; var firstPainted=""
            for(y in 0 until 320) for(x in 0 until 320) {
                val pixel=pen.getPixel(x,y)
                if(pixel!=0xFF303030.toInt()) { painted++; if(firstPainted.isEmpty()) firstPainted="$x,$y=${Integer.toHexString(pixel)}" }
            }
            check(painted>100) { "画笔高亮没有落笔：$painted 个像素，首个 $firstPainted" }
            pen.recycle()

            val layer=ScreenshotDrawingLayer(context,image) {}
            layer.layout(0,0,320,320); layer.region=Rect(0,0,320,320); layer.tool=ScreenshotDrawingTool.HIGHLIGHT
            val controls=ScreenshotDrawingControls(context,layer,{ _,_,_-> },false)
            controls.measure(View.MeasureSpec.makeMeasureSpec(840,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.AT_MOST))
            controls.layout(0,0,840,controls.measuredHeight)
            check(find(controls,"作用方式 PEN")!=null && find(controls,"作用方式 RECTANGLE")!=null) { "高亮缺少模式切换" }
            check(find(controls,"透明度")==null) { "高亮仍暴露独立透明度滑杆" }
            layer.changeArea(ScreenshotAreaMode.RECTANGLE)
            controls.refresh()
            controls.measure(View.MeasureSpec.makeMeasureSpec(840,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.AT_MOST))
            controls.layout(0,0,840,controls.measuredHeight)
            check(find(controls,"边框色")!=null) { "矩形高亮缺少边框色" }
            find(controls,"边框色")
            layer.close()
        } finally { renderer.close(); image.recycle() }
    }
}
