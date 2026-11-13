package local.voxfix.debug

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 真机「正常使用流程」诊断套件（只读断言，不改业务代码）。
 *
 * 与 ScreenshotParityFoundationSmoke 的区别：这里按用户真实操作顺序驱动控件，
 * 把上一轮审计里有争议的行为量成 PASS/FAIL，一项失败不会中断其余项。
 *
 * 入口：
 *   adb shell am instrument -w -e modelCompare screenshot-flow-audit \
 *     local.voxfix.debug.test/local.voxfix.debug.VoxFixSmokeInstrumentation
 */
internal object ScreenshotFlowAuditChecks {
    private class Translator : ScreenshotTranslator {
        override fun translate(text: String, pair: TranslatePair, onResult: (String) -> Unit,
                               onFailure: (String) -> Unit, onUpdate: (String) -> Unit) {}
        override fun cancel() {}
        override fun available() = true
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun method(target: Any, name: String, vararg types: Class<*>): Method =
        target.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }

    private fun find(view: View, label: String): View? {
        if (view.contentDescription == label) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), label)?.let { return it }
        return null
    }

    private fun findAll(view: View, predicate: (View) -> Boolean, out: MutableList<View>) {
        if (predicate(view)) out.add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) findAll(view.getChildAt(i), predicate, out)
    }

    private fun layout(view: View, width: Int = 1080, height: Int = 2400) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    fun run(test: Instrumentation): String {
        val report = mutableListOf<String>()
        test.runOnMainSync {
            val context = object : ContextWrapper(test.targetContext) {
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("flow-audit-$name", mode)
            }
            fun record(name: String, block: () -> Pair<Boolean, String>) {
                val result = runCatching(block)
                result.onSuccess { (ok, detail) -> report += "${if (ok) "PASS" else "FAIL"} $name $detail" }
                    .onFailure { report += "ERROR $name ${it.message}" }
            }

            var image: Bitmap? = null
            var overlay: ScreenshotCaptureOverlay? = null
            try {
                image = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                overlay = ScreenshotCaptureOverlay(context, image, {}, {}, {}, {}, {}, Translator())
                val editor = overlay
                val root = field(editor, "root") as ViewGroup
                // 复刻 Service 的绑定顺序：构造之后立刻接上长截屏/录屏回调。
                editor.onScrollRequested = { _, _, _ -> }
                editor.onRecordRequested = { }

                record("scroll-record-disabled-before-region") {
                    layout(root)
                    val scroll = find(root, "长截屏")
                    val recordButton = find(root, "录制选区")
                    val bothGone = scroll == null && recordButton == null
                    val bothDisabled = scroll?.isEnabled == false && recordButton?.isEnabled == false
                    (bothGone || bothDisabled) to
                        "scroll=${scroll?.isEnabled} record=${recordButton?.isEnabled}"
                }

                method(editor, "select", Rect::class.java).invoke(editor, Rect(40, 200, 1000, 1200))
                layout(root)
                record("scroll-record-enabled-after-region") {
                    val scroll = find(root, "长截屏")
                    val recordButton = find(root, "录制选区")
                    val ok = scroll?.isEnabled == true && recordButton?.isEnabled == true
                    ok to "scroll=${scroll?.isEnabled} record=${recordButton?.isEnabled}"
                }

                record("watermark-slider-survives-change") {
                    val drawing = field(editor, "drawing") as ScreenshotDrawingLayer
                    drawing.tool = ScreenshotDrawingTool.WATERMARK
                    method(editor, "rebuildDrawingStyles").invoke(editor)
                    layout(root)
                    val bars = mutableListOf<View>()
                    findAll(root, { it is SeekBar }, bars)
                    val before = bars.firstOrNull()
                        ?: return@record false to "no SeekBar"
                    drawing.setWatermark(ScreenshotWatermark(text = "AUDIT", opacity = .5f))
                    method(editor, "rebuildDrawingStyles").invoke(editor)
                    layout(root)
                    val after = mutableListOf<View>().also { out -> findAll(root, { it is SeekBar }, out) }.firstOrNull()
                    val same = before === after
                    same to "sameInstance=$same (false = 面板在拖动中被重建)"
                }

                record("apng-decodes-as-animation") {
                    val file = java.io.File(context.cacheDir, "flow-audit.apng").apply { delete() }
                    val width = 96
                    val height = 64
                    ApngEncoder(file, width, height, loop = true).apply {
                        writeHeader()
                        for (frame in 0 until 4) {
                            val pixels = IntArray(width * height) { i ->
                                val stripe = if (((i % width) / 8 + frame) % 2 == 0) 0xFF3366 else 0x22AAFF
                                (0xFF shl 24) or stripe
                            }
                            writeFrame(pixels, 100)
                        }
                        finish()
                    }
                    val drawable = android.graphics.ImageDecoder.decodeDrawable(
                        android.graphics.ImageDecoder.createSource(file))
                    val animated = drawable is android.graphics.drawable.AnimatedImageDrawable
                    (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.stop()
                    val detail = "bytes=${file.length()} animated=$animated"
                    runCatching {
                        context.getExternalFilesDir(null)?.let { dir ->
                            file.copyTo(java.io.File(dir, "flow-audit.apng"), overwrite = true)
                        }
                    }
                    file.delete()
                    animated to detail
                }

                record("apng-reference-decodes-as-animation") {
                    val ref = java.io.File(context.getExternalFilesDir(null), "ref.apng")
                    if (!ref.isFile) false to "missing ref.apng" else {
                        val drawable = android.graphics.ImageDecoder.decodeDrawable(
                            android.graphics.ImageDecoder.createSource(ref))
                        val animated = drawable is android.graphics.drawable.AnimatedImageDrawable
                        (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.stop()
                        animated to "ref animated=$animated"
                    }
                }

                record("corner-radius-keeps-uniform-others") {
                    val radii = ScreenshotCornerRadii.set(null, ScreenshotCornerRadii.BOTTOM_RIGHT, 20f)
                    val others = listOf(radii[0], radii[1], radii[3])
                    val ok = others.all { it == 6f }
                    ok to "radii=$radii expected others=6.0 (uniform 6)"
                }

                record("delete-serial-removes-bound-text") {
                    val small = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                    val layer = ScreenshotDrawingLayer(context, small, null) {}
                    try {
                        layer.layout(0, 0, 400, 400)
                        layer.region = Rect(0, 0, 400, 400)
                        val serial = ScreenshotDrawingStroke(ScreenshotDrawingTool.SERIAL,
                            listOf(ScreenshotDrawingPoint(80f, 200f)), Color.RED, 4f, layer.style, number = 1)
                        val label = ScreenshotSerialLabels.create(serial, layer.style, 1f, 1f,
                            ScreenshotDrawingPoint(200f, 200f)).copy(text = "说明")
                        layer.history.addSerialWithText(serial, label)
                        val serialIndex = layer.history.items.indexOfFirst { it.tool == ScreenshotDrawingTool.SERIAL }
                        layer.history.remove(setOf(serialIndex))
                        val remaining = layer.history.items.count { it.tool == ScreenshotDrawingTool.TEXT }
                        (remaining == 0) to "remainingTextObjects=$remaining (expected 0)"
                    } finally { small.recycle() }
                }
            } finally {
                overlay?.dismiss(context.getSystemService(android.view.WindowManager::class.java))
                image?.recycle()
            }
        }
        return "SCREENSHOT_FLOW_AUDIT\n" + report.joinToString("\n")
    }
}
