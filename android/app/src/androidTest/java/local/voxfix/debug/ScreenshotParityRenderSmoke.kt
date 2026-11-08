package local.voxfix.debug

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import java.io.File

/**
 * B-line parity render: the same base image and the same stroke as the original's MCP render
 * (probe original-render-1.png), exported through the phone's own drawing pipeline.
 * Output lands in getExternalFilesDir(null)/parity/ for adb pull.
 */
object ScreenshotParityRenderSmoke {
    fun run(test: Instrumentation): String {
        var failure: Throwable? = null
        val appContext = test.targetContext
        test.runOnMainSync {
            try { render(appContext) } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
        return "SCREENSHOT_PARITY_RENDER_PASS"
    }
    /**
     * Base image comes from files/parity/parity-base.png (adb push from
     * .android/snow-parity/probe/base-desktop-1.png). It contains the user's desktop,
     * so it must never be committed as a test asset.
     */
    private fun render(appContext: Context) {
        val baseFile = File(appContext.getExternalFilesDir(null), "parity/parity-base.png")
        check(baseFile.isFile) { "缺少底图：先 adb push base-desktop-1.png 到 ${baseFile.absolutePath}" }
        val base = BitmapFactory.decodeFile(baseFile.absolutePath)
        try {
            check(base.width == 1920 && base.height == 1080) { "底图尺寸不对：${base.width}x${base.height}" }
            val out = Bitmap.createBitmap(700, 500, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawBitmap(base, Rect(100, 100, 700, 500), Rect(0, 0, 700, 500), null)
            canvas.translate(-100f, -100f)
            val renderer = ScreenshotDrawingRenderer(base) { 1f }
            try {
                val stroke = ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN, listOf(
                    ScreenshotDrawingPoint(150f, 150f), ScreenshotDrawingPoint(250f, 200f),
                    ScreenshotDrawingPoint(350f, 160f), ScreenshotDrawingPoint(450f, 220f),
                    ScreenshotDrawingPoint(560f, 180f)), Color.RED, 6f,
                    smooth = true)
                renderer.draw(canvas, listOf(stroke), Rect(100, 100, 700, 500))
            } finally { renderer.close() }
            val dir = File(appContext.getExternalFilesDir(null), "parity").apply { mkdirs() }
            val file = File(dir, "phone-render-1.png")
            file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            check(file.length() > 0) { "导出为空" }
            out.recycle()
        } finally { base.recycle() }
    }
}
