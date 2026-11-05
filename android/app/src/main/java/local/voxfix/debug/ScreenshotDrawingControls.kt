package local.voxfix.debug

import local.voxfix.debug.ScreenshotStyleProperty.*
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
    private val edit: (String,String,(String)->Unit) -> Unit,
    /** The screenshot editor renders these in the bar's right column instead of a bottom row. */
    private val showSelectActions: Boolean = true) : LinearLayout(context) {
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private val tool get()=drawing.inspectedTool
    private val style get()=drawing.inspectedStyle
    private var mixedProperties=emptySet<ScreenshotStyleProperty>()
    private fun mixed(vararg p: ScreenshotStyleProperty)=p.any { it in mixedProperties }
    private fun chip(text: String,selected: Boolean=false,description: String=text,run:()->Unit)=TextView(context).apply {
        this.text=text; textSize=13f; gravity=Gravity.CENTER; contentDescription=description; tooltipText=description
        setTextColor(if(selected) 0xFF69B1FF.toInt() else 0xFFFFFFFF.toInt())
        background=GradientDrawable().apply { setColor(if(selected) 0x331677FF else Color.TRANSPARENT); cornerRadius=dp(4).toFloat() }
        minHeight=dp(44); isFocusable=true; setOnClickListener { run() }
    }
    private fun row(views: List<View>)=LinearLayout(context).also { line ->
        views.forEach { line.addView(it,LayoutParams(0,dp(44),1f)) }; addView(line,LayoutParams(-1,-2))
    }
    private fun change(vararg properties: ScreenshotStyleProperty,block: (ScreenshotDrawingStyle)->ScreenshotDrawingStyle) { drawing.applyStyle(block(style),*properties) }
    private fun wheel(view: View,run:(Int)->Unit)=view.apply {
        setOnGenericMotionListener { _,event ->
            val delta=event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if(event.actionMasked==MotionEvent.ACTION_SCROLL && delta.isFinite() && delta!=0f) {
                run(if(delta>0) 1 else -1); true
            } else false
        }
    }
    private fun group(label: String) {
        addView(View(context).apply { setBackgroundColor(0xFF3A3A3C.toInt()) },LayoutParams(-1,dp(1).coerceAtLeast(1)).apply { topMargin=dp(6); bottomMargin=dp(4) })
        addView(TextView(context).apply { text=label; textSize=12f; setTextColor(0xFF9A9A9E.toInt()); setPadding(dp(4),0,0,0) },LayoutParams(-1,dp(24)))
    }
    private fun color(label: String,value: Int,none: Boolean=false,property: ScreenshotStyleProperty,block:(ScreenshotDrawingStyle,Int?)->ScreenshotDrawingStyle): View {
        lateinit var button: TextView
        button=chip(label,description=if(label=="颜色") "自定义绘图颜色" else label) {
            val base=style
            drawing.beginStylePreview()
            ScreenshotColorPicker.show(button,label,value,none,{ drawing.previewStyle(block(base,it),*(if(none) (if(it==null) arrayOf(HAS_FILL) else arrayOf(property,HAS_FILL)) else arrayOf(property))) },drawing::finishStylePreview,sample=drawing::pickColor)
        }
        button.stateDescription=if(mixed(property) || (none && mixed(HAS_FILL))) "Mixed" else null
        return button
    }
    private fun slider(property: ScreenshotStyleProperty,label: String,value: Int,max: Int,offset: Int=0,block:(ScreenshotDrawingStyle,Int)->ScreenshotDrawingStyle) {
        val line=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        val readout=TextView(context).apply { text="$label ${if(mixed(property)) "-" else (value+offset).toString()}"; textSize=12f; setTextColor(0xFFFFFFFF.toInt()) }
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
                        if(tracking) drawing.previewStyle(block(base,value+offset),property)
                        else drawing.applyStyle(block(style,value+offset),property)
                    }
                }
                override fun onStopTrackingTouch(bar: SeekBar) { if(tracking) drawing.finishStylePreview(!cancelled); tracking=false }
            })
        },LayoutParams(0,dp(44),1f)); addView(line)
    }
    init { orientation=VERTICAL; refresh() }
    /** False when the active tool has no options; the host must not show an empty card. */
    val hasContent: Boolean get() = childCount>0
    fun refresh() {
        val selectionScroll = (getChildAt(0) as? ScreenshotSelectionActionControls)?.scrollX ?: 0
        removeAllViews()
        mixedProperties=drawing.mixedStyleProperties
        val active=tool ?: return
        if (drawing.tool == ScreenshotDrawingTool.SELECT) {
            val actions = ScreenshotSelectionActionControls(context, drawing)
            addView(actions, LayoutParams(-1, -2))
            if (selectionScroll != 0) actions.post { actions.scrollTo(selectionScroll, 0) }
        }
        if(active==ScreenshotDrawingTool.ERASER || active==ScreenshotDrawingTool.SELECT) return
        if(active==ScreenshotDrawingTool.WATERMARK) {
            // A canvas-level config, so it has no selection actions.
            addView(ScreenshotWatermarkControls(context,drawing,edit),LayoutParams(-1,-2))
            return
        }
        if(active==ScreenshotDrawingTool.PEN) {
            addView(ScreenshotPenControls(context,drawing),LayoutParams(-1,-2))
            if(showSelectActions || drawing.selectedIndices.size>1) selectedActions()
            return
        }
        if(active==ScreenshotDrawingTool.ARROW) {
            addView(ScreenshotArrowControls(context,drawing),LayoutParams(-1,-2))
            if(showSelectActions || drawing.selectedIndices.size>1) selectedActions()
            return
        }
        if(active==ScreenshotDrawingTool.LINE) {
            addView(ScreenshotLineControls(context,drawing),LayoutParams(-1,-2))
            if(showSelectActions || drawing.selectedIndices.size>1) selectedActions()
            return
        }
        if(active==ScreenshotDrawingTool.RECTANGLE) {
            // Shape has its own row in the original palette: kind radio, stroke, width, fill.
            addView(ScreenshotShapeControls(context,drawing),LayoutParams(-1,-2))
            if(showSelectActions || drawing.selectedIndices.size>1) selectedActions()
            return
        }
        if(active==ScreenshotDrawingTool.SPOTLIGHT) {
            addView(ScreenshotSpotlightControls(context,drawing),LayoutParams(-1,-2))
            slider(OPACITY,"透明度",(style.opacity*100).toInt(),100) { s,v -> s.copy(opacity=v/100f) }
            if(showSelectActions || drawing.selectedIndices.size>1) selectedActions()
            return
        }
        val hasWidth=active in setOf(ScreenshotDrawingTool.ARROW,ScreenshotDrawingTool.RECTANGLE,ScreenshotDrawingTool.PEN) ||
            (active in setOf(ScreenshotDrawingTool.HIGHLIGHT,ScreenshotDrawingTool.FILTER) && style.area==ScreenshotAreaMode.PEN)
        val customColor=if(active!=ScreenshotDrawingTool.FILTER) color("颜色",style.color,property=COLOR) { s,c -> s.copy(color=c!!) } else null
        if(drawing.selectedIndices.isEmpty() && active in setOf(ScreenshotDrawingTool.FILTER,ScreenshotDrawingTool.HIGHLIGHT))
            row(ScreenshotAreaMode.values().map { mode -> chip(if(mode==ScreenshotAreaMode.PEN) "画笔" else "矩形",!mixed(AREA) && style.area==mode,"作用方式 ${mode.name}") { drawing.changeArea(mode) } })
        if(active!=ScreenshotDrawingTool.FILTER) {
            val colors=if(active in setOf(ScreenshotDrawingTool.TEXT,ScreenshotDrawingTool.SERIAL))
                listOf(0xFFF5222D.toInt() to "红色",0xFF52C41A.toInt() to "绿色",0xFF1677FF.toInt() to "蓝色",0xFFFADB14.toInt() to "黄色",Color.BLACK to "黑色")
                // `style_presets::strokeColors()`: the rectangle stroke anchors the row.
                else listOf(0xFFF5222D.toInt() to "红色",0xFF52C41A.toInt() to "绿色",0xFF1677FF.toInt() to "蓝色",
                    0xFFFADB14.toInt() to "黄色",Color.BLACK to "黑色")
            row(colors.map { (value,name) ->
                object : View(context) {
                    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
                    override fun onDraw(canvas: Canvas) {
                        paint.style=Paint.Style.FILL; paint.color=value
                        canvas.drawCircle(width/2f,height/2f,dp(9).toFloat(),paint)
                        paint.style=Paint.Style.STROKE; paint.strokeWidth=dp(1).coerceAtLeast(1).toFloat()
                        paint.color=if(!mixed(COLOR) && style.color==value) 0xFF69B1FF.toInt() else 0xFF6A6A6E.toInt()
                        canvas.drawCircle(width/2f,height/2f,dp(12).toFloat(),paint)
                    }
                }.apply { contentDescription="绘图颜色 $name"; tooltipText=contentDescription; isFocusable=true; setOnClickListener { change(COLOR) { it.copy(color=value) } } }
            })
            if(!hasWidth) row(listOf(customColor!!))
        }
        if(active==ScreenshotDrawingTool.FILTER) {
            row(ScreenshotFilter.values().map { filter -> chip(if(filter==ScreenshotFilter.MOSAIC) "马赛克" else "高斯模糊",!mixed(FILTER) && style.filter==filter) { change(FILTER) { it.copy(filter=filter) } } })
            slider(STRENGTH,"强度",style.strength-4,60,4) { s,v -> s.copy(strength=v) }
        }
        if(hasWidth) {
            val widths=if(active in setOf(ScreenshotDrawingTool.HIGHLIGHT,ScreenshotDrawingTool.FILTER)) listOf(8,16,24,40) else listOf(2,4,8)
            row(listOfNotNull(customColor)+widths.map { width -> chip(width.toString(),!mixed(WIDTH) && style.width==width,"线宽 $width") { change(WIDTH) { it.copy(width=width) } } })
        }
        if(active==ScreenshotDrawingTool.HIGHLIGHT && !mixed(AREA) && style.area==ScreenshotAreaMode.RECTANGLE) {
            group("高亮边框")
            row(listOf(color("边框色",style.outlineColor,property=OUTLINE_COLOR) { s,c -> s.copy(outlineColor=c!!) }))
            slider(OUTLINE_WIDTH,"边框宽",style.outlineWidth,12) { s,v -> s.copy(outlineWidth=v) }
        }
        if(active in setOf(ScreenshotDrawingTool.TEXT,ScreenshotDrawingTool.SERIAL)) {
            val supportsFamily=active!=ScreenshotDrawingTool.SERIAL || mixed(SERIAL_SHAPE) || style.serialShape!=ScreenshotSerialShape.CIRCLE
            group("字体与排版")
            val fontSize=ScreenshotFontSize.format(style.fontSize)
            val fontReadout=if(mixed(FONT_SIZE)) "-" else fontSize
            row(listOf(wheel(chip("字号 $fontReadout",description="字号 $fontReadout") {
                edit("字号",fontSize) { value -> ScreenshotFontSize.parse(active,value)?.let { size -> change(FONT_SIZE) { it.copy(fontSize=size) } } }
            }) { delta -> change(FONT_SIZE) { it.copy(fontSize=ScreenshotFontSize.clamp(active,it.fontSize+delta)) } },
                chip("B",!mixed(BOLD) && style.bold,"加粗") { change(BOLD) { it.copy(bold=if(mixed(BOLD)) true else !it.bold) } }.apply { isEnabled=supportsFamily; alpha=if(isEnabled) 1f else .4f }))
            if(active==ScreenshotDrawingTool.SERIAL) {
                row(listOf(ScreenshotSerialShape.OUTLINED_CIRCLE,ScreenshotSerialShape.SOLID_CIRCLE,
                    ScreenshotSerialShape.OUTLINED_SQUARE,ScreenshotSerialShape.SOLID_SQUARE,ScreenshotSerialShape.CIRCLE).mapIndexed { index,shape ->
                    chip(listOf("①","❶","□1","▣1","○")[index],!mixed(SERIAL_SHAPE) && style.serialShape==shape,listOf("空心圆","实心圆","空心方","实心方","圆形（无数字）")[index]) {
                        change(SERIAL_SHAPE,SERIAL_GEOMETRY_VERSION) { it.copy(serialShape=shape,serialGeometryVersion=1) }
                    }
                })
                val number=drawing.inspectedObject?.number ?: drawing.nextNumber
                val numberReadout=if(drawing.mixedNumber) "-" else number.toString()
                if(mixed(SERIAL_SHAPE) || style.serialShape!=ScreenshotSerialShape.CIRCLE)
                    row(listOf(wheel(chip("序号 $numberReadout",description="序号 $numberReadout，可滚轮调整") { edit("序号",number.toString()) { value -> value.toLongOrNull()?.let(drawing::setNumber) } },drawing::stepNumber)))
                if(drawing.selectedIndices.size==1) row(listOf(chip(if(drawing.selected!!.boundTextId==0L) "创建关联文字" else "编辑关联文字") { drawing.editSerialText() }))
            } else if(drawing.selectedIndices.size==1) row(listOf(chip("编辑文字") { drawing.editSelectedText() }))
            run {
                row(listOf("sans-serif" to "默认","serif" to "衬线","monospace" to "等宽").map { (family,label) ->
                    chip(label,!mixed(FONT_FAMILY) && style.fontFamily==family,"字体 $label") { change(FONT_FAMILY) { it.copy(fontFamily=family) } }.apply { isEnabled=supportsFamily; alpha=if(isEnabled) 1f else .4f }
                })
                lateinit var familyButton: TextView
                familyButton=chip(if(mixed(FONT_FAMILY)) "Mixed" else style.fontFamily,description="选择字体") { ScreenshotFontPicker.show(familyButton,drawing) }
                familyButton.isEnabled=supportsFamily; familyButton.alpha=if(supportsFamily) 1f else .4f
                row(listOf(familyButton))
                row(listOf(24f,30f,42f,54f).map { size -> chip(ScreenshotFontSize.format(size),!mixed(FONT_SIZE) && style.fontSize==size,"字号 ${ScreenshotFontSize.format(size)}") { change(FONT_SIZE) { it.copy(fontSize=size) } } })
                group("描边与填充")
                val modernSerial=active==ScreenshotDrawingTool.SERIAL && !mixed(SERIAL_GEOMETRY_VERSION) && style.serialGeometryVersion==1
                val supportsFill=mixed(SERIAL_SHAPE) || !modernSerial || style.serialShape !in setOf(ScreenshotSerialShape.SOLID_CIRCLE,ScreenshotSerialShape.SOLID_SQUARE)
                row(listOfNotNull(if(modernSerial) null else color(if(active==ScreenshotDrawingTool.TEXT) "描边色" else "轮廓色",style.outlineColor,property=OUTLINE_COLOR) { s,c -> s.copy(outlineColor=c!!) },
                    color("填充色",style.fillColor,true,property=FILL_COLOR) { s,c -> s.copy(fillColor=c ?: s.fillColor,hasFill=c!=null) }.apply { isEnabled=supportsFill; alpha=if(isEnabled) 1f else .4f }))
                if(!modernSerial) slider(OUTLINE_WIDTH,"描边宽",style.outlineWidth,12) { s,v -> s.copy(outlineWidth=v) }
                row(ScreenshotFillPattern.values().mapIndexed { index,pattern ->
                    chip(listOf("实心填充","交叉线填充","线条填充")[index],!mixed(HAS_FILL) && style.hasFill && !mixed(FILL_PATTERN) && style.fillPattern==pattern) {
                        change(FILL_PATTERN,HAS_FILL,*(if(active==ScreenshotDrawingTool.SERIAL) arrayOf(SERIAL_GEOMETRY_VERSION) else emptyArray())) { it.copy(fillPattern=pattern,hasFill=true,serialGeometryVersion=if(active==ScreenshotDrawingTool.SERIAL) 1 else it.serialGeometryVersion) }
                    }.apply { isEnabled=supportsFill; alpha=if(isEnabled) 1f else .4f }
                })
                row(listOf(Color.TRANSPARENT to "无填充",0xFFFFCCC7.toInt() to "浅红",0xFFD9F7BE.toInt() to "浅绿",
                    0xFFBAE0FF.toInt() to "浅蓝",0xFFFFF1B8.toInt() to "浅黄").map { (value,label) ->
                    chip(label,if(value==Color.TRANSPARENT) !mixed(HAS_FILL) && !style.hasFill else !mixed(HAS_FILL) && style.hasFill && !mixed(FILL_COLOR) && style.fillColor==value,"背景填充 $label") {
                        change(*(if(value==Color.TRANSPARENT) arrayOf(HAS_FILL) else arrayOf(FILL_COLOR,HAS_FILL))) { it.copy(fillColor=value,hasFill=value!=Color.TRANSPARENT) }
                    }.apply { isEnabled=supportsFill; alpha=if(isEnabled) 1f else .4f }
                })
                if(active==ScreenshotDrawingTool.TEXT) {
                    row(listOf("左","中","右").mapIndexed { index,label -> chip(label,!mixed(ALIGN) && style.align==index,"文字${label}对齐") { change(ALIGN) { it.copy(align=index) } } })
                    row(listOf("上","中","下").mapIndexed { index,label -> chip(label,!mixed(VERTICAL_ALIGN) && style.verticalAlign==index,"文字垂直${label}对齐") { change(VERTICAL_ALIGN) { it.copy(verticalAlign=index) } } })
                    addView(LinearLayout(context).apply {
                        setPadding(dp(6),dp(6),dp(6),dp(6))
                        addView(ScreenshotCornerRadiusButton(context,drawing,"背景圆角"),LayoutParams(dp(56),dp(28)))
                        ScreenshotTouchTargets.install(this)
                    })
                }
            }
        }
        if(showSelectActions || drawing.selectedIndices.size>1) selectedActions()
    }
    private fun selectedActions() {
        if(drawing.selected!=null) {
            val actions=mutableListOf<View>()
            if(drawing.selectedIndices.size==1 && drawing.overlapCount>1) actions.add(chip("切换重叠",description="切换重叠对象，共 ${drawing.overlapCount} 个") { drawing.cycleSelection() })
            actions.add(chip("删除标注") { drawing.deleteSelected() })
            row(actions)
        }
    }
}
