package local.voxfix.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView

data class ScreenshotTextBlock(val left: Int,val top: Int,val right: Int,val bottom: Int,val text: String)

/**
 * Recognized lines stay on their source blocks as editable text. Each block carries a thin
 * outline so the extracted text is visibly floating above the frozen screenshot.
 */
class ScreenshotTextLayer(
    context: Context, private val source: Bitmap, private val blocks: List<ScreenshotTextBlock>,
    private val changed: (String) -> Unit, private val focus: (EditText) -> Unit
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val fillColors: List<Int> = blocks.map { backgroundColor(it) }
    private var activeField: EditText? = null
    private fun plate(fill: Int) = GradientDrawable().apply {
        // Fully immersive: the plate is exactly the sampled image colour, with no focus tint.
        setColor(fill)
        cornerRadius = 1f*density
    }
    private fun backgroundColor(block: ScreenshotTextBlock): Int {
        val xs=listOf(block.left-2,block.right+2).map { it.coerceIn(0,source.width-1) }
        val ys=listOf(block.top-2,block.bottom+2).map { it.coerceIn(0,source.height-1) }
        val colors=xs.flatMap { x->ys.map { y->source.getPixel(x,y) } }
        fun channel(read:(Int)->Int)=colors.map(read).sorted()[colors.size/2]
        return Color.rgb(channel(Color::red),channel(Color::green),channel(Color::blue))
    }
    private val fields: List<EditText> = blocks.mapIndexed { index,block ->
        val fill = fillColors[index]
        EditText(context).apply {
            setText(block.text); setPadding((2*density).toInt(),0,(2*density).toInt(),0)
            minWidth=0; minimumWidth=0; minHeight=0; minimumHeight=0
            gravity=Gravity.TOP or Gravity.START; includeFontPadding=false
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            background=plate(fill)
            setTextColor(if (Color.red(fill)*.299+Color.green(fill)*.587+Color.blue(fill)*.114>128) Color.BLACK else Color.WHITE)
            isClickable = true
            isFocusableInTouchMode = true
            contentDescription="识别文字，可点按编辑：${block.text.take(40)}"
            setOnFocusChangeListener { _,hasFocus ->
                background=plate(fill)
                if (hasFocus) activeField = this
            }
            setOnTouchListener { _,event ->
                if(event.actionMasked==MotionEvent.ACTION_DOWN) {
                    android.util.Log.i("VoxFix","TEXT_FIELD_TAP index=$index")
                    activeField = this
                    requestFocus(); focus(this)
                }
                // Let the EditText handle cursor placement, long-press selection and drag handles.
                false
            }
            addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { changed(this@ScreenshotTextLayer.text()); }
                override fun afterTextChanged(s:Editable?) {}
            })
            this@ScreenshotTextLayer.addView(this,LayoutParams(1,1))
        }
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
        val width=measuredWidth; val height=measuredHeight
        if(width<=0||height<=0) return
        blocks.forEachIndexed { i,b ->
            val w=maxOf(2,(b.right-b.left)*width/source.width)
            val h=maxOf(2,(b.bottom-b.top)*height/source.height)
            // Layout params set during onSizeChanged never re-measure in the same pass;
            // measure and place the fields here so they are never left at 1x1.
            fields[i].measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))
            val lines=maxOf(1,b.text.lines().size)
            fields[i].setTextSize(TypedValue.COMPLEX_UNIT_PX,maxOf(8f,h.toFloat()/lines*.82f))
        }
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val width=measuredWidth; val height=measuredHeight
        if(width<=0||height<=0) return
        blocks.forEachIndexed { i,block ->
            val left=block.left*width/source.width
            val top=block.top*height/source.height
            fields[i].layout(left,top,left+fields[i].measuredWidth,top+fields[i].measuredHeight)
            android.util.Log.i("VoxFix","TEXT_FIELD_RECT i=$i l=$left t=$top w=${fields[i].width} h=${fields[i].height}")
        }
    }
    init {
        // Taps in the gaps stay inside the result layer instead of resetting the selection.
        isClickable = true
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val target = fields.minByOrNull { field ->
                val dx = (field.left + field.width / 2f) - event.x
                val dy = (field.top + field.height / 2f) - event.y
                dx * dx + dy * dy
            }
            if (target != null) {
                android.util.Log.i("VoxFix","TEXT_LAYER_TAP field=${fields.indexOf(target)}")
                activeField = target
                target.requestFocus(); focus(target)
            }
        }
        return true
    }
    fun text():String=fields.joinToString("\n") { it.text.toString() }
    fun currentBlocks()=blocks.mapIndexed { i,b->b.copy(text=fields[i].text.toString()) }
    fun focusedField():EditText?=activeField ?: fields.firstOrNull { it.hasFocus() }
    fun copyText():String {
        val field=focusedField()
        if (field!=null && field.selectionStart>=0 && field.selectionEnd>field.selectionStart)
            return field.text.substring(minOf(field.selectionStart,field.selectionEnd),maxOf(field.selectionStart,field.selectionEnd))
        return text()
    }
    fun beginEditing() {
        val target=focusedField() ?: fields.firstOrNull() ?: return
        android.util.Log.i("VoxFix","TEXT_EDIT_BEGIN field=${fields.indexOf(target)} width=${target.width} text=${target.text.length}")
        focus(target); target.requestFocus()
    }
    fun endEditing() { fields.forEach { it.clearFocus() }; activeField = null }
    fun focusedBottom()=focusedField()?.let { it.top+it.height } ?: 0
    fun render(region:Rect):Bitmap {
        check(width>0 && height>0)
        val crop=Bitmap.createBitmap(source,region.left,region.top,region.width(),region.height())
        val out=try { crop.copy(Bitmap.Config.ARGB_8888,true) ?: error("结果图片创建失败") } finally { if(crop!==source)crop.recycle() }
        val canvas=Canvas(out);canvas.translate(-region.left.toFloat(),-region.top.toFloat());canvas.scale(source.width.toFloat()/width,source.height.toFloat()/height)
        fields.forEachIndexed { index,field ->
            val label=TextView(context).apply {
                text=field.text.toString();setTextColor(field.currentTextColor);setTextSize(TypedValue.COMPLEX_UNIT_PX,field.textSize)
                setPadding(0,0,0,0);includeFontPadding=false;gravity=field.gravity
                background=ColorDrawable(fillColors[index])
            }
            label.measure(MeasureSpec.makeMeasureSpec(field.width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(field.height,MeasureSpec.EXACTLY));label.layout(0,0,field.width,field.height)
            canvas.save();canvas.translate(field.left.toFloat(),field.top.toFloat());label.draw(canvas);canvas.restore()
        }
        return out
    }
}
