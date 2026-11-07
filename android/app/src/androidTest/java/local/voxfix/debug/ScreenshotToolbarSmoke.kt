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
    private class FakeTranslator(private val output: String = "你好世界") : ScreenshotTranslator {
        var calls = 0
        var pair: TranslatePair? = null
        var cancelled = 0
        override fun translate(text: String, pair: TranslatePair, onResult: (String) -> Unit, onFailure: (String) -> Unit, onUpdate: (String) -> Unit) {
            calls++; this.pair = pair; onResult(output)
        }
        override fun cancel() { cancelled++ }
        override fun available() = true
    }

    fun run(test: Instrumentation): String {
        val preferences = test.targetContext.getSharedPreferences("screen-text", android.content.Context.MODE_PRIVATE)
        val oldSource = preferences.getString("translate.source",null)
        val oldTarget = preferences.getString("translate.target",null)
        val oldLayout = preferences.getString(ScreenshotToolbarEditor.KEY,null)
        val oldSections=ScreenshotToolbarSection.values().associate { it.key to preferences.getString(it.key,null) }
        return try {
        // The overlay remembers the last pair; the smoke expects the default on a fresh install.
        test.targetContext.getSharedPreferences("screen-text", android.content.Context.MODE_PRIVATE)
            .edit().remove("translate.source").remove("translate.target").remove(ScreenshotToolbarEditor.KEY).commit()
        preferences.edit().remove(ScreenshotToolbarSection.DRAWING.key).remove(ScreenshotToolbarSection.ACTIONS.key).commit()
        ScreenshotToolbarCustomizationSmoke.run(test)
        preferences.edit().putString(ScreenshotToolbarEditor.KEY,
            ScreenshotToolbarLayout(ScreenshotAction.values().map { listOf(it) }).encode()).commit()
        ScreenshotDrawingSmoke.run(test)
        ScreenshotEditingSmoke.run(test)
        ScreenshotRetentionSmoke.run(test)
        ScreenshotTranslationFeedbackSmoke.run(test)
        val bitmap = Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("第一行文字",80f,180f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f })
            drawText("第二行文字",80f,300f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f })
        }
        val evidence = mutableListOf<String>()
        var failure: Throwable? = null
        test.runOnMainSync {
            try {
            var savedRegion: Rect? = null
            var savedImage: Bitmap? = null
            var savedCalls = 0
            val translator = FakeTranslator()
            lateinit var overlay: ScreenshotCaptureOverlay
            overlay = ScreenshotCaptureOverlay(test.targetContext,bitmap,
                { request -> savedCalls++; savedRegion = request.region; savedImage = request.composite },
                {},{},{},{},translator)
            val root = field(overlay,"root") as ViewGroup
            root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            root.layout(0,0,1080,2400)
            fun action(label: String): ImageView? = find(root,label) as? ImageView
            fun view(label: String): View? = find(root,label)
            fun visible(label: String) = action(label)?.visibility == View.VISIBLE
            fun iconOk(label: String) = checkNotNull(action(label)) { "missing $label" }.drawable != null

            check(visible("选择整屏") && iconOk("选择整屏")) { "full screen missing before selection" }
            check(iconOk("取消截图")) { "cancel icon missing" }
            check(visible("识别文字") && action("识别文字")?.isEnabled==false && action("保存截图")?.isEnabled==false) { "main slots stay visible but wait for a selection" }
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
            check(visible("翻译文字")) { "translation must be available immediately after selecting a region" }
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
            check(visible("选择整屏") && action("选择整屏")?.isEnabled==false) { "full-screen slot must stay disabled without moving" }
            check(visible("识别文字")) { "recognition slot must remain after OCR" }
            check(action("显示原图")?.parent===field(overlay,"resultActions")) { "eye belongs to the second toolbar" }
            evidence.add("stage=text")

            check(visible("翻译文字")) { "translate action missing after OCR" }
            checkNotNull(action("翻译文字")).performClick()
            root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            root.layout(0,0,1080,2400)
            check(translator.calls == 1 && translator.pair == TranslatePair.DEFAULT) {
                "translate call=${translator.calls} pair=${translator.pair}"
            }
            val card = field(overlay,"translationLayer") as? ScreenshotTextLayer
            check(card != null && card.visibility == View.VISIBLE) { "in-image translation missing" }
            check(card!!.text() == "你好世界") { "translation text=${card.text()}" }
            val sourceLayer = field(overlay,"textLayer") as ScreenshotTextLayer
            check(sourceLayer.visibility == View.GONE && sourceLayer.text() == "第一行文字\n第二行文字")
            check(card.currentBlocks().first().top == sourceLayer.currentBlocks().first().top)
            card.updateLine(0,"已编辑译文")
            check(card.copyText().startsWith("已编辑译文"))
            check(sourceLayer.text() == "第一行文字\n第二行文字") { "editing translation changed source" }
            checkNotNull(action("显示原图")).performClick()
            check((field(overlay,"resultLayer") as View).visibility == View.INVISIBLE)
            checkNotNull(action("显示识别结果")).performClick()
            check(card.visibility == View.VISIBLE && visible("复制译文"))
            check(visible("显示原文") && visible("复制译文")) { "translation labels did not switch" }
            evidence.add("stage=translate")

            checkNotNull(view("源语言：自动检测")).performClick()
            root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            root.layout(0,0,1080,2400)
            checkNotNull(view("日文")).performClick()
            root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            root.layout(0,0,1080,2400)
            check(translator.calls == 2 && translator.pair == TranslatePair(TranslateLanguage.JA,TranslateLanguage.ZH)) {
                "language switch call=${translator.calls} pair=${translator.pair}"
            }
            checkNotNull(view("交换源语言和目标语言")).performClick()
            check(translator.calls == 3 && translator.pair == TranslatePair(TranslateLanguage.ZH,TranslateLanguage.JA)) {
                "swap call=${translator.calls} pair=${translator.pair}"
            }
            checkNotNull(action("显示原文")).performClick()
            check((field(overlay,"translationLayer") as View).visibility == View.GONE && sourceLayer.visibility == View.VISIBLE) { "translation did not restore source" }
            evidence.add("translate=language")
            checkNotNull(action("翻译文字")).performClick()
            checkNotNull(action("显示原图")).performClick()
            checkNotNull(action("显示原文")).performClick()
            check((field(overlay,"resultLayer") as View).visibility == View.INVISIBLE &&
                field(overlay,"showingOriginal") == true) { "repeated active tool must return to the original image" }
            check(translator.calls==3) { "reopening cached translation repeated inference" }
            checkNotNull(action("识别文字")).performClick()
            evidence.add("translate=source-visible")

            // Verify the copy payload without overwriting the user's system clipboard.
            val layer = field(overlay,"textLayer") as ScreenshotTextLayer
            check(layer.copyText() == "第一行文字\n第二行文字") { "copy text=${layer.copyText()}" }
            // 上游 `recognitionClipboardMimeData`：译文态取 `overlayTranslation.presentation`，
            // 原文态取 `originalOcrText`；两者不能串。默认 formatting／punctuation 为空 ⇒ 变换恒等，
            // 所以换行按识别结果原样保留。
            val translationCopy = (field(overlay,"translationLayer") as ScreenshotTextLayer).copyText()
            check(translationCopy.isNotBlank() && translationCopy != layer.copyText()) {
                "译文复制内容不对：$translationCopy"
            }
            evidence.add("copytext=ok")

            checkNotNull(action("显示原图")).performClick()
            val result = field(overlay,"resultLayer") as View
            check(result.visibility == View.INVISIBLE) { "original view must hide the text layer" }
            check(visible("显示识别结果")) { "eye label did not switch" }
            checkNotNull(action("显示识别结果")).performClick()
            check(result.visibility == View.VISIBLE) { "text layer must come back with its draft" }
            evidence.add("eye=ok")

            checkNotNull(action("编辑文字")).performClick()
            check(visible("完成编辑")) { "edit label did not switch" }
            check(action("选择整屏")?.isEnabled==false && action("识别文字")?.isEnabled==false) { "region changes must pause while editing" }
            checkNotNull(action("完成编辑")).performClick()
            check(visible("编辑文字")) { "edit exit failed" }
            evidence.add("edit=ok")
            layer.beginDocumentEditing(Rect(60,60,1020,600))
            val document = layer.focusedField()!!
            document.setText("第一行文字\n跨行新增\n第二行文字")
            document.setSelection(2,10)
            check(layer.copyText() == document.text.substring(2,10)) { "cross-line selection failed" }
            layer.endEditing()
            check(layer.text() == "第一行文字\n跨行新增\n第二行文字") { "document edit lost lines" }
            evidence.add("edit=document")

            // T19：结果栏两个文本变换下拉（上游 Formatting／Punctuation 两个 AdSelect）。
            // 默认空值＝恒等；改动从基线重算，清空即回到识别原文。
            val formatEntry = field(overlay,"textFormatButton") as android.widget.TextView
            val punctEntry = field(overlay,"textPunctButton") as android.widget.TextView
            check(formatEntry.parent===field(overlay,"resultActions") &&
                punctEntry.parent===field(overlay,"resultActions")) { "transform entries outside the result bar" }
            check(formatEntry.text=="格式" && punctEntry.text=="标点") {
                "transform entries must start empty: ${formatEntry.text}/${punctEntry.text}"
            }
            overlay.applyTextTransform(true, ScreenshotTextTransforms.REMOVE)
            check(formatEntry.text=="移除换行") { "format label=${formatEntry.text}" }
            check(layer.text()=="第一行文字跨行新增第二行文字") { "remove line breaks text=${layer.text()}" }
            overlay.applyTextTransform(true, ScreenshotTextTransforms.KEEP)
            check(formatEntry.text=="保留换行") { "keep label=${formatEntry.text}" }
            check(layer.text()=="第一行文字\n跨行新增\n第二行文字") { "keep did not restore=${layer.text()}" }
            overlay.applyTextTransform(true, "")
            check(formatEntry.text=="格式" && layer.text()=="第一行文字\n跨行新增\n第二行文字") {
                "clearing format did not restore: ${formatEntry.text}/${layer.text()}"
            }
            checkNotNull(action("完成编辑")).performClick()
            evidence.add("transform=ok")

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
                { request -> rawCalls++; raw = request.composite },{},{},{},{},FakeTranslator())
            val plainRoot = field(plain,"root") as ViewGroup
            plainRoot.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            plainRoot.layout(0,0,1080,2400)
            (field(plain,"selector") as ScreenTextRegionView).selectRegion(Rect(10,10,500,400))
            plainRoot.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            plainRoot.layout(0,0,1080,2400)
            val regionPreview=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888)
            plainRoot.draw(Canvas(regionPreview))
            java.io.File(test.targetContext.cacheDir,"screenshot-region-actions-smoke.png").outputStream().use {
                regionPreview.compress(Bitmap.CompressFormat.PNG,100,it)
            }
            regionPreview.recycle()
            (find(plainRoot,"保存截图") as ImageView).performClick()
            check(rawCalls == 1 && raw == null) { "plain save must pass the original crop" }
            evidence.add("save=original")

            var recognitionCalls=0
            val directTranslator=FakeTranslator()
            val direct=ScreenshotCaptureOverlay(test.targetContext,bitmap,{ _ -> },
                { recognitionCalls++ },{},{},{},directTranslator)
            val directRoot=field(direct,"root") as ViewGroup
            directRoot.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            directRoot.layout(0,0,1080,2400)
            (field(direct,"selector") as ScreenTextRegionView).selectRegion(Rect(60,60,1020,600))
            (find(directRoot,"翻译文字") as ImageView).performClick()
            check(recognitionCalls==1 && directTranslator.calls==0) { "translation must recognize internally first" }
            check((find(directRoot,"取消翻译") as ImageView).isEnabled) { "OCR stage cannot cancel" }
            (find(directRoot,"取消翻译") as ImageView).performClick()
            direct.textResult(listOf(ScreenshotTextBlock(60,60,1020,140,"取消后的迟到识别")))
            check(directTranslator.calls==0 && field(direct,"textLayer")==null) { "cancelled OCR started translation" }
            (find(directRoot,"翻译文字") as ImageView).performClick()
            direct.failure("识别失败，请重试")
            check(directTranslator.calls==0 && (find(directRoot,"翻译文字") as ImageView).isEnabled)
            (find(directRoot,"翻译文字") as ImageView).performClick()
            direct.textResult(listOf(ScreenshotTextBlock(60,60,1020,140,"直接翻译")))
            check(recognitionCalls==3 && directTranslator.calls==1) { "direct translation did not continue after OCR" }
            check((field(direct,"translationLayer") as View).visibility==View.VISIBLE)
            evidence.add("translate=direct,cancel=ocr,edit=viewport-scroll")

            // Bottom-edge selection: the toolbar flips above the region, so the card must flip too.
            val bottomTranslator = FakeTranslator("The meeting starts at 9:30 AM in Room 1204. Please bring the monthly report and confirm the budget before Friday.")
            var translatedImage: Bitmap? = null
            val bottom = ScreenshotCaptureOverlay(test.targetContext,bitmap,
                { request -> translatedImage=request.composite },{},{},{},{},bottomTranslator)
            val bottomRoot = field(bottom,"root") as ViewGroup
            bottomRoot.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            bottomRoot.layout(0,0,1080,2400)
            (field(bottom,"selector") as ScreenTextRegionView).selectRegion(Rect(60,1800,1020,2360))
            bottom.textResult(listOf(ScreenshotTextBlock(60,1800,1020,1900,"底部文字")))
            repeat(2) {
                bottomRoot.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
                bottomRoot.layout(0,0,1080,2400)
            }
            (find(bottomRoot,"翻译文字") as ImageView).performClick()
            repeat(2) {
                bottomRoot.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
                bottomRoot.layout(0,0,1080,2400)
            }
            val bottomCard = field(bottom,"translationLayer") as ScreenshotTextLayer
            val bottomToolbar = field(bottom,"toolbar") as View
            check(bottomCard.visibility == View.VISIBLE) { "bottom in-image translation missing" }
            check(bottomCard.currentBlocks().single().top == 1800)
            val scroll = bottomCard.getChildAt(0) as android.widget.ScrollView
            val fitted = (scroll.getChildAt(0) as ViewGroup).getChildAt(0) as android.widget.EditText
            check(fitted.layout.height <= fitted.height && fitted.layout.getLineEnd(fitted.layout.lineCount-1) == fitted.text.length) { "translation clipped" }
            val languageBar = field(bottom,"pairBar") as View
            check(languageBar.translationY+languageBar.height <= bottomToolbar.translationY) { "language bar covers selection" }
            evidence.add("translate=in-image")
            val preview = Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888)
            bottomRoot.draw(Canvas(preview))
            java.io.File(test.targetContext.cacheDir,"screenshot-in-image-smoke.png").outputStream().use {
                preview.compress(Bitmap.CompressFormat.PNG,100,it)
            }
            preview.recycle()
            (find(bottomRoot,"保存截图") as ImageView).performClick()
            check(translatedImage?.width == 960 && (translatedImage?.height ?: 0) >= 560) { "translated image export missing" }
            translatedImage?.recycle()
            bitmap.recycle()
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
        "SCREENSHOT_TOOLBAR_PASS " + evidence.joinToString(",")
        } finally {
            preferences.edit().apply { oldSections.forEach { (key,value) -> putString(key,value) } }.commit()
            preferences.edit().putString("translate.source",oldSource).putString("translate.target",oldTarget)
                .putString(ScreenshotToolbarEditor.KEY,oldLayout).commit()
        }
    }
    private fun field(target: Any,name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun find(view: View,label: String): View? {
        if (view.contentDescription?.toString() == label) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i),label)?.let { return it }
        return null
    }
}
