package local.voxfix.debug

import local.voxfix.debug.ScreenshotStyleProperty.*
import android.content.Context
import android.graphics.*
import android.view.View

/**
 * Line style row from Snow Shot 1.1.8 (`screenshottoolpalettestylecontrols.cpp` 1398–1432):
 * a Straight/Curve radio (initial Curve), then the shared stroke colour/pattern and width editors.
 * Arrowheads, shaft and ratio belong to the Arrow tool and are not part of this row.
 */
internal class ScreenshotLineControls(context: Context,private val drawing: ScreenshotDrawingLayer) : ScreenshotStyleRow(context) {
    private val style get()=drawing.inspectedStyle
    private val mixedProperties=drawing.mixedStyleProperties
    private fun mixed(vararg p: ScreenshotStyleProperty)=p.any { it in mixedProperties }
    private fun change(vararg properties: ScreenshotStyleProperty,block:(ScreenshotDrawingStyle)->ScreenshotDrawingStyle)=drawing.applyStyle(block(style),*properties)
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
        contentDescription="直线样式"
        addCell(textCell("直线",!mixed(CURVED) && !style.curved,"直线") { change(CURVED) { it.copy(curved=false) } },40)
        addCell(textCell("曲线",!mixed(CURVED) && style.curved,"曲线") { change(CURVED) { it.copy(curved=true) } },40)
        groupBreak()
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
    }
}
