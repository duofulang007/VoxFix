package local.voxfix.debug

import android.app.Instrumentation
import android.graphics.*
import android.view.*
import android.widget.LinearLayout
import local.voxfix.debug.ScreenshotStyleProperty.*

internal object ScreenshotSelectionStyleChecks {
    private fun find(v: View,label: String): View? {
        if(v.contentDescription==label) return v
        if(v is ViewGroup) for(i in 0 until v.childCount) find(v.getChildAt(i),label)?.let { return it }
        return null
    }
    fun run(test: Instrumentation): String {
        var failure: Throwable?=null
        test.runOnMainSync {
            val image=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val layer=ScreenshotDrawingLayer(test.targetContext,image) {}
            fun point(x: Float,y: Float)=ScreenshotDrawingPoint(x,y)
            fun touch(action: Int,x: Float,y: Float) {
                val e=MotionEvent.obtain(1,2,action,x,y,0)
                try { check(layer.onTouchEvent(e)) } finally { e.recycle() }
            }
            fun select() { touch(MotionEvent.ACTION_DOWN,30f,80f); touch(MotionEvent.ACTION_UP,550f,600f); check(layer.selectedIndices.size==3) }
            fun controls()=ScreenshotDrawingControls(test.targetContext,layer,{ _,_,_ -> })
            try {
                layer.layout(0,0,600,900); layer.region=Rect(0,0,600,900)
                layer.tool=ScreenshotDrawingTool.RECTANGLE
                layer.applyStyle(layer.style.copy(color=Color.GREEN,width=8))
                val creationStyle=layer.style
                layer.tool=ScreenshotDrawingTool.SELECT
                val a=ScreenshotDrawingStroke(ScreenshotDrawingTool.RECTANGLE,listOf(point(80f,130f),point(220f,240f)),Color.RED,2f,
                    ScreenshotDrawingStyle(color=Color.RED,width=2,hasFill=true,fillColor=Color.YELLOW),id=9101)
                val b=a.copy(points=listOf(point(300f,130f),point(440f,240f)),color=Color.BLUE,width=7f,
                    style=a.style.copy(color=Color.BLUE,width=7,fillColor=Color.CYAN,cornerRadius=11,strokePattern=ScreenshotStrokePattern.DASHED),id=9102)
                val pen=a.copy(tool=ScreenshotDrawingTool.PEN,points=listOf(point(100f,350f),point(450f,480f)),id=9103)
                layer.savedPixelUnit=1f; layer.savedFontUnit=1f
                layer.history.load(listOf(a,b,pen)); select()
                val before=layer.history.items
                check(layer.inspectedTool==ScreenshotDrawingTool.RECTANGLE && layer.isStyleMixed(COLOR,WIDTH))
                val ui=controls()
                check(find(ScreenshotDrawingControls(test.targetContext,layer,{ _,_,_ -> },showSelectActions=false),"删除标注")!=null) { "overlay lost multi-delete" }
                check(find(ui,"描边颜色 红色")?.isSelected==false) { "Mixed falsely checked primary red" }
                check(find(ui,"描边颜色与样式")?.stateDescription=="Mixed")
                check(find(ui,"描边颜色 红色")!!.performClick())
                check(layer.history.items[0].color==0xFFF5222D.toInt() && layer.history.items[1].color==0xFFF5222D.toInt())
                check(layer.history.items[1].style.fillColor==b.style.fillColor && layer.history.items[1].width==b.width)
                check(layer.history.items[2]==pen)
                layer.undo(); select(); check(layer.history.items==before) { "batch not one undo" }
                layer.redo(); layer.undo(); select()
                // Explicitly choosing the representative value must still update the other object.
                layer.applyStyle(layer.inspectedStyle,COLOR)
                check(layer.history.items[1].color==Color.RED)
                layer.undo(); select(); check(layer.history.items==before)
                layer.beginStylePreview(); layer.previewStyle(layer.inspectedStyle.copy(color=Color.MAGENTA),COLOR)
                layer.previewStyle(layer.inspectedStyle.copy(color=Color.MAGENTA,strokePattern=ScreenshotStrokePattern.DOTTED),STROKE_PATTERN)
                check(layer.history.items==before)
                val preview=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888)
                try { layer.draw(Canvas(preview)) } finally { preview.recycle() }
                layer.finishStylePreview(false); check(layer.history.items==before && !layer.history.canUndo)
                layer.beginStylePreview(); layer.previewStyle(layer.inspectedStyle.copy(color=Color.MAGENTA),COLOR)
                layer.previewStyle(layer.inspectedStyle.copy(color=Color.MAGENTA,strokePattern=ScreenshotStrokePattern.DOTTED),STROKE_PATTERN)
                layer.finishStylePreview(true)
                check(layer.history.items.take(2).all { it.color==Color.MAGENTA && it.style.strokePattern==ScreenshotStrokePattern.DOTTED })
                layer.undo(); select(); check(layer.history.items==before)
                layer.tool=ScreenshotDrawingTool.RECTANGLE; check(layer.style==creationStyle) { "selection polluted creation defaults" }
                layer.tool=ScreenshotDrawingTool.SELECT
                // Font edits preserve scaled outlines/backgrounds and survive document reopen.
                val text=a.copy(tool=ScreenshotDrawingTool.TEXT,text="固定文字",fontScale=2f,
                    style=a.style.copy(fontSize=30f,outlineWidth=4,cornerRadius=5))
                layer.history.load(listOf(text,text.copy(points=b.points,style=text.style.copy(fontSize=24f),id=9102),pen)); select()
                check(layer.inspectedTool==ScreenshotDrawingTool.TEXT)
                check(find(controls(),"字号 -")!=null)
                layer.applyStyle(layer.inspectedStyle.copy(fontSize=6f),FONT_SIZE)
                check(layer.history.items.take(2).all { it.style.fontSize*it.fontScale==6f && it.style.outlineWidth==4 && it.style.cornerRadius==5 })
                val doc=ScreenshotDocument.full(600,900).copy(objects=layer.history.items)
                val decoded=ScreenshotDocumentCodec.decode(ScreenshotDocumentCodec.encode(doc))
                check(decoded.objects==layer.history.items) { "scaled font changed on reopen" }
                layer.undo(); select(); check(layer.history.items[0]==text)
                // No-number serials retain their hidden number and family in mixed-type edits.
                val numbered=a.copy(tool=ScreenshotDrawingTool.SERIAL,number=8,style=a.style.copy(serialGeometryVersion=1))
                val circle=numbered.copy(points=b.points,number=23,style=numbered.style.copy(serialShape=ScreenshotSerialShape.CIRCLE,fontFamily="serif"),id=9102)
                layer.history.load(listOf(numbered,circle,pen)); select()
                check(layer.mixedNumber)
                layer.setNumber(19); check(layer.history.items[0].number==19L && layer.history.items[1].number==23L)
                layer.applyStyle(layer.inspectedStyle.copy(fontFamily="monospace"),FONT_FAMILY)
                check(layer.history.items[0].style.fontFamily=="monospace" && layer.history.items[1].style.fontFamily=="serif")
                layer.undo(); select(); layer.undo(); select(); check(layer.history.items[0]==numbered && layer.history.items[1]==circle)
                // Synthetic control snapshot contains no screenshot/user data.
                val panel=controls()
                panel.measure(View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1800,View.MeasureSpec.AT_MOST))
                panel.layout(0,0,panel.measuredWidth,panel.measuredHeight)
                val bitmap=Bitmap.createBitmap(panel.width,panel.height,Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(0xFF1C1C1E.toInt()); panel.draw(Canvas(bitmap))
                    test.targetContext.cacheDir.resolve("snow-t22-mixed-controls.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
                } finally { bitmap.recycle() }
            } catch(e: Throwable) { failure=e }
            finally { layer.close(); image.recycle() }
        }
        failure?.let { throw it }
        return "SCREENSHOT_SELECTION_STYLE_PASS: category,mixed,explicit-property,one-undo,cancel,preview-union,defaults,scaled-font,reopen,serial,controls"
    }
}
