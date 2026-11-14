package local.voxfix.debug

import local.voxfix.debug.ScreenshotStyleProperty.*
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/**
 * 文字 / 序号 的二级栏：**一条横向属性行**。
 *
 * 顺序对齐原版 `buildTextFamily`（`screenshottoolpalettestylecontrols.cpp` 1994–2185）：
 * 颜色 → 字号（摘要 + S/M/L/XL）→ 字体 → 对齐 → 描边 → 填充 → 圆角。
 * 原版把「描边 / 填充 / 对齐」放进弹层，改造前手机把它们全铺成整宽行，
 * 文字面板因此涨到 11 行 ≈484dp，必须纵向滚动才能碰到圆角。
 */
internal class ScreenshotTextControls(
    context: Context,
    private val drawing: ScreenshotDrawingLayer,
    private val edit: (String,String,(String)->Unit) -> Unit
) : ScreenshotStyleRow(context) {
    private val style get()=drawing.inspectedStyle
    private val tool get()=drawing.inspectedTool
    private val mixedProperties=drawing.mixedStyleProperties
    private fun mixed(vararg p: ScreenshotStyleProperty)=p.any { it in mixedProperties }
    private fun change(vararg properties: ScreenshotStyleProperty,
                       block:(ScreenshotDrawingStyle)->ScreenshotDrawingStyle)=drawing.applyStyle(block(style),*properties)

    private fun swatch(color: Int):(Canvas,Paint)->Unit = { canvas,paint ->
        paint.color=color; paint.style=Paint.Style.FILL; canvas.drawCircle(14f,14f,6f,paint)
    }
    private fun rainbox():(Canvas,Paint)->Unit = { canvas,paint ->
        paint.style=Paint.Style.FILL
        paint.color=style.color
        canvas.drawCircle(14f,14f,7f,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=1f; paint.color=Color.WHITE
        canvas.drawCircle(14f,14f,8f,paint)
    }
    private fun boldSample():(Canvas,Paint)->Unit = { canvas,paint ->
        paint.color=ScreenshotUiTokens.TEXT; paint.style=Paint.Style.FILL
        paint.textSize=15f; paint.textAlign=Paint.Align.CENTER
        paint.isFakeBoldText=true
        canvas.drawText("B",14f,19f,paint)
    }
    private fun strokeSample(pattern: ScreenshotStrokePattern,width: Int,color: Int):(Canvas,Paint)->Unit = { canvas,paint ->
        paint.color=color; paint.style=Paint.Style.STROKE
        paint.strokeWidth=width.coerceAtLeast(1).toFloat(); paint.strokeCap=Paint.Cap.ROUND
        paint.pathEffect=ScreenshotPenRendering.effect(pattern,width.coerceAtLeast(1).toFloat())
        canvas.drawLine(6f,14f,22f,14f,paint); paint.pathEffect=null
    }
    private fun sampleRect(pattern: ScreenshotFillPattern,fill: Boolean):(Canvas,Paint)->Unit = { canvas,paint ->
        paint.color=if(fill && style.hasFill) style.fillColor else Color.TRANSPARENT
        paint.style=Paint.Style.FILL
        if(fill && style.hasFill) {
            canvas.drawRect(5f,5f,23f,23f,paint)
        }
        paint.style=Paint.Style.STROKE; paint.strokeWidth=1.5f
        paint.color=if(fill) 0xFFBFBFBF.toInt() else style.outlineColor
        canvas.drawRect(5f,5f,23f,23f,paint)
        if(!fill) {
            paint.color=style.outlineColor
            paint.strokeWidth=style.outlineWidth.coerceAtLeast(1).toFloat()
            canvas.drawRect(8f,8f,20f,20f,paint)
        }
    }
    private fun alignSample():(Canvas,Paint)->Unit = { canvas,paint ->
        paint.color=ScreenshotUiTokens.TEXT; paint.style=Paint.Style.FILL
        repeat(3) { index ->
            val inset=if(index==1) 7f else 4f
            canvas.drawRect(inset,8f+index*4.5f,28f-inset,9.5f+index*4.5f,paint)
        }
    }

    private fun sizeStepper(): View {
        var base=style
        val fontSizeText=ScreenshotFontSize.format(style.fontSize)
        return ScreenshotStepper(context,"字号",
            ScreenshotFontSize.MIN,ScreenshotFontSize.max(tool),
            if(mixed(FONT_SIZE)) null else style.fontSize,30f,
            begin={ base=drawing.inspectedStyle; drawing.beginStylePreview() },
            apply={ value -> drawing.previewStyle(base.copy(fontSize=value),FONT_SIZE) },
            commit={ drawing.finishStylePreview(true) },
            onEdit={
                edit("字号",fontSizeText) { value ->
                    ScreenshotFontSize.parse(tool,value)?.let { size -> change(FONT_SIZE) { it.copy(fontSize=size) } }
                }
            },
            format={ value -> ScreenshotFontSize.format(value) })
    }

    init {
        val serial=tool==ScreenshotDrawingTool.SERIAL
        contentDescription=if(serial) "序号样式" else "文字样式"
        val modernSerial=serial && !mixed(SERIAL_GEOMETRY_VERSION) && style.serialGeometryVersion==1
        val supportsFamily=!serial || mixed(SERIAL_SHAPE) || style.serialShape!=ScreenshotSerialShape.CIRCLE
        val supportsFill=mixed(SERIAL_SHAPE) || !modernSerial ||
            style.serialShape !in setOf(ScreenshotSerialShape.SOLID_CIRCLE,ScreenshotSerialShape.SOLID_SQUARE)

        if(serial) {
            val shapes=listOf(ScreenshotSerialShape.OUTLINED_CIRCLE,ScreenshotSerialShape.SOLID_CIRCLE,
                ScreenshotSerialShape.OUTLINED_SQUARE,ScreenshotSerialShape.SOLID_SQUARE,ScreenshotSerialShape.CIRCLE)
            shapes.forEachIndexed { index,shape ->
                addCell(textCell(listOf("①","❶","□1","▣1","○")[index],!mixed(SERIAL_SHAPE) && style.serialShape==shape,
                    listOf("空心圆","实心圆","空心方","实心方","圆形（无数字）")[index]) {
                    change(SERIAL_SHAPE,SERIAL_GEOMETRY_VERSION) { it.copy(serialShape=shape,serialGeometryVersion=1) }
                },32)
            }
            groupBreak()
        }

        // 颜色：一个自定义触发器 + 原版 5 色（`style_presets::textColors()`）。
        addCell(cell("自定义绘图颜色",mixed(COLOR),draw=rainbox()) { anchor ->
            val base=style; drawing.beginStylePreview()
            ScreenshotColorPicker.show(anchor,"颜色",base.color,false,
                { value -> drawing.previewStyle(base.copy(color=value!!),COLOR) },
                drawing::finishStylePreview,sample=drawing::pickColor)
        })
        listOf(0xFFF5222D.toInt() to "红色",0xFF52C41A.toInt() to "绿色",0xFF1677FF.toInt() to "蓝色",
            0xFFFADB14.toInt() to "黄色",Color.BLACK to "黑色").forEach { (value,name) ->
            addCell(cell("绘图颜色 $name",!mixed(COLOR) && style.color==value,draw=swatch(value)) {
                change(COLOR) { it.copy(color=value) }
            })
        }
        groupBreak()

        // 字号：原版是「摘要按钮点击循环 + 4 个 S/M/L/XL 图标」。手机改成 −/+ 步进 + 同四档快捷。
        addCell(sizeStepper(),100)
        addCell(cell("加粗",!mixed(BOLD) && style.bold,draw=boldSample()) {
            change(BOLD) { it.copy(bold=if(mixed(BOLD)) true else !it.bold) }
        }.apply { isEnabled=supportsFamily; alpha=if(isEnabled) 1f else .4f })
        listOf(24f,30f,42f,54f).forEach { size ->
            addCell(textCell(ScreenshotFontSize.format(size),!mixed(FONT_SIZE) && style.fontSize==size,
                "字号 ${ScreenshotFontSize.format(size)}") {
                change(FONT_SIZE) { it.copy(fontSize=size) }
            },32)
        }
        groupBreak()

        // 字体：原版三个族快捷 + 可搜索下拉；下拉在手机上就是弹层。
        listOf("sans-serif" to "默认","serif" to "衬线","monospace" to "等宽").forEach { (family,label) ->
            addCell(textCell(label,!mixed(FONT_FAMILY) && style.fontFamily==family,"字体 $label") {
                change(FONT_FAMILY) { it.copy(fontFamily=family) }
            },32)
        }
        lateinit var familyButton: TextView
        familyButton=textCell(if(mixed(FONT_FAMILY)) "Mixed" else style.fontFamily,false,"选择字体") {
            ScreenshotFontPicker.show(familyButton,drawing)
        }
        familyButton.isEnabled=supportsFamily
        familyButton.alpha=if(supportsFamily) 1f else .4f
        familyButton.isSingleLine=true
        familyButton.ellipsize=android.text.TextUtils.TruncateAt.END
        familyButton.gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        addCell(familyButton,76)
        groupBreak()

        if(serial) {
            // 序号值：保留原 chip + 滚轮（跨到 Long.MAX，步进控件不适合），文案与置灰语义不变。
            val number=drawing.inspectedObject?.number ?: drawing.nextNumber
            val numberReadout=if(drawing.mixedNumber) "-" else number.toString()
            val supportsNumber=mixed(SERIAL_SHAPE) || style.serialShape!=ScreenshotSerialShape.CIRCLE
            val numberChip=textCell("序号 $numberReadout",false,"序号 $numberReadout，可滚轮调整") {
                edit("序号",number.toString()) { value -> value.toLongOrNull()?.let(drawing::setNumber) }
            }
            if(supportsNumber) {
                numberChip.setOnGenericMotionListener { _,event ->
                    val delta=event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    if(event.actionMasked==MotionEvent.ACTION_SCROLL && delta.isFinite() && delta!=0f) {
                        drawing.stepNumber(if(delta>0) 1 else -1); true
                    } else false
                }
            } else { numberChip.isEnabled=false; numberChip.alpha=.4f }
            addCell(numberChip,88)
            if(drawing.selectedIndices.size==1) {
                addCell(textCell(if(drawing.selected!!.boundTextId==0L) "创建关联文字" else "编辑关联文字",
                    false,"关联文字") { drawing.editSerialText() },96)
            }
            groupBreak()
        } else if(drawing.selectedIndices.size==1) {
            addCell(textCell("编辑文字",false,"编辑文字") { drawing.editSelectedText() },72)
        }

        // 描边 / 填充 / 对齐：原版是单触发器 + 弹层，这里同样只留触发器。
        addCell(cell("描边色设置",mixed(OUTLINE_COLOR,STROKE_PATTERN,OUTLINE_WIDTH),
            draw=strokeSample(style.strokePattern,style.outlineWidth,style.outlineColor)) { anchor ->
            ScreenshotStylePopups.stroke(anchor,drawing)
        })
        addCell(cell("填充色设置",mixed(HAS_FILL,FILL_COLOR,FILL_PATTERN),
            draw=sampleRect(style.fillPattern,true)) { anchor ->
            if(supportsFill) ScreenshotStylePopups.fill(anchor,drawing)
        }.apply { isEnabled=supportsFill; alpha=if(isEnabled) 1f else .4f })
        if(!serial) {
            addCell(cell("对齐设置",mixed(ALIGN,VERTICAL_ALIGN),draw=alignSample()) { anchor ->
                ScreenshotStylePopups.align(anchor,drawing)
            })
            addCell(ScreenshotCornerRadiusButton(context,drawing,"背景圆角"),56)
        }
    }
}
