package local.voxfix.debug

import android.app.Instrumentation
import android.graphics.*
import kotlin.math.abs

/** Compiled locally; queued for the user's next USB session. */
internal object ScreenshotLinearHandlesChecks {
    fun run(test: Instrumentation): String {
        var failure: Throwable?=null
        test.runOnMainSync {
            val image=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val renderer=ScreenshotDrawingRenderer(image) { 1f }
            val history=ScreenshotDrawing()
            val selection=ScreenshotObjectSelection(history,renderer,{ 1f },{ 4f })
            fun p(x: Float,y: Float)=ScreenshotDrawingPoint(x,y)
            fun close(a: ScreenshotDrawingPoint,b: ScreenshotDrawingPoint)=check(abs(a.x-b.x)<.001f && abs(a.y-b.y)<.001f) { "$a != $b" }
            val line=ScreenshotDrawingStroke(ScreenshotDrawingTool.LINE,listOf(p(100f,300f),p(400f,300f)),Color.RED,4f,
                ScreenshotDrawingStyle(color=Color.RED,width=4),id=9401)
            try {
                history.load(listOf(line)); selection.select(0)
                // Click and subthreshold release do not create a point or undo entry.
                selection.press(p(253f,302f),false,listOf(0)); selection.release(p(253f,311f),false,600,900)
                check(history.items.single()==line && !history.canUndo)
                // The offset between the finger and midpoint is retained.
                selection.press(p(253f,302f),false,listOf(0)); selection.move(p(273f,352f),false,600,900)
                check(history.items.single()==line && selection.preview[0]!!.points.size==3)
                close(renderer.worldPoints(selection.preview[0]!!)[1],p(270f,350f))
                selection.cancel(); check(history.items.single()==line && !history.canUndo)
                selection.press(p(253f,302f),false,listOf(0)); selection.release(p(273f,352f),false,600,900)
                val inserted=history.items.single()
                check(inserted.id==line.id && inserted.style==line.style && inserted.width==line.width)
                check(inserted.points==listOf(p(100f,300f),p(270f,350f),p(400f,300f)))
                history.undo(); check(history.items.single()==line)
                history.redo(); check(history.items.single()==inserted)
                // Every preview starts from the original: moving back below threshold stays a no-op.
                history.load(listOf(line)); selection.select(0)
                selection.press(p(250f,300f),false,listOf(0)); selection.move(p(250f,350f),false,600,900)
                selection.release(p(250f,309f),false,600,900)
                check(history.items.single()==line && !history.canUndo)
                // A rotated path must insert at the actual world midpoint, then survive reopening.
                val rotated=line.copy(rotation=.7f)
                history.load(listOf(rotated)); selection.select(0)
                val world=renderer.worldPoints(rotated); val middle=p((world[0].x+world[1].x)/2,(world[0].y+world[1].y)/2)
                selection.press(middle,false,listOf(0)); selection.release(p(middle.x+20,middle.y+50),false,600,900)
                val updated=history.items.single()
                check(updated.rotation==rotated.rotation && updated.id==rotated.id)
                close(renderer.worldPoints(updated)[1],p(middle.x+20,middle.y+50))
                val doc=ScreenshotDocument.full(600,900).copy(objects=listOf(updated))
                check(ScreenshotDocumentCodec.decode(ScreenshotDocumentCodec.encode(doc)).objects.single()==updated)
                // Both overlapping closed endpoints remain reachable by the two ring hit zones.
                val loop=line.copy(points=listOf(p(100f,500f),p(250f,500f),p(250f,650f),p(100f,500f)))
                history.load(listOf(loop)); selection.select(0)
                selection.press(p(107f,500f),false,listOf(0)); selection.release(p(127f,530f),false,600,900)
                check(history.items.single().points.first()==loop.points.first())
                close(history.items.single().points.last(),p(120f,530f))
                history.undo(); selection.select(0)
                selection.press(p(100f,500f),false,listOf(0)); selection.release(p(120f,530f),false,600,900)
                check(history.items.single().points.last()==loop.points.last())
                close(history.items.single().points.first(),p(120f,530f))
                history.undo(); selection.select(0)
                val snapshot=image.copy(Bitmap.Config.ARGB_8888,true)
                try {
                    renderer.draw(Canvas(snapshot),history.items,Rect(0,0,600,900)); selection.draw(Canvas(snapshot))
                    test.targetContext.cacheDir.resolve("snow-t22-linear-handles.png").outputStream().use { check(snapshot.compress(Bitmap.CompressFormat.PNG,100,it)) }
                } finally { snapshot.recycle() }
                val elbow=line.copy(tool=ScreenshotDrawingTool.ARROW,
                    style=line.style.copy(arrowType=ScreenshotArrowType.ELBOW),
                    points=listOf(p(100f,700f),p(400f,700f)))
                history.load(listOf(elbow)); selection.select(0)
                selection.press(p(250f,700f),false,listOf(0))
                selection.move(p(250f,750f),false,600,900)
                val visible=selection.preview.getValue(0)
                check(visible.points==listOf(p(100f,700f),p(100f,750f),p(400f,750f),p(400f,700f)))
                check(visible.fixedElbowSegments==setOf(2))
                selection.cancel()
                check(history.items.single()==elbow && !history.canUndo)
                selection.press(p(250f,700f),false,listOf(0))
                selection.move(p(250f,750f),false,600,900)
                selection.release(p(250f,750f),false,600,900)
                check(history.items.single()==visible && history.items.single().id==elbow.id)
                history.undo(); check(history.items.single()==elbow)
                history.redo(); check(history.items.single()==visible)
                val fixedDoc=ScreenshotDocument.full(600,900).copy(objects=listOf(visible))
                val encoded=ScreenshotDocumentCodec.encode(fixedDoc)
                check(ScreenshotDocumentCodec.decode(encoded).objects.single()==visible)
                val invalid=org.json.JSONObject(encoded)
                invalid.getJSONArray("objects").getJSONObject(0)
                    .put("fixedElbowSegments",org.json.JSONArray(listOf(4)))
                check(runCatching { ScreenshotDocumentCodec.decode(invalid.toString()) }.exceptionOrNull()
                    is ScreenshotDocumentCodec.FormatException)
                val endpoint = p(430f,730f)
                selection.press(visible.points.last(),false,listOf(0))
                selection.move(endpoint,false,600,900)
                val reconnected = selection.preview.getValue(0)
                check(reconnected.points==listOf(p(100f,700f),p(100f,750f),p(430f,750f),endpoint))
                check(reconnected.fixedElbowSegments==setOf(2))
                selection.cancel()
                check(history.items.single()==visible)
                selection.press(visible.points.last(),false,listOf(0))
                selection.release(endpoint,false,600,900)
                check(history.items.single()==reconnected)
                history.undo(); check(history.items.single()==visible)
                history.redo(); check(history.items.single()==reconnected)
                val endpointDoc = fixedDoc.copy(objects=listOf(reconnected))
                check(ScreenshotDocumentCodec.decode(ScreenshotDocumentCodec.encode(endpointDoc)).objects.single()==reconnected)
            } catch(e: Throwable) { failure=e }
            finally { renderer.close(); image.recycle() }
        }
        failure?.let { throw it }
        return "SCREENSHOT_LINEAR_HANDLES_PASS: midpoint,threshold,offset,cancel,insert,one-undo,return-noop,rotated,reopen,loop-start,loop-end,render,elbow-preview,elbow-reopen,elbow-endpoint"
    }
}
