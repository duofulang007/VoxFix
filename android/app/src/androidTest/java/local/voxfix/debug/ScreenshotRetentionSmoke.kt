package local.voxfix.debug

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.widget.Button
import android.widget.EditText

/** Uses synthetic text only; never captures the screen or writes the clipboard. */
internal object ScreenshotRetentionSmoke {
    fun run(test: Instrumentation) {
        test.runOnMainSync {
            val image = Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val translator = object : ScreenshotTranslator {
                var finish: ((String) -> Unit)? = null
                override fun available() = true
                override fun cancel() {}
                override fun translate(text: String, pair: TranslatePair, onResult: (String) -> Unit,
                                       onFailure: (String) -> Unit, onUpdate: (String) -> Unit) { finish = onResult }
            }
            val capture = ScreenshotCaptureOverlay(test.targetContext,image,{ _ -> },{},{},{},{},translator)
            check(capture.retainedResult() == null)
            capture.textResult(listOf(ScreenshotTextBlock(10,10,1000,60,"第一行"),
                ScreenshotTextBlock(10,60,1000,110,"第二行")))
            val source = field(capture,"textLayer") as ScreenshotTextLayer
            val line = source.getChildAt(1) as EditText
            line.requestFocus(); line.setSelection(1,3)
            val selection = checkNotNull(capture.retainedResult())
            check(selection.value == "第一行\n第二行" && selection.start == 5 && selection.end == 7)
            source.beginDocumentEditing(Rect(10,10,1000,110))
            source.focusedField()!!.apply { setText("修改原文\n跨行草稿"); setSelection(2,7) }
            capture.javaClass.getDeclaredMethod("startTranslation").apply { isAccessible = true }.invoke(capture)
            val pending = checkNotNull(capture.retainedResult())
            check(pending.value == "修改原文\n跨行草稿" && pending.start == 2 && pending.end == 7)
            check(!pending.translation && pending.alternate == null) { "partial translation retained as a completed draft" }
            translator.finish!!("译文一和译文二")
            val translated = field(capture,"translationLayer") as ScreenshotTextLayer
            translated.beginDocumentEditing(Rect(10,10,1000,110))
            translated.focusedField()!!.apply { setText("已修译文\n保留整段"); setSelection(1,6) }
            val state = checkNotNull(capture.retainedResult())
            check(state.translation && state.value == "已修译文\n保留整段" && state.start == 1 && state.end == 6)
            check(state.alternate?.value == "修改原文\n跨行草稿")
            val recovered = ScreenTextOverlay(test.targetContext,null,{},{})
            recovered.restoreResult(state)
            val editor = field(recovered,"text") as EditText
            check(editor.selectionStart == 1 && editor.selectionEnd == 6)
            val toggle = field(recovered,"switchResult") as Button
            check(toggle.visibility == View.VISIBLE && toggle.text == "原文")
            toggle.performClick()
            check(editor.text.toString() == "修改原文\n跨行草稿" && editor.selectionStart == 2 && editor.selectionEnd == 7)
            editor.append("新增")
            val rotated = ScreenTextOverlay(test.targetContext,null,{},{})
            rotated.restoreResult(checkNotNull(recovered.retainedResult()))
            (field(rotated,"switchResult") as Button).performClick()
            check((field(rotated,"text") as EditText).text.toString() == state.value)
            (field(rotated,"switchResult") as Button).performClick()
            check((field(rotated,"text") as EditText).text.toString().endsWith("新增"))
            check(!(field(rotated,"region") as Button).isEnabled)
            image.recycle()
        }
    }
    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
}
