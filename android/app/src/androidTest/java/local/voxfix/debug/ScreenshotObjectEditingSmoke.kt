package local.voxfix.debug

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Re-selects and transforms already-drawn objects through the same touch entry the phone uses.
 *
 * The cases here are the reported failures: a tap on a selected box's empty interior dropped the
 * selection, objects that stick out of the current capture box could not be moved at all, an object
 * that overlaps a larger one could not be reached, and finishing a text mark did not leave the user
 * on that object.
 */
object ScreenshotObjectEditingSmoke {
    fun run(test: Instrumentation): String {
        var error: Throwable? = null
        test.runOnMainSync { try { ScreenshotConsistencyChecks.run(test.targetContext); editing(test.targetContext) } catch (e: Throwable) { error = e } }
        error?.let { throw it }
        return "SCREENSHOT_OBJECT_EDITING_PASS: interior-drag,oversized-move,overlap-cycle,select-delete,text-select,handle-resize,outside-draw,outside-drag-in"
    }

    private fun editing(context: Context) {
        val source = Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val layer = ScreenshotDrawingLayer(context,source) {}
        try {
            layer.layout(0,0,600,900)
            layer.region = Rect(0,0,600,900)
            fun touch(action: Int,x: Float,y: Float) {
                val event = MotionEvent.obtain(1,2,action,x,y,0)
                try { check(layer.onTouchEvent(event)) } finally { event.recycle() }
            }
            fun drag(x: Float,y: Float,toX: Float,toY: Float) {
                touch(MotionEvent.ACTION_DOWN,x,y)
                // Two moves: the first one is inside the touch slop and must not start a transform.
                touch(MotionEvent.ACTION_MOVE,(x+toX)/2,(y+toY)/2)
                touch(MotionEvent.ACTION_MOVE,toX,toY)
                touch(MotionEvent.ACTION_UP,toX,toY)
            }
            layer.tool = ScreenshotDrawingTool.RECTANGLE
            drag(80f,100f,240f,260f)
            check(layer.history.items.size==1) { "rectangle not created" }
            layer.tool = ScreenshotDrawingTool.SELECT
            val renderer = ScreenshotDrawingRenderer(source) { context.resources.displayMetrics.scaledDensity }
            val box = renderer.bounds(layer.history.items.single())

            // 1. The first tap lands on the box interior, which is exactly where a user grabs a
            // selected shape; losing the selection there makes moving impossible.
            drag(box.centerX(),box.centerY(),box.centerX()+50,box.centerY()+50)
            val moved = layer.selected ?: error("点击选中框内部后丢了选择")
            check(moved.points.first()==ScreenshotDrawingPoint(130f,150f)) { "选框内部拖动没有移动对象：${moved.points}" }

            // 2. Shrinking the capture box afterwards leaves the object outside the viewport on both
            // sides; it must stay selectable and must still move.
            layer.region = Rect(200,200,340,340)
            touch(MotionEvent.ACTION_DOWN,270f,250f)
            touch(MotionEvent.ACTION_UP,270f,250f)
            val oversized = layer.selected ?: error("缩小框选后旧标注无法选中")
            check(oversized.points.first()==moved.points.first()) { "缩小框选后选中了别的对象" }
            drag(270f,250f,230f,210f)
            check(layer.selected?.points?.first()==ScreenshotDrawingPoint(90f,110f)) { "越界对象无法移动：${layer.selected?.points}" }
            layer.region = Rect(0,0,600,900)

            // 3. Resize from a corner handle still works after the selection rules changed.
            val beforeResize = layer.selected!!.points
            val handle = renderer.bounds(beforeResize.let {
                ScreenshotDrawingStroke(ScreenshotDrawingTool.RECTANGLE,it,0,4f)
            })
            touch(MotionEvent.ACTION_DOWN,handle.right,handle.bottom)
            touch(MotionEvent.ACTION_MOVE,handle.right+60,handle.bottom+60)
            touch(MotionEvent.ACTION_UP,handle.right+60,handle.bottom+60)
            check(layer.selected!!.points.last().x>beforeResize.last().x+40) { "角点缩放失效" }

            // 4. Deleting the selected object must be reachable without switching to the eraser.
            layer.deleteSelected()
            check(layer.history.items.isEmpty()) { "SELECT 下无法删除选中对象" }
            layer.undo()

            // 5. A larger object added later must not trap the smaller one underneath it.
            layer.history.remove(layer.history.items.indices.toSet())
            layer.tool = ScreenshotDrawingTool.SPOTLIGHT
            drag(100f,100f,400f,400f)
            layer.tool = ScreenshotDrawingTool.ARROW
            drag(150f,150f,350f,350f)
            layer.tool = ScreenshotDrawingTool.SELECT
            touch(MotionEvent.ACTION_DOWN,250f,250f)
            touch(MotionEvent.ACTION_UP,250f,250f)
            check(layer.selected?.tool==ScreenshotDrawingTool.ARROW) { "重叠时未选中上层箭头" }
            check(layer.overlapCount>1) { "重叠候选未计算" }
            layer.cycleSelection()
            check(layer.selected?.tool==ScreenshotDrawingTool.SPOTLIGHT) { "无法切换到重叠的聚焦对象" }

            // 6. Finishing a text mark leaves that object selected instead of staying in draw mode.
            layer.history.remove(layer.history.items.indices.toSet())
            layer.tool = ScreenshotDrawingTool.TEXT
            layer.onTextRequest = { point,index -> layer.addText(point,"标注",index) }
            drag(120f,600f,120f,600f)
            check(layer.tool==ScreenshotDrawingTool.SELECT) { "文字完成后没有回到选择模式" }
            check(layer.selected?.text=="标注") { "文字完成后未选中新对象" }
            // 7. Drawing may start outside the capture box; only what is inside the box is exported.
            val outside = Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val outsideLayer = ScreenshotDrawingLayer(context,outside) {}
            try {
                outsideLayer.layout(0,0,600,900)
                outsideLayer.region = Rect(200,200,400,400)
                outsideLayer.tool = ScreenshotDrawingTool.PEN
                val down = MotionEvent.obtain(1,2,MotionEvent.ACTION_DOWN,20f,20f,0)
                val move = MotionEvent.obtain(1,3,MotionEvent.ACTION_MOVE,120f,120f,0)
                val up = MotionEvent.obtain(1,4,MotionEvent.ACTION_UP,120f,120f,0)
                try { outsideLayer.onTouchEvent(down); outsideLayer.onTouchEvent(move); outsideLayer.onTouchEvent(up) }
                finally { down.recycle(); move.recycle(); up.recycle() }
                val stroke = outsideLayer.history.items.singleOrNull() ?: error("框外起笔被丢弃")
                check(stroke.points.any { it.x < 200f }) { "框外笔画被限制在框内：${stroke.points}" }
                val exported = outsideLayer.render(Rect(200,200,400,400),null) ?: error("导出失败")
                try {
                    check(exported.width==200 && exported.height==200) { "导出尺寸不是选框：${exported.width}x${exported.height}" }
                    var touched = 0
                    for (x in 0 until exported.width step 4) for (y in 0 until exported.height step 4)
                        if (exported.getPixel(x,y)!=Color.WHITE) touched++
                    check(touched==0) { "框外笔画被写进了导出图片：$touched 个像素" }
                } finally { exported.recycle() }
                // The larger working area is the point: draw outside, then drag it into the box.
                outsideLayer.tool = ScreenshotDrawingTool.SELECT
                fun touch2(action: Int,x: Float,y: Float) {
                    val event = MotionEvent.obtain(1,2,action,x,y,0)
                    try { check(outsideLayer.onTouchEvent(event)) } finally { event.recycle() }
                }
                touch2(MotionEvent.ACTION_DOWN,70f,70f)
                touch2(MotionEvent.ACTION_MOVE,100f,100f)
                touch2(MotionEvent.ACTION_MOVE,330f,330f)
                touch2(MotionEvent.ACTION_UP,330f,330f)
                val movedInto = outsideLayer.selected ?: error("框外对象无法选中")
                check(movedInto.points.first()==ScreenshotDrawingPoint(280f,280f)) { "框外对象拖不进框：${movedInto.points}" }
                val inside = outsideLayer.render(Rect(200,200,400,400),null) ?: error("导出失败")
                try {
                    var painted = 0
                    for (x in 0 until inside.width step 4) for (y in 0 until inside.height step 4)
                        if (inside.getPixel(x,y)!=Color.WHITE) painted++
                    check(painted>0) { "拖进框内的笔画没有被导出" }
                } finally { inside.recycle() }
                check(outside.getPixel(20,20)==Color.WHITE) { "导出改写了原始截图" }
            } finally { outsideLayer.close(); outside.recycle() }
            renderer.close()
        } finally { layer.close(); source.recycle() }
    }
}
