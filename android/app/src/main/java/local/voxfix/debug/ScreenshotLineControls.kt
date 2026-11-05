package local.voxfix.debug

import local.voxfix.debug.ScreenshotStyleProperty.*
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * Line style row from Snow Shot 1.1.8 (`screenshottoolpalettestylecontrols.cpp` 1398–1432):
 * a Straight/Curve radio (initial Curve), then the shared stroke colour/pattern and width editors.
 * Arrowheads, shaft and ratio belong to the Arrow tool and are not part of this row.
 */
internal class ScreenshotLineControls(context: Context,private val drawing: ScreenshotDrawingLayer) : LinearLayout(context) {
    private val style get()=drawing.inspectedStyle
    private val mixedProperties=drawing.mixedStyleProperties
    private fun mixed(vararg p: ScreenshotStyleProperty)=p.any { it in mixedProperties }
    private val density=resources.displayMetrics.density
    private fun dp(value: Int)=(value*density).toInt()
    private fun change(vararg properties: ScreenshotStyleProperty,block:(ScreenshotDrawingStyle)->ScreenshotDrawingStyle)=drawing.applyStyle(block(style),*properties)
    private fun chip(label: String,curved: Boolean): View = android.widget.TextView(context).apply {
        text=label; textSize=13f; gravity=Gravity.CENTER
        contentDescription=label; tooltipText=label
        setTextColor(if(!mixed(CURVED) && curved==style.curved) 0xFF69B1FF.toInt() else 0xFFFFFFFF.toInt())
        background=GradientDrawable().apply {
            setColor(if(!mixed(CURVED) && curved==style.curved) 0x331677FF else Color.TRANSPARENT); cornerRadius=dp(6).toFloat()
            setStroke(dp(1).coerceAtLeast(1),if(!mixed(CURVED) && curved==style.curved) 0xFF1677FF.toInt() else 0xFF424242.toInt())
        }
        isFocusable=true; isClickable=true; setOnClickListener { change(CURVED) { it.copy(curved=curved) } }
    }
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
    private fun swatch(color: Int):(Canvas,Paint)->Unit = { canvas,paint ->
        paint.color=color; paint.style=Paint.Style.FILL; canvas.drawCircle(14f,14f,6f,paint)
    }
    private fun line(pattern: ScreenshotStrokePattern,width: Int=2,color: Int=Color.WHITE):(Canvas,Paint)->Unit = { canvas,paint ->
        paint.color=color; paint.style=Paint.Style.STROKE; paint.strokeWidth=width.toFloat(); paint.strokeCap=Paint.Cap.ROUND
        paint.pathEffect=ScreenshotPenRendering.effect(pattern,width.toFloat())
        canvas.drawLine(6f,14f,22f,14f,paint); paint.pathEffect=null
    }
    private fun color(anchor: View) {
        var current=style; drawing.beginStylePreview()
        ScreenshotColorPicker.show(anchor,"描边色",current.color,false,
            { value -> current=current.copy(color=value!!); drawing.previewStyle(current,COLOR) },
            drawing::finishStylePreview,sample=drawing::pickColor,pattern=current.strokePattern,
            patternPreview={ value -> current=current.copy(strokePattern=value); drawing.previewStyle(current,STROKE_PATTERN) })
    }
    init {
        orientation=VERTICAL; contentDescription="直线样式"
        addView(LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL; setPadding(dp(6),dp(4),dp(6),dp(4))
            addView(chip("直线",false),LayoutParams(0,dp(44),1f).apply { marginEnd=dp(4) })
            addView(chip("曲线",true),LayoutParams(0,dp(44),1f))
        },LayoutParams(-1,dp(52)))
        val stroke=group()
        addCell(stroke,cell("描边颜色与样式",mixed=mixed(COLOR,STROKE_PATTERN),draw=line(style.strokePattern,color=style.color)) { color(it) })
        listOf(0xFFF5222D.toInt(),0xFF52C41A.toInt(),0xFF1677FF.toInt(),0xFFFADB14.toInt(),Color.BLACK).forEachIndexed { index,value ->
            addCell(stroke,cell("描边颜色 ${listOf("红色","绿色","蓝色","黄色","黑色")[index]}",!mixed(COLOR) && style.color==value,draw=swatch(value)) { change(COLOR) { it.copy(color=value) } })
        }
        listOf(ScreenshotStrokePattern.SOLID to "实线",ScreenshotStrokePattern.DASHED to "虚线",
            ScreenshotStrokePattern.DOTTED to "点线").forEach { (pattern,label) ->
            addCell(stroke,cell("描边$label",!mixed(STROKE_PATTERN) && style.strokePattern==pattern,draw=line(pattern)) { change(STROKE_PATTERN) { it.copy(strokePattern=pattern) } })
        }
        val width=group()
        addCell(width,cell("当前线宽 ${style.width}，点击切换",mixed=mixed(WIDTH),draw=line(ScreenshotStrokePattern.SOLID,style.width.coerceAtMost(12))) {
            val widths=listOf(2,4,8); change(WIDTH) { it.copy(width=widths[(widths.indexOf(it.width)+1)%widths.size]) }
        })
        listOf(2,4,8).forEach { value -> addCell(width,cell("线宽 $value",!mixed(WIDTH) && style.width==value,draw=line(ScreenshotStrokePattern.SOLID,value)) { change(WIDTH) { it.copy(width=value) } }) }
    }
}
