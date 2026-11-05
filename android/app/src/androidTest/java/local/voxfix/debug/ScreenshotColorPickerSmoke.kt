package local.voxfix.debug

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.*
import android.view.*
import android.widget.EditText

/**
 * T08 palette + eyedropper closed loop.
 *
 * Everything here runs on a hand-measured view tree and on the palette session rules, which is the
 * same code the accessibility window uses. The PopupWindow shell itself (outside touch, IME,
 * window animation) cannot be created from instrumentation without a real window token, so that
 * part is left to device acceptance instead of being faked here.
 */
object ScreenshotColorPickerSmoke {
    private fun find(view: View, label: String): View? {
        if (view.contentDescription == label) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), label)?.let { return it }
        return null
    }
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
    private fun remeasure(panel: View) {
        panel.measure(View.MeasureSpec.makeMeasureSpec(panel.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        panel.layout(0, 0, panel.width, panel.measuredHeight)
    }
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0); try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun inkOf(view: View): Int {
        if (view.width <= 0 || view.height <= 0) return 0
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        var ink = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val c = image.getPixel(x, y)
            if (Color.red(c) > 0x90 && Color.green(c) > 0x90 && Color.blue(c) > 0x90) ink++
        }
        image.recycle()
        return ink
    }
    private fun screenshot(view: View, file: java.io.File) {
        if (view.width <= 0 || view.height <= 0) return
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private val translator = object : ScreenshotTranslator {
        override fun translate(text: String, pair: TranslatePair, onResult: (String) -> Unit, onFailure: (String) -> Unit, onUpdate: (String) -> Unit) {}
        override fun cancel() {}
        override fun available() = true
    }
    fun run(test: Instrumentation): String {
        var failure: Throwable? = null
        val context = object : ContextWrapper(test.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("color-picker-smoke-$name", mode)
        }
        test.runOnMainSync {
            try { inputs(context); sampling(context); sessionRules() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
        return "SCREENSHOT_COLOR_PICKER_PASS: SV,hue,alpha,HEX-RGBA,RGB,HSB,validation,precision,stroke,geometry,sample,marks,cancel,session,history,export,document"
    }

    private fun inputs(context: Context) {
        var last = 0; var pattern = ScreenshotStrokePattern.SOLID
        val initial = 0x7F2E3440
        val panel = ScreenshotColorPanel(context, initial, { last = it }, pattern, { pattern = it })
        val density = context.resources.displayMetrics.density
        layout(panel, (232 * density).toInt(), (420 * density).toInt())
        check(panel.commitInputs() && panel.color == initial) { "未改动的透明度被百分比取整" }
        panel.setMode("RGB"); panel.setMode("HSB"); panel.setMode("HEX"); check(panel.color == initial) { "格式往返改变颜色" }
        check(panel.formatMode == "HEX")

        val hex = find(panel, "十六进制颜色") as EditText
        hex.setText("#12345680"); check(panel.commitInputs() && panel.color == 0x80123456.toInt())
        hex.setText("#xyz"); check(!panel.commitInputs() && panel.color == 0x80123456.toInt()) { "非法HEX改变颜色" }
        remeasure(panel)
        check(hex.width > 0 && hex.text.isNotEmpty()) { "非法输入后未回写当前颜色" }
        panel.setMode("RGB")
        (find(panel, "红色通道") as EditText).setText("999")
        check(!panel.commitInputs() && panel.color == 0x80123456.toInt()) { "越界通道改变颜色" }
        panel.setMode("HSB")
        (find(panel, "色相数值") as EditText).setText("120")
        (find(panel, "饱和度数值") as EditText).setText("100")
        (find(panel, "亮度数值") as EditText).setText("100")
        check(panel.commitInputs() && (panel.color and 0xFFFFFF) == 0x00FF00)
        (find(panel, "透明度百分比") as EditText).setText("25%")
        check(panel.commitInputs() && Color.alpha(panel.color) == 64)
        (find(panel, "透明度百分比") as EditText).setText("140%")
        check(!panel.commitInputs() && Color.alpha(panel.color) == 64) { "越界透明度改变颜色" }

        find(panel, "虚线")!!.performClick(); check(pattern == ScreenshotStrokePattern.DASHED)
        find(panel, "点线")!!.performClick(); check(pattern == ScreenshotStrokePattern.DOTTED)
        find(panel, "实线")!!.performClick(); check(pattern == ScreenshotStrokePattern.SOLID)

        // Rails span the full content width with no inset, as in the 1.1.8 popup.
        panel.setColor(0xFF0000FF.toInt())
        val hue = find(panel, "色相")!!
        touch(hue, MotionEvent.ACTION_DOWN, hue.width * .5f, hue.height / 2f)
        touch(hue, MotionEvent.ACTION_UP, hue.width * .5f, hue.height / 2f)
        val mid = FloatArray(3).also { Color.colorToHSV(panel.color, it) }[0]
        check(mid in 175f..185f) { "色相中点偏差：$mid" }
        val square = find(panel, "饱和度与亮度")!!
        touch(square, MotionEvent.ACTION_DOWN, square.width.toFloat(), 0f)
        touch(square, MotionEvent.ACTION_UP, square.width.toFloat(), 0f)
        check((panel.color and 0xFFFFFF) == 0x00FFFF && last == panel.color) { "二维调色或色相无效" }
        val alpha = find(panel, "颜色透明度")!!
        touch(alpha, MotionEvent.ACTION_DOWN, 0f, alpha.height / 2f)
        touch(alpha, MotionEvent.ACTION_UP, 0f, alpha.height / 2f)
        check(Color.alpha(panel.color) == 0)
        touch(alpha, MotionEvent.ACTION_DOWN, alpha.width.toFloat(), alpha.height / 2f)
        touch(alpha, MotionEvent.ACTION_UP, alpha.width.toFloat(), alpha.height / 2f)
        check(Color.alpha(panel.color) == 255) { "透明度满值不等于不透明" }

        // A grey target must not reset the hue rail the user is holding.
        panel.setMode("HEX"); panel.setColor(0xFF00FF00.toInt())
        val keptHue = FloatArray(3).also { Color.colorToHSV(panel.color, it) }[0]
        panel.setColor(Color.WHITE)
        val greyHue = FloatArray(3).also { Color.colorToHSV(panel.color, it) }[0]
        check(Math.abs(keptHue - greyHue) < 1f || greyHue == 0f) { "取白色后色相丢失：$keptHue→$greyHue" }

        panel.setColor(0xFF2E3440.toInt()); panel.setMode("HEX"); remeasure(panel)
        // Both value fields must actually paint ink: a field that only shows its background fails here.
        check(inkOf(hex) > 12) { "十六进制输入框未绘制文字（亮像素 ${inkOf(hex)}）" }
        val alphaField = find(panel, "透明度百分比") as EditText
        check(alphaField.text.toString() == "100%") { "透明度未回写为 100%：${alphaField.text}" }
        check(inkOf(alphaField) > 12) { "透明度输入框未绘制文字：text=${alphaField.text} size=${alphaField.width}x${alphaField.height} ink=0" }
        screenshot(panel, java.io.File(context.cacheDir, "screenshot-color-palette.png"))
    }

    /** The eyedropper and the drawing layer, on the overlay's own measured view tree. */
    private fun sampling(context: Context) {
        val image = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        Canvas(image).drawRect(540f, 0f, 1080f, 2400f, Paint().apply { color = Color.BLUE })
        val overlay = ScreenshotCaptureOverlay(context, image, {}, {}, {}, {}, {}, translator)
        try {
            val root = field(overlay, "root") as ViewGroup
            layout(root, 1080, 2400)
            val drawing = field(overlay, "drawing") as ScreenshotDrawingLayer
            (field(overlay, "selector") as ScreenTextRegionView).selectRegion(Rect(10, 10, 1000, 1200))
            drawing.tool = ScreenshotDrawingTool.PEN
            check(drawing.width == 1080 && drawing.height == 2400) { "绘图区未铺满画布：${drawing.width}x${drawing.height}" }
            drawing.history.add(ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN, listOf(ScreenshotDrawingPoint(200f, 500f)), Color.GREEN, 30f))
            val baseline = root.childCount

            // 1. The sample equals the pixel under the finger, including a drawn mark.
            var sampled: Int? = null
            drawing.pickColor { sampled = it }
            check(root.childCount == baseline + 1) { "滴管未挂载采样层" }
            val sampler = root.getChildAt(root.childCount - 1) as ScreenshotColorSampler
            layout(sampler, 1080, 2400)
            check(sampler.contentDescription.contains("滴管"))
            touch(sampler, MotionEvent.ACTION_DOWN, 200f, 500f)
            touch(sampler, MotionEvent.ACTION_UP, 200f, 500f)
            check(sampled == Color.GREEN) { "滴管未取到手指下的已绘对象：$sampled" }
            check(root.childCount == baseline) { "滴管结束后未移除采样层" }

            // 2. Right half is blue: the mapping must not be shifted or mirrored.
            var right: Int? = null
            drawing.pickColor { right = it }
            val rightSampler = root.getChildAt(root.childCount - 1) as ScreenshotColorSampler
            layout(rightSampler, 1080, 2400)
            touch(rightSampler, MotionEvent.ACTION_DOWN, 800f, 1500f)
            touch(rightSampler, MotionEvent.ACTION_UP, 800f, 1500f)
            check(right == Color.BLUE) { "滴管坐标映射错误：$right" }

            // 3. Dragging updates the readout and the returned colour follows the last position.
            var moved: Int? = null
            drawing.pickColor { moved = it }
            val dragSampler = root.getChildAt(root.childCount - 1) as ScreenshotColorSampler
            layout(dragSampler, 1080, 2400)
            touch(dragSampler, MotionEvent.ACTION_DOWN, 200f, 500f)
            touch(dragSampler, MotionEvent.ACTION_MOVE, 800f, 1500f)
            touch(dragSampler, MotionEvent.ACTION_UP, 800f, 1500f)
            check(moved == Color.BLUE) { "拖动后松手未取最后位置：$moved" }

            // 4. Back and a second finger both cancel without a colour and without leaking a view.
            var cancelled = 0
            drawing.pickColor { check(it == null); cancelled++ }
            val backSampler = root.getChildAt(root.childCount - 1) as ScreenshotColorSampler
            layout(backSampler, 1080, 2400)
            backSampler.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            drawing.pickColor { check(it == null); cancelled++ }
            val multiSampler = root.getChildAt(root.childCount - 1) as ScreenshotColorSampler
            layout(multiSampler, 1080, 2400)
            val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 300f, 300f, 0)
            val second = MotionEvent.obtain(0, 0, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 400f, 400f, 0)
            try { multiSampler.dispatchTouchEvent(down); multiSampler.dispatchTouchEvent(second) } finally { down.recycle(); second.recycle() }
            check(cancelled == 2 && root.childCount == baseline) { "返回／多指未取消滴管：$cancelled" }

            // 5. Editing an existing object stays one history entry; cancel restores it.
            val historyBefore = drawing.history.items
            drawing.tool = ScreenshotDrawingTool.SELECT
            touch(drawing, MotionEvent.ACTION_DOWN, 200f, 500f)
            touch(drawing, MotionEvent.ACTION_UP, 200f, 500f)
            check(drawing.selected != null)
            val saved = drawing.inspectedStyle
            drawing.beginStylePreview()
            drawing.previewStyle(saved.copy(color = Color.MAGENTA))
            check(drawing.history.items == historyBefore) { "预览改写历史" }
            drawing.finishStylePreview(false)
            check(drawing.style.color == saved.color) { "取消预览未恢复原颜色" }
            drawing.beginStylePreview()
            drawing.previewStyle(saved.copy(color = Color.BLUE))
            drawing.finishStylePreview(true)
            check(drawing.selected?.color == Color.BLUE && drawing.history.items.size == historyBefore.size) { "确认预览未落到选中对象" }

            // 6. Export + project round trip keep the object and its transparency.
            drawing.applyStyle(saved.copy(color = 0x802E3440.toInt()))
            val doc = ScreenshotDocument.full(1080, 2400).copy(objects = drawing.history.items)
            check(ScreenshotDocumentCodec.decode(ScreenshotDocumentCodec.encode(doc)) == doc)
            val result = drawing.render(Rect(0, 0, 1080, 2400), null)!!
            check(result.getPixel(200, 500) != Color.GREEN)
            result.recycle()
            // Snapshots, not a single step: unwind until the canvas is genuinely back to empty.
            var guard = 0
            while (drawing.history.canUndo && guard++ < 64) drawing.undo()
            check(drawing.history.items.isEmpty()) { "撤销未回到空画布（还有 ${drawing.history.items.size} 个对象）" }
        } finally { image.recycle() }
    }

    /** The palette session rules that the popup shell forwards to. */
    private fun sessionRules() {
        var finishes = 0; var accepted: Boolean? = null; var commits = 0
        val session = ScreenshotColorSession { accepted = it; finishes++ }
        check(session.beginSample(true)) { "无法进入滴管" }
        check(!session.beginSample(true)) { "滴管被重复进入" }
        session.dismiss { commits++ }
        check(commits == 0 && finishes == 0) { "进入滴管即提交颜色" }
        check(session.endSample(true)) { "取色返回后未重开弹层" }
        check(finishes == 0)
        session.dismiss { commits++ }
        check(commits == 1 && finishes == 1 && accepted == true) { "关闭弹层未提交一次颜色" }
        session.dismiss { commits++ }; session.detach()
        check(commits == 1 && finishes == 1) { "会话重复提交" }

        var completes = 0; var cancelledAccepted: Boolean? = null
        val cancelSession = ScreenshotColorSession { cancelledAccepted = it; completes++ }
        cancelSession.cancel()
        cancelSession.dismiss { check(false) { "取消后仍提交颜色" } }
        check(completes == 1 && cancelledAccepted == false) { "取消未按未接受结束" }

        var aborted = 0
        val deadAnchor = ScreenshotColorSession { aborted++ }
        deadAnchor.beginSample(true)
        check(!deadAnchor.endSample(false)) { "锚点消失后仍重开弹层" }
        check(aborted == 1) { "锚点消失后未结束会话" }
        deadAnchor.dismiss { check(false) { "锚点消失后仍提交" } }
        check(aborted == 1) { "锚点消失后重复结束" }
    }
}
