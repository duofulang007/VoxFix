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
    private val GRIP=18
    private val GRIP_INSET=8
    private val GRIP_GAP=4
    private val grip=ImageView(context).apply {
        setImageResource(R.drawable.snow_grip); setColorFilter(0xFF8C8C8C.toInt(),PorterDuff.Mode.SRC_IN)
        contentDescription="按住拖动工具栏"; tooltipText=contentDescription
        isClickable=true; isFocusable=true
        setPadding(dp(1),dp(1),dp(1),dp(1))
    }
    init {
        background=GradientDrawable().apply { setColor(0xFF1C1C1E.toInt()); cornerRadius=dp(8).toFloat() }
        elevation=dp(6).toFloat()
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
        val previousScroll=body?.scrollY ?: 0
        removeAllViews(); body=null
        addView(grip,LayoutParams(dp(GRIP),dp(32),Gravity.LEFT or Gravity.CENTER_VERTICAL).apply { leftMargin=dp(GRIP_INSET) })
        val closeWidth=if(cancel==null) 0 else dp(32)
        val closeGap=if(cancel!=null && items.isNotEmpty()) dp(8) else 0
        val room=(available-dp(24)-closeWidth-closeGap-dp(GRIP+GRIP_INSET+GRIP_GAP)).coerceAtLeast(dp(32))
        val content=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        var row=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        var width=0; var widest=0; var rowCount=1; var previousSeparator=false
        content.addView(row,LinearLayout.LayoutParams(-2,dp(32)))
        for((view,separator) in items) {
            val cellWidth=if(separator) dp(25) else dp(32)
            var gap=if(width==0 || separator || previousSeparator) 0 else dp(8)
            if(separator && (width==0 || width+cellWidth>room)) continue
            if(width>0 && width+gap+cellWidth>room) {
                widest=maxOf(widest,width); width=0; gap=0; rowCount++
                row=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
                content.addView(row,LinearLayout.LayoutParams(-2,dp(32)).apply { topMargin=dp(8) })
            }
            if(gap>0) row.addView(Space(context),LinearLayout.LayoutParams(gap,1))
            if(separator) row.addView(view,LinearLayout.LayoutParams(dp(1),dp(16)).apply { marginStart=dp(12); marginEnd=dp(12) })
            else row.addView(view,LinearLayout.LayoutParams(dp(32),dp(32)))
            width+=gap+cellWidth; previousSeparator=separator
        }
        val bodyWidth=if(items.isEmpty()) 0 else maxOf(widest,width)
        val rowsLimit=if(visibleHeight>=dp(400)) 2 else 1
        val bodyHeight=if(items.isEmpty()) 0 else dp(minOf(rowCount,rowsLimit)*32+(minOf(rowCount,rowsLimit)-1)*8)
        if(bodyWidth>0) addView(ScrollView(context).also { body=it }.apply {
            contentDescription="截图工具栏，可上下滚动"; isVerticalScrollBarEnabled=rowCount>rowsLimit
            addView(content)
            if(previousScroll>0) post { scrollTo(0,previousScroll) }
        },LayoutParams(bodyWidth,bodyHeight).apply { leftMargin=dp(GRIP+GRIP_INSET+GRIP_GAP); topMargin=dp(4) })
        val lead=dp(GRIP_INSET+GRIP)+(if(bodyWidth>0) dp(GRIP_GAP) else 0)
        packedWidth=lead+bodyWidth+closeGap+closeWidth+dp(12)
        packedHeight=dp(8)+maxOf(bodyHeight,if(cancel!=null) dp(32) else 0)
        if(cancel!=null) addView(cancel,LayoutParams(dp(32),dp(32),Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin=dp(12) })
        layoutParams=LayoutParams(packedWidth,packedHeight)
    }
}
