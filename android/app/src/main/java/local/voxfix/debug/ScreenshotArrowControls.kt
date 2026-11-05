package local.voxfix.debug

import local.voxfix.debug.ScreenshotStyleProperty.*
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Arrow style row: Snow Shot exposes the head shape twice (start and end), each with the same option
 * set, plus the shared stroke colour/pattern/width editors
 * (`screenshottoolpalettestylecontrols.cpp` 1651–1717).
 *
 * The three background-filled outline heads are not offered yet: they need a document background
 * colour that a screenshot over an arbitrary photo does not have.
 */
internal class ScreenshotArrowControls(context: Context,private val drawing: ScreenshotDrawingLayer) : LinearLayout(context) {
    private val style get()=drawing.inspectedStyle
    private val mixedProperties=drawing.mixedStyleProperties
    private fun mixed(vararg p: ScreenshotStyleProperty)=p.any { it in mixedProperties }
    private val density=resources.displayMetrics.density
    private fun dp(value: Int)=(value*density).toInt()
    private fun change(vararg properties: ScreenshotStyleProperty,block:(ScreenshotDrawingStyle)->ScreenshotDrawingStyle)=drawing.applyStyle(block(style),*properties)
    private val labels=mapOf(
        ScreenshotArrowhead.NONE to "无",
        ScreenshotArrowhead.ARROW to "箭头",
        ScreenshotArrowhead.BAR to "横杠",
        ScreenshotArrowhead.DOT to "圆点",
        ScreenshotArrowhead.CIRCLE to "圆",
        ScreenshotArrowhead.CIRCLE_OUTLINE to "空圆",
        ScreenshotArrowhead.INDENTED_TRIANGLE to "凹三角",
        ScreenshotArrowhead.TRIANGLE to "三角",
        ScreenshotArrowhead.TRIANGLE_OUTLINE to "空三角",
        ScreenshotArrowhead.DIAMOND to "菱形",
        ScreenshotArrowhead.DIAMOND_OUTLINE to "空菱形",
        ScreenshotArrowhead.SQUARE to "方块",
        ScreenshotArrowhead.CROWFOOT_ONE to "单叉",
        ScreenshotArrowhead.CROWFOOT_MANY to "多叉",
        ScreenshotArrowhead.CROWFOOT_ONE_OR_MANY to "双叉")
    private fun chip(label: String,selected: Boolean,run:()->Unit): View = TextView(context).apply {
        text=label; textSize=12f; gravity=Gravity.CENTER
        contentDescription=label; tooltipText=label; isSelected=selected
        setTextColor(if(selected) 0xFF69B1FF.toInt() else 0xFFFFFFFF.toInt())
        background=GradientDrawable().apply {
            setColor(if(selected) 0x331677FF else Color.TRANSPARENT); cornerRadius=dp(4).toFloat()
        }
        isFocusable=true; isClickable=true; setOnClickListener { run() }
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
        val row=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(6),dp(4),dp(6),dp(4)) }
        ScreenshotTouchTargets.install(row)
        addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false; addView(row) },LayoutParams(-1,dp(40)))
        return row
    }
    private fun heads(atStart: Boolean) {
        val row=group()
        row.addView(TextView(context).apply { text=if(atStart) "起点" else "终点"; textSize=12f; setTextColor(0xFF9A9A9E.toInt()) },
            LayoutParams(dp(34),dp(32)))
        val current=if(atStart) style.startHead else style.endHead
        ScreenshotArrowhead.values().forEach { head ->
            row.addView(chip((if(atStart) "起点" else "终点")+(labels[head] ?: head.name),!mixed(if(atStart) START_HEAD else END_HEAD) && current==head) {
                change(if(atStart) START_HEAD else END_HEAD) { if(atStart) it.copy(startHead=head) else it.copy(endHead=head) }
            },LayoutParams(dp(56),dp(32)).apply { marginStart=dp(4) })
        }
    }
    private fun swatch(color: Int):(Canvas,Paint)->Unit = { canvas,paint ->
        paint.color=color; paint.style=Paint.Style.FILL; canvas.drawCircle(14f,14f,6f,paint)
    }
    private fun wheel(view: View,run:(Int)->Unit)=view.apply {
        setOnGenericMotionListener { _,event ->
            val delta=event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if(event.actionMasked==MotionEvent.ACTION_SCROLL && delta.isFinite() && delta!=0f) { run(if(delta>0) 1 else -1); true }
            else false
        }
    }
    private fun ratioText(value: Float)=String.format(java.util.Locale.US,"%.1f",value)
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
        orientation=VERTICAL; contentDescription="箭头样式"
        heads(true); heads(false)
        val shaft=group()
        listOf(ScreenshotArrowType.STRAIGHT to "直线",ScreenshotArrowType.CURVE to "曲线",
            ScreenshotArrowType.ELBOW to "折线").forEach { (type,label) ->
            shaft.addView(chip("箭身$label",!mixed(ARROW_TYPE) && style.arrowType==type) { change(ARROW_TYPE) { it.copy(arrowType=type) } },
                LayoutParams(dp(56),dp(32)).apply { marginStart=dp(4) })
        }
        // `handleArrowRatioWheel`: tenths in 10..30, click resets to the identity scale.
        // `shaftConfig`: Plain / Tapered radio; unsupported heads render plain without losing the choice.
        listOf(ScreenshotArrowShaftType.PLAIN to "普通",ScreenshotArrowShaftType.TAPERED to "锥形").forEach { (type,label) ->
            shaft.addView(chip("截面$label",!mixed(ARROW_SHAFT_TYPE) && style.arrowShaftType==type) { change(ARROW_SHAFT_TYPE) { it.copy(arrowShaftType=type) } },
                LayoutParams(dp(56),dp(32)).apply { marginStart=dp(4) })
        }
        shaft.addView(wheel(chip("比例 ${if(mixed(ARROW_RATIO)) "-" else ratioText(style.arrowRatio)}",false) {
            change(ARROW_RATIO) { it.copy(arrowRatio=1f) }
        }) { delta -> change(ARROW_RATIO) { it.copy(arrowRatio=((it.arrowRatio*10).roundToInt()+delta).coerceIn(10,30)/10f) } },
            LayoutParams(dp(72),dp(32)).apply { marginStart=dp(8) })
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
