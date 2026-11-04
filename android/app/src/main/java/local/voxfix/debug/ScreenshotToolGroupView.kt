package local.voxfix.debug

import android.content.Context
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.View
import android.widget.FrameLayout

/** One normal-width tool target. The corner mark shares the icon's space. */
class ScreenshotToolGroupView(context: Context, tool: View, label: String, open: (View) -> Unit) : FrameLayout(context) {
    private val gestures=GestureDetector(context,object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent)=true
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            performClick()
            return true
        }
        override fun onLongPress(event: MotionEvent) { performLongClick() }
    })
    init {
        contentDescription = "${tool.contentDescription}，长按切换组内工具"
        tooltipText = label
        isClickable = true; isFocusable = true
        tool.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(tool,LayoutParams(-1,-1))
        setOnClickListener { tool.performClick() }
        setOnLongClickListener { open(this); true }
    }
    override fun onInterceptTouchEvent(event: MotionEvent) = true
    override fun onTouchEvent(event: MotionEvent)=gestures.onTouchEvent(event)
    // No corner mark: Snow Shot's bar shows no group badge, and the mark read as an icon defect.
}
