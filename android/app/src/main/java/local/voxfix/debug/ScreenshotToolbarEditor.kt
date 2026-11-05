package local.voxfix.debug

import android.content.ClipData
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Independent Android implementation of Snow Shot 1.1.8's spatial toolbar editor. */
class ScreenshotToolbarEditor(context: Context, private val section: ScreenshotToolbarSection? = null) : LinearLayout(context) {
    private val preferences = ScreenTextSettingsActivity.preferences(context)
    private var config = section?.let { ScreenshotToolbarPreferences.load(context,it) }
        ?: ScreenshotToolbarLayout.decode(preferences.getString(KEY,null))
    private var preview: ScrollView? = null
    private var scrollStep = 0
    private val autoScroll = object : Runnable {
        override fun run() {
            if (scrollStep == 0) return
            preview?.scrollBy(0,scrollStep)
            postDelayed(this,32)
        }
    }
    private fun dp(value: Int) = (value*resources.displayMetrics.density).toInt()
    private fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(8).toFloat() }

    init { orientation = VERTICAL; setBackgroundColor(BACKGROUND); render() }

    private fun save(next: ScreenshotToolbarLayout) {
        if (next == config) return
        config = next
        if (section == null) preferences.edit().putString(KEY,next.encode()).apply()
        else ScreenshotToolbarPreferences.save(context,section,next)
        render()
    }
    fun reset() { save(section?.let { ScreenshotToolbarLayout.defaults().inSection(it) } ?: ScreenshotToolbarLayout.defaults()) }

    private fun tool(action: ScreenshotAction): View = ImageView(context).apply {
        tag = action
        contentDescription = ScreenshotToolbarState().label(action)
        tooltipText = contentDescription
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(4),dp(4),dp(4),dp(4))
        if (action == ScreenshotAction.SEPARATOR) {
            setImageDrawable(GradientDrawable().apply { setColor(TEXT); setSize(dp(1),dp(16)) })
        } else {
            setImageResource(icon(action)); setColorFilter(TEXT)
        }
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x30FFFFFF),null,shape(-1))
        isFocusable = true
        var downX = 0f; var downY = 0f
        setOnTouchListener { view,event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    if (kotlin.math.abs(event.x-downX) > slop || kotlin.math.abs(event.y-downY) > slop)
                        return@setOnTouchListener view.startDragAndDrop(ClipData.newPlainText("截图工具",action.name),View.DragShadowBuilder(view),action,0)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        setOnLongClickListener { startDragAndDrop(ClipData.newPlainText("截图工具",action.name),View.DragShadowBuilder(this),action,0) }
        // Accessibility actions keep rearranging available without a drag gesture.
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host,info)
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(0x01000001,"向左移动"))
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(0x01000002,"向右移动"))
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(0x01000003,"隐藏或恢复"))
            }
            override fun performAccessibilityAction(host: View, id: Int, args: android.os.Bundle?): Boolean {
                val index = config.columns.indexOfFirst { action in it }
                when (id) {
                    0x01000001 -> save(config.place(action,(index-1).coerceAtLeast(0)))
                    0x01000002 -> save(config.place(action,(index+2).coerceAtMost(config.columns.size)))
                    0x01000003 -> save(if (action in config.hidden) config.place(action,config.columns.size) else config.hide(action))
                    else -> return super.performAccessibilityAction(host,id,args)
                }
                return true
            }
        }
    }

    private fun render() {
        val previousScroll = preview?.scrollY ?: 0
        val wasAtBottom = preview?.let { it.scrollY>0 && it.scrollY+it.height >= (it.getChildAt(0)?.height ?: 0)-dp(8) } ?: false
        removeAllViews()
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = section?.label ?: "截图工具栏"; textSize = 18f; setTextColor(TEXT); setTypeface(typeface,android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        },LayoutParams(0,dp(40),1f))
        header.addView(ImageView(context).apply {
            contentDescription = "恢复默认布局"; setImageResource(R.drawable.snow_reload); setColorFilter(TEXT)
            setPadding(dp(12),dp(12),dp(12),dp(12))
            setOnClickListener { save(section?.let { ScreenshotToolbarLayout.defaults().inSection(it) } ?: ScreenshotToolbarLayout.defaults()) }
        },LayoutParams(dp(40),dp(40)))
        if (section == null) addView(header)
        val surface = Surface(false)
        addView(ScrollView(context).apply {
            isFillViewport = true; minimumHeight = dp(96); addView(surface)
            preview = this
            post { scrollTo(0,if (wasAtBottom) (surface.height-height).coerceAtLeast(0) else previousScroll) }
        },LayoutParams(-1,0,1f))
        addView(TextView(context).apply {
            text = "隐藏的工具"; textSize = 16f; setTextColor(TEXT); setTypeface(typeface,android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        },LayoutParams(-1,dp(32)))
        val hidden = Surface(true)
        val columns = ((resources.displayMetrics.widthPixels-dp(40)+dp(8))/dp(40)).coerceAtLeast(1)
        val trayHeight = dp(20+((config.hidden.size+columns-1)/columns)*40).coerceIn(dp(56),dp(128))
        addView(ScrollView(context).apply { addView(hidden) },LayoutParams(-1,trayHeight))
    }

    private inner class Surface(private val hidden: Boolean) : ViewGroup(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val positions = mutableListOf<RectF>()
        private val rows = mutableListOf<IntRange>()
        private var indicator: RectF? = null
        private var destination = 0
        private var insertion: Int? = null
        private var dragging = false
        init {
            setWillNotDraw(false)
            clipChildren = false
            contentDescription = if (hidden) "隐藏工具区域" else "截图工具栏预览"
            if (hidden) config.hidden.forEach { addView(tool(it)) }
            else config.columns.flatten().forEach { addView(tool(it)) }
            setOnDragListener { _, event ->
                val action = event.localState as? ScreenshotAction ?: return@setOnDragListener false
                handleDrag(event.action, event.x, event.y, action)
            }
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width = MeasureSpec.getSize(widthSpec).takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels-dp(16)
            val count = ((width-dp(24)+dp(8))/dp(40)).coerceAtLeast(1)
            val perRow = ((width-dp(72)+dp(8))/dp(40)).coerceAtLeast(1)
            val groups = config.columns.chunked(perRow)
            val height = if (hidden) maxOf(dp(56),dp(20+((childCount+count-1)/count)*40))
                else dp(52+groups.sumOf { row -> (row.maxOf { it.size }*36)+4 }+(groups.size-1).coerceAtLeast(0)*16)
            setMeasuredDimension(width,if (!hidden && MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY)
                maxOf(height,MeasureSpec.getSize(heightSpec)) else height)
            for (index in 0 until childCount) getChildAt(index).measure(
                MeasureSpec.makeMeasureSpec(dp(32),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(dp(32),MeasureSpec.EXACTLY))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            positions.clear()
            rows.clear()
            if (hidden) {
                val count = ((width-dp(24)+dp(8))/dp(40)).coerceAtLeast(1)
                for (index in 0 until childCount) {
                    val x = dp(12+index%count*40); val y = dp(10+index/count*40)
                    getChildAt(index).layout(x,y,x+dp(32),y+dp(32))
                }
            } else {
                var child = 0
                val perRow = ((width-dp(72)+dp(8))/dp(40)).coerceAtLeast(1)
                val chunks = config.columns.chunked(perRow)
                var rowTop = dp(24)
                chunks.forEach { row ->
                    val start = (width-dp(24+row.size*32+(row.size-1)*8))/2+dp(12)
                    val bottomY = rowTop+dp(row.maxOf { it.size }*36)
                    val first = positions.size
                    row.forEachIndexed { index,column ->
                    val x = start+dp(index*40)
                    val y = bottomY-dp(column.size*32+(column.size-1)*4)
                    positions.add(RectF(x.toFloat(),y.toFloat(),(x+dp(32)).toFloat(),bottomY.toFloat()))
                    column.forEachIndexed { item,_ ->
                        val topY = y+dp(item*36)
                        getChildAt(child++).layout(x,topY,x+dp(32),topY+dp(32))
                    }
                    }
                    rows.add(first until positions.size)
                    rowTop = bottomY+dp(20)
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = if (hidden) 0xFF333333.toInt() else BACKGROUND
            if (hidden) canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),dp(8).toFloat(),dp(8).toFloat(),paint)
            else if (positions.isNotEmpty()) {
                paint.setShadowLayer(dp(8).toFloat(),0f,dp(3).toFloat(),0x66000000)
                for (row in rows) {
                    val first = positions[row.first]; val last = positions[row.last]
                    canvas.drawRoundRect(first.left-dp(12),first.bottom-dp(36),
                        last.right+dp(12),first.bottom+dp(4),dp(8).toFloat(),dp(8).toFloat(),paint)
                }
                for (position in positions) if (position.height() > dp(32))
                    canvas.drawRoundRect(position.left-dp(4),position.top-dp(4),position.right+dp(4),position.bottom+dp(4),
                        dp(8).toFloat(),dp(8).toFloat(),paint)
                paint.clearShadowLayer()
            }
            if (dragging && hidden) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1).toFloat(); paint.color = ACCENT
                canvas.drawRoundRect(1f,1f,width-1f,height-1f,dp(8).toFloat(),dp(8).toFloat(),paint)
                paint.style = Paint.Style.FILL
            }
            indicator?.let { paint.color = ACCENT; canvas.drawRect(it,paint) }
        }

        private fun locate(x: Float, y: Float, action: ScreenshotAction) {
            insertion = null
            if (hidden) {
                destination = (0 until childCount).firstOrNull { index ->
                    val child = getChildAt(index)
                    y < child.bottom && (y < child.top || x < (child.left+child.right)/2f)
                } ?: childCount
                val target = getChildAt(destination)
                val px = target?.left?.minus(dp(4)) ?: if (childCount == 0) dp(12) else getChildAt(childCount-1).right+dp(4)
                val py = target?.top ?: dp(10)
                indicator = RectF(px.toFloat(),py.toFloat(),(px+dp(3)).toFloat(),(py+dp(32)).toFloat())
                return
            }
            val row = rows.minByOrNull { indices ->
                val top = indices.minOf { positions[it].top }
                val bottom = positions[indices.first].bottom
                maxOf(top-y,y-bottom,0f)
            } ?: (0 until 0)
            destination = if (row.isEmpty()) 0 else row.last+1
            for (index in row) {
                val rect = positions[index]
                if (x in rect.left..rect.right && action != ScreenshotAction.SEPARATOR && ScreenshotAction.SEPARATOR !in config.columns[index]) {
                    destination = index
                    insertion = config.columns[index].indices.firstOrNull { item -> y < rect.top+dp(item*36+16) } ?: config.columns[index].size
                    val lineY = rect.top+dp(insertion!!*36)-dp(3)
                    indicator = RectF(rect.left+dp(4),lineY,rect.right-dp(4),lineY+dp(3))
                    return
                }
                if (x <= rect.centerX()) { destination = index; break }
            }
            val lineX = when {
                positions.isEmpty() -> width/2f
                destination == row.first -> positions[row.first].left-dp(6)
                destination == row.last+1 -> positions[row.last].right+dp(6)
                else -> (positions[destination-1].right+positions[destination].left)/2
            }
            val bottom = row.firstOrNull()?.let { positions[it].bottom } ?: (height-dp(28)).toFloat()
            indicator = RectF(lineX-dp(1),bottom-dp(32),lineX+dp(2),bottom)
        }

        private fun handleDrag(kind: Int, x: Float, y: Float, action: ScreenshotAction): Boolean {
            when (kind) {
                DragEvent.ACTION_DRAG_STARTED -> { dragging = true; invalidate() }
                DragEvent.ACTION_DRAG_LOCATION, DragEvent.ACTION_DRAG_ENTERED -> {
                    locate(x,y,action)
                    dragging = true
                    if (!hidden) preview?.let { scroll ->
                        val origin = IntArray(2); val viewport = IntArray(2)
                        getLocationOnScreen(origin); scroll.getLocationOnScreen(viewport)
                        val viewportY = origin[1]+y-viewport[1]
                        scrollStep = when { viewportY < dp(32) -> -dp(8); viewportY > scroll.height-dp(32) -> dp(8); else -> 0 }
                        removeCallbacks(autoScroll); if (scrollStep != 0) this@ScreenshotToolbarEditor.post(autoScroll)
                    }
                    invalidate()
                }
                DragEvent.ACTION_DROP -> {
                    locate(x,y,action)
                    scrollStep = 0; this@ScreenshotToolbarEditor.removeCallbacks(autoScroll)
                    save(if (hidden) config.hide(action,destination) else config.place(action,destination,insertion))
                }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                    indicator = null; dragging = false; scrollStep = 0
                    this@ScreenshotToolbarEditor.removeCallbacks(autoScroll); invalidate()
                }
            }
            return true
        }
    }

    override fun onDetachedFromWindow() { scrollStep = 0; removeCallbacks(autoScroll); super.onDetachedFromWindow() }

    companion object {
        const val KEY = "screenshot.toolbar.layout"
        const val BACKGROUND = 0xFF141414.toInt()
        private const val TEXT = 0xFFD9D9D9.toInt()
        private const val ACCENT = 0xFF1677FF.toInt()
        fun icon(action: ScreenshotAction) = when (action) {
            ScreenshotAction.FULL_SCREEN -> R.drawable.ic_screenshot_fullscreen
            ScreenshotAction.ARROW -> R.drawable.snow_arrow
            ScreenshotAction.LINE -> R.drawable.snow_line
            ScreenshotAction.WATERMARK -> R.drawable.snow_watermark
            ScreenshotAction.RECTANGLE -> R.drawable.snow_rectangle
            ScreenshotAction.PEN -> R.drawable.snow_pen
            ScreenshotAction.FILTER -> R.drawable.snow_filter
            ScreenshotAction.HIGHLIGHT -> R.drawable.snow_highlight
            ScreenshotAction.SPOTLIGHT -> R.drawable.snow_spotlight
            ScreenshotAction.TEXT -> R.drawable.snow_text
            ScreenshotAction.SERIAL -> R.drawable.snow_serial
            ScreenshotAction.ERASER -> R.drawable.snow_eraser
            ScreenshotAction.SELECT -> R.drawable.snow_select
            ScreenshotAction.MOVE -> R.drawable.snow_move
            ScreenshotAction.BARCODE -> R.drawable.snow_barcode
            ScreenshotAction.PIN -> R.drawable.snow_pin
            ScreenshotAction.SHARE -> R.drawable.snow_share
            ScreenshotAction.SCROLL -> R.drawable.snow_scroll
            ScreenshotAction.RECORD -> R.drawable.snow_record
            ScreenshotAction.RECOGNIZE -> R.drawable.snow_recognize
            ScreenshotAction.TRANSLATE -> R.drawable.snow_translate
            ScreenshotAction.SAVE -> R.drawable.snow_save
            ScreenshotAction.COPY_TEXT -> R.drawable.snow_copy
            ScreenshotAction.SHOW_ORIGINAL -> R.drawable.snow_eye
            ScreenshotAction.EDIT -> R.drawable.snow_edit
            ScreenshotAction.UNDO -> R.drawable.snow_undo
            ScreenshotAction.REDO -> R.drawable.snow_redo
            ScreenshotAction.CLOSE -> R.drawable.snow_close
            ScreenshotAction.SEPARATOR -> R.drawable.snow_reload
        }
    }
}
