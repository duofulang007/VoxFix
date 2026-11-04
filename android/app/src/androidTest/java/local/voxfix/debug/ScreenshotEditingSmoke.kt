package local.voxfix.debug

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.widget.EditText

/** A one-line OCR box must not become a one-line document editor. */
internal object ScreenshotEditingSmoke {
    fun run(test: Instrumentation) {
        test.runOnMainSync {
            val image=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val original="第一行原文\n第二行原文"
            val layer=ScreenshotTextLayer(test.targetContext,image,listOf(
                ScreenshotTextBlock(60,1800,1020,1840,"第一行原文"),
                ScreenshotTextBlock(60,1840,1020,1880,"第二行原文")), {}, {})
            fun layout() {
                layer.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
                layer.layout(0,0,1080,2400)
            }
            layout()
            layer.beginDocumentEditing(Rect(60,1800,1020,1880))
            layer.setEditingViewport(100,1200)
            layout()
            val editor=layer.focusedField() as EditText
            check(editor.top>=100 && editor.bottom<=1200)
            check(editor.height>80) { "editor is still confined to the OCR line height" }
            check(editor.selectionStart==original.length && editor.selectionEnd==original.length) { "edit should append by default" }
            val size=editor.textSize
            val height=editor.height
            val addition="\n新增文字仍使用正常字号，不挤掉已有内容。".repeat(80)
            editor.text.insert(editor.selectionEnd,addition)
            layout()
            check(editor.textSize==size && editor.height==height) { "typing resized the editor or shrank the font" }
            check(editor.layout.height>editor.height && (editor.canScrollVertically(1) || editor.canScrollVertically(-1))) {
                "long document cannot scroll: content=${editor.layout.height} viewport=${editor.height} y=${editor.scrollY}"
            }
            check(layer.text()==original+addition)
            layer.setEditingViewport(100,800)
            layout()
            check(editor.top>=100 && editor.bottom<=800 && editor.textSize==size)
            editor.scrollTo(0,0)
            val preview=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888)
            val canvas=android.graphics.Canvas(preview)
            canvas.drawColor(Color.LTGRAY)
            layer.draw(canvas)
            java.io.File(test.targetContext.cacheDir,"screenshot-editing-smoke.png").outputStream().use {
                preview.compress(Bitmap.CompressFormat.PNG,100,it)
            }
            preview.recycle()
            layer.endEditing()
            check(layer.text()==original+addition) { "ending editing lost original or added text" }
            image.recycle()
        }
    }
}
