package local.voxfix.debug

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space

/** At most two visible rows, a scrollable body and an always reachable cancel action. */
internal class ScreenshotToolbarPanel(context: Context) : FrameLayout(context) {
    var packedWidth=0; private set
    var packedHeight=0; private set
    /** Called with screen deltas while the grip is held; the host moves the whole bar. */
    var onDrag: ((Float,Float)->Unit)? = null
    var onDragStart: (() -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private var body: ScrollView?=null
    /** 主栏竖向位置跨 setTools 存活；body==null（空工具列表）时也不清零，下次有内容还能恢复。 */
    private var bodyScrollY=0
    private val grip=ImageView(context).apply {
        setImageResource(R.drawable.snow_grip); setColorFilter(0xFF8C8C8C.toInt(),PorterDuff.Mode.SRC_IN)
        contentDescription="按住拖动工具栏"; tooltipText=contentDescription
        isClickable=true; isFocusable=true
        setPadding(dp(1),dp(1),dp(1),dp(1))
    }
    init {
        background=GradientDrawable().apply {
            setColor(ScreenshotUiTokens.PANEL); cornerRadius=dp(ScreenshotToolbarMetrics.PANEL_RADIUS).toFloat()
        }
        elevation=dp(ScreenshotToolbarMetrics.PANEL_ELEVATION).toFloat()
        ScreenshotTouchTargets.install(this)
        var lastX=0f; var lastY=0f; var dragging=false
        grip.setOnTouchListener { _, event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragging=true; lastX=event.rawX; lastY=event.rawY; onDragStart?.invoke(); true }
                MotionEvent.ACTION_MOVE -> {
                    if(dragging) { val dx=event.rawX-lastX; val dy=event.rawY-lastY; lastX=event.rawX; lastY=event.rawY; onDrag?.invoke(dx,dy) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if(dragging) { dragging=false; onDragEnd?.invoke() }; true }
                else -> false
            }
        }
    }
    fun setTools(items: List<Pair<View,Boolean>>,cancel: View?,available: Int,visibleHeight: Int) {
        body?.let { bodyScrollY=it.scrollY }
        removeAllViews(); body=null
        val cell=ScreenshotToolbarMetrics.MAIN_CELL
        val rowHeight=dp(cell)
        addView(grip,LayoutParams(dp(ScreenshotToolbarMetrics.GRIP_WIDTH),rowHeight,Gravity.LEFT or Gravity.CENTER_VERTICAL).apply {
            leftMargin=dp(ScreenshotToolbarMetrics.GRIP_INSET)
        })
        val closeWidth=if(cancel==null) 0 else rowHeight
        val closeGap=if(cancel!=null && items.isNotEmpty()) dp(ScreenshotToolbarMetrics.MAIN_GAP) else 0
        val gripLead=dp(ScreenshotToolbarMetrics.GRIP_WIDTH+ScreenshotToolbarMetrics.GRIP_INSET+ScreenshotToolbarMetrics.GRIP_GAP)
        val room=(available-dp(24)-closeWidth-closeGap-gripLead).coerceAtLeast(rowHeight)
        val content=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        var row=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        var width=0; var widest=0; var rowCount=1; var previousSeparator=false
        content.addView(row,LinearLayout.LayoutParams(-2,rowHeight))
        val separatorCell=ScreenshotToolbarMetrics.SEPARATOR_SIDE*2+ScreenshotToolbarMetrics.SEPARATOR_WIDTH
        for((view,separator) in items) {
            val cellWidth=if(separator) dp(separatorCell) else rowHeight
            var gap=if(width==0 || separator || previousSeparator) 0 else dp(ScreenshotToolbarMetrics.MAIN_GAP)
            if(separator && (width==0 || width+cellWidth>room)) continue
            if(width>0 && width+gap+cellWidth>room) {
                widest=maxOf(widest,width); width=0; gap=0; rowCount++
                row=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
                content.addView(row,LinearLayout.LayoutParams(-2,rowHeight).apply { topMargin=dp(ScreenshotToolbarMetrics.MAIN_GAP) })
            }
            if(gap>0) row.addView(Space(context),LinearLayout.LayoutParams(gap,1))
            if(separator) row.addView(view,LinearLayout.LayoutParams(dp(ScreenshotToolbarMetrics.SEPARATOR_WIDTH),dp(ScreenshotToolbarMetrics.SEPARATOR_HEIGHT)).apply {
                marginStart=dp(ScreenshotToolbarMetrics.SEPARATOR_SIDE); marginEnd=dp(ScreenshotToolbarMetrics.SEPARATOR_SIDE)
            })
            else row.addView(view,LinearLayout.LayoutParams(rowHeight,rowHeight))
            width+=gap+cellWidth; previousSeparator=separator
        }
        val bodyWidth=if(items.isEmpty()) 0 else maxOf(widest,width)
        val rowsLimit=if(visibleHeight>=dp(400)) 2 else 1
        val shownRows=minOf(rowCount,rowsLimit)
        val bodyHeight=if(items.isEmpty()) 0 else dp(shownRows*cell+(shownRows-1)*ScreenshotToolbarMetrics.MAIN_GAP)
        if(bodyWidth>0) {
            val scroller=ScrollView(context).also { body=it }.apply {
                contentDescription="截图工具栏，可上下滚动"; isVerticalScrollBarEnabled=rowCount>rowsLimit
                addView(content)
                // 等布局完成再恢复：post{scrollTo} 在 extent=0 时被夹成 0，主栏会跳回第一行。
                if(bodyScrollY>0) ScreenshotScrollMemory.applyAfterLayout(this,bodyScrollY,horizontal=false)
            }
            val holderParams=LayoutParams(bodyWidth,bodyHeight).apply {
                leftMargin=gripLead; topMargin=dp(ScreenshotToolbarMetrics.PANEL_PAD_V)
            }
            if(rowCount<=rowsLimit) addView(scroller,holderParams)
            else {
                // 多出来的工具藏在第二屏里，只靠一条 1dp 滚动条等于没有提示。
                val fade=View(context).apply {
                    background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(android.graphics.Color.TRANSPARENT,ScreenshotUiTokens.PANEL))
                    isClickable=false
                }
                val holder=FrameLayout(context).apply {
                    addView(scroller,LayoutParams(bodyWidth,bodyHeight))
                    addView(fade,LayoutParams(bodyWidth,dp(14),Gravity.BOTTOM))
                }
                fun syncFade() { fade.visibility=if(scroller.canScrollVertically(1)) View.VISIBLE else View.GONE }
                scroller.setOnScrollChangeListener { _,_,_,_,_ -> syncFade() }
                addView(holder,holderParams)
                holder.post { syncFade() }
            }
        }
        val lead=dp(ScreenshotToolbarMetrics.GRIP_INSET+ScreenshotToolbarMetrics.GRIP_WIDTH)+
            (if(bodyWidth>0) dp(ScreenshotToolbarMetrics.GRIP_GAP) else 0)
        packedWidth=lead+bodyWidth+closeGap+closeWidth+dp(ScreenshotToolbarMetrics.PANEL_PAD_H)
        packedHeight=dp(ScreenshotToolbarMetrics.PANEL_PAD_V*2)+maxOf(bodyHeight,if(cancel!=null) rowHeight else 0)
        if(cancel!=null) addView(cancel,LayoutParams(rowHeight,rowHeight,Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply {
            rightMargin=dp(ScreenshotToolbarMetrics.PANEL_PAD_H)
        })
        layoutParams=LayoutParams(packedWidth,packedHeight)
    }
}
