package local.voxfix.debug

import android.app.Instrumentation
import android.graphics.*
import kotlin.math.abs

/** Ready for the next USB session; a build does not establish these device assertions passed. */
internal object ScreenshotSelectionResizeChecks {
    fun run(test: Instrumentation): String {
        var failure: Throwable?=null
        test.runOnMainSync {
            val image=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val renderer=ScreenshotDrawingRenderer(image) { 1f }
            val history=ScreenshotDrawing()
            val selection=ScreenshotObjectSelection(history,renderer,{ 1f },{ 4f })
            fun p(x: Float,y: Float)=ScreenshotDrawingPoint(x,y)
            fun rect(x: Float)=ScreenshotDrawingStroke(ScreenshotDrawingTool.RECTANGLE,listOf(p(x,100f),p(x+100,200f)),Color.RED,4f,
                ScreenshotDrawingStyle(color=Color.RED),id=x.toLong())
            fun close(a: Float,b: Float)=check(abs(a-b)<.01f) { "$a != $b" }
            try {
                val a=rect(100f); val b=rect(300f)
                history.load(listOf(a,b)); selection.select(0)
                // Grab three pixels off the right-edge centre: there must be no initial jump.
                selection.press(p(207f,150f),false,emptyList())
                selection.move(p(207f,150f),false,600,900); check(selection.preview.isEmpty())
                selection.release(p(307f,150f),false,600,900)
                close(history.items[0].points[0].x,100f); close(history.items[0].points[1].x,300f)
                close(history.items[0].width,4f); check(history.items[1]==b)
                history.undo(); check(history.items==listOf(a,b))
                // Cross the opposite edge: signed frame scale, positive dimensions, one undo.
                selection.select(0); selection.press(p(204f,150f),false,emptyList())
                selection.release(p(-96f,150f),false,600,900)
                close(history.items[0].points[0].x,-100f); close(history.items[0].points[1].x,100f)
                close(history.items[0].points[0].y,100f); close(history.items[0].width,4f)
                history.undo(); check(history.items==listOf(a,b))
                selection.select(0); selection.press(p(96f,96f),false,emptyList())
                selection.move(p(46f,46f),false,600,900); check(selection.preview.isNotEmpty())
                selection.cancel(); check(history.items==listOf(a,b) && !history.canUndo)
                selection.press(p(50f,50f),false,emptyList()); selection.release(p(450f,250f),false,600,900)
                check(selection.indices==setOf(0,1))
                selection.press(p(404f,150f),false,emptyList()); selection.release(p(554f,150f),false,600,900)
                close(history.items[0].points[0].x,100f); close(history.items[0].points[1].x,250f)
                close(history.items[1].points[0].x,400f); close(history.items[1].points[1].x,550f)
                check(history.items.map { it.id }==listOf(a.id,b.id)); check(history.items.all { it.width==4f })
                history.undo(); check(history.items==listOf(a,b)); history.redo()
                val output=image.copy(Bitmap.Config.ARGB_8888,true)
                try { renderer.draw(Canvas(output),history.items,Rect(0,0,600,900)); selection.draw(Canvas(output)) }
                finally { output.recycle() }
                // Single text edges reflow with the real Android host measurement, not estimates.
                val text=a.copy(tool=ScreenshotDrawingTool.TEXT,points=listOf(p(100f,300f)),text="固定文字固定文字固定文字固定文字",
                    textWidth=240f,style=a.style.copy(fontSize=30f,outlineWidth=2,cornerRadius=5))
                history.load(listOf(text)); selection.select(0)
                val old=renderer.selectionBounds(text)
                selection.press(p(old.right+14,old.centerY()),false,emptyList())
                selection.release(p(old.right+14-120,old.centerY()),false,600,900)
                val wrapped=history.items.single(); val wrappedBox=renderer.selectionBounds(wrapped)
                close(wrapped.textWidth,120f); close(wrapped.style.fontSize,30f)
                check(wrappedBox.height()>old.height()); close(wrappedBox.left,old.left)
                check(wrapped.style.outlineWidth==2 && wrapped.style.cornerRadius==5)
                history.undo(); check(history.items.single()==text)
                val large=text.copy(style=text.style.copy(fontSize=300f),text="大",textWidth=500f)
                history.load(listOf(large)); selection.select(0)
                val largeBox=renderer.selectionBounds(large)
                selection.press(p(largeBox.centerX(),largeBox.bottom+14),false,emptyList())
                selection.release(p(largeBox.centerX(),largeBox.bottom+14+largeBox.height()),false,600,900)
                val enlarged=history.items.single()
                close(enlarged.style.fontSize,600f)
                check(enlarged.style.outlineWidth==2 && enlarged.style.cornerRadius==5)
                val reopened=ScreenshotDocumentCodec.decode(ScreenshotDocumentCodec.encode(ScreenshotDocument.full(600,900).copy(objects=listOf(enlarged))))
                check(reopened.objects.single()==enlarged) { "resize above manual font limit changed on reopen" }
                // The selection proxy excludes modern serial stroke expansion and arrowhead paint.
                val serial=a.copy(tool=ScreenshotDrawingTool.SERIAL,points=listOf(p(300f,400f)),style=a.style.copy(fontSize=24f,serialGeometryVersion=1))
                val serialBox=renderer.selectionBounds(serial); val paintBox=renderer.localBounds(serial)
                check(serialBox.width()<paintBox.width())
                history.load(listOf(serial)); selection.select(0)
                selection.press(p(serialBox.right+4,serialBox.centerY()),false,emptyList())
                selection.release(p(serialBox.left-100,serialBox.centerY()),false,600,900)
                close(history.items.single().style.fontSize,6f)
                val arrow=a.copy(tool=ScreenshotDrawingTool.ARROW,points=listOf(p(100f,600f),p(400f,600f)),width=20f,
                    style=a.style.copy(endHead=ScreenshotArrowhead.TRIANGLE))
                close(renderer.selectionBounds(arrow).width(),300f)
                check(renderer.localBounds(arrow).height()>renderer.selectionBounds(arrow).height())
            } catch(e: Throwable) { failure=e }
            finally { renderer.close(); image.recycle() }
        }
        failure?.let { throw it }
        return "SCREENSHOT_SELECTION_RESIZE_PASS: edge,offset,flip,group,cancel,one-undo,identity,stroke,text-reflow,text-font,reopen,serial-min,proxy"
    }
}
