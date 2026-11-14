package local.voxfix.debug

import android.content.Context
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject

internal object ScreenshotSerialChecks {
    private fun find(view: View,label: String): View? {
        if(view.contentDescription==label) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),label)?.let { return it }
        return null
    }
    fun run(context: Context) {
        val image=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val renderer=ScreenshotDrawingRenderer(image) { 1f }
        val layer=ScreenshotDrawingLayer(context,image,ScreenshotToolMemory(context)) {}
        val defaults=defaultScreenshotDrawingStyle(ScreenshotDrawingTool.SERIAL)
        fun serial(style: ScreenshotDrawingStyle=defaults,number: Long=1L)=ScreenshotDrawingStroke(ScreenshotDrawingTool.SERIAL,
            listOf(ScreenshotDrawingPoint(160f,160f)),style.color,style.width.toFloat(),style,number=number,id=9501)
        fun render(stroke: ScreenshotDrawingStroke)=image.copy(Bitmap.Config.ARGB_8888,true).also {
            renderer.draw(Canvas(it),listOf(stroke),Rect(0,0,320,320))
        }
        try {
            check(defaults.fontSize==24f && defaults.serialGeometryVersion==1 && defaults.color==0xFFF5222D.toInt())
            check(kotlin.math.abs(ScreenshotSerialRendering.radius(serial(),1f)*2-40.32f)<.001f)
            check(ScreenshotSerialRendering.radius(serial(number=10000),1f)>ScreenshotSerialRendering.radius(serial(),1f))
            val circle=serial(defaults.copy(serialShape=ScreenshotSerialShape.CIRCLE)).copy(id=9502)
            check(ScreenshotSerialRendering.radius(circle,1f)==6f)
            val plain=render(circle)
            check(plain.getPixel(160,160)==Color.WHITE) { "无数字圆形仍绘制文字" }
            plain.recycle()
            val solid=render(serial(defaults.copy(color=Color.BLACK,serialShape=ScreenshotSerialShape.SOLID_CIRCLE)))
            check(solid.getPixel(160,172)==Color.BLACK)
            check((152..166).any { y -> (155..165).any { x -> solid.getPixel(x,y)==Color.WHITE } }) { "深色实心序号没有白色对比文字" }
            solid.recycle()
            check(!renderer.hit(serial(),ScreenshotDrawingPoint(180f,180f),0f)) { "圆形外角误命中" }
            val encoded=ScreenshotDocumentCodec.encode(ScreenshotDocument.full(320,320).copy(objects=listOf(serial(),circle)))
            check(ScreenshotDocumentCodec.decode(encoded).objects.first().style.serialGeometryVersion==1)
            val old=JSONObject(encoded)
            old.getJSONArray("objects").getJSONObject(0).getJSONObject("style").remove("serialGeometryVersion")
            check(ScreenshotDocumentCodec.decode(old.toString()).objects.first().style.serialGeometryVersion==0) { "旧工程序号被强制改外观" }
            layer.layout(0,0,320,320); layer.region=Rect(0,0,320,320); layer.tool=ScreenshotDrawingTool.SERIAL
            layer.applyStyle(defaults)
            val controls=ScreenshotDrawingControls(context,layer,{ _,_,_-> },false)
            // 填充细节收进弹层，行内只留触发器。
            check(find(controls,"圆形（无数字）")!=null && find(controls,"填充色设置")!=null && find(controls,"字号 54")!=null)
            check(find(controls,"透明度")==null)
            find(controls,"圆形（无数字）")!!.performClick()
            check(layer.style.serialShape==ScreenshotSerialShape.CIRCLE)
            check(ScreenshotToolMemory(context).style(ScreenshotDrawingTool.SERIAL)?.serialGeometryVersion==1)
            layer.setNumber(0); check(layer.nextNumber==0L)
            layer.applyStyle(defaults.copy(fontFamily="sans-serif-condensed"))
            check(ScreenshotToolMemory(context).style(ScreenshotDrawingTool.SERIAL)?.fontFamily=="sans-serif-condensed")
            val text=ScreenshotDrawingStroke(ScreenshotDrawingTool.TEXT,listOf(ScreenshotDrawingPoint(30f,30f)),Color.BLACK,2f,
                ScreenshotDrawingStyle(color=Color.BLACK,fontSize=24f,hasFill=true,fillColor=Color.RED,fillPattern=ScreenshotFillPattern.CROSS_LINE),
                text="Text",textWidth=180f,textHeight=120f)
            val textured=render(text)
            try {
                // The pill hugs each line (`drawBackground`): sample the left padding band, which is
                // inside the pill for any text width, instead of the old wrap rectangle.
                val pixels=(30..58).flatMap { y -> (23..29).map { x -> textured.getPixel(x,y) } }
                check(pixels.any { it==Color.RED } && pixels.any { it==Color.WHITE }) { "文字背景纹理未接通或不透明铺满" }
                check(textured.getPixel(250,250)==Color.WHITE) { "文字填充纹理越界" }
            } finally { textured.recycle() }
        } finally { layer.close(); renderer.close(); image.recycle() }
    }
}
