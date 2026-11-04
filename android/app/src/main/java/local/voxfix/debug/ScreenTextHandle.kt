package local.voxfix.debug

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.sin

/** Small accessibility window, independent of the keyboard and with no full-screen touch blocker. */
internal class ScreenTextHandle(private val context: Context, private val capture: (Boolean) -> Unit) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val prefs = ScreenTextSettingsActivity.preferences(context)
    private val main = Handler(Looper.getMainLooper())
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private var right = prefs.getBoolean("right", true)
    var expanded = false
        private set
    private var attached = false
    private var progress = 0f
    private var collapsedY = 0
    private var animation: ValueAnimator? = null
    private val root = FrameLayout(context).apply { alpha = 0f }
    private val params = WindowManager.LayoutParams(dp(24), dp(64), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        windowAnimations = 0
        setFitInsetsTypes(0)
    }
    private val handle = Icon("截图工具，向内滑动展开，长按拖动", 0).apply {
        isClickable = true
        setOnClickListener { open() } // Accessibility activation also works without a swipe.
    }
    private val close = Icon("收起截图工具", 1).apply { setOnClickListener { collapse() } }
    private val full = Icon("截图", 3).apply { setOnClickListener { capture(false) } }
    private val gesture = ScreenTextHandleGesture(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), dp(16).toFloat())
    private val tone get() = HandleToneResolver.peek(context)
    private var downX = 0f; private var downY = 0f
    private var initialX = 0; private var initialY = 0
    private var initialProgress = 0f
    private val hold = Runnable {
        if (gesture.longPress()) handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
    init {
        // A newly added overlay must not paint at the window's provisional origin.
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            render(progress)
        }
        root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (attached && root.width == params.width && root.height == params.height) {
                    root.alpha = 1f
                    root.viewTreeObserver.removeOnPreDrawListener(this)
                }
                return true
            }
        })
        root.addView(full, FrameLayout.LayoutParams(dp(48), dp(48)))
        root.addView(close, FrameLayout.LayoutParams(dp(48), dp(48)))
        root.addView(handle, FrameLayout.LayoutParams(dp(24), dp(64)))
        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE || event.actionMasked == MotionEvent.ACTION_DOWN) collapse()
            true
        }
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    animation?.cancel()
                    initialProgress = progress
                    downX = event.rawX; downY = event.rawY; initialX = params.x; initialY = params.y
                    gesture.begin()
                    if (params.width == dp(24)) main.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> when (gesture.move(event.rawX - downX, event.rawY - downY, right)) {
                    ScreenTextHandleGesture.Move.PULL -> {
                        main.removeCallbacks(hold); prepareMenu(); render(gesture.progressFrom(initialProgress))
                    }
                    ScreenTextHandleGesture.Move.DRAG -> {
                        params.x = initialX + (event.rawX - downX).toInt()
                        params.y = initialY + (event.rawY - downY).toInt()
                        clamp(); update()
                    }
                    else -> Unit
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val open = event.actionMasked == MotionEvent.ACTION_UP && gesture.shouldOpen
                    main.removeCallbacks(hold); gesture.end()
                    if (params.width > dp(24)) {
                        expanded = open; animateTo(if (open) 1f else 0f)
                    } else {
                        val bounds = windows.currentWindowMetrics.bounds
                        right = params.x + params.width / 2 > bounds.width() / 2
                        snap(); update()
                        prefs.edit().putBoolean("right", right)
                            .putFloat("y", params.y.toFloat() / maxOf(1, bounds.height() - dp(64))).apply()
                        handle.invalidate()
                    }
                    view.isPressed = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    view.isPressed = false
                    main.removeCallbacks(hold); gesture.end()
                    if (params.width > dp(24)) { expanded = false; animateTo(0f) }
                }
            }
            true
        }
        render(0f)
    }
    fun show() {
        HandleToneResolver.refresh(context) { handle.postInvalidate() }
        val bounds = windows.currentWindowMetrics.bounds
        params.y = (prefs.getFloat("y", .4f) * (bounds.height() - dp(64))).toInt()
        snap()
        windows.addView(root, params); attached = true; root.invalidate()
    }
    private fun clamp() {
        val metrics = windows.currentWindowMetrics
        val bars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        params.x = params.x.coerceIn(bars.left, maxOf(bars.left, metrics.bounds.width() - params.width - bars.right))
        params.y = params.y.coerceIn(bars.top, maxOf(bars.top, metrics.bounds.height() - params.height - bars.bottom))
    }
    private fun snap() {
        params.x = if (right) windows.currentWindowMetrics.bounds.width() - params.width else 0
        clamp()
    }
    private fun update() { if (attached) windows.updateViewLayout(root, params) }
    private fun open() {
        if (expanded || !attached) return
        main.removeCallbacks(hold); gesture.end()
        expanded = true
        prepareMenu(); animateTo(1f)
    }
    private fun prepareMenu() {
        if (params.width > dp(24)) return
        collapsedY = params.y
        params.y -= dp(40); params.width = dp(104); params.height = dp(144)
        snap(); render(progress); update()
    }
    fun collapse(): Boolean {
        if (!expanded && progress == 0f) return false
        expanded = false; animateTo(0f)
        return true
    }
    private fun animateTo(target: Float) {
        animation?.cancel()
        animation = ValueAnimator.ofFloat(progress, target).apply {
            duration = if (target == 1f) 220 else 180
            interpolator = PathInterpolator(.32f, .72f, 0f, 1f)
            addUpdateListener {
                render(it.animatedValue as Float)
                if (progress == 0f && !expanded) {
                    params.y = collapsedY; params.width = dp(24); params.height = dp(64)
                    snap(); update(); render(0f)
                }
            }
            start()
        }
    }
    private fun render(value: Float) {
        progress = value
        val wide = params.width > dp(24)
        val edge = dp(12) + if (wide) (dp(24) - dp(12)) * value else 0f
        val cx = if (right) params.width - edge.toFloat() else edge.toFloat()
        val originY = if (wide) collapsedY + dp(32) - params.y else dp(32)
        val cy = originY + (dp(72) - originY) * value
        handle.translationX = cx - dp(12); handle.translationY = cy - dp(32)
        handle.alpha = 1f - value; handle.visibility = if (value == 1f) View.INVISIBLE else View.VISIBLE
        close.translationX = cx - dp(24); close.translationY = cy - dp(24)
        for (button in listOf(full)) {
            val angle = Math.toRadians((1f - value) * 24.0)
            val radius = dp(52) * value
            button.translationX = cx + (if (right) -1 else 1) * (cos(angle) * radius).toFloat() - dp(24)
            button.translationY = cy + (sin(angle) * radius).toFloat() - dp(24)
            button.alpha = value; button.scaleX = .95f + .05f * value; button.scaleY = button.scaleX
            button.visibility = if (value == 0f) View.INVISIBLE else View.VISIBLE
            button.isEnabled = expanded && value >= .95f
        }
        close.alpha = value; close.visibility = if (value == 0f) View.INVISIBLE else View.VISIBLE
        close.isEnabled = expanded; handle.invalidate()
    }
    fun dismiss() {
        main.removeCallbacks(hold); gesture.end(); animation?.cancel()
        root.visibility = View.GONE
        if (attached) { attached = false; windows.removeViewImmediate(root) }
    }
    private inner class Icon(label: String, private val kind: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        init { contentDescription = label; isClickable = true; isFocusable = true }
        override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
        override fun onDraw(canvas: Canvas) {
            val unit = resources.displayMetrics.density
            canvas.save(); canvas.translate(width / 2f, height / 2f); canvas.scale(unit, unit)
            paint.style = Paint.Style.FILL
            if (kind == 0) {
                paint.style = Paint.Style.STROKE
                val path = Path().apply {
                    val x = if (this@ScreenTextHandle.right) 9f else -9f
                    moveTo(x, -14f); quadTo(if (this@ScreenTextHandle.right) 3f else -3f, 0f, x, 14f)
                }
                paint.color = tone.colorFor(isPressed)
                paint.strokeWidth = if (isPressed) 2.5f else 2f
                canvas.drawPath(path, paint)
            } else {
                paint.color = if (isPressed) 0xd04a4a4a.toInt() else 0xb34a4a4a.toInt()
                canvas.drawCircle(0f, 0f, if (kind == 1) 15f else 21f, paint)
                paint.color = 0xfffafafa.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f
                if (kind == 1) {
                    canvas.drawLine(-4f, -4f, 4f, 4f, paint); canvas.drawLine(4f, -4f, -4f, 4f, paint)
                } else if (kind == 2) {
                    canvas.drawRoundRect(-7f, -9f, 7f, 9f, 2f, 2f, paint)
                    for (y in listOf(-4f, 0f, 4f)) canvas.drawLine(-3f, y, 3f, y, paint)
                } else {
                    for (sx in listOf(-1, 1)) for (sy in listOf(-1, 1)) {
                        canvas.drawLine(sx * 3f, sy * 7f, sx * 7f, sy * 7f, paint)
                        canvas.drawLine(sx * 7f, sy * 7f, sx * 7f, sy * 3f, paint)
                    }
                    if (kind == 3) {
                        paint.strokeWidth = 1.4f
                        canvas.drawLine(-2f, 0f, 2f, 0f, paint); canvas.drawLine(0f, -2f, 0f, 2f, paint)
                    }
                }
            }
            canvas.restore()
        }
    }
}
