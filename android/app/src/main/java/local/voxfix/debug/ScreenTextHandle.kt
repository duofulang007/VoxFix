package local.voxfix.debug

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
    /** 窗口是否还挂在 WindowManager 上：系统回收后服务必须据此重建，不能只看引用非空。 */
    val isAttached: Boolean get() = attached
    private var progress = 0f
    private var collapsedY = 0
    /** 展开态弧心的屏幕坐标；夹取后重算，否则贴屏幕上下沿呼出时按钮会错位。 */
    private var centerY = 0
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
    private val adb = Icon("无线调试", 4).apply { setOnClickListener { toggleWirelessDebug() } }
    /** 两枚动作按钮同半径上下分列：±30° / 60dp，中心距 60dp > 48dp，不重叠。 */
    private val radials = listOf(full to 30.0, adb to -30.0)
    /**
     * 面板内的短提示。HyperOS 会以 "Suppressing toast from package ... by user request" 直接吞掉
     * Toast，所以状态提示必须画在自己的悬浮窗里，不能依赖 Toast。
     */
    private val hint = android.widget.TextView(context).apply {
        setTextColor(0xffffffff.toInt()); textSize = 11f; gravity = Gravity.CENTER
        maxLines = 2; alpha = 0f; visibility = View.INVISIBLE
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = (9 * context.resources.displayMetrics.density); setColor(0xcc22262b.toInt())
        }
        setPadding(dp(6), dp(3), dp(6), dp(3))
    }
    private var hintToken = 0
    private val gesture = ScreenTextHandleGesture(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), dp(16).toFloat())
    private val tone get() = HandleToneResolver.peek(context)
    private var downX = 0f; private var downY = 0f
    private var initialX = 0; private var initialY = 0
    private var initialProgress = 0f
    private var wirelessOn = AdbWireless.enabled(context.contentResolver)
    private var wirelessBusy = false
    private var wirelessProgress = if (wirelessOn) 1f else 0f
    private var wirelessObserver: ContentObserver? = null
    private var wirelessAnim: ValueAnimator? = null
    private var spin: ValueAnimator? = null
    private var spinAngle = 0f
    private var shakeX = 0f
    private var shakeAnim: ValueAnimator? = null
    private var hintUntil = 0L
    private var companionWasUp = false
    private var beatPulse = 0f
    private var beatAnim: ValueAnimator? = null
    /** 每 2.5 秒刷新一次电脑心跳：只有面板开着才需要出字，其余时候只在"刚连上"时给一次触感。 */
    private val tick = object : Runnable {
        override fun run() {
            if (!attached) return
            val up = companionUp()
            if (up != companionWasUp) {
                companionWasUp = up
                if (up) {
                    handle.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    pulseHandle()
                }
            }
            updateStatus()
            main.postDelayed(this, 2500)
        }
    }
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
        root.addView(adb, FrameLayout.LayoutParams(dp(48), dp(48)))
        root.addView(close, FrameLayout.LayoutParams(dp(48), dp(48)))
        root.addView(handle, FrameLayout.LayoutParams(dp(24), dp(64)))
        root.addView(hint, FrameLayout.LayoutParams(dp(104), dp(38)))
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
        centerY = params.y + dp(32)
        watchWireless()
        companionWasUp = companionUp()
        adb.contentDescription = if (wirelessOn) "无线调试，已开启" else "无线调试，已关闭"
        windows.addView(root, params); attached = true; root.invalidate()
        main.removeCallbacks(tick); main.postDelayed(tick, 600)
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
        main.removeCallbacks(tick); main.postDelayed(tick, 240)
    }
    private fun prepareMenu() {
        if (params.width > dp(24)) return
        collapsedY = params.y
        params.y = collapsedY - dp(68); params.width = dp(112); params.height = dp(200)
        snap()
        // clamp 可能把窗口推走；弧心按夹取后的实际高度重算，否则贴屏幕上下沿呼出会错位。
        centerY = params.y + params.height / 2
        render(progress); update()
    }
    fun collapse(): Boolean {
        if (!expanded && progress == 0f) return false
        expanded = false; hideHint(); animateTo(0f)
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
                    snap(); centerY = params.y + dp(32); update(); render(0f)
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
        val menuY = if (wide) centerY - params.y else dp(32)
        val cy = originY + (menuY - originY) * value
        handle.translationX = cx - dp(12); handle.translationY = cy - dp(32)
        handle.alpha = 1f - value; handle.visibility = if (value == 1f) View.INVISIBLE else View.VISIBLE
        close.translationX = cx - dp(24); close.translationY = cy - dp(24)
        for ((button, degrees) in radials) {
            val angle = Math.toRadians((1f - value) * 24.0 + degrees)
            val radius = dp(60) * value
            button.translationX = cx + (if (right) -1 else 1) * (cos(angle) * radius).toFloat() - dp(24) + shakeX
            button.translationY = cy + (sin(angle) * radius).toFloat() - dp(24)
            button.alpha = value; button.scaleX = .95f + .05f * value; button.scaleY = button.scaleX
            button.visibility = if (value == 0f) View.INVISIBLE else View.VISIBLE
            button.isEnabled = expanded && value >= .95f
        }
        close.alpha = value; close.visibility = if (value == 0f) View.INVISIBLE else View.VISIBLE
        close.isEnabled = expanded
        hint.translationX = (params.width - dp(104)) / 2f
        hint.translationY = dp(156).toFloat()
        handle.invalidate()
    }
    private fun toggleWirelessDebug() {
        if (wirelessBusy || !expanded) return
        val target = !wirelessOn
        if (!AdbWireless.granted(context)) {
            showHint("缺少授权\n见设置页")
            reject(); return
        }
        wirelessBusy = true; adb.invalidate(); startSpin()
        AdbWireless.set(context, target) { outcome ->
            wirelessBusy = false; stopSpin()
            when (outcome) {
                AdbWireless.Outcome.OK -> {
                    wirelessOn = AdbWireless.enabled(context.contentResolver)
                    animateWireless(if (wirelessOn) 1f else 0f)
                    adb.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }
                // 手机这边没连 Wi-Fi：把系统网络面板拉起来，比只给一行字少一步。
                AdbWireless.Outcome.NO_WIFI -> { showHint("先连 Wi-Fi"); openNetworkPanel(); reject() }
                AdbWireless.Outcome.NO_PC_INFO -> { showHint("电脑端没运行"); reject() }
                AdbWireless.Outcome.DIFFERENT_NETWORK -> { showHint("不在同一 Wi-Fi"); reject() }
                AdbWireless.Outcome.PC_OFFLINE -> { showHint("电脑端没开"); reject() }
                AdbWireless.Outcome.DENIED -> { showHint("缺少授权\n见设置页"); reject() }
                AdbWireless.Outcome.REVERTED -> { showHint("系统未接受"); reject() }
            }
            adb.contentDescription = if (wirelessOn) "无线调试，已开启" else "无线调试，已关闭"
            adb.invalidate(); handle.invalidate()
        }
    }
    /** 只拉起系统网络面板（Android 10+ 禁止应用自己开 Wi-Fi），一键就能补上。 */
    private fun openNetworkPanel() {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        runCatching {
            context.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { android.util.Log.w("VoxFix", "NETWORK_PANEL_FAILED", it) }
    }
    private fun animateWireless(target: Float) {
        wirelessAnim?.cancel()
        wirelessAnim = ValueAnimator.ofFloat(wirelessProgress, target).apply {
            duration = 260
            interpolator = PathInterpolator(.32f, .72f, 0f, 1f)
            addUpdateListener {
                wirelessProgress = it.animatedValue as Float
                adb.invalidate(); handle.invalidate()
            }
            start()
        }
    }
    private fun reject() {
        shakeAnim?.cancel()
        shakeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            addUpdateListener {
                val t = it.animatedValue as Float
                shakeX = sin(t * Math.PI.toFloat() * 4f) * dp(3) * (1f - t)
                render(progress)
            }
            start()
        }
    }
    private fun companionUp(): Boolean {
        val beat = prefs.getLong("pc.beat", 0L)
        return beat > 0 && android.os.SystemClock.elapsedRealtime() - beat < 15_000
    }
    private fun companionStatus(): String? {
        if (!companionUp()) return "电脑未连"
        val transport = prefs.getString("pc.transport", "").orEmpty()
        val clip = prefs.getString("pc.clip", "0") == "1"
        return "电脑已连 · " + (if (transport == "usb") "USB" else "Wi-Fi") + if (clip) "" else " · 剪贴板未启"
    }
    private fun updateStatus() {
        if (!expanded) return
        if (android.os.SystemClock.uptimeMillis() < hintUntil) return
        hint.text = companionStatus() ?: return
        hint.animate().cancel()
        hint.visibility = View.VISIBLE
        hint.alpha = .82f
    }
    private fun pulseHandle() {
        beatAnim?.cancel()
        beatAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 600
            addUpdateListener {
                beatPulse = it.animatedValue as Float
                handle.invalidate()
            }
            start()
        }
    }
    private fun showHint(text: String) {
        hint.text = text
        hint.visibility = View.VISIBLE
        hintUntil = android.os.SystemClock.uptimeMillis() + 1900
        hint.animate().cancel()
        hint.animate().alpha(1f).setDuration(120).start()
        val token = ++hintToken
        main.postDelayed({
            if (token != hintToken) return@postDelayed
            hint.animate().alpha(0f).setDuration(200).withEndAction {
                if (token == hintToken) hint.visibility = View.INVISIBLE
            }.start()
        }, 1600)
    }
    private fun hideHint() {
        hintToken++
        hint.animate().cancel()
        hint.alpha = 0f
        hint.visibility = View.INVISIBLE
    }
    private fun startSpin() {
        spin?.cancel()
        spin = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { spinAngle = it.animatedValue as Float; adb.invalidate() }
            start()
        }
    }
    private fun stopSpin() { spin?.cancel(); spin = null; spinAngle = 0f }
    private fun watchWireless() {
        if (wirelessObserver != null) return
        wirelessObserver = AdbWireless.observe(context) {
            if (wirelessBusy) return@observe
            val next = AdbWireless.enabled(context.contentResolver)
            if (next == wirelessOn) return@observe
            wirelessOn = next
            animateWireless(if (next) 1f else 0f)
            adb.contentDescription = if (next) "无线调试，已开启" else "无线调试，已关闭"
        }
    }
    fun dismiss() {
        main.removeCallbacks(hold); gesture.end(); animation?.cancel()
        wirelessAnim?.cancel(); shakeAnim?.cancel(); beatAnim?.cancel(); stopSpin(); hideHint()
        main.removeCallbacks(tick)
        wirelessObserver?.let { AdbWireless.stop(context, it) }; wirelessObserver = null
        root.visibility = View.GONE
        if (attached) { attached = false; windows.removeViewImmediate(root) }
    }
    private inner class Icon(label: String, private val kind: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val arcRect = RectF()
        private val dash = DashPathEffect(floatArrayOf(3.6f, 3f), 0f)
        init { contentDescription = label; isClickable = true; isFocusable = true }
        override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
        override fun onDraw(canvas: Canvas) {
            val unit = resources.displayMetrics.density
            canvas.save(); canvas.translate(width / 2f, height / 2f); canvas.scale(unit, unit)
            when (kind) {
                0 -> drawHandle(canvas)
                1 -> drawClose(canvas)
                3 -> drawCapture(canvas)
                else -> drawWireless(canvas)
            }
            canvas.restore()
        }
        private fun drawHandle(canvas: Canvas) {
            paint.style = Paint.Style.STROKE
            val x = if (this@ScreenTextHandle.right) 9f else -9f
            val path = Path().apply {
                moveTo(x, -14f); quadTo(if (this@ScreenTextHandle.right) 3f else -3f, 0f, x, 14f)
            }
            // 无线调试开启时弧线常驻"激活"态（更实 + 更粗），不额外加图形标记：
            // 屏幕边缘只有 24dp，任何附加标记不是被裁掉就是读成"!"这类误义符号。
            paint.color = if (wirelessOn) tone.active else tone.colorFor(isPressed)
            paint.strokeWidth = (if (wirelessOn || isPressed) 2.6f else 2f) + beatPulse * 1.4f
            canvas.drawPath(path, paint)
        }
        private fun drawClose(canvas: Canvas) {
            paint.style = Paint.Style.FILL
            paint.color = if (isPressed) 0xd04a4a4a.toInt() else 0xb34a4a4a.toInt()
            canvas.drawCircle(0f, 0f, 15f, paint)
            paint.color = 0xfffafafa.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f
            canvas.drawLine(-4f, -4f, 4f, 4f, paint); canvas.drawLine(4f, -4f, -4f, 4f, paint)
        }
        private fun drawCapture(canvas: Canvas) {
            paint.style = Paint.Style.FILL
            paint.color = if (isPressed) 0xd04a4a4a.toInt() else 0xb34a4a4a.toInt()
            canvas.drawCircle(0f, 0f, 21f, paint)
            paint.color = 0xfffafafa.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f
            for (sx in listOf(-1, 1)) for (sy in listOf(-1, 1)) {
                canvas.drawLine(sx * 3f, sy * 7f, sx * 7f, sy * 7f, paint)
                canvas.drawLine(sx * 7f, sy * 7f, sx * 7f, sy * 3f, paint)
            }
            paint.strokeWidth = 1.4f
            canvas.drawLine(-2f, 0f, 2f, 0f, paint); canvas.drawLine(0f, -2f, 0f, 2f, paint)
        }
        /**
         * 无线调试：三段弧 + 圆点常驻盘面，关闭时压暗到 46% 并叠一条斜杠。
         * 圆心放在 6.15dp 而不是几何中心 7.65dp，是把图形整体上提 1.5dp 补偿 wifi 上宽下重的视觉重心。
         */
        private fun drawWireless(canvas: Canvas) {
            paint.style = Paint.Style.FILL
            paint.color = if (isPressed) 0xd04a4a4a.toInt() else 0xb34a4a4a.toInt()
            canvas.drawCircle(0f, 0f, 21f, paint)
            val value = wirelessProgress.coerceIn(0f, 1f)
            val dim = if (wirelessBusy) .3f else 1f
            val white = 0xffffff
            if (value > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.1f
                paint.color = ((107 * value).toInt().coerceIn(0, 255) shl 24) or white
                canvas.drawCircle(0f, 0f, 17.6f, paint)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.4f
            paint.color = (((.46f + .49f * value) * 255 * dim).toInt().coerceIn(0, 255) shl 24) or white
            for (radius in floatArrayOf(7.5f, 11.5f, 16.5f)) {
                arcRect.set(-radius, 6.15f - radius, radius, 6.15f + radius)
                canvas.drawArc(arcRect, 215f, 110f, false, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = (((.58f + .42f * value) * 255 * dim).toInt().coerceIn(0, 255) shl 24) or white
            canvas.drawCircle(0f, 6.15f, 2.2f, paint)
            val slash = ((1f - value) * .82f * 255 * dim).toInt()
            if (slash > 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.2f
                paint.color = (slash shl 24) or white
                canvas.drawLine(-12.5f, -12.5f, 12.5f, 12.5f, paint)
            }
            if (wirelessBusy) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.6f
                paint.color = (0xb3 shl 24) or white
                paint.pathEffect = dash
                canvas.save(); canvas.rotate(spinAngle, 0f, 0f)
                canvas.drawCircle(0f, 0f, 17.6f, paint)
                canvas.restore()
                paint.pathEffect = null
            }
        }
    }
}
