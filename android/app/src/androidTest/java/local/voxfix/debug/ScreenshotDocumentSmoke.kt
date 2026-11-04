package local.voxfix.debug

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/**
 * Project document round trip. org.json only exists on the device, so this runs as a native smoke
 * instead of a JVM unit test; the assertions are ordinary `check` failures with a readable reason.
 */
object ScreenshotDocumentSmoke {
    fun run(test: Instrumentation): String {
        var error: Throwable? = null
        test.runOnMainSync { try { documents(); textBlocks(test.targetContext) } catch (e: Throwable) { error = e } }
        error?.let { throw it }
        return "SCREENSHOT_DOCUMENT_PASS: round-trip,stable-ids,unknown-version,invalid-values,bounded-input,block-move,block-undo"
    }

    private fun document() = ScreenshotDocument(
        canvasWidth = 1080, canvasHeight = 2400,
        viewport = ScreenshotDocRect(60, 300, 1000, 1500),
        objects = listOf(
            ScreenshotDrawingStroke(ScreenshotDrawingTool.ARROW,
                listOf(ScreenshotDrawingPoint(120f, 400f), ScreenshotDrawingPoint(700.5f, 900.25f)),
                0xFFFF0000.toInt(), 6f,
                ScreenshotDrawingStyle(color = 0xFFFF0000.toInt(), width = 6, opacity = .8f, outlineColor = 1, outlineWidth = 3),
                id = 11),
            ScreenshotDrawingStroke(ScreenshotDrawingTool.TEXT, listOf(ScreenshotDrawingPoint(200f, 1200f)),
                0xFF00FF00.toInt(), 2f,
                ScreenshotDrawingStyle(color = 0xFF00FF00.toInt(), fontSize = 32, bold = true, align = 2,
                    fontFamily = "serif", verticalAlign = 1, hasFill = true, fillColor = 0xFF223344.toInt(), cornerRadius = 8),
                text = "第一行\n第二行", textWidth = 480f, fontScale = 1.5f, textHeight = 90f, id = 12),
            ScreenshotDrawingStroke(ScreenshotDrawingTool.FILTER,
                listOf(ScreenshotDrawingPoint(300f, 500f), ScreenshotDrawingPoint(600f, 800f)),
                0, 24f, ScreenshotDrawingStyle(width = 24, filter = ScreenshotFilter.BLUR, strength = 40, area = ScreenshotAreaMode.PEN), id = 13)
        ),
        text = ScreenshotDocumentText(
            blocks = listOf(ScreenshotTextBlock(70, 320, 500, 380, "识别原文"), ScreenshotTextBlock(70, 400, 480, 460, "第二段")),
            translation = listOf(ScreenshotTextBlock(70, 320, 500, 380, "Recognised")),
            mode = ScreenshotTextMode.TRANSLATION,
            translated = true
        )
    )

    private fun documents() {
        val original = document()
        val encoded = ScreenshotDocumentCodec.encode(original)
        val restored = ScreenshotDocumentCodec.decode(encoded)
        check(original == restored) { "工程往返丢失内容" }
        check(restored.objects.map { it.id } == listOf(11L, 12L, 13L)) { "工程对象编号未保留" }
        check(restored.text?.mode == ScreenshotTextMode.TRANSLATION) { "工程未记录译文模式" }
        check(restored.text?.translation?.single()?.text == "Recognised") { "工程丢失译文" }

        val history = ScreenshotDrawing()
        history.load(restored.objects)
        history.add(ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN, listOf(ScreenshotDrawingPoint(1f, 1f)), 0, 2f))
        val ids = history.items.map { it.id }
        check(ids.size == ids.toSet().size) { "重开后新对象编号冲突：$ids" }
        check(ids.last() > 13L) { "重开后新对象编号未递增：${ids.last()}" }

        fun fails(reason: String, json: String): Boolean {
            val error = runCatching { ScreenshotDocumentCodec.decode(json) }.exceptionOrNull()
            check(error is ScreenshotDocumentCodec.FormatException) { "$reason 未被拒绝：$error" }
            return true
        }
        fails("未来版本", encoded.replace("\"version\":1", "\"version\":9"))
        fails("坏 JSON", "{")
        fails("零画布", encoded.replace("\"canvasWidth\":1080", "\"canvasWidth\":0"))
        fails("视窗越界", encoded.replace("[60,300,1000,1500]", "[0,0,4000,4000]"))
        fails("重复编号", encoded.replace("\"id\":11", "\"id\":12"))
        fails("未知类型", encoded.replace("\"tool\":\"ARROW\"", "\"tool\":\"TELEPORT\""))
        val rows = (0 until ScreenshotDocumentCodec.MAX_OBJECTS + 1).joinToString(",") { "{\"id\":${it + 1},\"tool\":\"PEN\",\"points\":[1,1]}" }
        fails("超量对象", "{\"version\":1,\"canvasWidth\":10,\"canvasHeight\":10,\"viewport\":[0,0,10,10],\"objects\":[$rows]}")
    }

    /** Recognised lines are placed by hand: the old pixels are covered and undo restores both. */
    private fun textBlocks(context: android.content.Context) {
        val source = Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val canvas = android.graphics.Canvas(source)
        canvas.drawRect(10f,10f,110f,40f,android.graphics.Paint().apply { color=Color.BLACK })
        val layer = ScreenshotTextLayer(context,source,
            listOf(ScreenshotTextBlock(10,10,110,40,"原文")),{},{})
        try {
            layer.measure(View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY))
            layer.layout(0,0,300,300)
            layer.beginLayoutMode()
            check(layer.isLayoutMode) { "文字块无法进入位置模式" }
            fun touch(action: Int,x: Float,y: Float) {
                val event=MotionEvent.obtain(1,2,action,x,y,0)
                try { layer.onTouchEvent(event) } finally { event.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN,40f,20f)
            touch(MotionEvent.ACTION_MOVE,140f,120f)
            touch(MotionEvent.ACTION_UP,140f,120f)
            val moved = layer.currentBlocks().single()
            check(moved.left==110 && moved.top==110) { "文字块没有移动：$moved" }
            check(moved.origin==ScreenshotDocRect(10,10,110,40)) { "文字块没有记录原始位置：${moved.origin}" }
            check(moved.text=="原文") { "移动文字块改变了内容" }
            val exported = layer.render(Rect(0,0,300,300))
            try {
                check(exported.getPixel(20,20)==Color.WHITE) { "原位置的文字没有被遮盖" }
            } finally { exported.recycle() }
            layer.undo()
            val restored = layer.currentBlocks().single()
            check(restored.left==10 && restored.top==10 && restored.origin==null) { "撤销没有恢复文字块位置：$restored" }
            layer.endLayoutMode()
            check(!layer.isLayoutMode) { "位置模式无法退出" }
        } finally { layer.endEditing(); source.recycle() }
    }
}
