package local.voxfix.debug

import local.voxfix.debug.ScreenshotStyleProperty.*
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 马赛克/滤镜 与 高亮 的二级栏：一条横向属性行。
 *
 * 原版 `filter-mode / filter-type / filter-intensity` 与
 * `highlight-mode / highlight-color / highlight-border` 本就在同一行；改造前手机把
 * 「自动筛选区域类别」按 4 列铺成多行，才让滤镜面板撑成纵向长卡片，
 * 现在类别收进「按类别填充」弹层。
 */
internal class ScreenshotAreaControls(
    context: Context,
    private val drawing: ScreenshotDrawingLayer,
    private val allowAutoFilter: Boolean
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
    private fun widthSample(width: Int):(Canvas,Paint)->Unit = { canvas,paint ->
        paint.color=style.color; paint.style=Paint.Style.STROKE
        paint.strokeWidth=width.coerceAtMost(16).toFloat()/2f+1f; paint.strokeCap=Paint.Cap.ROUND
        canvas.drawLine(5f,14f,23f,14f,paint)
    }

    private fun strength(value: Int) {
        val line=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        val readout=TextView(context).apply {
            text="强度 $value"; textSize=12f; setTextColor(ScreenshotUiTokens.TEXT)
        }
        line.addView(readout,LayoutParams(dp(52),dp(ScreenshotToolbarMetrics.STYLE_CELL)))
        var base=style
        var cancelled=false
        var tracking=false
        line.addView(SeekBar(context).apply {
            max=100; progress=value.coerceIn(0,100); contentDescription="强度"
            // 滑杆处于横向滚动行内：DOWN 时声明父级不得拦截，否则拖动会被抢走变成滚动并触发 CANCEL 回滚。
            bindUninterruptedDrag { cancelled=true; tracking=false; drawing.finishStylePreview(false) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { base=style; cancelled=false; tracking=true; drawing.beginStylePreview() }
                override fun onProgressChanged(bar: SeekBar,progress: Int,fromUser: Boolean) {
                    readout.text="强度 $progress"; bar.stateDescription=progress.toString()
                    if(fromUser) {
                        if(tracking) drawing.previewStyle(base.copy(strength=progress/100f),STRENGTH)
                        else drawing.applyStyle(style.copy(strength=progress/100f),STRENGTH)
                    }
                }
                override fun onStopTrackingTouch(bar: SeekBar) { if(tracking) drawing.finishStylePreview(!cancelled); tracking=false }
            })
        },LayoutParams(dp(150),dp(ScreenshotToolbarMetrics.STYLE_CELL)))
        addCell(line,206)
        // Line 里是横向 LinearLayout，直接插入会压成 28dp，这里显式给宽度。
    }

    init {
        val filter=tool==ScreenshotDrawingTool.FILTER
        contentDescription=if(filter) "滤镜样式" else "高亮样式"
        if(drawing.selectedIndices.isEmpty()) {
            addCell(textCell("画笔",!drawing.autoFilterActive && !mixed(AREA) && style.area==ScreenshotAreaMode.PEN,
                "作用方式 PEN") { drawing.changeArea(ScreenshotAreaMode.PEN) },44)
            addCell(textCell("矩形",!drawing.autoFilterActive && !mixed(AREA) && style.area==ScreenshotAreaMode.RECTANGLE,
                "作用方式 RECTANGLE") { drawing.changeArea(ScreenshotAreaMode.RECTANGLE) },44)
            if(filter && allowAutoFilter) {
                addCell(textCell("自动筛选",drawing.autoFilterActive,"自动筛选") { drawing.chooseAutoFilter() },68)
            }
            groupBreak()
        }
        if(filter) {
            // 上游 DisplayFilterType 的六种滤镜。灰度/反色的强度恒为 1.0，所以不显示强度条。
            val labels=listOf("马赛克","高斯模糊","灰度","反色","浮雕","亮度")
            addCell(cell("自定义绘图颜色",mixed(COLOR),draw={ canvas,paint ->
                paint.color=style.color; paint.style=Paint.Style.FILL
                canvas.drawCircle(14f,14f,7f,paint)
                paint.style=Paint.Style.STROKE; paint.strokeWidth=1f; paint.color=Color.WHITE
                canvas.drawCircle(14f,14f,8f,paint)
            }) { anchor ->
                val base=style; drawing.beginStylePreview()
                ScreenshotColorPicker.show(anchor,"绘图颜色",base.color,false,
                    { value -> drawing.previewStyle(base.copy(color=value!!),COLOR) },
                    drawing::finishStylePreview,sample=drawing::pickColor)
            })
            ScreenshotFilter.values().forEachIndexed { index,value ->
                addCell(cell(labels.getOrElse(index) { value.name },!mixed(FILTER) && style.filter==value,
                    draw={ canvas,paint ->
                        paint.color=style.color; paint.style=Paint.Style.STROKE
                        paint.strokeWidth=1.5f
                        canvas.drawRect(5f,5f,23f,23f,paint)
                        canvas.drawLine(5f,23f,23f,5f,paint)
                    }) { change(FILTER) { it.copy(filter=value) } })
            }
            groupBreak()
            if(!mixed(FILTER) && style.filter !in setOf(ScreenshotFilter.GRAYSCALE,ScreenshotFilter.INVERT)) {
                strength((style.strength*100).roundToInt())
                groupBreak()
            }
            // 上游 `brush-width` 只属于画笔模式的滤镜；自动筛选没有笔宽这个概念。
            if(!drawing.autoFilterActive && style.area==ScreenshotAreaMode.PEN) {
                listOf(8,16,24,40).forEach { value ->
                    addCell(cell("线宽 $value",!mixed(WIDTH) && style.width==value,draw=widthSample(value)) {
                        change(WIDTH) { it.copy(width=value) }
                    })
                }
                groupBreak()
            }
            if(drawing.autoFilterActive) {
                val regions=drawing.autoFilterRegions
                val categories=regions.orEmpty().map { it.category }.distinct().sorted()
                val label=when {
                    drawing.autoFilterFailed -> "识别失败，点自动筛选重试"
                    regions==null -> "正在识别区域"
                    regions.isEmpty() -> "未识别到区域"
                    else -> "按类别填充（${categories.size} 类）"
                }
                val trigger=textCell(label,!regions.isNullOrEmpty(),"按类别填充")
                trigger.setOnClickListener {
                    if(categories.isNotEmpty()) ScreenshotStylePopups.autoFilterCategories(trigger,drawing,categories)
                }
                addCell(trigger,168)
            }
        } else {
            addCell(cell("自定义绘图颜色",mixed(COLOR),draw={ canvas,paint ->
                paint.color=style.color; paint.style=Paint.Style.FILL
                canvas.drawCircle(14f,14f,7f,paint)
                paint.style=Paint.Style.STROKE; paint.strokeWidth=1f; paint.color=Color.WHITE
                canvas.drawCircle(14f,14f,8f,paint)
            }) { anchor ->
                val base=style; drawing.beginStylePreview()
                ScreenshotColorPicker.show(anchor,"绘图颜色",base.color,false,
                    { value -> drawing.previewStyle(base.copy(color=value!!),COLOR) },
                    drawing::finishStylePreview,sample=drawing::pickColor)
            })
            listOf(0xFFF5222D.toInt() to "红色",0xFF52C41A.toInt() to "绿色",0xFF1677FF.toInt() to "蓝色",
                0xFFFADB14.toInt() to "黄色",Color.BLACK to "黑色").forEach { (value,name) ->
                addCell(cell("绘图颜色 $name",!mixed(COLOR) && style.color==value,draw=swatch(value)) {
                    change(COLOR) { it.copy(color=value) }
                })
            }
            // 笔宽只在画笔模式下有；矩形高亮的边框宽是另一个属性（原版 `highlight-border`）。
            if(style.area==ScreenshotAreaMode.PEN) {
                groupBreak()
                listOf(8,16,24,40).forEach { value ->
                    addCell(cell("线宽 $value",!mixed(WIDTH) && style.width==value,draw=widthSample(value)) {
                        change(WIDTH) { it.copy(width=value) }
                    })
                }
            }
            if(!mixed(AREA) && style.area==ScreenshotAreaMode.RECTANGLE) {
                groupBreak()
                addCell(cell("高亮边框设置",mixed(OUTLINE_COLOR,OUTLINE_WIDTH),draw={ canvas,paint ->
                    paint.color=style.outlineColor; paint.style=Paint.Style.STROKE
                    paint.strokeWidth=style.outlineWidth.coerceAtLeast(1).toFloat()
                    canvas.drawRect(5f,5f,23f,23f,paint)
                }) { anchor -> ScreenshotStylePopups.stroke(anchor,drawing) })
            }
        }
    }
}
