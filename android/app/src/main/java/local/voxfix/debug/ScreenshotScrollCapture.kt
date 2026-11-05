package local.voxfix.debug

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.nio.ByteBuffer

/** Main sequences screenshots and gestures; a single worker owns pixels and stitching. */
internal class ScreenshotScrollCapture(
    private val context: AccessibilityService, private val windows: WindowManager, private val crop: Rect,
    private val displayWidth: Int, private val displayHeight: Int, private val barTop: Int,
    private val horizontal: Boolean, private val captureAllowed: () -> Boolean,
    private val sendSwipe: ((Boolean) -> Unit) -> Boolean,
    private val closed: (String?) -> Unit
) {
    private enum class Phase { CAPTURING, FINISHING, PREVIEW, CLOSED }
    @Volatile private var phase = Phase.CAPTURING
    private val main = Handler(Looper.getMainLooper())
    private val firstFrameTimeout = Runnable { cancel("未能获取屏幕画面") }
    private val captureTick = Runnable { captureFrame() }
    private var frameInFlight = false
    private var captureRevision = 0
    private var lastCaptureAt = 0L
    private var scheduledAt = Long.MAX_VALUE
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var stitch: ScreenshotStitch? = null
    private var pixels: ByteBuffer? = null
    private var window: View? = null
    private var thumbnail: Bitmap? = null
    private var previewImage: Bitmap? = null
    private var hasFrame = false
    private var saving = false
    private lateinit var finishButton: ImageButton
    private lateinit var autoButton: ImageButton
    private lateinit var status: TextView
    private lateinit var thumbnailView: ImageView
    private var trim: ScreenshotScrollTrim? = null
    private var resultWidth = 0
    private var resultHeight = 0
    private var autoRunning = false
    private var swipePending = false
    private val preferences = ScreenTextSettingsActivity.preferences(context)
    private var autoIntervalMs = preferences.getInt("scroll.autoIntervalMs",200).coerceIn(128,1000).toLong()
    private var growth = 0
    private var stalledSteps = 0
    private var duplicateSteps = 0
    private val autoTick = Runnable { runAutoStep() }
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt().coerceAtLeast(1)

    fun start() {
        try {
            val handlerThread = HandlerThread("VoxFix long screenshot").apply { start() }
            thread = handlerThread
            worker = Handler(handlerThread.looper)
            showControls()
            main.postDelayed(firstFrameTimeout, 5_000)
            scheduleFrame(FRAME_INTERVAL_MS)
        } catch (_: Exception) { cancel("无法开始长截图") }
    }

    private fun captureFrame() {
        scheduledAt = Long.MAX_VALUE
        if (phase != Phase.CAPTURING || frameInFlight || swipePending) return
        if (!captureAllowed()) {
            if (hasFrame) finish() else cancel("原页面已切换，请重新开始长截图")
            return
        }
        frameInFlight = true
        lastCaptureAt = SystemClock.uptimeMillis()
        val revision = captureRevision
        try {
            context.takeScreenshot(Display.DEFAULT_DISPLAY, context.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    if (revision != captureRevision || phase != Phase.CAPTURING || !captureAllowed()) {
                        result.hardwareBuffer.close()
                        if (revision == captureRevision && phase == Phase.CAPTURING) {
                            frameInFlight = false
                            if (hasFrame) finish() else cancel("原页面已切换，请重新开始长截图")
                        }
                        return
                    }
                    if (worker?.post { acceptFrame(result, revision) } != true) {
                        result.hardwareBuffer.close()
                        cancel("长截图采集已停止")
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (revision != captureRevision || phase != Phase.CAPTURING) return
                    frameInFlight = false
                    if (runPendingAction()) return
                    when (errorCode) {
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> scheduleFrame(FRAME_INTERVAL_MS)
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> cancel("此页面禁止截图")
                        else -> if (hasFrame) finish() else cancel("无法获取长截图画面")
                    }
                }
            })
        } catch (_: Exception) { cancel("无法获取长截图画面") }
    }

    private fun acceptFrame(result: AccessibilityService.ScreenshotResult, revision: Int) {
        try {
            result.hardwareBuffer.use { buffer ->
                if (phase != Phase.CAPTURING) return
                val engine = stitch ?: ScreenshotStitch(crop.width(), crop.height(), horizontal).also { stitch = it }
                if (engine.atLimit) {
                    main.post {
                        frameInFlight = false
                        if (phase == Phase.CAPTURING && !runPendingAction()) finish()
                    }
                    return
                }
                check(buffer.width == displayWidth && buffer.height == displayHeight)
                val hardware = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, result.colorSpace))
                val software = try { checkNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false)) }
                    finally { hardware.recycle() }
                val event = try {
                    val bytes = software.byteCount
                    val data = pixels?.takeIf { it.capacity() >= bytes } ?: ByteBuffer.allocateDirect(bytes).also { pixels = it }
                    data.clear()
                    software.copyPixelsToBuffer(data)
                    engine.push(data, software.rowBytes, crop.top * software.rowBytes + crop.left * 4)
                } finally { software.recycle() }
                val preview = if (event == ScreenshotStitch.INITIAL || event in listOf(1, 2, 6, 7)) engine.render(160) else null
                val width = engine.width
                val height = engine.height
                main.post {
                    if (phase != Phase.CAPTURING || revision != captureRevision) { preview?.recycle(); return@post }
                    frameInFlight = false
                    if (preview != null) {
                        main.removeCallbacks(firstFrameTimeout)
                        hasFrame = true
                        resultWidth = width
                        resultHeight = height
                        val extent = if (horizontal) width else height
                        trim?.grow(extent, event == 1 || event == 6)
                            ?: run { trim = ScreenshotScrollTrim(extent) }
                        growth++
                        finishButton.isEnabled = true
                        autoButton.isEnabled = true
                        thumbnail?.recycle()
                        thumbnail = preview
                        updateThumbnail()
                    } else if (event == ScreenshotStitch.UNMATCHED) {
                        status.text = "请慢滑"
                        thumbnailView.stateDescription = "未找到重叠，向回滑一点"
                    }
                    stalledSteps = if (preview == null) stalledSteps + 1 else 0
                    duplicateSteps = if (event == ScreenshotStitch.DUPLICATE) duplicateSteps + 1 else 0
                    if (!runPendingAction()) scheduleAfterFrame()
                }
            }
        } catch (error: Exception) {
            android.util.Log.w("VoxFix", "SCROLL_STITCH_FAILED", error)
            main.post { if (phase == Phase.CAPTURING) cancel("长截图采集失败") }
        } catch (error: LinkageError) {
            android.util.Log.w("VoxFix", "SCROLL_STITCH_LIBRARY_FAILED", error)
            main.post { if (phase == Phase.CAPTURING) cancel("无法加载长截图拼接器") }
        }
    }

    private fun icon(resource: Int, label: String, run: () -> Unit) = ImageButton(context).apply {
        setImageResource(resource)
        setColorFilter(Color.WHITE)
        background = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            .let { attributes -> try { attributes.getDrawable(0) } finally { attributes.recycle() } }
        contentDescription = label
        tooltipText = label
        setOnClickListener { run() }
    }

    private fun showControls() {
        val tile = FrameLayout(context)
        thumbnailView = object : ImageView(context) {
            private val shade = android.graphics.Paint().apply { color = 0xb3000000.toInt() }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val range = trim ?: return
                val photo = drawable ?: return
                val bounds = android.graphics.RectF(0f, 0f, photo.intrinsicWidth.toFloat(), photo.intrinsicHeight.toFloat())
                imageMatrix.mapRect(bounds)
                val head = range.start.toFloat() / range.extent
                val tail = range.end.toFloat() / range.extent
                if (horizontal) {
                    canvas.drawRect(bounds.left, bounds.top, bounds.left + bounds.width()*head, bounds.bottom, shade)
                    canvas.drawRect(bounds.left + bounds.width()*tail, bounds.top, bounds.right, bounds.bottom, shade)
                } else {
                    canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.top + bounds.height()*head, shade)
                    canvas.drawRect(bounds.left, bounds.top + bounds.height()*tail, bounds.right, bounds.bottom, shade)
                }
            }
        }.apply {
            contentDescription = "打开实时预览，拖边裁剪"
            scaleType = ImageView.ScaleType.FIT_CENTER
            isFocusable = true
            setOnClickListener { finish() }
        }
        status = TextView(context).apply {
            text = "准备"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0xb31c1c1e.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        tile.addView(thumbnailView, FrameLayout.LayoutParams(-1, -1))
        tile.addView(status, FrameLayout.LayoutParams(-1, dp(16), Gravity.BOTTOM))
        autoButton = icon(android.R.drawable.ic_media_play, "自动滚动") {
            if (autoRunning) {
                stopAuto()
                scheduleAfterFrame()
            } else startAuto()
        }.apply { isEnabled = hasFrame }
        finishButton = icon(R.drawable.snow_save, "保存长截图") { save() }.apply { isEnabled = hasFrame }
        val bar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xf21c1c1e.toInt())
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(4), 0, dp(4), 0)
            addView(tile, LinearLayout.LayoutParams(dp(64), dp(48)))
            addView(autoButton, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(finishButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        addWindow(bar, dp(BAR_DP), barTop, focusable = false)
        updateThumbnail()
    }

    private fun updateThumbnail() {
        val range = trim ?: return
        thumbnailView.setImageBitmap(thumbnail)
        val width = if (horizontal) range.size else resultWidth
        val height = if (horizontal) resultHeight else range.size
        status.text = "$width × $height"
        thumbnailView.stateDescription = "已选 $width × $height 像素"
    }
    private fun startAuto() {
        if (phase != Phase.CAPTURING || !hasFrame) return
        autoRunning = true
        autoButton.setImageResource(android.R.drawable.ic_media_pause)
        autoButton.contentDescription = "暂停自动滚动"
        autoButton.tooltipText = autoButton.contentDescription
        stalledSteps = 0
        duplicateSteps = 0
        scheduleAfterFrame()
    }

    private fun stopAuto(message: String? = null) {
        autoRunning = false
        main.removeCallbacks(autoTick)
        if (::autoButton.isInitialized) {
            autoButton.setImageResource(android.R.drawable.ic_media_play)
            autoButton.contentDescription = "自动滚动"
            autoButton.tooltipText = autoButton.contentDescription
        }
        if (message != null) {
            status.text = "手动"
            thumbnailView.stateDescription = message
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun runAutoStep() {
        if (!autoRunning || phase != Phase.CAPTURING || swipePending || frameInFlight) return
        swipePending = true
        val sent = runCatching {
            sendSwipe { completed ->
                swipePending = false
                if (phase == Phase.CAPTURING) {
                    if (!completed && autoRunning) stopAuto("滑动已中断，可手动滑动")
                    scheduleFrame(autoIntervalMs)
                }
            }
        }.getOrDefault(false)
        if (!sent) {
            swipePending = false
            stopAuto("当前窗口无法自动滚动，可手动滑动")
            scheduleAfterFrame()
        }
    }

    private fun scheduleAfterFrame() {
        if (phase != Phase.CAPTURING || frameInFlight || swipePending) return
        main.removeCallbacks(captureTick)
        scheduledAt = Long.MAX_VALUE
        main.removeCallbacks(autoTick)
        if (autoRunning && duplicateSteps >= 3 && growth > 1) {
            stopAuto()
            updateThumbnail()
        }
        if (autoRunning && stalledSteps >= 3) stopAuto("画面未继续增长，可预览或手动滑动")
        if (autoRunning) main.post(autoTick)
        else scheduleFrame(FRAME_INTERVAL_MS)
    }

    fun onHostScrolled() {
        if (!autoRunning && phase == Phase.CAPTURING && !frameInFlight && !swipePending) scheduleFrame(0)
    }

    private fun scheduleFrame(delay: Long) {
        val due = maxOf(SystemClock.uptimeMillis() + delay, lastCaptureAt + MIN_CAPTURE_GAP_MS)
        if (due >= scheduledAt) return
        main.removeCallbacks(captureTick)
        scheduledAt = due
        main.postAtTime(captureTick, due)
    }

    private fun runPendingAction(): Boolean {
        val action = pendingAction ?: return false
        pendingAction = null
        action()
        return true
    }

    private var pendingAction: (() -> Unit)? = null

    private fun pauseCapture() {
        phase = Phase.FINISHING
        captureRevision++
        stopAuto()
        main.removeCallbacks(captureTick)
        scheduledAt = Long.MAX_VALUE
    }

    private fun finish() {
        if (phase != Phase.CAPTURING || !hasFrame || saving) return
        if (frameInFlight) { pendingAction = ::finish; return }
        pauseCapture()
        worker?.post {
            try {
                val preview = checkNotNull(stitch).render(4096)
                main.post {
                    if (phase != Phase.FINISHING) { preview.recycle(); return@post }
                    previewImage = preview
                    try { showPreview(preview) }
                    catch (_: Exception) { cancel("无法打开长截图预览") }
                }
            } catch (_: Exception) { main.post { if (phase == Phase.FINISHING) cancel("无法生成长截图") } }
        }
    }

    private fun showPreview(preview: Bitmap) {
        removeWindow()
        val range = checkNotNull(trim)
        val info = TextView(context).apply { gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
        val save = icon(R.drawable.snow_save, "保存长截图") { save() }
        val resume = icon(android.R.drawable.ic_media_play, "继续手动滚动") { resume() }
        val close = icon(R.drawable.snow_close, "取消长截图") { cancel() }
        val editor = ScreenshotScrollRangeView(context, preview, horizontal, range) {
            info.text = trimSizeLabel(range)
        }
        info.text = trimSizeLabel(range)
        val gutter = dp(48)
        val width = (displayWidth - dp(20)).coerceAtLeast(1)
        val height = (displayHeight - dp(152)).coerceAtLeast(1)
        val scale = if (horizontal) height.toFloat() / preview.height else width.toFloat() / preview.width
        val size = FrameLayout.LayoutParams(
            (preview.width * scale).toInt() + if (horizontal) gutter else 0,
            (preview.height * scale).toInt() + if (horizontal) 0 else gutter,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP
        )
        val scroller = if (horizontal) HorizontalScrollView(context).apply { addView(editor, size) }
            else ScrollView(context).apply { addView(editor, size) }
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(resume, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(info, LinearLayout.LayoutParams(0, -1, 1f))
            addView(save, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff1c1c1e.toInt())
            addView(row, LinearLayout.LayoutParams(-1, dp(52)))
            addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        addWindow(root, -1, 0, focusable = true)
        phase = Phase.PREVIEW
    }

    private fun trimSizeLabel(range: ScreenshotScrollTrim): String {
        val width = if (horizontal) range.size else resultWidth
        val height = if (horizontal) resultHeight else range.size
        return "$width × $height"
    }

    private fun resume() {
        if (phase != Phase.PREVIEW || saving) return
        if (!captureAllowed()) {
            android.widget.Toast.makeText(context, "请返回原页面继续滚动", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        removeWindow()
        previewImage?.recycle(); previewImage = null
        phase = Phase.CAPTURING
        showControls()
        scheduleAfterFrame()
    }

    private fun save() {
        if (!hasFrame || saving || phase !in setOf(Phase.CAPTURING, Phase.PREVIEW)) return
        if (frameInFlight) { pendingAction = ::save; return }
        val live = phase == Phase.CAPTURING
        if (live) pauseCapture()
        saving = true
        val range = checkNotNull(trim)
        val start = range.start
        val end = range.end
        worker?.post {
            val result = runCatching {
                val output = checkNotNull(stitch).render(start = start, end = end)
                try { ScreenshotStore(context).save(output) } finally { output.recycle() }
            }
            main.post {
                if (phase == Phase.CLOSED) return@post
                saving = false
                if (result.isSuccess) cancel("长截图已保存到图片剪贴板")
                else {
                    android.widget.Toast.makeText(context,
                        result.exceptionOrNull()?.message ?: "保存失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
                    if (live) {
                        phase = Phase.CAPTURING
                        scheduleAfterFrame()
                    }
                }
            }
        }
    }
    private fun addWindow(view: View, height: Int, top: Int, focusable: Boolean) {
        val flags = if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val width = if (focusable) -1 else dp(168).coerceAtMost(displayWidth)
        windows.addView(view, WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or if (focusable) Gravity.LEFT else Gravity.RIGHT
            if (!focusable) x = dp(8)
            y = top
            windowAnimations = 0
            if (!focusable) setFitInsetsTypes(0)
        })
        window = view
    }

    private fun removeWindow() {
        window?.let { runCatching { windows.removeViewImmediate(it) } }
        window = null
    }

    fun cancel(message: String? = null) {
        if (phase == Phase.CLOSED) return
        close()
        closed(message)
    }

    fun back() { if (!saving) { if (phase == Phase.PREVIEW) resume() else cancel() } }

    fun close() {
        if (phase == Phase.CLOSED) return
        phase = Phase.CLOSED
        stopAuto()
        main.removeCallbacks(captureTick)
        main.removeCallbacks(firstFrameTimeout)
        removeWindow()
        thumbnail?.recycle(); thumbnail = null
        val preview = previewImage
        previewImage = null
        worker?.post {
            stitch?.close(); stitch = null
            pixels = null
            preview?.recycle()
        }
        thread?.quitSafely()
        thread = null
    }

    companion object {
        const val BAR_DP = 56
        private const val FRAME_INTERVAL_MS = 460L
        private const val MIN_CAPTURE_GAP_MS = 350L
        data class Swipe(val startX: Float, val startY: Float, val endX: Float, val endY: Float)

        fun swipe(left: Int, top: Int, right: Int, bottom: Int, horizontal: Boolean): Swipe {
            val width = right - left
            val height = bottom - top
            val x = left + width / 2f
            val y = top + height / 2f
            return if (horizontal) Swipe(left + width * 2f / 3f,y,left + width / 3f,y)
            else Swipe(x,top + height * 2f / 3f,x,top + height / 3f)
        }

        fun controlTop(cropTop: Int, cropBottom: Int, screenTop: Int, screenBottom: Int, barHeight: Int): Int? {
            if (cropTop - screenTop >= barHeight) return screenTop
            if (screenBottom - cropBottom >= barHeight) return screenBottom - barHeight
            return null
        }
    }
}
