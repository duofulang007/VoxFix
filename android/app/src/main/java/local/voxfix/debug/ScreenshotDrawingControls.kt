package local.voxfix.debug

import local.voxfix.debug.ScreenshotStyleProperty.*
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 二级栏派发器：按当前工具挂上对应的**单行**属性面板。
 *
 * 改造前这里把 TEXT / SERIAL / FILTER / HIGHLIGHT 的参数全部平铺成整宽 44dp 行，
 * 文字面板 11 行 ≈484dp、面板上限 280dp，必须纵向滚动才够得到圆角。现在每个工具
 * 都是一条 40dp 高的横向行（[ScreenshotStyleRow]），细节进弹层。
 */
internal class ScreenshotDrawingControls(context: Context,private val drawing: ScreenshotDrawingLayer,
    private val edit: (String,String,(String)->Unit) -> Unit,
    /** The screenshot editor renders these in the bar's right column instead of a bottom row. */
    private val showSelectActions: Boolean = true,
    private val showDrawTemplates: ((View) -> Unit)? = null,
    /**
     * 自动筛选要在画布位图上跑轮廓识别。录制标注层底图是透明的，识别只会得到噪声，
     * 所以那里不显示入口（`ScreenRecordingAnnotation` 传 false）。
     */
    private val allowAutoFilter: Boolean = true) : LinearLayout(context) {
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private val tool get()=drawing.inspectedTool
    private val style get()=drawing.inspectedStyle
    private var mixedProperties=emptySet<ScreenshotStyleProperty>()
    private fun mixed(vararg p: ScreenshotStyleProperty)=p.any { it in mixedProperties }
    /** 横向位置按工具归档：换工具再回来，各自回到上次的滚动位置，而不是一律回行首。 */
    private var lastTool: ScreenshotDrawingTool?=null
    private val scrollByTool=mutableMapOf<ScreenshotDrawingTool?,List<Int>>()
    private fun chip(text: String,selected: Boolean=false,description: String=text,run:()->Unit)=TextView(context).apply {
        this.text=text; textSize=13f; gravity=Gravity.CENTER; contentDescription=description; tooltipText=description
        setTextColor(if(selected) ScreenshotUiTokens.TEXT_ACCENT else ScreenshotUiTokens.TEXT)
        background=GradientDrawable().apply {
            setColor(if(selected) ScreenshotUiTokens.TONAL_CELL else Color.TRANSPARENT)
            cornerRadius=dp(4).toFloat()
        }
        minHeight=dp(44); isFocusable=true; setOnClickListener { run() }
    }
    private fun row(views: List<View>)=LinearLayout(context).also { line ->
        views.forEach { line.addView(it,LayoutParams(0,dp(44),1f)) }; addView(line,LayoutParams(-1,-2))
    }
    init { orientation=VERTICAL; refresh() }
    /** False when the active tool has no options; the host must not show an empty card. */
    val hasContent: Boolean get() = childCount>0
    fun refresh() {
        // 拆之前把整棵子树里**每个**滚动容器的位置按 DFS 序存档。
        // SELECT 模式选中对象时样式行是第二个滚动容器（前面是选区操作条），只看
        // getChildAt(0) 会漏掉它——「点一下样式行就回最前」的残留根因。
        val archived=collectScrolls()
        scrollByTool[lastTool]=archived
        val wanted=tool
        val previousScrolls=scrollByTool[wanted] ?: List(archived.size){0}
        lastTool=wanted
        removeAllViews()
        mixedProperties=drawing.mixedStyleProperties
        try {
            val active=wanted ?: return
            if (drawing.tool == ScreenshotDrawingTool.SELECT) {
                val actions = ScreenshotSelectionActionControls(context, drawing)
                addView(actions, LayoutParams(-1, -2))
                showDrawTemplates?.let { show -> showDrawTemplatesRow(show) }
            }
            if(active==ScreenshotDrawingTool.ERASER || active==ScreenshotDrawingTool.SELECT) return
            when(active) {
                ScreenshotDrawingTool.WATERMARK -> {
                    // A canvas-level config, so it has no selection actions.
                    addView(ScreenshotWatermarkControls(context,drawing,edit),LayoutParams(-1,-2))
                    return
                }
                ScreenshotDrawingTool.PEN -> addView(ScreenshotPenControls(context,drawing),LayoutParams(-1,-2))
                ScreenshotDrawingTool.ARROW -> addView(ScreenshotArrowControls(context,drawing),LayoutParams(-1,-2))
                ScreenshotDrawingTool.LINE -> addView(ScreenshotLineControls(context,drawing),LayoutParams(-1,-2))
                ScreenshotDrawingTool.RECTANGLE -> addView(ScreenshotShapeControls(context,drawing),LayoutParams(-1,-2))
                ScreenshotDrawingTool.SPOTLIGHT -> addView(ScreenshotSpotlightControls(context,drawing),LayoutParams(-1,-2))
                ScreenshotDrawingTool.TEXT, ScreenshotDrawingTool.SERIAL ->
                    addView(ScreenshotTextControls(context,drawing,edit),LayoutParams(-1,-2))
                ScreenshotDrawingTool.FILTER, ScreenshotDrawingTool.HIGHLIGHT ->
                    addView(ScreenshotAreaControls(context,drawing,allowAutoFilter),LayoutParams(-1,-2))
                else -> return
            }
            if(showSelectActions || drawing.selectedIndices.size>1) selectedActions()
        } finally {
            // 统一出口：WATERMARK / SELECT / ERASER / else 四个提前 return 也走这里。
            // 恢复按 DFS 序对应到新树的每个滚动容器，在 layout 回调里同步执行
            // （draw 在 layout 之后），同帧到位、不会先闪行首再跳回。
            restoreScrolls(previousScrolls)
        }
    }
    /**
     * DFS 收集子树里每个滚动容器的当前位置：[ScreenshotStyleRow] 与裸
     * HorizontalScrollView（选区操作条）各算一个，不再深入其内部（它们内部没有嵌套滚动行）。
     * 重建前后的 addView 顺序一致，DFS 序一一对应。
     */
    private fun collectScrolls(): List<Int> {
        val positions=mutableListOf<Int>()
        fun walk(view: View) {
            when(view) {
                is ScreenshotStyleRow -> positions.add(view.currentScroll())
                is HorizontalScrollView -> positions.add(view.scrollX)
                is ViewGroup -> for(i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(this)
        return positions
    }
    private fun restoreScrolls(positions: List<Int>) {
        if(positions.isEmpty()) return
        var index=0
        fun walk(view: View) {
            when(view) {
                is ScreenshotStyleRow -> {
                    if(index<positions.size) view.restoreScroll(positions[index])
                    index++
                }
                is HorizontalScrollView -> {
                    if(index<positions.size) ScreenshotScrollMemory.applyAfterLayout(view,positions[index],horizontal=true)
                    index++
                }
                is ViewGroup -> for(i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(this)
    }
    private fun showDrawTemplatesRow(show: (View) -> Unit) {
        val button=chip("绘图模板") {}
        button.setOnClickListener { show(it) }
        row(listOf(button))
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
