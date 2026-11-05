package local.voxfix.debug

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

internal object ScreenshotDrawingRefinementChecks {
    fun run(context: Context) {
        val image=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val renderer=ScreenshotDrawingRenderer(image) { 1f }
        val layer=ScreenshotDrawingLayer(context,image) {}
        layer.layout(0,0,600,900); layer.region=Rect(0,0,600,900)
        try {
            val rectangle=ScreenshotDrawingStroke(ScreenshotDrawingTool.RECTANGLE,
                listOf(ScreenshotDrawingPoint(100f,100f),ScreenshotDrawingPoint(300f,300f)),Color.RED,2f)
            check(!renderer.hit(rectangle,ScreenshotDrawingPoint(200f,200f),4f)) { "empty rectangle interior steals selection" }
            check(renderer.hit(rectangle,ScreenshotDrawingPoint(101f,200f),4f))
            val arrow=rectangle.copy(tool=ScreenshotDrawingTool.ARROW,points=listOf(ScreenshotDrawingPoint(100f,100f),ScreenshotDrawingPoint(300f,100f)))
            check(renderer.hit(arrow,ScreenshotDrawingPoint(286f,108f),3f)) { "arrowhead not selectable" }
            val text=ScreenshotDrawingStroke(ScreenshotDrawingTool.TEXT,listOf(ScreenshotDrawingPoint(50f,400f)),Color.BLUE,1f,
                ScreenshotDrawingStyle(color=Color.BLUE,fontSize=24f,hasFill=true,fillColor=Color.GREEN,outlineWidth=2,cornerRadius=12),
                text="Hi\n中文",textWidth=180f,textHeight=100f)
            check(!renderer.hit(text.copy(style=text.style.copy(hasFill=false)),ScreenshotDrawingPoint(210f,405f),1f)) { "blank text width steals selection" }
            val output=image.copy(Bitmap.Config.ARGB_8888,true)
            try {
                renderer.draw(Canvas(output),listOf(text),Rect(0,0,600,900))
                // Snow Shot fills one pill per line, padded by 0.32/0.1 of the 1.2x line height, so the
                // natural text width wins over the wrap rectangle and the corner is rounded per line.
                check(output.getPixel(45,410)==Color.GREEN) { "text background not exported" }
                check(output.getPixel(200,480)==Color.WHITE) { "text background still covers the wrap width" }
                check(output.getPixel(42,399)==Color.WHITE) { "rounded text background lost its corner" }
                val serial=text.copy(tool=ScreenshotDrawingTool.SERIAL,points=listOf(ScreenshotDrawingPoint(400f,600f)),number=21,
                    style=text.style.copy(serialShape=ScreenshotSerialShape.SOLID_CIRCLE,fillColor=Color.YELLOW,fontSize=30f))
                renderer.draw(Canvas(output),listOf(serial),Rect(0,0,600,900))
                var foreground=0; var fill=0
                for(y in 572..628) for(x in 372..428) {
                    if(output.getPixel(x,y)==Color.BLUE) foreground++
                    if(output.getPixel(x,y)==Color.YELLOW) fill++
                }
                check(foreground>20 && fill>40) { "serial foreground/fill are not independent: $foreground,$fill" }
            } finally { output.recycle() }
            layer.history.add(rectangle); layer.tool=ScreenshotDrawingTool.SELECT
            fun touch(action: Int,x: Float,y: Float) {
                val event=MotionEvent.obtain(0,1,action,x,y,0); try { layer.onTouchEvent(event) } finally { event.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN,100f,200f); touch(MotionEvent.ACTION_UP,100f,200f)
            check(layer.selectedIndex==0)
            val original=layer.history.items.single()
            layer.beginStylePreview()
            repeat(20) { layer.previewStyle(layer.inspectedStyle.copy(color=Color.BLUE,opacity=(it+1)/20f)) }
            check(layer.history.items.single()==original) { "preview wrote undo history" }
            layer.finishStylePreview(false); check(layer.history.items.single()==original)
            layer.beginStylePreview(); layer.previewStyle(layer.inspectedStyle.copy(color=Color.BLUE)); layer.finishStylePreview(true)
            check(layer.history.items.single().color==Color.BLUE)
            layer.undo(); check(layer.history.items.single()==original) { "style drag must undo once" }
            layer.redo(); check(layer.history.items.single().color==Color.BLUE)
            layer.tool=ScreenshotDrawingTool.HIGHLIGHT
            layer.changeArea(ScreenshotAreaMode.PEN); layer.applyStyle(layer.style.copy(width=40))
            layer.changeArea(ScreenshotAreaMode.RECTANGLE); layer.applyStyle(layer.style.copy(outlineWidth=6))
            layer.changeArea(ScreenshotAreaMode.PEN); check(layer.style.width==40)
            layer.changeArea(ScreenshotAreaMode.RECTANGLE); check(layer.style.outlineWidth==6)
            val controls=ScreenshotDrawingControls(context,layer,{ _,_,_-> })
            fun find(view: View,label: String): View? {
                if(view.contentDescription==label) return view
                if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),label)?.let { return it }
                return null
            }
            check(find(controls,"边框宽")!=null && find(controls,"线宽 40")==null) { "rectangle highlight exposes pen width" }
            check(find(controls,"透明度")==null) { "highlight must not expose an opacity slider" }
            // Highlight has no opacity role any more; the slider contract is checked on spotlight.
            layer.tool=ScreenshotDrawingTool.SPOTLIGHT
            val spotControls=ScreenshotDrawingControls(context,layer,{ _,_,_-> })
            val opacity=find(spotControls,"透明度") as android.widget.SeekBar
            val arguments=android.os.Bundle().apply {
                putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,45f)
            }
            check(opacity.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,arguments)) { "spotlight opacity slider rejected the progress action" }
            check(kotlin.math.abs(layer.style.opacity-.45f)<.001f) { "non-drag slider adjustment stayed in uncommitted preview: ${layer.style.opacity}" }
            val parent=FrameLayout(context).apply { layout(0,0,600,900) }
            parent.addView(layer,FrameLayout.LayoutParams(600,900))
            layer.tool=ScreenshotDrawingTool.TEXT
            val before=layer.history.items.toList()
            val point=ScreenshotDrawingPoint(80f,600f)
            val input=ScreenshotAnnotationInput(parent,layer,point,null,{ RectF(80f,600f,500f,720f) }) {}
            input.place(0,850); input.field.setText("输入中\n第二行")
            check(layer.history.items==before) { "typing preview committed early" }
            input.place(0,450)
            check((input.field.layoutParams as FrameLayout.LayoutParams).topMargin<450) { "IME-covered field did not remain accessible" }
            input.close(); check(layer.history.items==before) { "cancel changed existing marks" }
            layer.beginTextPreview(point,null); layer.previewText("保存后"); layer.addText(point,"保存后")
            check(layer.history.items.last().text=="保存后"); layer.undo(); check(layer.history.items==before)
        } finally { layer.close(); renderer.close(); image.recycle() }
    }
}
