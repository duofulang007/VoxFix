package local.voxfix.debug

import android.content.Context
import android.graphics.*
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import java.io.File

internal object ScreenshotCornerChecks {
    private fun scroll(view: View,delta: Float) {
        val pointer=MotionEvent.PointerProperties().apply { id=0; toolType=MotionEvent.TOOL_TYPE_MOUSE }
        val coords=MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_VSCROLL,delta) }
        val event=MotionEvent.obtain(0,1,MotionEvent.ACTION_SCROLL,1,arrayOf(pointer),arrayOf(coords),0,0,1f,1f,0,0,InputDevice.SOURCE_MOUSE,0)
        try { check(view.dispatchGenericMotionEvent(event)) } finally { event.recycle() }
    }
    private fun find(view: View,label: String): View? {
        if(view.contentDescription==label) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),label)?.let { return it }
        return null
    }
    fun run(context: Context) {
        val image=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val renderer=ScreenshotDrawingRenderer(image) { 1f }
        val memory=ScreenshotToolMemory(context)
        val layer=ScreenshotDrawingLayer(context,image,memory) {}
        val style=ScreenshotDrawingStyle(color=Color.RED,width=2,hasFill=true,fillColor=Color.YELLOW,cornerRadius=60)
        val stroke=ScreenshotDrawingStroke(ScreenshotDrawingTool.RECTANGLE,listOf(ScreenshotDrawingPoint(80f,80f),ScreenshotDrawingPoint(320f,320f)),Color.RED,2f,style,id=9601)
        fun render(value: ScreenshotDrawingStroke)=image.copy(Bitmap.Config.ARGB_8888,true).also { renderer.draw(Canvas(it),listOf(value),Rect(0,0,400,400)) }
        fun event(target: View,action: Int,y: Float) {
            val value=MotionEvent.obtain(0,1,action,40f,y,0)
            try { target.onTouchEvent(value) } finally { value.recycle() }
        }
        fun select() {
            layer.tool=ScreenshotDrawingTool.SELECT
            val down=MotionEvent.obtain(0,1,MotionEvent.ACTION_DOWN,200f,200f,0)
            val up=MotionEvent.obtain(0,1,MotionEvent.ACTION_UP,200f,200f,0)
            try { layer.onTouchEvent(down); layer.onTouchEvent(up) } finally { down.recycle(); up.recycle() }
            check(layer.selectedIndex==0)
        }
        fun button()=ScreenshotCornerRadiusButton(context,layer,"圆角").apply {
            layout(0,0,(56*resources.displayMetrics.density).toInt(),(28*resources.displayMetrics.density).toInt())
        }
        try {
            val rounded=render(stroke)
            val legacy=render(stroke.copy(style=style.copy(cornerRadius=0)))
            check(rounded.getPixel(82,82)==Color.WHITE && legacy.getPixel(82,82)==Color.YELLOW)
            check(rounded.getPixel(200,200)==Color.YELLOW && rounded.getPixel(100,100)==Color.YELLOW)
            rounded.recycle(); legacy.recycle()
            check(!renderer.hit(stroke,ScreenshotDrawingPoint(82f,82f),0f))
            check(renderer.hit(stroke,ScreenshotDrawingPoint(200f,200f),0f,outline=false))
            for(shape in listOf(ScreenshotRectangleShape.ELLIPSE,ScreenshotRectangleShape.DIAMOND)) {
                val first=render(stroke.copy(style=style.copy(shape=shape,cornerRadius=0)))
                val second=render(stroke.copy(style=style.copy(shape=shape,cornerRadius=83)))
                check(first.sameAs(second)) { "非矩形不应被圆角参数改变：$shape" }
                first.recycle(); second.recycle()
            }
            layer.layout(0,0,400,400); layer.region=Rect(0,0,400,400); layer.savedFontUnit=1f; layer.savedPixelUnit=1f
            layer.history.load(listOf(stroke)); select()
            scroll(button(),1f); check(layer.history.items.single().style.cornerRadius==61)
            layer.undo(); check(layer.history.items.single().style.cornerRadius==60)
            select(); button().performClick(); check(layer.history.items.single().style.cornerRadius==6)
            layer.undo(); select()
            val drag=button(); val move=40f-40*context.resources.displayMetrics.density
            event(drag,MotionEvent.ACTION_DOWN,40f); event(drag,MotionEvent.ACTION_MOVE,move)
            check(layer.history.items.single().style.cornerRadius==60)
            val preview=image.copy(Bitmap.Config.ARGB_8888,true)
            layer.draw(Canvas(preview)); check(preview.getPixel(100,100)==Color.WHITE); preview.recycle()
            event(drag,MotionEvent.ACTION_UP,move)
            check(layer.history.items.single().style.cornerRadius==80)
            layer.undo(); check(layer.history.items.single().style.cornerRadius==60 && !layer.history.canUndo)
            select()
            val cancel=button()
            event(cancel,MotionEvent.ACTION_DOWN,40f); event(cancel,MotionEvent.ACTION_MOVE,move)
            event(cancel,MotionEvent.ACTION_CANCEL,move); event(cancel,MotionEvent.ACTION_UP,move)
            check(layer.history.items.single().style.cornerRadius==60 && !layer.history.canUndo)
            layer.applyStyle(style.copy(cornerRadius=83)); val max=button(); scroll(max,1f)
            check(layer.history.items.single().style.cornerRadius==83)
            memory.save(ScreenshotDrawingTool.RECTANGLE,style.copy(cornerRadius=83))
            check(ScreenshotToolMemory(context).style(ScreenshotDrawingTool.RECTANGLE)?.cornerRadius==83)
            val document=ScreenshotDocument.full(400,400).copy(objects=layer.history.items)
            check(ScreenshotDocumentCodec.decode(ScreenshotDocumentCodec.encode(document))==document)
            val icon=Bitmap.createBitmap(max.width,max.height,Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF1C1C1E.toInt()) }
            max.draw(Canvas(icon))
            File(context.getExternalFilesDir(null),"snow-corner-radius.png").outputStream().use { icon.compress(Bitmap.CompressFormat.PNG,100,it) }; icon.recycle()
            layer.tool=ScreenshotDrawingTool.TEXT
            val textControls=ScreenshotDrawingControls(context,layer,{ _,_,_-> },false)
            val textButton=find(textControls,"背景圆角") as ScreenshotCornerRadiusButton
            textButton.performClick(); check(layer.style.cornerRadius==6)
            check(memory.style(ScreenshotDrawingTool.RECTANGLE)?.cornerRadius==83)
        } finally { layer.close(); renderer.close(); image.recycle() }
    }
}
