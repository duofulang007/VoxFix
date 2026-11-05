package local.voxfix.debug

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

object ScreenshotToolbarCustomizationSmoke {
    fun run(test: Instrumentation) {
        val context = object : android.content.ContextWrapper(test.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences(if (name == "screen-text") "screenshot-toolbar-smoke" else name,mode)
        }
        val preferences = ScreenTextSettingsActivity.preferences(context)
        val previous = preferences.getString(ScreenshotToolbarEditor.KEY, null)
        try {
            var failure: Throwable? = null
            test.runOnMainSync {
                try {
                val configured = ScreenshotToolbarLayout.defaults()
                    .move(ScreenshotAction.COPY_TEXT, ScreenshotAction.SAVE, true)
                    .hide(ScreenshotAction.UNDO)
                preferences.edit().putString(ScreenshotToolbarEditor.KEY,ScreenshotToolbarLayout.defaults().encode()).commit()
                val editor = ScreenshotToolbarEditor(context)
                fun layoutEditor() {
                editor.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.AT_MOST))
                editor.layout(0,0,1080,editor.measuredHeight)
                }
                fun descendants(view: View): List<View> = listOf(view) +
                    if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
                fun stage() = descendants(editor).first { it.contentDescription == "截图工具栏预览" }
                fun drop(surface: View, action: ScreenshotAction, x: Float, y: Float) {
                    val handleDrag = surface.javaClass.getDeclaredMethod("handleDrag", Int::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, ScreenshotAction::class.java)
                        .apply { isAccessible = true }
                    for (kind in listOf(android.view.DragEvent.ACTION_DRAG_STARTED,android.view.DragEvent.ACTION_DRAG_ENTERED,
                        android.view.DragEvent.ACTION_DRAG_LOCATION,android.view.DragEvent.ACTION_DROP,android.view.DragEvent.ACTION_DRAG_ENDED)) {
                        check(handleDrag.invoke(surface, kind, x, y, action) == true)
                    }
                    layoutEditor()
                }
                layoutEditor()
                val initialStage = stage()
                val recognize = descendants(initialStage).first { it.tag == ScreenshotAction.RECOGNIZE }
                drop(initialStage,ScreenshotAction.FULL_SCREEN,(recognize.left+recognize.right)/2f,recognize.top-4f)
                check(ScreenshotToolbarLayout.decode(preferences.getString(ScreenshotToolbarEditor.KEY,null)).columns.any { it ==
                    listOf(ScreenshotAction.FULL_SCREEN,ScreenshotAction.RECOGNIZE,ScreenshotAction.TRANSLATE) }) { "drop above must stack" }
                val first = descendants(stage()).first { it.tag == ScreenshotAction.MOVE }
                drop(stage(),ScreenshotAction.FULL_SCREEN,first.left-8*context.resources.displayMetrics.density,first.bottom.toFloat())
                check(ScreenshotToolbarLayout.decode(preferences.getString(ScreenshotToolbarEditor.KEY,null)) == ScreenshotToolbarLayout.defaults()) {
                    "drop beside must separate the source stack"
                }
                val hiddenZone = descendants(editor).first { it.contentDescription == "隐藏工具区域" }
                drop(hiddenZone,ScreenshotAction.FULL_SCREEN,12f,12f)
                check(ScreenshotToolbarLayout.decode(preferences.getString(ScreenshotToolbarEditor.KEY,null)).hidden == listOf(ScreenshotAction.FULL_SCREEN))
                val recognized = descendants(stage()).first { it.tag == ScreenshotAction.MOVE }
                drop(stage(),ScreenshotAction.FULL_SCREEN,recognized.left-8*context.resources.displayMetrics.density,recognized.bottom.toFloat())
                check(ScreenshotToolbarLayout.decode(preferences.getString(ScreenshotToolbarEditor.KEY,null)) == ScreenshotToolbarLayout.defaults())
                val preview = Bitmap.createBitmap(1080,editor.height,Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(preview).apply { drawColor(ScreenshotToolbarEditor.BACKGROUND); editor.draw(this) }
                java.io.File(test.targetContext.cacheDir,"screenshot-toolbar-settings-smoke.png").outputStream().use {
                    preview.compress(Bitmap.CompressFormat.PNG,100,it)
                }
                preview.recycle()
                val flat = ScreenshotToolbarLayout(ScreenshotAction.values().map { listOf(it) })
                editor.javaClass.getDeclaredMethod("save",ScreenshotToolbarLayout::class.java).apply { isAccessible=true }.invoke(editor,flat)
                for (widthDp in listOf(320,360,411,640)) {
                    val width = (widthDp*context.resources.displayMetrics.density).toInt()
                    editor.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(6000,View.MeasureSpec.AT_MOST))
                    editor.layout(0,0,width,editor.measuredHeight)
                    val surface = stage() as ViewGroup
                    check(surface.width <= width) { "flat editor overflow at $widthDp dp" }
                    for (index in 0 until surface.childCount) {
                        val child = surface.getChildAt(index)
                        check(child.left >= 0 && child.right <= surface.width && child.bottom <= surface.height)
                    }
                    if (widthDp == 320) {
                        check((0 until surface.childCount).map { surface.getChildAt(it).top }.distinct().size > 1)
                        val snapshot = Bitmap.createBitmap(width,editor.height,Bitmap.Config.ARGB_8888)
                        editor.draw(android.graphics.Canvas(snapshot))
                        java.io.File(test.targetContext.cacheDir,"screenshot-toolbar-mobile-flat.png").outputStream().use { snapshot.compress(Bitmap.CompressFormat.PNG,100,it) }
                        snapshot.recycle()
                    }
                }
                val tall = ScreenshotToolbarLayout(listOf(ScreenshotAction.values().filter { it != ScreenshotAction.SEPARATOR }),listOf(ScreenshotAction.SEPARATOR))
                editor.javaClass.getDeclaredMethod("save",ScreenshotToolbarLayout::class.java).apply { isAccessible=true }.invoke(editor,tall)
                val shortWidth = (320*context.resources.displayMetrics.density).toInt()
                val shortHeight = (360*context.resources.displayMetrics.density).toInt()
                editor.measure(View.MeasureSpec.makeMeasureSpec(shortWidth,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(shortHeight,View.MeasureSpec.EXACTLY))
                editor.layout(0,0,shortWidth,shortHeight)
                val canvasArea = stage()
                val canvasViewport = canvasArea.parent as android.widget.ScrollView
                val hiddenArea = descendants(editor).first { it.contentDescription == "隐藏工具区域" }
                val hiddenViewport = hiddenArea.parent as android.widget.ScrollView
                check(canvasViewport.height >= (96*context.resources.displayMetrics.density).toInt()) { "editing workspace collapsed" }
                check(canvasArea.height > canvasViewport.height) { "tall stacks must scroll inside workspace" }
                check(hiddenViewport.bottom <= editor.height && hiddenViewport.height > 0) { "hidden tray must remain on screen" }
                editor.javaClass.getDeclaredMethod("save", ScreenshotToolbarLayout::class.java)
                    .apply { isAccessible = true }.invoke(editor, configured)
                check(ScreenshotToolbarLayout.decode(preferences.getString(ScreenshotToolbarEditor.KEY,null)) == configured)
                val bitmap = Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888)
                val translator = object : ScreenshotTranslator {
                    override fun available() = true
                    override fun cancel() {}
                    override fun translate(text: String, pair: TranslatePair, onResult: (String) -> Unit,
                        onFailure: (String) -> Unit, onUpdate: (String) -> Unit) { onResult("译文") }
                }
                val overlay = ScreenshotCaptureOverlay(context,bitmap,{ _ -> },{},{},{},{},translator)
                fun field(name: String) = overlay.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(overlay)
                val root = field("root") as ViewGroup
                root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
                root.layout(0,0,1080,2400)
                (field("selector") as ScreenTextRegionView).selectRegion(Rect(10,10,1000,500))
                overlay.textResult(listOf(ScreenshotTextBlock(10,10,1000,100,"测试文字")))
                val tools = descendants(field("toolbar") as View)
                check(tools.any { it is ScreenshotToolGroupView }) {
                    "stack missing: config=${field("toolbarLayout")} tools=${tools.map { it.contentDescription }}"
                }
                check(tools.any { it.contentDescription == "保存截图" })
                check(tools.none { it.contentDescription == "复制文字" }) // Text actions belong to the shared second panel.
                val grouped = tools.filterIsInstance<ScreenshotToolGroupView>()
                check(grouped.all { it.layoutParams.width == (32*context.resources.displayMetrics.density).toInt() })
                check(tools.none { it is android.widget.TextView && it.text.toString() == "▾" })
                val entry = grouped.first { it.contentDescription.toString().contains("箭头") }
                entry.performLongClick()
                repeat(2) { root.forceLayout(); root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY)); root.layout(0,0,1080,2400) }
                val panel = field("stackPopup") as ViewGroup
                check(panel.translationX >= 0 && panel.translationX+panel.width <= root.width)
                check(panel.translationY >= 0 && panel.translationY+panel.height <= root.height)
                check(descendants(panel).any { it.contentDescription == "矩形" })
                entry.performLongClick()
                check(field("stackPopup")==null) { "same group entry must collapse its options" }
                entry.performLongClick()
                check(field("stackPopup")!=null)
                (field("stackBackdrop") as View).performClick()
                check(field("stackPopup")==null) { "outside tap must collapse its options" }
                entry.performLongClick()
                check(root.indexOfChild(field("stackBackdrop") as View)<root.indexOfChild(field("toolbar") as View)) {
                    "dismiss surface must leave the main tool groups reachable"
                }
                check(overlay.back())
                for (width in listOf(540,840,1080)) {
                    repeat(2) {
                    root.forceLayout()
                    root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
                    root.layout(0,0,width,2400)
                    }
                    val toolbar = field("toolbar") as View
                    check(toolbar.translationX >= 0 && toolbar.translationX + toolbar.width <= width) {
                        "toolbar clipped at $width: measured=${toolbar.width} params=${toolbar.layoutParams.width} x=${toolbar.translationX} root=${root.width}"
                    }
                }
                bitmap.recycle()
                } catch (error: Throwable) { failure = error }
            }
            failure?.let { throw it }
        } finally {
            preferences.edit().putString(ScreenshotToolbarEditor.KEY,previous).commit()
        }
    }
}
