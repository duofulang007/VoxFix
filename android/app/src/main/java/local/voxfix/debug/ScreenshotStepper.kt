package local.voxfix.debug

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 数值参数的加减步进控件：`[ − ][ 读数 ][ + ]`。
 *
 * 为什么不是滑杆：字号是 6–256 且允许小数，滑杆在这么大跨度上精度不可用；原版用鼠标滚轮
 * 逐格走，触屏用「加减 + 长按连续」等价替代（平台差异清单 D11）。
 *
 * 事务语义：一次按压 = 一条撤销记录。按下时 [begin]，每帧只走 [apply]（预览，不重建控制栏），
 * 抬手/取消时 [commit]。中途调 `applyStyle` 会触发宿主 `rebuildDrawingStyles()`，
 * 把正在按住的 View 从视图树上摘掉——这是判断「按到一半控制栏消失」时要先排掉的坑。
 */
internal class ScreenshotStepper(
    context: Context,
    private val label: String,
    private val min: Float,
    private val max: Float,
    initial: Float?,
    /** Mixed（多选且值不一致）时 `−/+` 的起点。 */
    private val fallback: Float,
    private val begin: () -> Unit,
    private val apply: (Float) -> Unit,
    private val commit: () -> Unit,
    private val onEdit: (() -> Unit)? = null,
    private val step: Float = 1f,
    private val format: (Float) -> String = { it.toInt().toString() },
    /** 读数格的 contentDescription；默认「字号 42」。设备套件靠它定位，文案不可改。 */
    private val description: (String) -> String = { "$label $it" }
) : LinearLayout(context) {
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private val handler=Handler(Looper.getMainLooper())
    private var current: Float?=initial
    private var accum=0f
    private var direction=0
    private var tick=0
    private var holding=false

    private val readout=TextView(context).apply {
        textSize=12f; gravity=Gravity.CENTER; setSingleLine(true)
        setTextColor(ScreenshotUiTokens.TEXT)
        background=surface()
        isFocusable=true
        setPadding(dp(2),0,dp(2),0)
        setOnClickListener { onEdit?.invoke() }
        // 滚轮分支保留：设备套件用它验证 ±1（触屏上不触发，投屏/桌面鼠标可用）。
        setOnGenericMotionListener { _,event ->
            val delta=event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if(event.actionMasked==MotionEvent.ACTION_SCROLL && delta.isFinite() && delta!=0f) {
                val base=current ?: fallback
                val next=ScreenshotStepperMath.clamp(base+(if(delta>0) step else -step),min,max)
                if(next!=base) { begin(); render(next); apply(next); commit() }
                true
            } else false
        }
    }

    private val ticker=object : Runnable {
        override fun run() {
            if(!holding) return
            tick++
            val delta=ScreenshotStepperMath.stepForTick(tick)*step*direction
            val next=ScreenshotStepperMath.clamp(accum+delta,min,max)
            if(next!=accum) { accum=next; render(next); apply(next) }
            handler.postDelayed(this,ScreenshotStepperMath.REPEAT_MS)
        }
    }

    private fun step(direction: Int) {
        if(!isEnabled) return
        holding=true; this.direction=direction; tick=0
        accum=ScreenshotStepperMath.clamp(current ?: fallback,min,max)
        begin()
        val next=ScreenshotStepperMath.clamp(accum+direction*step,min,max)
        if(next!=accum) { accum=next; render(next); apply(next) }
        handler.postDelayed(ticker,ScreenshotStepperMath.HOLD_DELAY_MS)
    }

    private fun release() {
        if(!holding) return
        holding=false
        handler.removeCallbacks(ticker)
        // 一次按压 = 一条 undo；没走到步的按压在 finishStylePreview 里会自然变成 no-op。
        commit()
    }

    private fun key(glyph: String, stepDirection: Int, hint: String)=TextView(context).apply {
        text=glyph; textSize=16f; gravity=Gravity.CENTER
        setTextColor(ScreenshotUiTokens.TEXT)
        background=surface()
        contentDescription=hint; tooltipText=hint
        isFocusable=true; isClickable=true
        setOnClickListener { }
        setOnTouchListener { _,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true); step(stepDirection); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    release(); parent?.requestDisallowInterceptTouchEvent(false); performClick(); true
                }
                else -> true
            }
        }
    }

    private fun surface()=GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius=dp(ScreenshotToolbarMetrics.STYLE_CELL_RADIUS).toFloat()
        setStroke(dp(1).coerceAtLeast(1),ScreenshotUiTokens.OUTLINE_IDLE_CELL)
    }

    private fun render(value: Float?) {
        current=value
        readout.text=if(value==null) "-" else format(value)
        readout.contentDescription=description(readout.text.toString())
        readout.tooltipText="点击输入精确值"
        readout.stateDescription=if(value==null) "Mixed" else null
    }

    init {
        orientation=HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
        addView(key("−",-1,"$label 减小"),LayoutParams(dp(28),dp(28)))
        addView(readout,LayoutParams(dp(40),dp(28)).apply { marginStart=dp(2) })
        addView(key("+",1,"$label 增大"),LayoutParams(dp(28),dp(28)).apply { marginStart=dp(2) })
        render(initial)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha=if(enabled) 1f else .4f
        for(index in 0 until childCount) getChildAt(index).isEnabled=enabled
    }

    override fun onDetachedFromWindow() {
        holding=false; handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }
}
