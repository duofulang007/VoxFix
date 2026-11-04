package local.voxfix.debug

import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Edits over the same object; an IME-covered field alone moves into the visible viewport. */
internal class ScreenshotAnnotationInput(private val parent: FrameLayout,private val drawing: ScreenshotDrawingLayer,
    private val point: ScreenshotDrawingPoint,private val index: Int?,private val bounds:()->RectF,
    private val actionBounds:(()->RectF)?=null,private val done:()->Unit) {
    private val context=parent.context
    private fun dp(n: Int)=(n*context.resources.displayMetrics.density).toInt()
    val field=ScreenshotTextEditor(context,index?.let { drawing.history.items[it].text }.orEmpty()).apply {
        contentDescription="标注文字"; setPadding(0,0,0,0)
        val style=drawing.inspectedStyle
        textSize=style.fontSize.toFloat(); typeface=Typeface.create(style.fontFamily,if(style.bold) Typeface.BOLD else Typeface.NORMAL)
        gravity=Gravity.TOP or when(style.align) { 1 -> Gravity.CENTER_HORIZONTAL; 2 -> Gravity.RIGHT; else -> Gravity.LEFT }
        background=GradientDrawable().apply { setColor(0x08FFFFFF); setStroke(dp(1).coerceAtLeast(1),0xFF1677FF.toInt()) }
    }
    private val backdrop=View(context).apply { isClickable=true }
    val actions=LinearLayout(context).apply {
        background=GradientDrawable().apply { setColor(Color.WHITE); cornerRadius=dp(8).toFloat() }
        elevation=dp(6).toFloat()
    }
    private var closed=false
    private val showKeyboard=Runnable { if(!closed) context.getSystemService(InputMethodManager::class.java).showSoftInput(field,InputMethodManager.SHOW_IMPLICIT) }
    init {
        drawing.beginTextPreview(point,index)
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { drawing.previewText(s.toString()) }
            override fun afterTextChanged(s:Editable?) {}
        })
        fun action(label: String,run:()->Unit) { actions.addView(TextView(context).apply {
            text=label; textSize=15f; gravity=Gravity.CENTER; setTextColor(0xFF1677FF.toInt()); contentDescription=label
            setOnClickListener { run() }
        },LinearLayout.LayoutParams(dp(88),dp(44))) }
        action("取消") { done() }
        action("保存") { drawing.addText(point,field.text.toString(),index); done() }
        parent.addView(backdrop,FrameLayout.LayoutParams(-1,-1))
        parent.addView(field); parent.addView(actions)
        field.requestFocus(); field.setSelection(field.length()); field.postDelayed(showKeyboard,80)
    }
    fun place(safeTop: Int,safeBottom: Int) {
        if(closed) return
        val rect=bounds()
        val actionRect=actionBounds?.invoke()
        val barY=(actionRect?.top?.toInt() ?: (safeBottom-dp(44))).coerceIn(safeTop,maxOf(safeTop,safeBottom-dp(44)))
        val available=(barY-safeTop-dp(8)).coerceAtLeast(dp(44))
        val height=maxOf(dp(44),rect.height().toInt()).coerceAtMost(available)
        val top=rect.top.toInt().coerceIn(safeTop,maxOf(safeTop,barY-height-dp(8)))
        val width=rect.width().toInt().coerceIn(dp(44),maxOf(dp(44),parent.width-dp(16)))
        field.layoutParams=FrameLayout.LayoutParams(width,height).apply {
            leftMargin=rect.left.toInt().coerceIn(dp(8),maxOf(dp(8),parent.width-width-dp(8))); topMargin=top
        }
        // The canvas renders the exact output style; displaced input remains readable above the IME.
        field.setTextColor(if(kotlin.math.abs(top-rect.top)>dp(4)) Color.BLACK else Color.TRANSPARENT)
        val actionWidth=(actionRect?.width()?.toInt() ?: dp(176)).coerceAtMost(parent.width).coerceAtLeast(1)
        actions.layoutParams=FrameLayout.LayoutParams(actionWidth,dp(44)).apply {
            leftMargin=(actionRect?.left?.toInt() ?: ((parent.width-actionWidth)/2)).coerceIn(0,maxOf(0,parent.width-actionWidth)); topMargin=barY
        }
    }
    fun close() {
        if(closed) return
        closed=true; field.removeCallbacks(showKeyboard); drawing.cancelTextPreview()
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken,0)
        parent.removeView(field); parent.removeView(actions); parent.removeView(backdrop)
    }
}
