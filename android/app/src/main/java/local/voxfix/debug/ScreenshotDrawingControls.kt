package local.voxfix.debug

import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Tool-specific controls; continuous previews never rebuild the active slider. */
internal class ScreenshotDrawingControls(context: Context,private val drawing: ScreenshotDrawingLayer,
    private val edit: (String,String,(String)->Unit) -> Unit) : LinearLayout(context) {
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private val tool get()=drawing.selected?.tool ?: drawing.tool
    private val style get()=drawing.inspectedStyle
    private fun chip(text: String,selected: Boolean=false,description: String=text,run:()->Unit)=TextView(context).apply {
        this.text=text; textSize=13f; gravity=Gravity.CENTER; contentDescription=description; tooltipText=description
        setTextColor(if(selected) 0xFF69B1FF.toInt() else 0xFFFFFFFF.toInt())
        background=GradientDrawable().apply { setColor(if(selected) 0x331677FF else Color.TRANSPARENT); cornerRadius=dp(4).toFloat() }
        minHeight=dp(44); isFocusable=true; setOnClickListener { run() }
    }
    private fun row(views: List<View>)=LinearLayout(context).also { line ->
        views.forEach { line.addView(it,LayoutParams(0,dp(44),1f)) }; addView(line,LayoutParams(-1,-2))
    }
    private fun change(block: (ScreenshotDrawingStyle)->ScreenshotDrawingStyle) { drawing.applyStyle(block(style)) }
    private fun group(label: String) {
        addView(View(context).apply { setBackgroundColor(0xFF3A3A3C.toInt()) },LayoutParams(-1,dp(1).coerceAtLeast(1)).apply { topMargin=dp(6); bottomMargin=dp(4) })
        addView(TextView(context).apply { text=label; textSize=12f; setTextColor(0xFF9A9A9E.toInt()); setPadding(dp(4),0,0,0) },LayoutParams(-1,dp(24)))
    }
    private fun color(label: String,value: Int,none: Boolean=false,block:(ScreenshotDrawingStyle,Int?)->ScreenshotDrawingStyle): View {
        lateinit var button: TextView
        button=chip(label,description=if(label=="颜色") "自定义绘图颜色" else label) {
            val base=style
            drawing.beginStylePreview()
            ScreenshotColorPicker.show(button,label,value,none,{ drawing.previewStyle(block(base,it)) },drawing::finishStylePreview)
        }
        return button
    }
    private fun slider(label: String,value: Int,max: Int,offset: Int=0,block:(ScreenshotDrawingStyle,Int)->ScreenshotDrawingStyle) {
        val line=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        val readout=TextView(context).apply { text="$label ${value+offset}"; textSize=12f; setTextColor(0xFFFFFFFF.toInt()) }
        line.addView(readout,LayoutParams(dp(82),dp(44)))
        var base=style
        var cancelled=false
        var tracking=false
        line.addView(SeekBar(context).apply {
            this.max=max; progress=value.coerceIn(0,max); contentDescription=label
            setOnTouchListener { _,event ->
                if(event.actionMasked==MotionEvent.ACTION_CANCEL) {
                    cancelled=true; tracking=false; drawing.finishStylePreview(false); post { refresh() }
                }; false
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { base=style; cancelled=false; tracking=true; drawing.beginStylePreview() }
                override fun onProgressChanged(bar: SeekBar,value: Int,fromUser: Boolean) {
                    readout.text="$label ${value+offset}"; bar.stateDescription=(value+offset).toString()
                    if(fromUser) {
                        if(tracking) drawing.previewStyle(block(base,value+offset))
                        else drawing.applyStyle(block(style,value+offset))
                    }
                }
                override fun onStopTrackingTouch(bar: SeekBar) { if(tracking) drawing.finishStylePreview(!cancelled); tracking=false }
            })
        },LayoutParams(0,dp(44),1f)); addView(line)
    }
    init { orientation=VERTICAL; refresh() }
    /** False when the active tool has no options (eraser, select); the host must not show an empty card. */
    val hasContent: Boolean get() = childCount>0
    fun refresh() {
        removeAllViews()
        val active=tool ?: return
        if(active==ScreenshotDrawingTool.ERASER || active==ScreenshotDrawingTool.SELECT) return
        val hasWidth=active in setOf(ScreenshotDrawingTool.ARROW,ScreenshotDrawingTool.RECTANGLE,ScreenshotDrawingTool.PEN) ||
            (active in setOf(ScreenshotDrawingTool.HIGHLIGHT,ScreenshotDrawingTool.FILTER) && style.area==ScreenshotAreaMode.PEN)
        val customColor=if(active!=ScreenshotDrawingTool.FILTER) color("颜色",style.color) { s,c -> s.copy(color=c!!) } else null
        if(active in setOf(ScreenshotDrawingTool.FILTER,ScreenshotDrawingTool.HIGHLIGHT))
            row(ScreenshotAreaMode.values().map { mode -> chip(if(mode==ScreenshotAreaMode.PEN) "画笔" else "矩形",style.area==mode,"作用方式 ${mode.name}") { drawing.changeArea(mode) } })
        if(active!=ScreenshotDrawingTool.FILTER) {
            row(listOf(0xFFFF4D4F.toInt() to "红色",0xFFFADB14.toInt() to "黄色",0xFF52C41A.toInt() to "绿色",
                0xFF1677FF.toInt() to "蓝色",Color.BLACK to "黑色",Color.WHITE to "白色").map { (value,name) ->
                object : View(context) {
                    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
                    override fun onDraw(canvas: Canvas) {
                        paint.style=Paint.Style.FILL; paint.color=value
                        canvas.drawCircle(width/2f,height/2f,dp(9).toFloat(),paint)
                        paint.style=Paint.Style.STROKE; paint.strokeWidth=dp(1).coerceAtLeast(1).toFloat()
                        paint.color=if(style.color==value) 0xFF69B1FF.toInt() else 0xFF6A6A6E.toInt()
                        canvas.drawCircle(width/2f,height/2f,dp(12).toFloat(),paint)
                    }
                }.apply { contentDescription="绘图颜色 $name"; tooltipText=contentDescription; isFocusable=true; setOnClickListener { change { it.copy(color=value) } } }
            })
            if(!hasWidth) row(listOf(customColor!!))
        }
        if(active==ScreenshotDrawingTool.FILTER) {
            row(ScreenshotFilter.values().map { filter -> chip(if(filter==ScreenshotFilter.MOSAIC) "马赛克" else "高斯模糊",style.filter==filter) { change { it.copy(filter=filter) } } })
            slider("强度",style.strength-4,60,4) { s,v -> s.copy(strength=v) }
        }
        if(hasWidth) {
            val widths=if(active in setOf(ScreenshotDrawingTool.HIGHLIGHT,ScreenshotDrawingTool.FILTER)) listOf(8,16,24,40) else listOf(2,4,8)
            row(listOfNotNull(customColor)+widths.map { width -> chip(width.toString(),style.width==width,"线宽 $width") { change { it.copy(width=width) } } })
        }
        if(active==ScreenshotDrawingTool.HIGHLIGHT && style.area==ScreenshotAreaMode.RECTANGLE) {
            group("高亮边框")
            row(listOf(color("边框色",style.outlineColor) { s,c -> s.copy(outlineColor=c!!) }))
            slider("边框宽",style.outlineWidth,12) { s,v -> s.copy(outlineWidth=v) }
        }
        if(active in setOf(ScreenshotDrawingTool.HIGHLIGHT,ScreenshotDrawingTool.SPOTLIGHT,ScreenshotDrawingTool.TEXT,ScreenshotDrawingTool.SERIAL))
            slider("透明度",(style.opacity*100).toInt(),100) { s,v -> s.copy(opacity=v/100f) }
        if(active in setOf(ScreenshotDrawingTool.TEXT,ScreenshotDrawingTool.SERIAL)) {
            group("字体与排版")
            row(listOf(chip("字号 ${style.fontSize}") { edit("字号",style.fontSize.toString()) { value -> value.toIntOrNull()?.takeIf { it in 8..96 }?.let { size -> change { it.copy(fontSize=size) } } } },
                chip("B",style.bold,"加粗") { change { it.copy(bold=!it.bold) } }))
            if(active==ScreenshotDrawingTool.SERIAL) {
                row(ScreenshotSerialShape.values().mapIndexed { index,shape ->
                    chip(listOf("①","❶","□1","▣1","⑴")[index],style.serialShape==shape,listOf("空心圆","实心圆","空心方","实心方","带圈")[index]) {
                        change { it.copy(serialShape=shape,hasFill=shape in setOf(ScreenshotSerialShape.SOLID_CIRCLE,ScreenshotSerialShape.SOLID_SQUARE)) }
                    }
                })
                val number=drawing.selected?.number ?: drawing.nextNumber
                row(listOf(chip("序号 $number") { edit("序号",number.toString()) { value -> value.toIntOrNull()?.let(drawing::setNumber) } }))
            } else if(drawing.selected!=null) row(listOf(chip("编辑文字") { drawing.editSelectedText() }))
            run {
                row(listOf("sans-serif" to "默认","serif" to "衬线","monospace" to "等宽").map { (family,label) ->
                    chip(label,style.fontFamily==family,"字体 $label") { change { it.copy(fontFamily=family) } }
                })
                group("描边与填充")
                row(listOf(color(if(active==ScreenshotDrawingTool.TEXT) "描边色" else "轮廓色",style.outlineColor) { s,c -> s.copy(outlineColor=c!!) },
                    color("填充色",style.fillColor,true) { s,c -> s.copy(fillColor=c ?: s.fillColor,hasFill=c!=null) }))
                slider("描边宽",style.outlineWidth,12) { s,v -> s.copy(outlineWidth=v) }
                if(active==ScreenshotDrawingTool.TEXT) {
                    row(listOf("左","中","右").mapIndexed { index,label -> chip(label,style.align==index,"文字${label}对齐") { change { it.copy(align=index) } } })
                    row(listOf("上","中","下").mapIndexed { index,label -> chip(label,style.verticalAlign==index,"文字垂直${label}对齐") { change { it.copy(verticalAlign=index) } } })
                    slider("背景圆角",style.cornerRadius,32) { s,v -> s.copy(cornerRadius=v) }
                }
            }
        }
        if(drawing.selected!=null) {
            val actions=mutableListOf<View>()
            if(drawing.overlapCount>1) actions.add(chip("切换重叠",description="切换重叠对象，共 ${drawing.overlapCount} 个") { drawing.cycleSelection() })
            actions.add(chip("删除标注") { drawing.deleteSelected() })
            row(actions)
        }
    }
}
