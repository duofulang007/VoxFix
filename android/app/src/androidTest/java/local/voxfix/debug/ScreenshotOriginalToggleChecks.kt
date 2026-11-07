package local.voxfix.debug

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

/**
 * T02 原图对照：上游 `ScreenshotRecognitionWindow::setShowOriginalImage` 只做
 * `m_contentContainer->setVisible(!show)` —— 「显示原图」隐藏的是识别内容层，画布上的绘图对象照常
 * 可见；`m_showOriginalImage` 也不进任何导出路径，保存的永远是完整结果。手机此前把绘图层一起隐藏、
 * 并在导出时跳过绘图对象，两处都已按原版改掉，这里做回归。
 */
internal object ScreenshotOriginalToggleChecks {
    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun call(target: Any, name: String, vararg args: Pair<Class<*>, Any?>): Any? {
        val method = target.javaClass.getDeclaredMethod(name, *args.map { it.first }.toTypedArray())
        method.isAccessible = true
        return method.invoke(target, *args.map { it.second }.toTypedArray())
    }

    fun run(context: android.content.Context) {
        val image = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val overlay = ScreenshotCaptureOverlay(context, image, {}, {}, {}, {}, {}, object : ScreenshotTranslator {
            override fun translate(text: String, pair: TranslatePair, onResult: (String) -> Unit,
                                   onFailure: (String) -> Unit, onUpdate: (String) -> Unit) {}
            override fun cancel() {}
            override fun available() = true
        })
        try {
            val root = field(overlay, "root") as ViewGroup
            root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 600, 600)
            (field(overlay, "selector") as ScreenTextRegionView).selectRegion(Rect(0, 0, 600, 600))
            check(field(overlay, "picked") != null) { "初始框选没有生效" }

            // 一条横穿画面的粗线，中心点必须能被导出图读到。
            val drawing = field(overlay, "drawing") as ScreenshotDrawingLayer
            val style = defaultScreenshotDrawingStyle(ScreenshotDrawingTool.PEN)
            drawing.history.add(ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN,
                listOf(ScreenshotDrawingPoint(100f, 300f), ScreenshotDrawingPoint(500f, 300f)), Color.RED, 40f * drawing.savedPixelUnit.coerceAtLeast(1f), style))
            check(drawing.history.items.size == 1) { "画笔对象没有落笔" }

            // 显示原图：内容层隐藏，但绘图层必须仍然可见（上游只藏 OCR 容器）。
            setField(overlay, "showingOriginal", true)
            call(overlay, "sync")
            check(drawing.visibility == View.VISIBLE) { "显示原图时画布对象被一起隐藏了" }

            // 导出不受预览态影响：仍然要带上绘图对象。
            val exported = call(overlay, "currentImage") as Bitmap
            val center = exported.getPixel(exported.width / 2, exported.height / 2)
            check(Color.red(center) > 180 && Color.green(center) < 80) { "显示原图时导出的像素丢了绘图对象：$center" }
            exported.recycle()

            // 切回识别结果：对象仍在历史里，一次都没被动过。
            setField(overlay, "showingOriginal", false)
            call(overlay, "sync")
            check(drawing.visibility == View.VISIBLE) { "切回后画布没有恢复" }
            check(drawing.history.items.size == 1) { "切换原图丢掉了画布对象" }
        } finally { image.recycle() }
    }
}
