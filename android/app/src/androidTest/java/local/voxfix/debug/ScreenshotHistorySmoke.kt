package local.voxfix.debug

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

/**
 * T06/T07: undo and redo on the real gesture path. The original routes history three ways
 * (`historyActionEnabled` in `screenshottoolpalette.cpp`): table editing, text editing and canvas.
 * The phone has no table tool, so this suite covers the canvas history and the toolbar wiring;
 * the text-block history is already asserted by `screenshot-document` (`block-undo`).
 */
object ScreenshotHistorySmoke {
    private class Translator : ScreenshotTranslator {
        override fun translate(text: String, pair: TranslatePair, onResult: (String) -> Unit,
                               onFailure: (String) -> Unit, onUpdate: (String) -> Unit) {}
        override fun cancel() {}
        override fun available() = true
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun find(view: View, label: String): View? {
        if (view.contentDescription == label) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index), label)?.let { return it }
        return null
    }

    fun run(test: Instrumentation): String {
        var failure: Throwable? = null
        test.runOnMainSync {
            try { canvas(test.targetContext); toolbar(test.targetContext) } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
        return "SCREENSHOT_HISTORY_PASS: create,one-gesture,move,style,style-preview,delete,step-back,redo-branch,noop,toolbar"
    }

    /** Every step the palette promises: create, move, restyle, delete, then unwind one by one. */
    private fun canvas(context: Context) {
        val image = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val layer = ScreenshotDrawingLayer(context, image) {}
        layer.region = Rect(0, 0, 320, 320)
        layer.layout(0, 0, 320, 320)
        fun touch(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(1, 2, action, x, y, 0)
            try { check(layer.onTouchEvent(event)) { "手势未被消费：$action" } } finally { event.recycle() }
        }
        // A real drag emits many moves; history must still grow by exactly one entry.
        fun drag(x: Float, y: Float, toX: Float, toY: Float, steps: Int = 1) {
            touch(MotionEvent.ACTION_DOWN, x, y)
            for (step in 1..steps) {
                val t = step.toFloat() / steps
                touch(MotionEvent.ACTION_MOVE, x + (toX - x) * t, y + (toY - y) * t)
            }
            touch(MotionEvent.ACTION_UP, toX, toY)
        }
        fun depth(): Int {
            var steps = 0
            while (layer.history.canUndo) { layer.history.undo(); steps++ }
            repeat(steps) { layer.history.redo() }
            return steps
        }
        try {
            layer.tool = ScreenshotDrawingTool.RECTANGLE
            drag(80f, 100f, 240f, 260f, steps = 20)
            check(layer.history.items.size == 1) { "矩形没有落笔" }
            check(depth() == 1) { "一次手势产生了多条历史：${depth()}" }
            val created = layer.history.items.single()
            check(created.id > 0L) { "对象没有编号" }

            layer.undo(); check(layer.history.items.isEmpty()) { "撤销没有回到空画布" }
            layer.redo()
            check(layer.history.items.single() == created) { "重做没有恢复同一对象" }

            layer.tool = ScreenshotDrawingTool.SELECT
            drag(120f, 140f, 170f, 190f)
            val moved = layer.history.items.single()
            check(moved.points.first() != created.points.first()) { "选中对象没有移动" }
            check(moved.id == created.id) { "移动后对象编号变了" }
            check(depth() == 2) { "移动不是一条历史：${depth()}" }

            layer.applyStyle(layer.inspectedStyle.copy(color = Color.BLUE))
            check(layer.history.items.single().color == Color.BLUE) { "改色没有落到对象" }
            check(depth() == 3) { "改色不是一条历史：${depth()}" }

            // Slider and colour-panel drags preview continuously; only the commit may write history.
            val beforePreview = depth()
            layer.beginStylePreview()
            layer.previewStyle(layer.inspectedStyle.copy(color = Color.GREEN))
            layer.previewStyle(layer.inspectedStyle.copy(color = Color.YELLOW))
            check(depth() == beforePreview) { "样式预览写入了历史" }
            layer.finishStylePreview(true)
            check(depth() == beforePreview + 1) { "一次样式调整写了多条历史：${depth()}" }
            check(layer.history.items.single().color == Color.YELLOW) { "样式调整没有提交" }

            layer.deleteSelected()
            check(layer.history.items.isEmpty()) { "删除没有生效" }
            check(depth() == 5) { "删除不是一条历史：${depth()}" }

            layer.undo()
            check(layer.history.items.single().id == created.id) { "撤销删除没有恢复原对象" }
            check(layer.history.items.single().color == Color.YELLOW) { "撤销删除丢了颜色" }
            layer.undo()
            check(layer.history.items.single().color == Color.BLUE) { "撤销样式调整没有回到上一色" }
            layer.undo()
            check(layer.history.items.single().color == created.color) { "撤销改色没有回到旧色" }
            layer.undo()
            check(layer.history.items.single().points == created.points) { "撤销移动没有回到原位" }
            layer.undo()
            check(layer.history.items.isEmpty() && !layer.history.canUndo) { "撤销创建后仍可继续撤销" }

            repeat(5) { layer.redo() }
            check(layer.history.items.isEmpty() && !layer.history.canRedo) { "重做没有回到删除后的状态" }

            // A new edit must throw the redo branch away.
            layer.undo()
            check(layer.history.canRedo) { "撤销后没有可重做分支" }
            layer.tool = ScreenshotDrawingTool.RECTANGLE
            drag(40f, 40f, 120f, 120f, steps = 12)
            check(!layer.history.canRedo) { "新编辑后旧重做分支没有失效" }
            check(layer.history.items.size == 2) { "新编辑没有落在恢复后的画面上：${layer.history.items.size}" }

            // Touching empty canvas with the eraser removes nothing and must not add an entry.
            val before = depth()
            val objects = layer.history.items.size
            layer.tool = ScreenshotDrawingTool.ERASER
            drag(8f, 8f, 12f, 12f, steps = 4)
            check(layer.history.items.size == objects) { "空擦除删掉了对象" }
            check(depth() == before) { "空擦除产生了历史条目" }
        } finally { layer.close(); image.recycle() }
    }

    /** The buttons themselves: disabled with no history, then wired to the same stack. */
    private fun toolbar(context: Context) {
        val image = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        var exits = 0
        val overlay = ScreenshotCaptureOverlay(context, image, {}, {}, {}, { exits++ }, {}, Translator())
        try {
            val root = field(overlay, "root") as ViewGroup
            (field(overlay, "selector") as ScreenTextRegionView).selectRegion(Rect(20, 20, 300, 300))
            val sync = overlay.javaClass.getDeclaredMethod("sync").apply { isAccessible = true }
            sync.invoke(overlay)
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 2400)
            val undo = find(root, "撤销") as? ImageView ?: throw IllegalStateException("撤销按钮不存在")
            val redo = find(root, "重做") as? ImageView ?: throw IllegalStateException("重做按钮不存在")
            check(!undo.isEnabled && !redo.isEnabled) { "空历史时撤销/重做可用" }
            val drawing = field(overlay, "drawing") as ScreenshotDrawingLayer
            drawing.history.add(ScreenshotDrawingStroke(ScreenshotDrawingTool.RECTANGLE,
                listOf(ScreenshotDrawingPoint(60f, 60f), ScreenshotDrawingPoint(200f, 180f)), Color.RED, 3f))
            sync.invoke(overlay)
            check(undo.isEnabled && !redo.isEnabled) { "有历史时撤销不可用或重做被误开" }
            undo.performClick()
            sync.invoke(overlay)
            check(drawing.history.items.isEmpty()) { "撤销按钮没有回退" }
            check(redo.isEnabled && !undo.isEnabled) { "撤销后重做不可用" }
            redo.performClick()
            sync.invoke(overlay)
            check(drawing.history.items.size == 1) { "重做按钮没有恢复" }
            check(exits == 0) { "撤销重做误触了退出" }
        } finally {
            overlay.dismiss(context.getSystemService(android.view.WindowManager::class.java))
            image.recycle()
        }
    }
}
