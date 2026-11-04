package local.voxfix.debug

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

internal object ScreenshotTranslationFeedbackSmoke {
    private class Translator : ScreenshotTranslator {
        var calls=0
        var pair=TranslatePair.DEFAULT
        var update:((String)->Unit)?=null
        var finish:((String)->Unit)?=null
        override fun translate(text:String,pair:TranslatePair,onResult:(String)->Unit,onFailure:(String)->Unit,onUpdate:(String)->Unit) {
            calls++; this.pair=pair; update=onUpdate; finish=onResult
        }
        override fun cancel() {}
        override fun available()=true
    }
    fun run(test:Instrumentation) {
        val preferences=ScreenTextSettingsActivity.preferences(test.targetContext)
        val oldSource=preferences.getString("translate.source",null)
        val oldTarget=preferences.getString("translate.target",null)
        try {
        test.runOnMainSync {
            val image=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val translator=Translator()
            var recognized=0
            val overlay=ScreenshotCaptureOverlay(test.targetContext,image,{ _ -> },{ recognized++ },{},{},{},translator)
            val root=field(overlay,"root") as ViewGroup
            fun layout() {
                root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
                root.layout(0,0,1080,2400)
            }
            fun click(label:String) { checkNotNull(find(root,label)) { "missing $label" }.performClick() }
            fun actions(): List<String> {
                fun collect(view: View): List<String> = when(view) {
                    is ImageView -> listOf(view.contentDescription.toString())
                    is ViewGroup -> (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
                    else -> emptyList()
                }
                return collect(field(overlay,"toolbar") as View)
            }
            layout()
            (field(overlay,"selector") as ScreenTextRegionView).selectRegion(Rect(60,600,1020,1000))
            layout()
            val originalSlots=actions().size
            val main=field(overlay,"toolbar") as View
            val anchor=main.translationX to main.translationY
            click("翻译文字")
            val status=field(overlay,"status") as TextView
            check(recognized==1 && status.visibility==View.GONE)
            check(actions().size==originalSlots && "识别文字" in actions() && "取消翻译" in actions()) { "main slots changed during OCR: ${actions()}" }
            overlay.textResult(listOf(ScreenshotTextBlock(60,600,1020,700,"first"),ScreenshotTextBlock(60,720,1020,820,"second")))
            check(status.visibility==View.GONE)
            check((field(overlay,"pairBar") as View).visibility==View.VISIBLE)
            translator.update!!("你")
            val partial=field(overlay,"translationLayer") as ScreenshotTextLayer
            check(partial.currentBlocks()[0].text=="你") { "partial token not rendered" }
            translator.update!!("你好")
            check(partial.currentBlocks()[0].text=="你好")
            layout()
            val preview=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(preview))
            java.io.File(test.targetContext.cacheDir,"screenshot-streaming-smoke.png").outputStream().use {
                preview.compress(Bitmap.CompressFormat.PNG,100,it)
            }
            preview.recycle()
            translator.finish!!("你好世界")
            check(translator.calls==1) { "wrapped paragraph translated line by line" }
            layout()
            check(status.visibility==View.GONE && (field(overlay,"pairBar") as View).visibility==View.VISIBLE)
            check(actions().size==originalSlots && "识别文字" in actions() && "显示原文" in actions())
            check((main.translationX to main.translationY)==anchor) { "main toolbar moved after translation" }
            val secondary=field(overlay,"secondaryPanel") as View
            check(secondary.translationY>=main.translationY+main.height) { "details must stay below the main toolbar" }
            click("目标语言：简体中文"); click("英文")
            check(translator.pair==TranslatePair(TranslateLanguage.AUTO,TranslateLanguage.EN)) { "target selection was overwritten" }
            check(status.visibility==View.GONE && actions().size==originalSlots && "取消翻译" in actions())
            translator.finish!!("Hello world")
            layout()
            check(find(root,"目标语言：英文")!=null)
            click("目标语言：英文"); click("英文")
            check(translator.calls==2) { "same language selection restarted translation" }
            image.recycle()
        }
        } finally {
            preferences.edit().putString("translate.source",oldSource).putString("translate.target",oldTarget).commit()
        }
    }
    private fun field(target:Any,name:String):Any?=target.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(target)
    private fun find(view:View,label:String):View? {
        if(view.contentDescription?.toString()==label) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i),label)?.let { return it }
        return null
    }
}
