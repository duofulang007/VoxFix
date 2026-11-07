package local.voxfix.debug

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView

/**
 * Device checks for the recording annotation bar (T28 `recordingDrawingMode`).
 *
 * Upstream connects the **whole** screenshot drawing palette to the recording canvas
 * (`screenrecordingcontroller.cpp:585-658`), so the phone bar has to expose every one of those tools,
 * a text mark has to go through the same inline editor the pin editor uses, and the canvas-level
 * spotlight / watermark have to leave pixels on the overlay — that overlay is what the encoder sees.
 *
 * Everything runs off-screen on the overlay view itself: the annotation window is created by the
 * accessibility service, and instrumentation cannot own that token.
 */
internal object ScreenRecordingAnnotationChecks {
    fun run(test: Instrumentation): String {
        val context = test.targetContext
        val density = context.resources.displayMetrics.density
        val width = Math.round(360f * density)
        val height = Math.round(720f * density)
        val view = ScreenRecordingAnnotation(context, width, height)
        try {
            view.setDrawing(true)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)

            val expected = ScreenRecordingAnnotationLayout.tools.map { it.second } +
                listOf("撤销标注", "重做标注", "清空标注", "退出绘制")
            val present = descriptions(view)
            for (label in expected) check(label in present) { "标注工具条缺少「$label」" }

            // A text mark goes: 文字 tool → tap canvas → inline field → 保存 → SELECT tool.
            click(view, "文字")
            check(view.layer.tool == ScreenshotDrawingTool.TEXT) { "点「文字」没有切到文字工具" }
            tap(view.layer, width / 3f, height / 2f)
            val field = find(view) { it.contentDescription == "标注文字" } as? EditText
                ?: error("文字输入框没有出现")
            field.setText("录制标注")
            click(view, "保存")
            val text = view.layer.history.items.firstOrNull {
                it.tool == ScreenshotDrawingTool.TEXT && it.text == "录制标注"
            } ?: error("文字没有作为对象落到画布上")
            check(view.layer.tool == ScreenshotDrawingTool.SELECT) { "提交文字后应切到选择工具" }
            check(text.points.isNotEmpty()) { "文字对象缺少锚点" }

            // Spotlight: outside the hole the mask is opaque black, inside it nothing is painted.
            click(view, "聚光灯")
            drag(view.layer, width * .2f, height * .7f, width * .8f, height * .8f)
            val spotlight = view.layer.history.items.lastOrNull {
                it.tool == ScreenshotDrawingTool.SPOTLIGHT
            } ?: error("聚光灯没有落到画布上")
            val spotFrame = render(view.layer, width, height)
            try {
                val outside = spotFrame.getPixel(Math.round(width * .1f), Math.round(height * .1f))
                val inside = spotFrame.getPixel(Math.round(width * .5f), Math.round(height * .75f))
                check(android.graphics.Color.alpha(outside) > 0) {
                    "聚光灯没有遮住选区外：${Integer.toHexString(outside)}"
                }
                check(inside == 0) { "聚光灯空洞里被涂了色：${Integer.toHexString(inside)}" }
                check(spotlight.style.opacity > 0f) { "聚光灯透明度为 0" }
            } finally {
                spotFrame.recycle()
            }

            // Watermark is canvas-level: it paints even with no annotation object selected.
            click(view, "水印")
            view.layer.setWatermark(ScreenshotWatermark(text = "VoxFix", opacity = .5f))
            val watermarked = render(view.layer, width, height)
            val painted = try {
                countPainted(watermarked)
            } finally {
                watermarked.recycle()
            }
            check(painted > 0) { "水印没有画出任何像素" }
            check(!view.layer.watermark.text.isEmpty()) { "水印配置没有保存" }

            // Undo walks the same stack: the watermark commit is its own step.
            view.layer.undo()
            check(view.layer.watermark.text.isEmpty()) { "撤销没有回退水印" }

            // Upstream annotates on the region window, so nothing may paint outside the region.
            val region = android.graphics.Rect(width / 4, height / 3, width * 3 / 4, height * 2 / 3)
            view.setCanvasRegion(region)
            view.layer.setWatermark(ScreenshotWatermark(text = "VoxFix", opacity = .5f))
            val clipped = render(view.layer, width, height)
            try {
                val inside = clipped.getPixel(region.centerX(), region.centerY())
                val outside = clipped.getPixel(Math.round(width * .05f), Math.round(height * .05f))
                check(android.graphics.Color.alpha(inside) > 0) { "选区内没有水印" }
                check(outside == 0) { "选区外被涂抹：${Integer.toHexString(outside)}" }
            } finally {
                clipped.recycle()
            }
            // A touch outside the canvas is not ours: it must pass through to the app below.
            val passed = view.layer.onTouchEvent(MotionEvent.obtain(
                SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, 2f, 2f, 0))
            check(!passed) { "选区块外的触摸被标注层吃掉了" }
            view.layer.undo()

            return "SCREENSHOT_RECORDING_ANNOTATION_PASS:tools=${expected.size}:" +
                "text=${text.text}:spotlight=${spotlight.id}:watermark=$painted:" +
                "clip=${region.width()}x${region.height()}"
        } finally {
            view.release()
        }
    }

    private fun render(layer: ScreenshotDrawingLayer, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        layer.draw(Canvas(bitmap))
        return bitmap
    }

    private fun countPainted(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height step 4) {
            for (x in 0 until bitmap.width step 4) {
                if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 0) count++
            }
        }
        return count
    }

    private fun descriptions(root: View): Set<String> {
        val found = mutableSetOf<String>()
        fun walk(view: View) {
            view.contentDescription?.let { found.add(it.toString()) }
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(root)
        return found
    }

    private fun find(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            find(root.getChildAt(index), match)?.let { return it }
        }
        return null
    }

    private fun click(root: View, label: String) {
        val target = find(root) { it.contentDescription?.toString() == label && it.isClickable }
            ?: error("找不到「$label」")
        check(target.performClick()) { "「$label」点击没有生效" }
    }

    /** A tap through the layer's own touch path (not a direct API call). */
    private fun tap(layer: ScreenshotDrawingLayer, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        layer.onTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        layer.onTouchEvent(MotionEvent.obtain(down, down + 16, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun drag(layer: ScreenshotDrawingLayer, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val down = SystemClock.uptimeMillis()
        layer.onTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, fromX, fromY, 0))
        layer.onTouchEvent(MotionEvent.obtain(down, down + 16, MotionEvent.ACTION_MOVE, toX, toY, 0))
        layer.onTouchEvent(MotionEvent.obtain(down, down + 32, MotionEvent.ACTION_UP, toX, toY, 0))
    }
}
