package local.voxfix.debug

import local.voxfix.debug.ScreenshotStyleProperty.*
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 参数弹层：把「描边 / 填充 / 对齐」从二级栏的平铺行收回触发器。
 *
 * 原版就是这么组织的（`ScreenshotToolPaletteStrokeEditor` / `FillEditor` / `IconOptionEditor`
 * 都是一个触发器 + 一个 Popover），手机端改造前把这些参数全部铺成整宽行，
 * 才让文字面板膨胀到 11 行。
 *
 * 事务：颜色走 `ScreenshotColorPicker` 自带会话；线型/填充/对齐是一次点击一条 undo；
 * 线宽用 [ScreenshotStepper]，连续拖动合并成一条。
 */
internal object ScreenshotStylePopups {
    /** 最近一次构建的弹层内容，供设备套件在没有窗口 token 的环境里直接断言。 */
    internal var lastPanel: View? = null
        private set

    private fun dp(context: Context,n: Int)=(n*context.resources.displayMetrics.density).toInt()

    private fun surface(context: Context,selected: Boolean)=GradientDrawable().apply {
        setColor(if(selected) ScreenshotUiTokens.TONAL_CELL else Color.TRANSPARENT)
        cornerRadius=dp(context,ScreenshotToolbarMetrics.STYLE_CELL_RADIUS).toFloat()
        setStroke(dp(context,1).coerceAtLeast(1),
            if(selected) ScreenshotUiTokens.PRIMARY else ScreenshotUiTokens.OUTLINE_IDLE_CELL)
    }

