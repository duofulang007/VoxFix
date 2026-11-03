package local.voxfix.debug

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

/** Verifies the unsaved screenshot toolbar without opening an accessibility overlay window. */
object ScreenshotToolbarSmoke {
    fun run(test: Instrumentation): String {
        val bitmap = Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("第一行文字",80f,180f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f })
            drawText("第二行文字",80f,300f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f })
        }
        val evidence = mutableListOf<String>()
        test.runOnMainSync {
            var savedRegion: Rect? = null
            var savedImage: Bitmap? = null
            var savedCalls = 0
            lateinit var overlay: ScreenshotCaptureOverlay
            overlay = ScreenshotCaptureOverlay(test.targetContext,bitmap,
                { region,image -> savedCalls++; savedRegion = region; savedImage = image },
                {},{},{},{})
            val root = field(overlay,"root") as ViewGroup
            root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            root.layout(0,0,1080,2400)
            fun action(label: String): ImageView? = find(root,label) as? ImageView
            fun visible(label: String) = action(label)?.visibility == View.VISIBLE
            fun iconOk(label: String) = checkNotNull(action(label)) { "missing $label" }.drawable != null

            check(visible("选择整屏") && iconOk("选择整屏")) { "full screen missing before selection" }
            check(iconOk("取消截图")) { "cancel icon missing" }
            check(!visible("识别文字") && !visible("保存截图")) { "region actions must wait for a selection" }
            check(!visible("复制文字") && !visible("编辑文字") && !visible("显示原图")) { "text actions before recognition" }
            evidence.add("stage=empty")

            val selector = field(overlay,"selector") as ScreenTextRegionView
            selector.selectRegion(Rect(60,60,1020,600))
            root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            root.layout(0,0,1080,2400)
            check(visible("识别文字") && visible("保存截图")) { "region actions missing after selection" }
            check(iconOk("识别文字") && iconOk("保存截图")) { "region action icons missing" }
            check(!visible("复制文字") && !visible("编辑文字") && !visible("显示原图")) { "text actions leaked before OCR" }
            evidence.add("stage=region")

            overlay.textResult(listOf(
                ScreenshotTextBlock(60,60,1020,140,"第一行文字"),
                ScreenshotTextBlock(60,140,1020,220,"第二行文字")))
            repeat(2) {
                root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
                root.layout(0,0,1080,2400)
            }
            check(visible("复制文字") && visible("编辑文字") && visible("显示原图")) { "text actions missing after OCR" }
            check(iconOk("复制文字") && iconOk("编辑文字") && iconOk("显示原图")) { "text action icons missing" }
            check(!visible("选择整屏")) { "full screen must not disturb a recognized region" }
            evidence.add("stage=text")

            checkNotNull(action("复制文字")).performClick()
            // Android 10+ refuses clipboard writes without a focused window, so this checks the
            // text handed to the clipboard plus the user-visible result instead of the system clip.
            val layer = field(overlay,"textLayer") as ScreenshotTextLayer
            check(layer.copyText() == "第一行文字\n第二行文字") { "copy text=${layer.copyText()}" }
            val copyStatus = (field(overlay,"status") as android.widget.TextView).text.toString()
            check(copyStatus == "已复制文字" || copyStatus == "复制失败，请重试") { "copy status=$copyStatus" }
            evidence.add("copy=${if (copyStatus == "已复制文字") "clipboard" else "blocked"}")
            evidence.add("copytext=ok")
            evidence.add("copy=ok")

            checkNotNull(action("显示原图")).performClick()
            val result = field(overlay,"resultLayer") as View
            check(result.visibility == View.INVISIBLE) { "original view must hide the text layer" }
            check(visible("显示识别结果")) { "eye label did not switch" }
            checkNotNull(action("显示识别结果")).performClick()
            check(result.visibility == View.VISIBLE) { "text layer must come back with its draft" }
            evidence.add("eye=ok")

            checkNotNull(action("编辑文字")).performClick()
            check(visible("完成编辑")) { "edit label did not switch" }
            check(!visible("选择整屏") && !visible("识别文字")) { "region changes must pause while editing" }
            checkNotNull(action("完成编辑")).performClick()
            check(visible("编辑文字")) { "edit exit failed" }
            evidence.add("edit=ok")

            checkNotNull(action("保存截图")).performClick()
            check(savedCalls == 1) { "save not raised" }
            check(savedRegion == Rect(60,60,1020,600)) { "save region=${savedRegion}" }
            val composed = savedImage
            check(composed != null && composed.width == 960 && composed.height == 540) {
                "edited result not exported: ${composed?.width}x${composed?.height}"
            }
            evidence.add("save=composed:${composed!!.width}x${composed.height}")
            composed.recycle()

            // A fresh selection without text must export the untouched crop (null means original).
            var raw: Bitmap? = bitmap
            var rawCalls = 0
            val plain = ScreenshotCaptureOverlay(test.targetContext,bitmap,
                { _,image -> rawCalls++; raw = image },{},{},{},{})
            val plainRoot = field(plain,"root") as ViewGroup
            plainRoot.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            plainRoot.layout(0,0,1080,2400)
            (field(plain,"selector") as ScreenTextRegionView).selectRegion(Rect(10,10,500,400))
            (find(plainRoot,"保存截图") as ImageView).performClick()
            check(rawCalls == 1 && raw == null) { "plain save must pass the original crop" }
            evidence.add("save=original")
            bitmap.recycle()
        }
        return "SCREENSHOT_TOOLBAR_PASS " + evidence.joinToString(",")
    }
    private fun field(target: Any,name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun find(view: View,label: String): View? {
        if (view is ImageView && view.contentDescription?.toString() == label) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i),label)?.let { return it }
        return null
    }
}
