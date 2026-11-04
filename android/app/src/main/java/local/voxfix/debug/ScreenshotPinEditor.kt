package local.voxfix.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*

/** Result of one pin edit: the composite to show and the project that produced it. */
class ScreenshotPinResult(val document: ScreenshotDocument, val bitmap: Bitmap)

/**
 * Reuses the screenshot drawing engine while keeping the pin's screen geometry unchanged.
 * [image] is the untouched canvas, so reopening the editor never doubles the previous marks.
 */
internal class ScreenshotPinEditor(private val context: Context,private val image: Bitmap,private val geometry: Rect,
    private val memory: ScreenshotToolMemory?=null,private val document: ScreenshotDocument?=null,
    private val finished:(ScreenshotPinResult?)->Unit) {
    private fun dp(n: Int)=(n*context.resources.displayMetrics.density).toInt()
    private val manager=context.getSystemService(WindowManager::class.java)
    private val root=FrameLayout(context)
    private val viewport=document?.viewport?.toRect() ?: Rect(0,0,image.width,image.height)
    private val canvas=FrameLayout(context)
    private val picture=ImageView(context).apply { setImageBitmap(image); scaleType=ImageView.ScaleType.FIT_XY }
    private val layer=ScreenshotDrawingLayer(context,image,memory) { refresh() }
    private val textLayer=document?.text?.let { text ->
        val translated=text.mode==ScreenshotTextMode.TRANSLATION && text.translation.isNotEmpty()
        ScreenshotTextLayer(context,image,if(translated) text.translation else text.blocks,{}, { field ->
            layer.tool=null; refresh()
            context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(field,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        },
            fitToBlock=translated,translationRegion=if(translated) viewport else null)
    }
    private val bar=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; background=surface() }
    private val styles=ScrollView(context).apply { background=surface() }
    private var controls: ScreenshotDrawingControls?=null
    private var controlKey: Pair<ScreenshotDrawingTool?,Int?>?=null
    private var input: ScreenshotAnnotationInput?=null
    private var valuePopup: PopupWindow?=null
    private var top=0; private var bottom=0
    private var closed=false
    private val toolButtons=mutableMapOf<ScreenshotDrawingTool,ImageView>()
    private var undoButton: View?=null
    private var redoButton: View?=null
    private fun surface()=GradientDrawable().apply { setColor(0xFF1C1C1E.toInt()); cornerRadius=dp(8).toFloat() }
    init {
        root.isFocusableInTouchMode=true
        canvas.addView(picture)
        textLayer?.let { canvas.addView(it) }
        canvas.addView(layer)
        root.addView(canvas,FrameLayout.LayoutParams(geometry.width(),geometry.height()).apply { leftMargin=geometry.left; topMargin=geometry.top })
        placeCanvas()
        layer.region=Rect(viewport)
        layer.savedPixelUnit=document?.drawingUnit?.takeIf { it>0 } ?: (context.resources.displayMetrics.density*image.width/manager.currentWindowMetrics.bounds.width())
        layer.savedFontUnit=document?.fontUnit?.takeIf { it>0 } ?: (context.resources.displayMetrics.scaledDensity*image.width/manager.currentWindowMetrics.bounds.width())
        layer.history.load(document?.objects.orEmpty())
        layer.tool=ScreenshotDrawingTool.SELECT
        val tools=LinearLayout(context)
        ScreenshotDrawingTools.actions.forEach { (tool,action) ->
            tools.addView(ImageView(context).apply {
                toolButtons[tool]=this
                setImageResource(ScreenshotToolbarEditor.icon(action)); setPadding(dp(10),dp(10),dp(10),dp(10))
                setColorFilter(Color.WHITE)
                contentDescription=ScreenshotToolbarState().label(action); tooltipText=contentDescription
                setOnClickListener { textLayer?.endEditing(); textLayer?.endLayoutMode(); memory?.remember(action); layer.tool=if(layer.tool==tool) ScreenshotDrawingTool.SELECT else tool; refresh() }
            },LinearLayout.LayoutParams(dp(44),dp(44)))
        }
        textLayer?.let { text ->
            fun textAction(icon: Int,label: String,run:()->Unit) {
                tools.addView(ImageView(context).apply {
                    setImageResource(icon); setColorFilter(Color.WHITE); setPadding(dp(10),dp(10),dp(10),dp(10))
                    contentDescription=label; tooltipText=label; setOnClickListener { layer.tool=null; run(); refresh() }
                },LinearLayout.LayoutParams(dp(44),dp(44)))
            }
            textAction(R.drawable.snow_edit,"编辑识别文字") { text.endLayoutMode(); text.beginEditing() }
            if(document?.text?.mode!=ScreenshotTextMode.TRANSLATION)
                textAction(R.drawable.snow_select,"调整文字位置") { text.endEditing(); text.beginLayoutMode() }
        }
        bar.addView(HorizontalScrollView(context).apply { addView(tools); isHorizontalScrollBarEnabled=false })
        val actions=LinearLayout(context)
        fun action(label: String,run:()->Unit): View = TextView(context).apply {
            text=label; textSize=14f; gravity=Gravity.CENTER; contentDescription=label; setTextColor(0xFF1677FF.toInt())
            setOnClickListener { run() }
        }.also { actions.addView(it,LinearLayout.LayoutParams(0,dp(44),1f)) }
        action("取消") { close() }; undoButton=action("撤销") { layer.undo() }; redoButton=action("重做") { layer.redo() }
        action("完成") {
            try {
                val composed=textLayer?.render(viewport)
                val rendered=layer.render(viewport,composed) ?: Bitmap.createBitmap(image,viewport.left,viewport.top,viewport.width(),viewport.height())
                val region=document?.regions?.takeIf { it.isNotEmpty() }?.let { ScreenshotRegion(it.map(ScreenshotDocRect::toRect)) }
                val result=if(region==null) {
                    if(rendered===image) image.copy(Bitmap.Config.ARGB_8888,false) else rendered
                } else region.mask(if(rendered.isMutable && rendered!==image) rendered else rendered.copy(Bitmap.Config.ARGB_8888,true),viewport)
                val next=(document ?: ScreenshotDocument.full(image.width,image.height))
                    .copy(objects=layer.history.items,drawingUnit=layer.pixelUnit,fontUnit=layer.fontUnit,text=currentText())
                finish(ScreenshotPinResult(next,result))
            }
            catch(error: Exception) { Toast.makeText(context,"贴图导出失败，请重试",Toast.LENGTH_SHORT).show() }
        }
        bar.addView(actions); root.addView(styles); root.addView(bar)
        layer.onTextRequest={point,index ->
            input?.close()
            input=ScreenshotAnnotationInput(root,layer,point,index,{
                val rect=layer.textBounds(point,index)
                RectF(geometry.left+(rect.left-viewport.left)*geometry.width()/viewport.width(),geometry.top+(rect.top-viewport.top)*geometry.height()/viewport.height(),
                    geometry.left+(rect.right-viewport.left)*geometry.width()/viewport.width(),geometry.top+(rect.bottom-viewport.top)*geometry.height()/viewport.height())
            }, {
                val p=bar.layoutParams as FrameLayout.LayoutParams
                val top=(p.topMargin+p.height+dp(8)).toFloat()
                RectF(p.leftMargin.toFloat(),top,(p.leftMargin+p.width).toFloat(),top+dp(44))
            }) { input?.close(); input=null; refresh() }
            place()
        }
        root.setOnApplyWindowInsetsListener { _,insets ->
            top=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).top
            bottom=maxOf(insets.getInsets(WindowInsets.Type.systemBars()).bottom,insets.getInsets(WindowInsets.Type.ime()).bottom)
            place(); insets
        }
        root.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> place() }
        root.setOnKeyListener { _,key,event ->
            if(key==KeyEvent.KEYCODE_BACK) {
                if(event.action==KeyEvent.ACTION_UP) { if(input!=null) { input?.close(); input=null; refresh() } else close() }
                true
            } else false
        }
        refresh()
    }
    private fun editValue(title: String,value: String,save:(String)->Unit) {
        val body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; background=surface(); setPadding(dp(12),dp(8),dp(12),dp(8)) }
        val field=ScreenshotTextEditor(context,value).apply { contentDescription=title; inputType=android.text.InputType.TYPE_CLASS_NUMBER }
        body.addView(field,LinearLayout.LayoutParams(-1,dp(72)))
        val popup=PopupWindow(body,minOf(dp(280),manager.currentWindowMetrics.bounds.width()-dp(16)),-2,true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)); elevation=dp(8).toFloat()
            softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        body.addView(TextView(context).apply {
            text="保存$title"; gravity=Gravity.CENTER; setTextColor(0xFF1677FF.toInt()); contentDescription="保存$title"
            setOnClickListener {
                val number=field.text.toString().toIntOrNull()
                if(number==null || (title=="字号" && number !in 8..96) || (title=="序号" && number !in 1..9999)) field.error="数值超出范围"
                else { save(number.toString()); popup.dismiss() }
            }
        },LinearLayout.LayoutParams(-1,dp(44)))
        valuePopup=popup; popup.setOnDismissListener { valuePopup=null }
        popup.showAtLocation(root,Gravity.CENTER,0,0); field.requestFocus()
    }
    private fun refresh() {
        if(closed) return
        toolButtons.forEach { (tool,button) ->
            button.background=GradientDrawable().apply { setColor(if(layer.tool==tool) 0xFF1677FF.toInt() else Color.TRANSPARENT); cornerRadius=dp(6).toFloat() }
        }
        undoButton?.apply { isEnabled=layer.history.canUndo; alpha=if(isEnabled) 1f else .4f }
        redoButton?.apply { isEnabled=layer.history.canRedo; alpha=if(isEnabled) 1f else .4f }
        val scroll=styles.scrollY
        val key=(layer.selected?.tool ?: layer.tool) to layer.selectedIndex
        if(controls==null || key!=controlKey) {
            styles.removeAllViews(); controls=ScreenshotDrawingControls(context,layer,::editValue)
            styles.addView(controls); controlKey=key
        } else controls?.refresh()
        styles.post { styles.scrollTo(0,scroll) }
        place()
    }
    private fun place() {
        if(root.width<=0 || root.height<=0 || closed) return
        val safeBottom=root.height-bottom-dp(8); val safeTop=top+dp(8)
        fun enabled(view: View,value: Boolean) {
            view.isEnabled=value
            if(view is ViewGroup) for(i in 0 until view.childCount) enabled(view.getChildAt(i),value)
        }
        enabled(bar,input==null)
        bar.alpha=if(input==null) 1f else .4f
        bar.visibility=View.VISIBLE
        styles.visibility=if(input==null && (controls?.childCount ?: 0)>0) View.VISIBLE else View.GONE
        val width=minOf(dp(320),root.width-dp(16)).coerceAtLeast(1)
        val x=geometry.left.coerceIn(dp(8),maxOf(dp(8),root.width-width-dp(8)))
        val barHeight=dp(88)
        val reserve=dp(104)
        val y=if(geometry.bottom+barHeight+dp(8)+reserve<=safeBottom) geometry.bottom+dp(8)
            else if(geometry.top-barHeight-dp(8)-reserve>=safeTop) geometry.top-barHeight-dp(8)-reserve
            else maxOf(safeTop,safeBottom-barHeight-reserve)
        bar.layoutParams=FrameLayout.LayoutParams(width,barHeight).apply { leftMargin=x; topMargin=y }
        val styleHeight=minOf(dp(240),(safeBottom-y-barHeight-dp(8)).coerceAtLeast(1))
        styles.layoutParams=FrameLayout.LayoutParams(width,styleHeight).apply {
            leftMargin=x; topMargin=y+barHeight+dp(8)
        }
        input?.place(safeTop,safeBottom)
    }
    fun show() {
        val params=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT; setFitInsetsTypes(0); windowAnimations=0
            softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        manager.addView(root,params); root.requestFocus(); root.requestApplyInsets()
    }
    fun relayout(rect: Rect) {
        if(closed) return
        geometry.set(rect)
        canvas.layoutParams=FrameLayout.LayoutParams(rect.width(),rect.height()).apply {
            leftMargin=rect.left; topMargin=rect.top
        }
        placeCanvas()
        place()
    }
    private fun placeCanvas() {
        val scale=geometry.width().toFloat()/viewport.width()
        for(view in listOfNotNull(picture,textLayer,layer)) view.layoutParams=FrameLayout.LayoutParams(
            (image.width*scale).toInt().coerceAtLeast(1),(image.height*scale).toInt().coerceAtLeast(1)).apply {
            leftMargin=-(viewport.left*scale).toInt(); topMargin=-(viewport.top*scale).toInt()
        }
    }
    private fun currentText(): ScreenshotDocumentText? {
        val original=document?.text ?: return null
        val blocks=textLayer?.currentBlocks() ?: return original
        return if(original.mode==ScreenshotTextMode.TRANSLATION) original.copy(translation=blocks)
            else if(blocks==original.blocks) original else original.copy(blocks=blocks,translation=emptyList(),translated=false)
    }
    private fun finish(result: ScreenshotPinResult?) {
        if(closed) { result?.bitmap?.recycle(); return }
        closed=true; valuePopup?.dismiss(); input?.close(); layer.close(); picture.setImageDrawable(null)
        runCatching { manager.removeViewImmediate(root) }; finished(result)
    }
    fun close() { finish(null) }
}
