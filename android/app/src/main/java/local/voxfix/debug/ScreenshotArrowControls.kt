package local.voxfix.debug

import local.voxfix.debug.ScreenshotStyleProperty.*
import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Arrow style row: Snow Shot exposes the head shape twice (start and end), each with the same option
 * set, plus the shared stroke colour/pattern/width editors
 * (`screenshottoolpalettestylecontrols.cpp` 1651–1717).
 *
 * Each end is one trigger carrying the current head's upstream icon; its options are the same 14
 * files in a 4-column popover grid (`ScreenshotToolPaletteIconOptionEditor`, gridColumnCount 4).
 * The three background-filled outline heads still punch through to the screenshot instead of a
 * document background colour, which a screenshot does not have.
 */
internal class ScreenshotArrowControls(context: Context,private val drawing: ScreenshotDrawingLayer) : ScreenshotStyleRow(context) {
    private val style get()=drawing.inspectedStyle
    private val mixedProperties=drawing.mixedStyleProperties
    private fun mixed(vararg p: ScreenshotStyleProperty)=p.any { it in mixedProperties }
    private var picker: PopupWindow?=null
    /** The last built head grid, for the device suite: a popup needs a window token to show. */
    internal var pickerPanel: View?=null
        private set
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
    private fun label(text: String)=TextView(context).apply {
        this.text=text; textSize=12f; setTextColor(ScreenshotUiTokens.TEXT_DIM)
        gravity=Gravity.CENTER
    }
    /**
     * `ScreenshotToolPaletteIconOptionEditor` for one end: the trigger carries the current head's
     * icon and a tap opens the upstream 4×4 option grid. The original opens it on hover; a touch
     * screen has no hover, so the trigger opens it and the grid closes on pick or outside touch.
     */
    private fun headTrigger(atStart: Boolean) {
        val role=if(atStart) "起点" else "终点"
        addCell(label(role),32)
        val isMixed={ mixed(if(atStart) START_HEAD else END_HEAD) }
        addCell(cell("${role}箭头样式", mixed=isMixed(), draw={ canvas,paint ->
            val head=if(atStart) style.startHead else style.endHead
            ScreenshotArrowGlyphs.draw(canvas,ScreenshotArrowGlyphs.head(head),RectF(2f,5f,26f,19f),
                ScreenshotUiTokens.TEXT,atStart,paint)
        }) { openHeadPicker(it,atStart) })
    }

    /** The popup content: `gridColumnCount = 4`, 4px option spacing, active option outlined. */
    private fun headGrid(atStart: Boolean,onPick:(ScreenshotArrowhead)->Unit): View {
        val grid=GridLayout(context).apply { columnCount=4 }
        val current=if(atStart) style.startHead else style.endHead
        val selectionMixed=mixed(if(atStart) START_HEAD else END_HEAD)
        ScreenshotArrowGlyphs.PANEL_HEADS.forEach { head ->
            val view=cell((if(atStart) "起点" else "终点")+(labels[head] ?: head.name),
                !selectionMixed && current==head,draw={ canvas,paint ->
                    ScreenshotArrowGlyphs.draw(canvas,ScreenshotArrowGlyphs.head(head),RectF(2f,5f,26f,19f),
                        ScreenshotUiTokens.TEXT,atStart,paint)
                }) { onPick(head) }
            grid.addView(view,GridLayout.LayoutParams().apply {
                width=dp(32); height=dp(32); setMargins(dp(2),dp(2),dp(2),dp(2))
            })
        }
        return grid
    }