    private fun cell(context: Context,label: String,selected: Boolean=false,
                     draw:(Canvas,Paint)->Unit,run:(View)->Unit): View = object : View(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val unit=resources.displayMetrics.density
            canvas.save(); canvas.scale(unit,unit); draw(canvas,paint); canvas.restore()
        }
    }.apply {
        contentDescription=label; tooltipText=label
        isFocusable=true; isClickable=true; isSelected=selected
        background=surface(context,selected)
        setOnClickListener { run(it) }
    }

    private fun textCell(context: Context,text: String,label: String,selected: Boolean,
                         run:()->Unit)=TextView(context).apply {
        this.text=text; textSize=12f; gravity=Gravity.CENTER
        contentDescription=label; tooltipText=label
        setTextColor(if(selected) ScreenshotUiTokens.TEXT_ACCENT else ScreenshotUiTokens.TEXT)
        setPadding(dp(context,8),0,dp(context,8),0)
        background=surface(context,selected)
        isFocusable=true; isClickable=true
        setOnClickListener { run() }
    }

    private fun row(context: Context)=LinearLayout(context).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
    }

    private fun addRow(panel: LinearLayout,views: List<View>,cellSize: Int) {
        val line=row(panel.context)
        views.forEachIndexed { index,view ->
            val cellHeight=dp(panel.context,ScreenshotToolbarMetrics.STYLE_CELL)
            val params=when(view) {
                // 步进控件自带 −/读数/+ 三格，固定 100dp，不能压成 0。
                is LinearLayout -> LinearLayout.LayoutParams(dp(panel.context,100),cellHeight)
                is TextView -> LinearLayout.LayoutParams(-2,cellHeight)
                else -> LinearLayout.LayoutParams(dp(panel.context,cellSize),cellHeight)
            }
            if(index>0) params.marginStart=dp(panel.context,ScreenshotToolbarMetrics.STYLE_GAP)
            line.addView(view,params)
        }
        panel.addView(line,LinearLayout.LayoutParams(-2,-2).apply {
            if(panel.childCount>0) topMargin=dp(panel.context,ScreenshotToolbarMetrics.STYLE_GAP)
        })
    }

    private fun panel(context: Context)=LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL
        setPadding(dp(context,8),dp(context,8),dp(context,8),dp(context,8))
        background=GradientDrawable().apply {
            setColor(ScreenshotUiTokens.PANEL_ELEVATED); cornerRadius=dp(context,8).toFloat()
        }
    }

    private fun show(anchor: View,content: View): PopupWindow {
        val popup=PopupWindow(content,-2,-2,true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); isOutsideTouchable=true
            elevation=dp(anchor.context,8).toFloat()
            inputMethodMode=PopupWindow.INPUT_METHOD_NOT_NEEDED
            softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        lastPanel=content
        if(!anchor.isAttachedToWindow) return popup
        content.measure(View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val spot=IntArray(2); anchor.getLocationOnScreen(spot)
        val screen=anchor.resources.displayMetrics
        val point=ScreenshotPopupPlacement.place(
            spot[0],spot[1],spot[0]+anchor.width,spot[1]+anchor.height,
            content.measuredWidth,content.measuredHeight,
            screen.widthPixels,screen.heightPixels,dp(anchor.context,4))
        popup.showAtLocation(anchor.rootView,Gravity.NO_GRAVITY,point.x,point.y)
        return popup
    }

    private fun strokeSample(pattern: ScreenshotStrokePattern,width: Int,color: Int):(Canvas,Paint)->Unit = { canvas,paint ->
        paint.color=color; paint.style=Paint.Style.STROKE; paint.strokeWidth=width.coerceAtLeast(1).toFloat()
        paint.strokeCap=Paint.Cap.ROUND
        paint.pathEffect=ScreenshotPenRendering.effect(pattern,width.coerceAtLeast(1).toFloat())
        canvas.drawLine(6f,14f,22f,14f,paint); paint.pathEffect=null
    }

    private fun swatch(color: Int,box: Boolean=false):(Canvas,Paint)->Unit = { canvas,paint ->
        paint.color=if(Color.alpha(color)==0) Color.WHITE else color; paint.style=Paint.Style.FILL
        if(box) canvas.drawRect(6f,6f,22f,22f,paint) else canvas.drawCircle(14f,14f,6f,paint)
        if(Color.alpha(color)==0) {
            paint.color=0xFFF5222D.toInt(); paint.strokeWidth=1.5f; canvas.drawLine(5f,23f,23f,5f,paint)
        }
    }

    /** 描边：色 + 实线/虚线/点线 + 宽度步进。对应原版 `outline-stroke` + `outline-width`。 */
    fun stroke(anchor: View,drawing: ScreenshotDrawingLayer): PopupWindow {
        val context=anchor.context
        val panel=panel(context)
        val style=drawing.inspectedStyle
        lateinit var colorTrigger: View
        colorTrigger=cell(context,"描边颜色与样式",draw=strokeSample(style.strokePattern,style.width.coerceAtMost(12),style.color)) { view ->
            var current=drawing.inspectedStyle
            drawing.beginStylePreview()
            ScreenshotColorPicker.show(view,"描边色",current.color,false,
                { value -> current=current.copy(color=value!!); drawing.previewStyle(current,COLOR) },
                drawing::finishStylePreview,sample=drawing::pickColor,pattern=current.strokePattern,
                patternPreview={ value -> current=current.copy(strokePattern=value); drawing.previewStyle(current,STROKE_PATTERN) })
        }
        val colors=listOf(0xFFF5222D.toInt(),0xFF52C41A.toInt(),0xFF1677FF.toInt(),0xFFFADB14.toInt(),Color.BLACK)
        addRow(panel,listOf<View>(colorTrigger)+colors.mapIndexed { index,value ->
            cell(context,"描边颜色 ${listOf("红色","绿色","蓝色","黄色","黑色")[index]}",style.color==value,swatch(value)) {
                drawing.applyStyle(drawing.inspectedStyle.copy(color=value),COLOR)
            }
        },ScreenshotToolbarMetrics.STYLE_CELL)
        addRow(panel,listOf(ScreenshotStrokePattern.SOLID to "实线",ScreenshotStrokePattern.DASHED to "虚线",
            ScreenshotStrokePattern.DOTTED to "点线").map { (pattern,label) ->
            cell(context,"描边$label",style.strokePattern==pattern,strokeSample(pattern,2,style.color)) {
                drawing.applyStyle(drawing.inspectedStyle.copy(strokePattern=pattern),STROKE_PATTERN)
            }
        },ScreenshotToolbarMetrics.STYLE_CELL)
        var base=style
        val width=ScreenshotStepper(context,"描边宽",
            ScreenshotStyleBounds.OUTLINE_WIDTH_MIN.toFloat(),ScreenshotStyleBounds.OUTLINE_WIDTH_MAX.toFloat(),
            style.outlineWidth.toFloat(),0f,
            begin={ base=drawing.inspectedStyle; drawing.beginStylePreview() },
            apply={ value -> drawing.previewStyle(base.copy(outlineWidth=value.roundToInt()),OUTLINE_WIDTH) },
            commit={ drawing.finishStylePreview(true) })
        addRow(panel,listOf<View>(width)+listOf(2,4,8).map { value ->
            cell(context,"描边宽 $value",style.outlineWidth==value,strokeSample(ScreenshotStrokePattern.SOLID,value,style.color)) {
                drawing.applyStyle(drawing.inspectedStyle.copy(outlineWidth=value),OUTLINE_WIDTH)
            }
        },ScreenshotToolbarMetrics.STYLE_CELL)
        return show(anchor,panel)
    }

    /** 填充：无/4 浅色/自定义 + 实心/交叉线/线条。对应原版 `shape-fill`。 */
    fun fill(anchor: View,drawing: ScreenshotDrawingLayer): PopupWindow {
        val context=anchor.context
        val panel=panel(context)
        val style=drawing.inspectedStyle
        val fills=listOf(Color.TRANSPARENT to "无填充",0xFFFFCCC7.toInt() to "背景填充 浅红",
            0xFFD9F7BE.toInt() to "背景填充 浅绿",0xFFBAE0FF.toInt() to "背景填充 浅蓝",
            0xFFFFF1B8.toInt() to "背景填充 浅黄")
        lateinit var custom: View
        custom=cell(context,"填充色",style.hasFill,swatch(if(style.hasFill) style.fillColor else Color.TRANSPARENT,true)) { view ->
            var current=drawing.inspectedStyle
            drawing.beginStylePreview()
            ScreenshotColorPicker.show(view,"填充色",current.fillColor,true,
                { value ->
                    current=current.copy(fillColor=value ?: current.fillColor,hasFill=value!=null)
                    drawing.previewStyle(current,*(if(value==null) arrayOf(HAS_FILL) else arrayOf(FILL_COLOR,HAS_FILL)))
                },
                drawing::finishStylePreview,sample=drawing::pickColor)
        }
        addRow(panel,listOf(custom)+fills.map { (value,label) ->
            cell(context,label,if(value==Color.TRANSPARENT) !style.hasFill else style.hasFill && style.fillColor==value,swatch(value,true)) {
                drawing.applyStyle(drawing.inspectedStyle.copy(fillColor=value,hasFill=value!=Color.TRANSPARENT),
                    *(if(value==Color.TRANSPARENT) arrayOf(HAS_FILL) else arrayOf(FILL_COLOR,HAS_FILL)))
            }
        },ScreenshotToolbarMetrics.STYLE_CELL)
        addRow(panel,ScreenshotFillPattern.values().mapIndexed { index,pattern ->
            cell(context,listOf("实心填充","交叉线填充","线条填充")[index],style.hasFill && style.fillPattern==pattern,
                draw={ canvas,paint ->
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
                }) {
                drawing.applyStyle(drawing.inspectedStyle.copy(fillPattern=pattern,hasFill=true),FILL_PATTERN,HAS_FILL)
            }
        },ScreenshotToolbarMetrics.STYLE_CELL)
        return show(anchor,panel)
    }

    /** 自动筛选识别到的区域类别：点一类就把该类全部打码（原版按类别填充）。 */
    fun autoFilterCategories(anchor: View,drawing: ScreenshotDrawingLayer,
                             categories: List<Int>): PopupWindow {
        val context=anchor.context
        val panel=panel(context)
        val grid=GridLayout(context).apply { columnCount=4 }
        categories.forEach { category ->
            val label=ScreenshotAutoFilter.labels.getOrNull(category) ?: category.toString()
            grid.addView(textCell(context,label,"填充全部$label",false) {
                drawing.fillAutoFilterCategory(category)
            },GridLayout.LayoutParams().apply {
                width=dp(context,72); height=dp(context,ScreenshotToolbarMetrics.STYLE_CELL)
                setMargins(dp(context,2),dp(context,2),dp(context,2),dp(context,2))
            })
        }
        panel.addView(grid)
        return show(anchor,panel)
    }

    /** 对齐：水平 3 项 + 垂直 3 项。原版水平对齐是单触发器 + 弹层；垂直是手机侧补的。 */
    fun align(anchor: View,drawing: ScreenshotDrawingLayer): PopupWindow {
        val context=anchor.context
        val panel=panel(context)
        val style=drawing.inspectedStyle
        val grid=GridLayout(context).apply { columnCount=3 }
        val horizontal=listOf("左" to 0,"中" to 1,"右" to 2)
        horizontal.forEach { (label,value) ->
            grid.addView(textCell(context,label,"文字${label}对齐",style.align==value) {
                drawing.applyStyle(drawing.inspectedStyle.copy(align=value),ALIGN)
            },GridLayout.LayoutParams().apply {
                width=dp(context,40); height=dp(context,ScreenshotToolbarMetrics.STYLE_CELL)
                setMargins(dp(context,2),dp(context,2),dp(context,2),dp(context,2))
            })
        }
        val vertical=listOf("上" to 0,"中" to 1,"下" to 2)
        vertical.forEach { (label,value) ->
            grid.addView(textCell(context,label,"文字垂直${label}对齐",style.verticalAlign==value) {
                drawing.applyStyle(drawing.inspectedStyle.copy(verticalAlign=value),VERTICAL_ALIGN)
            },GridLayout.LayoutParams().apply {
                width=dp(context,40); height=dp(context,ScreenshotToolbarMetrics.STYLE_CELL)
                setMargins(dp(context,2),dp(context,2),dp(context,2),dp(context,2))
            })
        }
        panel.addView(grid)
        return show(anchor,panel)
    }
}
