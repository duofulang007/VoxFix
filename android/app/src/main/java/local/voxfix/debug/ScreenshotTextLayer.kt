package local.voxfix.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.LinearLayout

/**
 * A recognised line: where it sits now, what it says, and — once it has been moved — the place it
 * came from, whose pixels have to be covered again.
 */
data class ScreenshotTextBlock(
    val left: Int, val top: Int, val right: Int, val bottom: Int, val text: String,
    val origin: ScreenshotDocRect? = null
) {
    fun centerX(): Int = (left + right) / 2
    fun centerY(): Int = (top + bottom) / 2
    fun movedTo(rect: ScreenshotDocRect) = copy(left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom)
}

/**
 * Recognized lines stay on their source blocks with sampled backgrounds and no outline.
 *
 * Blocks can also be moved and resized in a layout mode: the text keeps a readable plate at its new
 * position while the original pixels are covered again, so moving a line never leaves the old text
 * showing through or repaints the screenshot permanently.
 */
class ScreenshotTextLayer(
    context: Context, private val source: Bitmap, private val blocks: List<ScreenshotTextBlock>,
    private val changed: (String) -> Unit, private val focus: (EditText) -> Unit,
    private val fitToBlock: Boolean = false,
    private val translationRegion: Rect? = null
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val history = ScreenshotEditHistory(blocks)
    private var restoring = false
    var historyChanged: () -> Unit = {}
    val canUndo get()=history.canUndo
    val canRedo get()=history.canRedo
    private var layoutBlocks = blocks.toList()
    private var layoutMode = false
    private var layoutIndex = -1
    private var layoutResize = false
    private var layoutMoved = false
    private var layoutDown = ScreenshotDrawingPoint(0f,0f)
    private var layoutFrom: ScreenshotTextBlock? = null
    private val layoutSlop = ViewConfiguration.get(context).scaledTouchSlop
    /** 结果栏两个下拉的状态机（上游 `ScreenshotOcrTextEditingSession` 的 formatting／punctuation）。 */
    private val transforms = ScreenshotTextTransformState()
    val transformFormatting: String get() = transforms.formatting
    val transformPunctuation: String get() = transforms.punctuation
    /** 下游只是换行或标点，不触碰版面：改完仍按行回到各自的文字块。 */
    fun setFormatting(value: String, region: Rect? = null): Boolean =
        applyTransform(region) { transforms.setFormatting(value, text()) }
    fun setPunctuation(value: String, region: Rect? = null): Boolean =
        applyTransform(region) { transforms.setPunctuation(value, text()) }
    private fun applyTransform(region: Rect?, compute: () -> String): Boolean {
        // 上游先 beginTextEditing() 再改草稿；这里按需建整段编辑器，不依赖调用方的延时。
        if (documentEditor == null) beginDocumentEditing(region ?: blockBounds())
        val editor = documentEditor ?: return false
        val next = compute()
        if (editor.text.toString() == next) return false
        restoring = true
        editor.setText(next); editor.setSelection(editor.length())
        restoring = false
        recordEdit()
        return true
    }
    private fun blockBounds(): Rect = Rect(
        layoutBlocks.minOfOrNull { it.left } ?: 0,
        layoutBlocks.minOfOrNull { it.top } ?: 0,
        layoutBlocks.maxOfOrNull { it.right } ?: source.width,
        layoutBlocks.maxOfOrNull { it.bottom } ?: source.height)
    private fun recordEdit() {
        if (!restoring) history.record(currentBlocks())
        changed(text()); historyChanged()
    }
    fun undo() { if (!history.canUndo) return; transforms.clear(); restoreEdit(history.undo()) }
    fun redo() { if (!history.canRedo) return; transforms.clear(); restoreEdit(history.redo()) }
    private fun restoreEdit(value: List<ScreenshotTextBlock>?) {
        if (value == null) return
        restoring=true
        try {
            layoutBlocks=value
            documentEditor?.let {
                it.setText(value.joinToString("\n"){block->block.text}); it.setSelection(it.length())
            } ?: fields.forEachIndexed { index,field -> field.setText(value[index].text) }
            refreshGeometry()
        } finally { restoring=false }
        changed(text()); historyChanged(); requestLayout()
    }
    private fun backgroundColor(block: ScreenshotTextBlock): Int {
        val xs=listOf(block.left-2,block.right+2).map { it.coerceIn(0,source.width-1) }
        val ys=listOf(block.top-2,block.bottom+2).map { it.coerceIn(0,source.height-1) }
        val colors=xs.flatMap { x->ys.map { y->source.getPixel(x,y) } }
        fun channel(read:(Int)->Int)=colors.map(read).sorted()[colors.size/2]
        return Color.rgb(channel(Color::red),channel(Color::green),channel(Color::blue))
    }
    private fun sameColor(a: Int, b: Int): Boolean =
        kotlin.math.abs(Color.red(a)-Color.red(b))<10 &&
            kotlin.math.abs(Color.green(a)-Color.green(b))<10 &&
            kotlin.math.abs(Color.blue(a)-Color.blue(b))<10

    /** Sampled at the region's top and bottom edges: a flat plate over a gradient background reads
     *  as a pasted patch, so follow the ramp when the two edges actually differ. */
    private fun regionColors(region: Rect): Pair<Int,Int> =
        backgroundColor(ScreenshotTextBlock(region.left,region.top,region.right,region.top,"")) to
            backgroundColor(ScreenshotTextBlock(region.left,region.bottom,region.right,region.bottom,""))

    private fun regionPlate(top: Int, bottom: Int) = GradientDrawable().apply {
        if (sameColor(top,bottom)) setColor(top) else {
            orientation=GradientDrawable.Orientation.TOP_BOTTOM
            colors=intArrayOf(top,bottom)
        }
        cornerRadius=1f*density
    }
    private fun plate(fill: Int) = GradientDrawable().apply {
        // Fully immersive: the plate is exactly the sampled image colour, with no focus tint.
        setColor(fill)
        cornerRadius = 1f*density
    }
    private fun readable(fill: Int) =
        if (Color.red(fill)*.299+Color.green(fill)*.587+Color.blue(fill)*.114>128) Color.BLACK else Color.WHITE
    /** Colour under the block as it sits now. */
    private var plateColors: List<Int> = blocks.map { backgroundColor(it) }
    /** Colour the block used to sit on; the patch that hides the old pixels keeps it. */
    private val originColors: List<Int> = blocks.map { block ->
        val origin=block.origin
        backgroundColor(if(origin==null) block else ScreenshotTextBlock(origin.left,origin.top,origin.right,origin.bottom,""))
    }
    private fun refreshGeometry() {
        plateColors = layoutBlocks.map { backgroundColor(it) }
        invalidate(); requestLayout()
    }
    private val patchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Painted under every field, so a moved line covers the pixels it came from. */
    private fun drawPatches(canvas: Canvas) {
        if (width<=0 || height<=0) return
        layoutBlocks.forEachIndexed { index,block ->
            val origin=block.origin ?: return@forEachIndexed
            patchPaint.color=originColors.getOrElse(index) { Color.WHITE }
            canvas.drawRect(origin.left*width.toFloat()/source.width, origin.top*height.toFloat()/source.height,
                origin.right*width.toFloat()/source.width, origin.bottom*height.toFloat()/source.height, patchPaint)
        }
    }
    override fun dispatchDraw(canvas: Canvas) {
        drawPatches(canvas)
        super.dispatchDraw(canvas)
    }
    private val reflowRegion = translationRegion ?: Rect(blocks.minOfOrNull { it.left } ?: 0,
        blocks.minOfOrNull { it.top } ?: 0, blocks.maxOfOrNull { it.right } ?: source.width,
        blocks.maxOfOrNull { it.bottom } ?: source.height)
    private val reflow = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isFillViewport=true
        contentDescription="译文，可上下滚动查看全文"
        addView(reflow,LayoutParams(-1,-2))
    }
    private var activeField: EditText? = null
    private var documentEditor: ScreenshotTextEditor? = null
    private var documentRegion: Rect? = null
    private var editorTop = 0
    private var editorBottom = Int.MAX_VALUE
    private val fields: List<EditText> = blocks.mapIndexed { index,block ->
        val fill = if (fitToBlock) backgroundColor(ScreenshotTextBlock(reflowRegion.left,reflowRegion.top,reflowRegion.right,reflowRegion.bottom,"")) else plateColors[index]
        EditText(context).apply {
            setText(block.text); setPadding((2*density).toInt(),0,(2*density).toInt(),0)
            minWidth=0; minimumWidth=0; minHeight=0; minimumHeight=0
            gravity=Gravity.TOP or Gravity.START; includeFontPadding=false
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            background=plate(fill)
            setTextColor(readable(fill))
            isClickable = true
            isFocusableInTouchMode = true
            contentDescription="识别文字，可点按编辑：${block.text.take(40)}"
            setOnFocusChangeListener { _,hasFocus ->
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
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                    // 上游 `recordCurrentText()`：用户改字即丢弃格式化状态，下拉回到空值。
                    if (!restoring) transforms.clear()
                    recordEdit()
                }
                override fun afterTextChanged(s:Editable?) {}
            })
            if (fitToBlock) {
                setTextSize(TypedValue.COMPLEX_UNIT_SP,16f)
                setHorizontallyScrolling(false)
                setPadding((6*density).toInt(),(4*density).toInt(),(6*density).toInt(),(4*density).toInt())
                reflow.addView(this,LinearLayout.LayoutParams(-1,-2))
            } else this@ScreenshotTextLayer.addView(this,LayoutParams(1,1))
        }
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
        val width=measuredWidth; val height=measuredHeight
        if(width<=0||height<=0) return
        if (fitToBlock) {
            scroll.measure(MeasureSpec.makeMeasureSpec(maxOf(2,reflowRegion.width()*width/source.width),MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxOf(2,reflowRegion.height()*height/source.height),MeasureSpec.EXACTLY))
            editingBounds()?.let { region -> documentEditor?.measure(
                MeasureSpec.makeMeasureSpec(region.width(),MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(region.height(),MeasureSpec.EXACTLY)) }
            return
        }
        layoutBlocks.forEachIndexed { i,b ->
            val w=maxOf(2,(b.right-b.left)*width/source.width)
            val h=maxOf(2,(b.bottom-b.top)*height/source.height)
            // Layout params set during onSizeChanged never re-measure in the same pass;
            // measure and place the fields here so they are never left at 1x1.
            val lines=maxOf(1,fields[i].text.lines().size)
            var size=maxOf(8f,h.toFloat()/lines*.82f)
            // Text or box changed: refit instead of spilling out of the new geometry.
            if (fields[i].text.toString() != blocks.getOrNull(i)?.text || layoutBlocks[i] != blocks.getOrNull(i)) {
                val paint = TextPaint(fields[i].paint)
                val text = fields[i].text.toString()
                val available = maxOf(1,w-fields[i].paddingLeft-fields[i].paddingRight)
                var low = 1f; var high = size
                repeat(10) {
                    val candidate = (low+high)/2
                    paint.textSize = candidate
                    val layout = StaticLayout.Builder.obtain(text,0,text.length,paint,available)
                        .setIncludePad(false).build()
                    if (layout.height <= h) low = candidate else high = candidate
                }
                size = low
            }
            fields[i].setTextSize(TypedValue.COMPLEX_UNIT_PX,size)
            fields[i].measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))
        }
        editingBounds()?.let { region -> documentEditor?.measure(
            MeasureSpec.makeMeasureSpec(region.width(),MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(region.height(),MeasureSpec.EXACTLY)) }
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        val width=measuredWidth; val height=measuredHeight
        if(width<=0||height<=0) return
        if (fitToBlock) {
            val x=reflowRegion.left*width/source.width; val y=reflowRegion.top*height/source.height
            scroll.layout(x,y,x+scroll.measuredWidth,y+scroll.measuredHeight)
            editingBounds()?.let { region -> documentEditor?.layout(region.left,region.top,region.right,region.bottom) }
            return
        }
        layoutBlocks.forEachIndexed { i,block ->
            val left=block.left*width/source.width
            val top=block.top*height/source.height
            fields[i].layout(left,top,left+fields[i].measuredWidth,top+fields[i].measuredHeight)
            android.util.Log.i("VoxFix","TEXT_FIELD_RECT i=$i l=$left t=$top w=${fields[i].width} h=${fields[i].height}")
        }
        editingBounds()?.let { region -> documentEditor?.let {
            it.layout(region.left,region.top,region.right,region.bottom)
        } }
    }
    init {
        if (fitToBlock) {
            val (top,bottom)=regionColors(reflowRegion)
            reflow.background=regionPlate(top,bottom)
            addView(scroll,LayoutParams(1,1))
        }
        // Taps in the gaps stay inside the result layer instead of resetting the selection.
        isClickable = true
    }
    /** Layout mode moves whole blocks; the text cursor and selection are not involved. */
    val isLayoutMode: Boolean get() = layoutMode
    fun beginLayoutMode() {
        if (fitToBlock || layoutMode) return
        layoutMode = true
        activeField = null
        fields.forEach { it.clearFocus() }
    }
    fun endLayoutMode() { layoutMode = false }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = layoutMode
    private fun imagePoint(event: MotionEvent) = ScreenshotDrawingPoint(
        (event.x*imageXScale()).coerceIn(0f,source.width.toFloat()),
        (event.y*imageYScale()).coerceIn(0f,source.height.toFloat()))
    private fun imageXScale() = if (width>0) source.width.toFloat()/width else 1f
    private fun imageYScale() = if (height>0) source.height.toFloat()/height else 1f
    private fun blockAt(point: ScreenshotDrawingPoint) = layoutBlocks.indices.reversed()
        .firstOrNull { layoutBlocks[it].let { b -> point.x>=b.left && point.x<=b.right && point.y>=b.top && point.y<=b.bottom } }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (layoutMode) {
            val point=imagePoint(event)
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    layoutIndex=blockAt(point) ?: -1
                    layoutMoved=false
                    layoutDown=point
                    layoutFrom=layoutBlocks.getOrNull(layoutIndex)
                    val block=layoutFrom
                    val grab=grabPx()
                    layoutResize=block!=null && point.x>=block.right-grab && point.y>=block.bottom-grab
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val from=layoutFrom ?: return true
                    val dx=point.x-layoutDown.x; val dy=point.y-layoutDown.y
                    if(!layoutMoved && (kotlin.math.abs(dx)>layoutSlop || kotlin.math.abs(dy)>layoutSlop)) layoutMoved=true
                    if(layoutMoved) {
                        val next=if(layoutResize) ScreenshotDocRect(from.left,from.top,
                            (from.right+dx.round()).coerceIn(from.left+minSize(),source.width.toInt()),
                            (from.bottom+dy.round()).coerceIn(from.top+minSize(),source.height.toInt()))
                            else {
                                val width=from.right-from.left; val height=from.bottom-from.top
                                val left=(from.left+dx.round()).coerceIn(0,source.width-width)
                                val top=(from.top+dy.round()).coerceIn(0,source.height-height)
                                ScreenshotDocRect(left,top,left+width,top+height)
                            }
                        updateBlock(layoutIndex,from,next)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if(layoutMoved) recordEdit()
                    layoutIndex=-1; layoutFrom=null; layoutMoved=false
                }
            }
            return true
        }
        if (documentEditor != null) return true
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
    private fun grabPx()=24f*density*imageXScale()
    private fun minSize()=(24f*density*imageXScale()).round().coerceAtLeast(4)
    private fun Float.round()=kotlin.math.round(this).toInt()
    private fun updateBlock(index: Int,from: ScreenshotTextBlock,rect: ScreenshotDocRect) {
        if(index !in layoutBlocks.indices) return
        val origin=from.origin ?: ScreenshotDocRect(from.left,from.top,from.right,from.bottom)
        layoutBlocks=layoutBlocks.toMutableList().also { it[index]=from.movedTo(rect).copy(origin=origin) }
        plateColors=layoutBlocks.map { backgroundColor(it) }
        fields.getOrNull(index)?.let { field ->
            field.background=plate(plateColors[index])
            field.setTextColor(readable(plateColors[index]))
        }
        invalidate(); requestLayout()
    }
    fun text():String=documentEditor?.text?.toString() ?: fields.joinToString("\n") { it.text.toString() }
    fun retainedResult(): ScreenTextOverlay.ResultState? {
        val value = text().takeIf { it.isNotBlank() } ?: return null
        val field = focusedField()
        val index = fields.indexOf(field)
        val offset = if (index >= 0) fields.take(index).sumOf { it.text.length+1 } else 0
        val start = field?.selectionStart?.takeIf { it >= 0 }?.plus(offset) ?: value.length
        val end = field?.selectionEnd?.takeIf { it >= 0 }?.plus(offset) ?: value.length
        return ScreenTextOverlay.ResultState(value, start, end, false)
    }
    fun updateLine(index:Int,value:String) { fields[index].setText(value) }
    /** Translation input: the same lines, but read-only and without touching the edit draft. */
    fun textForTranslation():String=currentBlocks().joinToString("\n") { it.text }
    fun currentBlocks():List<ScreenshotTextBlock> {
        val lines=documentEditor?.text?.toString()?.split('\n',limit=maxOf(1,layoutBlocks.size))
        return layoutBlocks.mapIndexed { i,b->b.copy(text=if(lines!=null) lines.getOrElse(i) { "" } else fields[i].text.toString()) }
    }
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
    fun beginDocumentEditing(region:Rect) {
        if(documentEditor==null) {
            val previous = activeField
            val previousIndex = fields.indexOf(previous)
            val offset = if (previousIndex >= 0) fields.take(previousIndex).sumOf { it.text.length+1 } else 0
            val selectionStart = if (previousIndex >= 0) offset+maxOf(0,previous!!.selectionStart) else text().length
            val selectionEnd = if (previousIndex >= 0) offset+maxOf(0,previous!!.selectionEnd) else text().length
            documentRegion=Rect(region)
            documentEditor=ScreenshotTextEditor(context,text()).also { editor ->
                editor.setSelection(selectionStart.coerceIn(0,editor.length()),selectionEnd.coerceIn(0,editor.length()))
                editor.addTextChangedListener(object:TextWatcher {
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                        if (!restoring) transforms.clear()
                        recordEdit()
                    }
                    override fun afterTextChanged(s:Editable?) {}
                })
                addView(editor,LayoutParams(1,1))
            }
            fields.forEach { it.visibility=INVISIBLE }
            if (fitToBlock) scroll.visibility=INVISIBLE
            requestLayout()
        }
        activeField=documentEditor
        documentEditor?.requestFocus()
    }
    fun setEditingViewport(top:Int,bottom:Int) {
        if(editorTop==top && editorBottom==bottom) return
        editorTop=top; editorBottom=bottom
        requestLayout()
    }
    fun editingBounds():Rect? {
        val region=documentRegion ?: return null
        if(measuredWidth<=0 || measuredHeight<=0) return null
        val margin=(8*density).toInt()
        val top=editorTop.coerceIn(0,maxOf(0,measuredHeight-2))
        val bottom=minOf(measuredHeight,editorBottom).coerceAtLeast(top+2)
        val w=maxOf(region.width()*measuredWidth/source.width,(240*density).toInt())
            .coerceAtMost(maxOf(2,measuredWidth-2*margin))
        val h=maxOf(region.height()*measuredHeight/source.height,(160*density).toInt()).coerceAtMost(bottom-top)
        val x=(region.left*measuredWidth/source.width).coerceIn(margin,maxOf(margin,measuredWidth-margin-w))
        val y=(region.top*measuredHeight/source.height).coerceIn(top,maxOf(top,bottom-h))
        return Rect(x,y,x+w,y+h)
    }
    fun endEditing() {
        documentEditor?.let { editor ->
            val edited=currentBlocks()
            documentEditor=null; documentRegion=null; removeView(editor)
            restoring=true
            layoutBlocks=edited
            fields.forEachIndexed { i,field -> field.setText(edited[i].text); field.visibility=VISIBLE }
            restoring=false
            refreshGeometry()
            if (fitToBlock) scroll.visibility=VISIBLE
        }
        fields.forEach { it.clearFocus() }; activeField = null
    }
    fun focusedBottom()=focusedField()?.let { it.top+it.height } ?: 0
    fun render(region:Rect):Bitmap {
        endEditing()
        check(width>0 && height>0)
        measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY))
        layout(left,top,right,bottom)
        if (fitToBlock) {
            // Export all reflowed text, including the part below the on-screen scroll viewport.
            val scale=source.width.toFloat()/width
            val outHeight=maxOf(region.height(),kotlin.math.ceil(reflow.height*scale).toInt())
            require(region.width().toLong()*outHeight <= 16_000_000) { "译文图片过长，请分段保存或复制文字" }
            val out=Bitmap.createBitmap(region.width(),outHeight,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(out)
            val (top,bottom)=regionColors(region)
            regionPlate(top,bottom).apply { setBounds(0,0,region.width(),region.height()); draw(canvas) }
            if (outHeight>region.height())
                canvas.drawRect(0f,region.height().toFloat(),region.width().toFloat(),outHeight.toFloat(),
                    Paint().apply { color=bottom })
            canvas.scale(scale,source.height.toFloat()/height)
            reflow.draw(canvas)
            return out
        }
        val crop=Bitmap.createBitmap(source,region.left,region.top,region.width(),region.height())
        val out=try { crop.copy(Bitmap.Config.ARGB_8888,true) ?: error("结果图片创建失败") } finally { if(crop!==source)crop.recycle() }
        val canvas=Canvas(out);canvas.translate(-region.left.toFloat(),-region.top.toFloat());canvas.scale(source.width.toFloat()/width,source.height.toFloat()/height)
        // A moved line leaves a patch of its own colour behind, exactly like the on-screen layer.
        val patch=Paint(Paint.ANTI_ALIAS_FLAG)
        layoutBlocks.forEachIndexed { index,block ->
            val origin=block.origin ?: return@forEachIndexed
            patch.color=originColors.getOrElse(index) { Color.WHITE }
            canvas.drawRect(RectF(origin.left*width.toFloat()/source.width,origin.top*height.toFloat()/source.height,
                origin.right*width.toFloat()/source.width,origin.bottom*height.toFloat()/source.height),patch)
        }
        fields.forEachIndexed { index,field ->
            val label=TextView(context).apply {
                text=field.text.toString();setTextColor(field.currentTextColor);setTextSize(TypedValue.COMPLEX_UNIT_PX,field.textSize)
                setPadding(field.paddingLeft,field.paddingTop,field.paddingRight,field.paddingBottom);includeFontPadding=false;gravity=field.gravity
                background=ColorDrawable(plateColors.getOrElse(index) { Color.WHITE })
            }
            label.measure(MeasureSpec.makeMeasureSpec(field.width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(field.height,MeasureSpec.EXACTLY));label.layout(0,0,field.width,field.height)
            canvas.save();canvas.translate(field.left.toFloat(),field.top.toFloat());label.draw(canvas);canvas.restore()
        }
        return out
    }
}