    /** Builds the panel before showing it, so a detached trigger cannot throw on a stale token. */
    private fun openHeadPicker(anchor: View,atStart: Boolean) {
        val panel=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(8),dp(8),dp(8),dp(8))
            // Matches `ScreenshotColorPanel`s dark surface so both popovers read the same.
            background=GradientDrawable().apply { setColor(ScreenshotUiTokens.PANEL_ELEVATED); cornerRadius=dp(8).toFloat() }
            addView(headGrid(atStart) { head ->
                change(if(atStart) START_HEAD else END_HEAD) { if(atStart) it.copy(startHead=head) else it.copy(endHead=head) }
                picker?.dismiss()
            })
        }
        pickerPanel=panel
        val window=PopupWindow(panel,-2,-2,true).apply {
            elevation=dp(8).toFloat(); setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); isOutsideTouchable=true
            inputMethodMode=PopupWindow.INPUT_METHOD_NOT_NEEDED
            softInputMode=android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        picker?.dismiss(); picker=window
        if(!anchor.isAttachedToWindow) return
        // Upstream anchors the popover under its trigger (`setPlacement(Bottom)`) and flips it up at
        // the screen edge; the row sits at the bottom, so the flip is the normal case here.
        panel.measure(View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val location=IntArray(2); anchor.getLocationOnScreen(location)
        val screen=resources.displayMetrics
        val gap=dp(4)
        var x=location[0]+(anchor.width-panel.measuredWidth)/2
        var y=location[1]+anchor.height+gap
        if(y+panel.measuredHeight>screen.heightPixels-dp(8)) y=location[1]-panel.measuredHeight-gap
        x=x.coerceIn(dp(8),(screen.widthPixels-panel.measuredWidth-dp(8)).coerceAtLeast(dp(8)))
        y=y.coerceIn(dp(8),(screen.heightPixels-panel.measuredHeight-dp(8)).coerceAtLeast(dp(8)))
        window.showAtLocation(anchor.rootView,Gravity.NO_GRAVITY,x,y)
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
    /** One upstream icon scaled into a 28dp cell, already density-scaled by the caller. */
    private fun glyph(value: ScreenshotArrowGlyphs.Glyph):(Canvas,Paint)->Unit = { canvas,paint ->
        ScreenshotArrowGlyphs.draw(canvas,value,RectF(2f,4f,26f,20f),ScreenshotUiTokens.TEXT,false,paint)
    }
    private fun color(anchor: View) {
        var current=style; drawing.beginStylePreview()
        ScreenshotColorPicker.show(anchor,"描边色",current.color,false,
            { value -> current=current.copy(color=value!!); drawing.previewStyle(current,COLOR) },
            drawing::finishStylePreview,sample=drawing::pickColor,pattern=current.strokePattern,
            patternPreview={ value -> current=current.copy(strokePattern=value); drawing.previewStyle(current,STROKE_PATTERN) })
    }
    /**
     * `IconNumericValuePreviewButton` with the `arrow-ratio` icon: the wheel steps tenths in 10..30
     * and a click returns to the identity scale (`handleArrowRatioWheel`).
     */
    private fun ratioButton(): View {
        val text=if(mixed(ARROW_RATIO)) "-" else ratioText(style.arrowRatio)
        val view=wheel(cell("比例 $text", draw={ canvas,paint ->
            ScreenshotArrowGlyphs.draw(canvas,ScreenshotArrowGlyphs.RATIO,RectF(2f,7f,22f,25f),
                ScreenshotUiTokens.TEXT,false,paint)
            paint.textSize=12f; paint.color=ScreenshotUiTokens.TEXT; paint.textAlign=Paint.Align.LEFT
            paint.style=Paint.Style.FILL
            canvas.drawText(text,24f,16f-(paint.ascent()+paint.descent())/2f,paint)
        }) { change(ARROW_RATIO) { it.copy(arrowRatio=1f) } }) { delta ->
            change(ARROW_RATIO) { it.copy(arrowRatio=((it.arrowRatio*10).roundToInt()+delta)
                .coerceIn(ScreenshotStyleBounds.ARROW_RATIO_MIN,ScreenshotStyleBounds.ARROW_RATIO_MAX)/10f) }
        }
        return view
    }
    init {
        contentDescription="箭头样式"
        // `buildArrowStyleEditor` order: stroke colour/style → stroke width → shaft type → arrow
        // ratio → start head → shaft section → end head. One row here; the head triggers keep a
        // text label because two icons alone are ambiguous on a phone.
        addCell(cell("描边颜色与样式",mixed=mixed(COLOR,STROKE_PATTERN),draw=line(style.strokePattern,color=style.color)) { color(it) })
        listOf(0xFFF5222D.toInt(),0xFF52C41A.toInt(),0xFF1677FF.toInt(),0xFFFADB14.toInt(),Color.BLACK).forEachIndexed { index,value ->
            addCell(cell("描边颜色 ${listOf("红色","绿色","蓝色","黄色","黑色")[index]}",!mixed(COLOR) && style.color==value,draw=swatch(value)) { change(COLOR) { it.copy(color=value) } })
        }
        listOf(ScreenshotStrokePattern.SOLID to "实线",ScreenshotStrokePattern.DASHED to "虚线",
            ScreenshotStrokePattern.DOTTED to "点线").forEach { (pattern,label) ->
            addCell(cell("描边$label",!mixed(STROKE_PATTERN) && style.strokePattern==pattern,draw=line(pattern)) { change(STROKE_PATTERN) { it.copy(strokePattern=pattern) } })
        }
        groupBreak()
        addCell(cell("当前线宽 ${style.width}，点击切换",mixed=mixed(WIDTH),draw=line(ScreenshotStrokePattern.SOLID,style.width.coerceAtMost(12))) {
            val widths=listOf(2,4,8); change(WIDTH) { it.copy(width=widths[(widths.indexOf(it.width)+1)%widths.size]) }
        })
        listOf(2,4,8).forEach { value -> addCell(cell("线宽 $value",!mixed(WIDTH) && style.width==value,draw=line(ScreenshotStrokePattern.SOLID,value)) { change(WIDTH) { it.copy(width=value) } }) }
        groupBreak()
        // `arrowTypeButtonGroup` and `shaftConfig` are icon editors upstream (`arrow-type-*.svg`,
        // `arrow-shaft-*.svg`); the radio semantics stay, only the artwork replaces the label.
        listOf(ScreenshotArrowType.STRAIGHT to "直线",ScreenshotArrowType.CURVE to "曲线",
            ScreenshotArrowType.ELBOW to "折线").forEach { (type,label) ->
            addCell(cell("箭身$label",!mixed(ARROW_TYPE) && style.arrowType==type,draw=glyph(ScreenshotArrowGlyphs.type(type))) {
                change(ARROW_TYPE) { it.copy(arrowType=type) }
            })
        }
        addCell(ratioButton(),46)
        groupBreak()
        headTrigger(true)
        listOf(ScreenshotArrowShaftType.PLAIN to "普通",ScreenshotArrowShaftType.TAPERED to "锥形").forEach { (type,label) ->
            addCell(cell("截面$label",!mixed(ARROW_SHAFT_TYPE) && style.arrowShaftType==type,draw=glyph(ScreenshotArrowGlyphs.shaft(type))) {
                change(ARROW_SHAFT_TYPE) { it.copy(arrowShaftType=type) }
            })
        }
        headTrigger(false)
    }
}
