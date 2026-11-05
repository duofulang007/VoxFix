package local.voxfix.debug

import local.voxfix.debug.ScreenshotStyleProperty.*
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/** Snow Shot 1.1.8 FreeDraw roles: outline-stroke, outline-width, shape-fill. */
internal class ScreenshotPenControls(context: Context,private val drawing: ScreenshotDrawingLayer) : LinearLayout(context) {
    private val style get()=drawing.inspectedStyle
    private val mixedProperties=drawing.mixedStyleProperties
    private fun mixed(vararg p: ScreenshotStyleProperty)=p.any { it in mixedProperties }
    private val density=resources.displayMetrics.density
    private fun dp(value: Int)=(value*density).toInt()
    private val strokeColors=listOf(0xFFF5222D.toInt(),0xFF52C41A.toInt(),0xFF1677FF.toInt(),0xFFFADB14.toInt(),Color.BLACK)
    private val fillColors=listOf(Color.TRANSPARENT,0xFFFFCCC7.toInt(),0xFFD9F7BE.toInt(),0xFFBAE0FF.toInt(),0xFFFFF1B8.toInt())
    private fun change(vararg properties: ScreenshotStyleProperty,block:(ScreenshotDrawingStyle)->ScreenshotDrawingStyle)=drawing.applyStyle(block(style),*properties)
    private fun cell(label: String,selected: Boolean=false,mixed: Boolean=false,draw:(Canvas,Paint)->Unit,run:(View)->Unit): View = object : View(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) { canvas.save(); canvas.scale(density,density); draw(canvas,paint); if(mixed) ScreenshotMixedStyleMark.draw(canvas,paint); canvas.restore() }
    }.apply {
        contentDescription=label; tooltipText=label; stateDescription=if(mixed) "Mixed" else null; isFocusable=true; isClickable=true; isSelected=selected
        background=GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius=dp(6).toFloat()
            setStroke(dp(1).coerceAtLeast(1),if(selected) 0xFF1677FF.toInt() else 0xFF424242.toInt())
        }
        setOnClickListener { run(it) }
    }
    private fun addCell(row: LinearLayout,view: View) = row.addView(view,LayoutParams(dp(28),dp(28)).apply { if(row.childCount>0) marginStart=dp(4) })
    private fun group(): LinearLayout {
        val row=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(6),dp(6),dp(6),dp(6)) }
        ScreenshotTouchTargets.install(row)
        addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false; addView(row) },LayoutParams(-1,dp(40)))
        return row
    }
    private fun swatch(color: Int,fill: Boolean=false):(Canvas,Paint)->Unit = { canvas,paint ->
        paint.color=if(Color.alpha(color)==0) Color.WHITE else color; paint.style=Paint.Style.FILL
        if(fill) canvas.drawRect(6f,6f,22f,22f,paint) else canvas.drawCircle(14f,14f,6f,paint)
        if(Color.alpha(color)==0) { paint.color=0xFFF5222D.toInt(); paint.strokeWidth=1.5f; canvas.drawLine(6f,22f,22f,6f,paint) }
    }
    private fun line(pattern: ScreenshotStrokePattern,width: Int=2,color: Int=Color.WHITE):(Canvas,Paint)->Unit = { canvas,paint ->
        paint.color=color; paint.style=Paint.Style.STROKE; paint.strokeWidth=width.toFloat(); paint.strokeCap=Paint.Cap.ROUND
        paint.pathEffect=ScreenshotPenRendering.effect(pattern,width.toFloat())
        canvas.drawLine(6f,14f,22f,14f,paint); paint.pathEffect=null
    }
    private fun fill(pattern: ScreenshotFillPattern):(Canvas,Paint)->Unit = { canvas,paint ->
        paint.color=if(style.hasFill) style.fillColor else 0xFFBFBFBF.toInt(); paint.style=Paint.Style.FILL
        if(pattern==ScreenshotFillPattern.SOLID) canvas.drawRect(6f,6f,22f,22f,paint)
        else {
            canvas.save(); canvas.clipRect(6f,6f,22f,22f); paint.strokeWidth=1f
            for(offset in -16..16 step 4) {
                canvas.drawLine(6f+offset,22f,22f+offset,6f,paint)
                if(pattern==ScreenshotFillPattern.CROSS_LINE) canvas.drawLine(6f+offset,6f,22f+offset,22f,paint)
            }
            canvas.restore()
        }
    }
    private fun color(anchor: View,fill: Boolean) {
        var current=style; drawing.beginStylePreview()
        ScreenshotColorPicker.show(anchor,if(fill) "填充色" else "描边色",if(fill) current.fillColor else current.color,fill,
            { value -> current=if(fill) current.copy(fillColor=value ?: current.fillColor,hasFill=value!=null) else current.copy(color=value!!); drawing.previewStyle(current,*(if(fill) (if(value==null) arrayOf(HAS_FILL) else arrayOf(FILL_COLOR,HAS_FILL)) else arrayOf(COLOR))) },
            drawing::finishStylePreview,sample=drawing::pickColor,pattern=if(fill) null else current.strokePattern,
            patternPreview={ current=current.copy(strokePattern=it); drawing.previewStyle(current,STROKE_PATTERN) })
    }
    init {
        orientation=VERTICAL; contentDescription="画笔样式"
        val stroke=group()
        addCell(stroke,cell("描边颜色与样式",mixed=mixed(COLOR,STROKE_PATTERN),draw=line(style.strokePattern,color=style.color)) { color(it,false) })
        strokeColors.forEachIndexed { index,value ->
            addCell(stroke,cell("绘图颜色 ${listOf("红色","绿色","蓝色","黄色","黑色")[index]}",!mixed(COLOR) && style.color==value,draw=swatch(value)) { change(COLOR) { it.copy(color=value) } })
        }
        val width=group()
        addCell(width,cell("当前线宽 ${style.width}，点击切换",mixed=mixed(WIDTH),draw=line(ScreenshotStrokePattern.SOLID,style.width.coerceAtMost(12))) {
            val widths=listOf(2,4,8); change(WIDTH) { it.copy(width=widths[(widths.indexOf(it.width)+1)%widths.size]) }
        })
        listOf(2,4,8).forEach { value -> addCell(width,cell("线宽 $value",!mixed(WIDTH) && style.width==value,draw=line(ScreenshotStrokePattern.SOLID,value)) { change(WIDTH) { it.copy(width=value) } }) }
        val fillRow=group()
        addCell(fillRow,cell("自定义填充颜色",mixed=mixed(FILL_COLOR,HAS_FILL),draw=swatch(if(style.hasFill) style.fillColor else Color.TRANSPARENT,true)) { color(it,true) })
        ScreenshotFillPattern.values().forEachIndexed { index,pattern ->
            addCell(fillRow,cell(listOf("实心填充","交叉线填充","线条填充")[index],!mixed(FILL_PATTERN) && style.fillPattern==pattern,draw=fill(pattern)) { change(FILL_PATTERN) { it.copy(fillPattern=pattern) } })
        }
        fillColors.forEach { value -> addCell(fillRow,cell(if(value==Color.TRANSPARENT) "无填充" else "填充颜色 ${String.format("#%06X",value and 0xFFFFFF)}",
            if(value==Color.TRANSPARENT) !mixed(HAS_FILL) && !style.hasFill else !mixed(HAS_FILL) && style.hasFill && !mixed(FILL_COLOR) && style.fillColor==value,draw=swatch(value,true)) { change(*(if(value==Color.TRANSPARENT) arrayOf(HAS_FILL) else arrayOf(FILL_COLOR,HAS_FILL))) { it.copy(fillColor=value,hasFill=value!=Color.TRANSPARENT) } }) }
    }
}
