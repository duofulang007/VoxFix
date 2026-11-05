package local.voxfix.debug

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs

internal object ScreenshotObjectSelectionChecks {
    fun run(context: Context) {
        val image=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val renderer=ScreenshotDrawingRenderer(image) { 1f }
        val history=ScreenshotDrawing()
        fun rect(x: Float)=ScreenshotDrawingStroke(ScreenshotDrawingTool.RECTANGLE,
            listOf(ScreenshotDrawingPoint(x,200f),ScreenshotDrawingPoint(x+80,300f)),Color.RED,4f,
            ScreenshotDrawingStyle(color=Color.RED,hasFill=true,fillColor=Color.RED),id=x.toLong())
        fun point(x: Float,y: Float)=ScreenshotDrawingPoint(x,y)
        try {
            history.load(listOf(rect(100f),rect(300f),rect(480f)))
            val selection=ScreenshotObjectSelection(history,renderer,{ 1f },{ 4f })
            // The blank-canvas marquee includes both intersecting objects, not only fully enclosed ones.
            selection.press(point(80f,180f),false,emptyList())
            selection.release(point(340f,320f),false,600,900)
            check(selection.indices==setOf(0,1)) { "marquee ${selection.indices}" }
            val before=history.items
            selection.press(point(240f,250f),false,emptyList())
            selection.move(point(260f,350f),false,600,900)
            check(history.items==before) { "preview entered history" }
            selection.cancel(); check(history.items==before)
            selection.press(point(240f,250f),false,emptyList())
            selection.release(point(260f,350f),false,600,900)
            check(history.items[0].points.first()==point(120f,300f))
            check(history.items[1].points.first()==point(320f,300f))
            check(history.items[2]==before[2])
            history.undo(); check(history.items==before) { "group needs more than one undo" }
            history.redo()
            selection.press(point(500f,250f),true,listOf(2)); check(selection.indices.size==3)
            selection.press(point(500f,250f),true,listOf(2)); check(selection.indices==setOf(0,1))
            history.undo()
            // Rotation uses the initial angle and rotates every member centre around the same pivot.
            selection.press(point(240f,174f),false,emptyList())
            selection.release(point(316f,250f),false,600,900)
            check(history.items.take(2).all { abs(it.rotation-PI.toFloat()/2)<.001f }) { "group rotation" }
            val preview=image.copy(Bitmap.Config.ARGB_8888,true)
            try {
                val canvas=Canvas(preview); renderer.draw(canvas,history.items,Rect(0,0,600,900)); selection.draw(canvas)
                context.cacheDir.resolve("snow-t22-selection.png").outputStream().use {
                    check(preview.compress(Bitmap.CompressFormat.PNG,100,it))
                }
            } finally { preview.recycle() }
            // The group's oriented frame survives release; grabbing its same handle rotates again.
            selection.press(point(316f,250f),false,emptyList())
            selection.release(point(240f,326f),false,600,900)
            check(history.items.take(2).all { abs(abs(it.rotation)-PI.toFloat())<.001f }) { "group frame jumped after release" }
            history.undo()
            history.undo(); check(history.items==before)

            val rotated=rect(100f).copy(rotation=PI.toFloat()/2)
            val output=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            try {
                renderer.draw(Canvas(output),listOf(rotated),Rect(0,0,600,900))
                check(output.getPixel(95,250)==Color.RED && output.getPixel(140,295)==Color.WHITE) { "rotated export" }
                check(renderer.hit(rotated,point(95f,250f),1f,null,false))
                check(!renderer.hit(rotated,point(140f,295f),1f,null,false)) { "unrotated hit survived" }
                val doc=ScreenshotDocument.full(600,900).copy(objects=listOf(rotated))
                val encoded=ScreenshotDocumentCodec.encode(doc)
                val decoded=ScreenshotDocumentCodec.decode(encoded)
                check(abs(decoded.objects.single().rotation-rotated.rotation)<.0001f)
                val legacy=JSONObject(encoded)
                legacy.getJSONArray("objects").getJSONObject(0).remove("rotation")
                check(ScreenshotDocumentCodec.decode(legacy.toString()).objects.single().rotation==0f)
                val bad=JSONObject(encoded)
                bad.getJSONArray("objects").getJSONObject(0).put("rotation","NaN")
                check(runCatching { ScreenshotDocumentCodec.decode(bad.toString()) }.isFailure)
            } finally { output.recycle() }
            val line=ScreenshotDrawingStroke(ScreenshotDrawingTool.LINE,listOf(point(100f,450f),point(240f,450f),point(360f,450f)),Color.RED,4f,id=7201)
            history.load(listOf(line)); selection.select(0)
            selection.press(point(102f,452f),false,listOf(0))
            selection.release(point(142f,502f),false,600,900)
            check(history.items.single().points==listOf(point(140f,500f),point(240f,450f),point(360f,450f))) { "point drag changed other vertices" }
            history.undo(); check(history.items.single()==line)
            val tilted=line.copy(rotation=.5f)
            val box=renderer.localBounds(tilted); val pivot=point(box.centerX(),box.centerY())
            val world=tilted.points.map { ScreenshotObjectTransform.rotate(it,pivot,tilted.rotation) }.toMutableList()
            world[0]=point(60f,520f)
            val rebased=renderer.withWorldPoints(tilted,world)
            val nextBox=renderer.localBounds(rebased); val nextPivot=point(nextBox.centerX(),nextBox.centerY())
            check(rebased.rotation==tilted.rotation)
            rebased.points.forEachIndexed { index,p ->
                val actual=ScreenshotObjectTransform.rotate(p,nextPivot,rebased.rotation)
                check(abs(actual.x-world[index].x)<.001f && abs(actual.y-world[index].y)<.001f) { "rotated point rebase" }
            }
            // The public touch path must actually select and delete a group, including in pin editors.
            val layer=ScreenshotDrawingLayer(context,image) {}
            try {
                layer.layout(0,0,600,900); layer.region=Rect(0,0,600,900); layer.tool=ScreenshotDrawingTool.SELECT
                layer.history.load(before)
                fun touch(action: Int,x: Float,y: Float) {
                    val event=MotionEvent.obtain(1,2,action,x,y,0)
                    try { check(layer.onTouchEvent(event)) } finally { event.recycle() }
                }
                touch(MotionEvent.ACTION_DOWN,80f,180f); touch(MotionEvent.ACTION_UP,340f,320f)
                check(layer.selectedIndices==setOf(0,1))
                layer.deleteSelected(); check(layer.history.items.size==1)
                layer.undo(); check(layer.history.items==before)
                val pen=ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN,listOf(point(100f,650f),point(200f,650f),point(250f,700f)),
                    Color.RED,4f,smooth=true,id=7301)
                layer.history.load(listOf(pen)); layer.tool=ScreenshotDrawingTool.PEN
                touch(MotionEvent.ACTION_DOWN,252f,702f); touch(MotionEvent.ACTION_UP,252f,702f)
                check(layer.history.items.single()==pen && !layer.history.canUndo) { "endpoint tap edited pen" }
                touch(MotionEvent.ACTION_DOWN,252f,702f); touch(MotionEvent.ACTION_MOVE,340f,750f)
                touch(MotionEvent.ACTION_CANCEL,340f,750f)
                check(layer.history.items.single()==pen && !layer.history.canUndo) { "cancelled continuation committed" }
                touch(MotionEvent.ACTION_DOWN,252f,702f); touch(MotionEvent.ACTION_MOVE,340f,750f)
                touch(MotionEvent.ACTION_UP,340f,750f)
                val continued=layer.history.items.single()
                check(continued.id==pen.id && continued.points.take(pen.points.size)==pen.points && continued.points.last()==point(340f,750f))
                layer.undo(); check(layer.history.items.single()==pen) { "continuation is not atomic" }
                touch(MotionEvent.ACTION_DOWN,100f,650f); touch(MotionEvent.ACTION_MOVE,40f,600f)
                touch(MotionEvent.ACTION_UP,40f,600f)
                check(layer.history.items.single().points.first()==point(40f,600f))
                check(layer.history.items.single().points.takeLast(pen.points.size)==pen.points) { "start continuation reversed original" }
            } finally { layer.close() }
        } finally { renderer.close(); image.recycle() }
        rotatedEffects()
    }
    private fun rotatedEffects() {
        val source=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
        for(y in 0 until 128) for(x in 0 until 128) source.setPixel(x,y,Color.rgb((x*17+y*31)%256,(x*47+y*13)%256,(x*7+y*53)%256))
        val renderer=ScreenshotDrawingRenderer(source) { 1f }
        try {
            for(type in ScreenshotFilter.values()) {
                val stroke=ScreenshotDrawingStroke(ScreenshotDrawingTool.FILTER,
                    listOf(ScreenshotDrawingPoint(40f,50f),ScreenshotDrawingPoint(80f,70f)),Color.RED,4f,
                    ScreenshotDrawingStyle(filter=type,strength=12),rotation=PI.toFloat()/2)
                val output=source.copy(Bitmap.Config.ARGB_8888,true)
                try {
                    renderer.draw(Canvas(output),listOf(stroke),Rect(0,0,128,128))
                    check((52..68).sumOf { x -> (42..48).count { y -> output.getPixel(x,y)!=source.getPixel(x,y) } }>20) { "rotated filter misses new region $type" }
                    check((42..47).all { x -> (52..68).all { y -> output.getPixel(x,y)==source.getPixel(x,y) } }) { "rotated filter changes old-only region $type" }
                } finally { output.recycle() }
            }
            val spot=ScreenshotDrawingStroke(ScreenshotDrawingTool.SPOTLIGHT,
                listOf(ScreenshotDrawingPoint(40f,50f),ScreenshotDrawingPoint(80f,70f)),Color.BLACK,4f,
                defaultScreenshotDrawingStyle(ScreenshotDrawingTool.SPOTLIGHT),rotation=PI.toFloat()/2)
            val output=source.copy(Bitmap.Config.ARGB_8888,true)
            try {
                renderer.draw(Canvas(output),listOf(spot),Rect(0,0,128,128))
                check(output.getPixel(60,40)==source.getPixel(60,40)) { "spotlight rotated hole missing" }
                check(output.getPixel(40,60)!=source.getPixel(40,60)) { "spotlight kept old hole" }
            } finally { output.recycle() }
        } finally { renderer.close(); source.recycle() }
    }
}
