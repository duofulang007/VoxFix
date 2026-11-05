package local.voxfix.debug

import android.content.Context
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar

/** T14 uses isolated preferences supplied by parity-foundation, never the user's tool defaults. */
internal object ScreenshotSpotlightChecks {
    private fun find(view: View,label: String): View? {
        if(view.contentDescription==label) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),label)?.let { return it }
        return null
    }
    fun run(context: Context) {
        val image=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val renderer=ScreenshotDrawingRenderer(image) { 1f }
        val layer=ScreenshotDrawingLayer(context,image,ScreenshotToolMemory(context)) {}
        val defaults=defaultScreenshotDrawingStyle(ScreenshotDrawingTool.SPOTLIGHT)
        fun spot(left: Float,top: Float,right: Float,bottom: Float,style: ScreenshotDrawingStyle=defaults)=
            ScreenshotDrawingStroke(ScreenshotDrawingTool.SPOTLIGHT,
                listOf(ScreenshotDrawingPoint(left,top),ScreenshotDrawingPoint(right,bottom)),style.color,2f,style)
        fun render(vararg marks: ScreenshotDrawingStroke)=image.copy(Bitmap.Config.ARGB_8888,true).also {
            renderer.draw(Canvas(it),marks.toList(),Rect(0,0,320,320))
        }
        try {
            check(defaults.opacity==.64f && defaults.color==Color.BLACK)
            val first=spot(40f,40f,180f,180f)
            val second=spot(120f,120f,260f,260f)
            val union=render(first,second)
            check(Color.red(union.getPixel(10,10)) in 91..93) { "遮罩不是64%黑色" }
            for((x,y) in listOf(80 to 80,150 to 150,240 to 240))
                check(union.getPixel(x,y)==Color.WHITE) { "聚光区域并集出现遮罩：$x,$y" }
            union.recycle()
            for((alpha,opacity) in listOf(0 to 1f,128 to .5f,255 to 0f)) {
                val result=render(first.copy(color=Color.argb(alpha,0,0,0),style=defaults.copy(opacity=opacity)))
                val expected=255-(alpha*opacity).toInt()
                check(kotlin.math.abs(Color.red(result.getPixel(10,10))-expected)<=1) { "颜色alpha没有乘遮罩透明度" }
                result.recycle()
            }
            layer.layout(0,0,320,320); layer.region=Rect(80,80,240,240); layer.tool=ScreenshotDrawingTool.SPOTLIGHT
            layer.history.add(first); layer.history.add(second)
            val document=ScreenshotDocument.full(320,320).copy(objects=layer.history.items)
            val reopened=ScreenshotDocumentCodec.decode(ScreenshotDocumentCodec.encode(document))
            check(reopened==document)
            layer.history.load(reopened.objects)
            val exported=layer.render(Rect(80,80,240,240),null)!!
            check(exported.width==160 && exported.height==160 && exported.getPixel(70,70)==Color.WHITE)
            exported.recycle()
            layer.history.replace(1,second.transformed(120f,120f,150f,120f))
            layer.undo(); check(layer.history.items.last()==second)
            layer.redo(); check(layer.history.items.last().points.first().x==150f)
            layer.history.remove(setOf(0,1)); check(layer.history.items.isEmpty())
            layer.undo(); check(layer.history.items.size==2)
            layer.tool=ScreenshotDrawingTool.SPOTLIGHT
            val controls=ScreenshotDrawingControls(context,layer,{ _,_,_-> },false)
            fun refresh() {
                controls.refresh()
                controls.measure(View.MeasureSpec.makeMeasureSpec(840,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.AT_MOST))
                controls.layout(0,0,840,controls.measuredHeight)
            }
            refresh()
            check(find(controls,"自定义遮罩颜色")!=null)
            check(find(controls,"绘图颜色 白色")==null && find(controls,"作用方式 PEN")==null)
            find(controls,"遮罩颜色 红色")!!.performClick()
            check(layer.style.color==0xFFF5222D.toInt())
            check(layer.history.items.all { it.color==0xFFF5222D.toInt() }) { "遮罩颜色没有全局更新" }
            layer.undo(); check(layer.history.items.all { it.color==Color.BLACK })
            layer.redo()
            check(ScreenshotToolMemory(context).style(ScreenshotDrawingTool.SPOTLIGHT)?.color==layer.style.color)
            refresh()
            val opacity=find(controls,"透明度") as SeekBar
            check(opacity.max==100)
            check(opacity.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                android.os.Bundle().apply { putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,25f) }))
            check(layer.style.opacity==.25f)
            layer.tool=ScreenshotDrawingTool.PEN
            check(layer.style.opacity!=.25f) { "聚光灯参数污染画笔" }
        } finally { layer.close(); renderer.close(); image.recycle() }
    }
}
