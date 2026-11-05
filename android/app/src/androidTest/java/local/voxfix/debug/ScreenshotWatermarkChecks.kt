package local.voxfix.debug

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import org.json.JSONObject

/**
 * T23 watermark: `document.rs:WatermarkConfig` defaults and clamps, the rotated repeating text,
 * opacity as colour alpha × opacity, and the canvas-level persistence. The tile spacing is our
 * documented approximation, so these checks assert coverage and spacing, not original pixels.
 */
internal object ScreenshotWatermarkChecks {
    private fun ink(bitmap: Bitmap,box: Rect): Int {
        var count=0
        for(y in box.top until box.bottom) for(x in box.left until box.right)
            if(bitmap.getPixel(x,y)!=Color.WHITE) count++
        return count
    }
    fun run(context: Context) {
        val image=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val memory=ScreenshotToolMemory(context)
        val layer=ScreenshotDrawingLayer(context,image,memory) {}
        val renderer=ScreenshotDrawingRenderer(image) { 1f }
        fun render(mark: ScreenshotWatermark)=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE); renderer.draw(Canvas(it),emptyList(),Rect(0,0,320,320),mark)
        }
        try {
            val defaults=ScreenshotWatermark()
            check(defaults.text.isEmpty() && defaults.fontSize==16f && defaults.angle==30f && defaults.gap==56f)
            check(defaults.opacity==.16f && defaults.color==Color.BLACK && !defaults.isVisible()) { "水印默认值或可见性不对" }
            check(!ScreenshotWatermark(text="   ").isVisible()) { "空白水印不该绘制" }
            check(ScreenshotWatermark(text="WM").effectiveAlpha() in .159f..0.161f)

            // Clamps: 10–200 gap, -90…90 angle, 0…1 opacity, 6–512 font size.
            val wild=ScreenshotWatermark(text="WM",fontSize=9999f,angle=120f,gap=5f,opacity=1.5f).normalized()
            check(wild.fontSize==512f && wild.angle==90f && wild.gap==10f && wild.opacity==1f) { "水印边界没有收敛：$wild" }
            check(ScreenshotWatermark(text="WM",gap=500f).normalized().gap==200f)

            // Repeating coverage: ink in every quadrant, at 0.16 opacity a black stroke is ~214 grey.
            val tiled=ScreenshotWatermark(text="WM",fontSize=16f,angle=0f,gap=20f)
            val plain=render(tiled)
            val wide=render(tiled.copy(gap=120f))
            val rotated=render(tiled.copy(angle=45f))
            val solid=render(ScreenshotWatermark(text="M",fontSize=120f,angle=0f,gap=200f,opacity=1f))
            try {
                for(box in listOf(Rect(0,0,160,160),Rect(160,0,320,160),Rect(0,160,160,320),Rect(160,160,320,320)))
                    check(ink(plain,box)>20) { "水印没有铺满象限 $box" }
                val dense=ink(plain,Rect(0,0,320,320)); val sparse=ink(wide,Rect(0,0,320,320))
                check(sparse<dense/2) { "间距没有拉开排布：$dense -> $sparse" }
                check(!rotated.sameAs(plain)) { "角度没有生效" }
                var darkest=255
                for(y in 0 until 320) for(x in 0 until 320) darkest=minOf(darkest,Color.red(solid.getPixel(x,y)))
                check(darkest<40) { "不透明水印仍然很淡：$darkest" }
            } finally { plain.recycle(); wide.recycle(); rotated.recycle(); solid.recycle() }

            // A large glyph guarantees fully covered interior pixels; antialiased edges do not
            // represent the configured alpha (0.16 × black on white ≈ 214).
            val translucent=render(ScreenshotWatermark(text="M",fontSize=120f,angle=0f,gap=200f))
            try {
                var darkest=255
                for(y in 0 until 320) for(x in 0 until 320) darkest=minOf(darkest,Color.red(translucent.getPixel(x,y)))
                check(darkest in 196..232) { "水印不是 0.16 透明度叠加：$darkest" }
            } finally { translucent.recycle() }

            // The layer renders and exports it, but it is not a selectable or erasable object.
            layer.layout(0,0,320,320); layer.region=Rect(0,0,320,320)
            layer.setWatermark(tiled)
            val exported=layer.render(Rect(0,0,320,320),null)!!
            try { check(ink(exported,Rect(0,0,320,320))>100) { "导出丢失水印" } } finally { exported.recycle() }
            // The watermark rides the object history: one change is one step, undo restores it.
            layer.undo()
            check(layer.watermark==ScreenshotWatermark() && layer.history.items.isEmpty()) { "撤销没有回退水印" }
            layer.redo()
            check(layer.watermark==tiled.normalized()) { "重做没有恢复水印" }
            layer.tool=ScreenshotDrawingTool.SELECT
            fun touch(action: Int,x: Float,y: Float) {
                val event=MotionEvent.obtain(0,1,action,x,y,0); try { layer.onTouchEvent(event) } finally { event.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN,160f,160f); touch(MotionEvent.ACTION_UP,160f,160f)
            check(layer.selected==null) { "水印被当成可选对象" }
            layer.tool=ScreenshotDrawingTool.ERASER
            touch(MotionEvent.ACTION_DOWN,20f,20f); touch(MotionEvent.ACTION_MOVE,300f,300f); touch(MotionEvent.ACTION_UP,300f,300f)
            check(layer.watermark==tiled.normalized()) { "橡皮擦掉了水印" }

            // Palette: text chip, size presets and the step button share the watermark config.
            layer.tool=ScreenshotDrawingTool.WATERMARK
            layer.setWatermark(ScreenshotWatermark(text="WM"))
            val controls=ScreenshotDrawingControls(context,layer,{ _,_,_-> },false)
            check(controls.hasContent && controls.childCount>0) { "水印没有参数栏" }
            check(controls.findByDescription("水印字号 16")!=null || controls.findByDescription("字号 16")!=null) { "缺少水印字号" }
            controls.findByDescription("水印字号 24")?.performClick()
            check(layer.watermark.fontSize==24f) { "字号预设没有改到水印" }
            controls.findByDescription("水印字号 30")?.performClick()
            check(layer.watermark.fontSize==30f)

            // Project round-trip keeps the config; older projects decode without one.
            val document=ScreenshotDocument.full(320,320).copy(watermark=tiled.normalized())
            val restored=ScreenshotDocumentCodec.decode(ScreenshotDocumentCodec.encode(document))
            check(restored.watermark==tiled.normalized()) { "工程丢失水印" }
            val legacy=JSONObject(ScreenshotDocumentCodec.encode(document)).apply { remove("watermark") }
            check(ScreenshotDocumentCodec.decode(legacy.toString()).watermark==null) { "旧工程被塞入水印" }
            val long=JSONObject(ScreenshotDocumentCodec.encode(document))
            long.getJSONObject("watermark").put("text","x".repeat(400))
            check(runCatching { ScreenshotDocumentCodec.decode(long.toString()) }.exceptionOrNull() is ScreenshotDocumentCodec.FormatException)

            // Templates resolve `{text}`/timestamps at draw time and survive the project round-trip.
            val templated=ScreenshotWatermark(text="截图",template="{text}-{yyyy}",
                appliedAt=System.currentTimeMillis(),angle=0f,gap=20f)
            val withTemplate=render(templated)
            try {
                check(ink(withTemplate,Rect(0,0,320,320))>50) { "模板水印没有绘制" }
                val plainText=render(templated.copy(template=""))
                try { check(!withTemplate.sameAs(plainText)) { "模板与纯文本渲染相同" } } finally { plainText.recycle() }
            } finally { withTemplate.recycle() }
            val templateDoc=ScreenshotDocument.full(320,320).copy(watermark=templated.normalized())
            val restoredTemplate=ScreenshotDocumentCodec.decode(ScreenshotDocumentCodec.encode(templateDoc))
            check(restoredTemplate.watermark?.template=="{text}-{yyyy}" && (restoredTemplate.watermark?.appliedAt ?: 0L)>0) { "工程丢失水印模板" }
            val legacyTemplate=JSONObject(ScreenshotDocumentCodec.encode(templateDoc))
            legacyTemplate.getJSONObject("watermark").remove("template")
            // A document without the template key renders the plain text; a stray appliedAt is inert.
            val oldTemplate=ScreenshotDocumentCodec.decode(legacyTemplate.toString())
            check(oldTemplate.watermark?.template!!.isEmpty() && oldTemplate.watermark!!.resolvedText()=="截图") { "旧工程被塞入模板" }

            // Evidence sheet: default 30°, 0° dense and one opaque copy.
            val sheet=Bitmap.createBitmap(960,320,Bitmap.Config.ARGB_8888)
            val sheetCanvas=Canvas(sheet).apply { drawColor(Color.WHITE) }
            val label=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xFF444444.toInt(); textSize=12f }
            val cells=listOf("30° 默认" to ScreenshotWatermark(text="Snow Shot",fontSize=16f),
                "0° 间距20" to ScreenshotWatermark(text="Snow Shot",fontSize=16f,angle=0f,gap=20f),
                "45° 不透明" to ScreenshotWatermark(text="Snow Shot",fontSize=16f,angle=45f,gap=40f,opacity=1f))
            cells.forEachIndexed { index,(name,mark) ->
                val cell=render(mark)
                sheetCanvas.drawBitmap(cell,index*320f,0f,null); cell.recycle()
                sheetCanvas.drawText(name,index*320f+8f,20f,label)
            }
            java.io.File(context.getExternalFilesDir(null),"snow-t23-watermark.png").outputStream()
                .use { sheet.compress(Bitmap.CompressFormat.PNG,100,it) }
            sheet.recycle()
        } finally { layer.close(); renderer.close(); image.recycle() }
    }
    private fun android.view.View.findByDescription(label: String): android.view.View? {
        if(contentDescription==label) return this
        if(this is android.view.ViewGroup) for(i in 0 until childCount) (getChildAt(i).findByDescription(label))?.let { return it }
        return null
    }
}
