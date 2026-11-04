package local.voxfix.debug

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.*
import android.view.*
import java.lang.reflect.Proxy

/** Captures the real pin entry's first window request, without requiring an overlay token. */
object ScreenshotRefinementSmoke {
    fun run(test: Instrumentation): String {
        var failure: Throwable?=null
        test.runOnMainSync { try { pinGeometry(test.targetContext); ScreenshotDrawingRefinementChecks.run(test.targetContext) } catch(error: Throwable) { failure=error } }
        failure?.let { throw it }
        return "SCREENSHOT_REFINEMENT_PASS: pin-entry-original-geometry,thumbnail-restore,edit-geometry-relayout,style-preview-undo-cancel,independent-fill,outline-hit,text-input-cancel,inline-action-level"
    }
    private fun pinGeometry(base: Context) {
        val manager=base.getSystemService(WindowManager::class.java)
        var first: WindowManager.LayoutParams?=null
        val fake=Proxy.newProxyInstance(WindowManager::class.java.classLoader,arrayOf(WindowManager::class.java)) { _,method,args ->
            when(method.name) {
                "addView" -> { first=WindowManager.LayoutParams().apply { copyFrom(args!![1] as WindowManager.LayoutParams) }; null }
                "updateViewLayout","removeView","removeViewImmediate" -> null
                else -> method.invoke(manager,*(args ?: emptyArray()))
            }
        }
        val context=object : ContextWrapper(base) {
            override fun getSystemService(name: String): Any? = if(name==Context.WINDOW_SERVICE) fake else super.getSystemService(name)
        }
        val translator=object : ScreenshotTranslator {
            override fun available()=false
            override fun cancel() {}
            override fun translate(text: String,pair: TranslatePair,onResult:(String)->Unit,onFailure:(String)->Unit,onUpdate:(String)->Unit) {}
        }
        for(region in listOf(Rect(240,480,840,1080),Rect(0,0,1080,2400),Rect(1020,2200,1080,2400),Rect(500,600,520,620))) {
            val bitmap=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888)
            val overlay=ScreenshotCaptureOverlay(context,bitmap,{_->},{},{},{},{},translator)
            fun field(name: String)=overlay.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(overlay)
            val root=field("root") as View
            root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            root.layout(0,0,1080,2400)
            try {
                (field("selector") as ScreenTextRegionView).selectRegion(region)
                first=null
                overlay.javaClass.getDeclaredMethod("pinImage").apply { isAccessible=true }.invoke(overlay)
                val actual=checkNotNull(first) { "pin entry did not create a window" }
                check(Rect(actual.x,actual.y,actual.x+actual.width,actual.y+actual.height)==region) {
                    "pin jumped: selected=$region first=(${actual.x},${actual.y},${actual.width},${actual.height})"
                }
                val pins=ScreenshotPins.javaClass.getDeclaredField("pins").apply { isAccessible=true }.get(ScreenshotPins) as Set<*>
                val pin=pins.single()!!
                val pinClass=pin.javaClass
                fun invoke(name: String)=pinClass.getDeclaredMethod(name).apply { isAccessible=true }.invoke(pin)
                fun geometry(): Rect {
                    val p=pinClass.getDeclaredField("params").apply { isAccessible=true }.get(pin) as WindowManager.LayoutParams
                    return Rect(p.x,p.y,p.x+p.width,p.y+p.height)
                }
                invoke("thumbnail"); invoke("thumbnail")
                check(geometry()==region) { "thumbnail restore lost pin geometry" }
                invoke("resetSize"); check(geometry().sizeEquals(region)) { "100% lost original dimensions" }
                var result: ScreenshotPinResult?=null
                val editor=ScreenshotPinEditor(context,bitmap,region) { result=it }
                try {
                    editor.show()
                    val editorRoot=editor.javaClass.getDeclaredField("root").apply { isAccessible=true }.get(editor) as android.view.ViewGroup
                    val picture=editorRoot.getChildAt(0)
                    val p=picture.layoutParams as android.widget.FrameLayout.LayoutParams
                    check(Rect(p.leftMargin,p.topMargin,p.leftMargin+p.width,p.topMargin+p.height)==region) { "edit changed pin geometry" }
                    val drawing=editor.javaClass.getDeclaredField("layer").apply { isAccessible=true }.get(editor) as ScreenshotDrawingLayer
                    val mark=ScreenshotDrawingStroke(ScreenshotDrawingTool.PEN,listOf(ScreenshotDrawingPoint(10f,10f)),Color.RED,4f)
                    drawing.history.add(mark)
                    editor.relayout(Rect(0,0,region.width(),region.height()))
                    check(drawing.history.items==listOf(mark)) { "relayout discarded unfinished pin edits" }
                } finally { editor.close(); result?.bitmap?.recycle() }
                if(region.left==240) {
                    overlay.javaClass.getDeclaredMethod("beginDrawingText",ScreenshotDrawingPoint::class.java,Integer::class.java)
                        .apply { isAccessible=true }.invoke(overlay,ScreenshotDrawingPoint(300f,600f),null)
                    val input=field("annotationInput") as ScreenshotAnnotationInput
                    val toolbar=field("toolbar") as View
                    val buttons=input.actions.layoutParams as android.widget.FrameLayout.LayoutParams
                    check(kotlin.math.abs(buttons.topMargin-toolbar.translationY-toolbar.measuredHeight-8*context.resources.displayMetrics.density)<=2) {
                        "text confirmation left the shared second-toolbar level"
                    }
                    check(!toolbar.isEnabled || !findEnabledPen(toolbar)) { "main tools can interrupt a text draft" }
                    input.field.setText("取消不落历史")
                    check(overlay.back())
                    check((field("drawing") as ScreenshotDrawingLayer).history.items.isEmpty())
                }
            } finally { ScreenshotPins.closeAll(); overlay.dismiss(manager); bitmap.recycle() }
        }
    }
    private fun Rect.sizeEquals(other: Rect)=width()==other.width() && height()==other.height()
    private fun findEnabledPen(view: View): Boolean {
        if(view.contentDescription=="画笔") return view.isEnabled
        if(view is android.view.ViewGroup) for(i in 0 until view.childCount) if(findEnabledPen(view.getChildAt(i))) return true
        return false
    }
}
