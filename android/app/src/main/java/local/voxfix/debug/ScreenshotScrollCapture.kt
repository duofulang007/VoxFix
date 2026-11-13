package local.voxfix.debug

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
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
import java.io.File
import java.nio.ByteBuffer

/** Main sequences screenshots and gestures; a single worker owns pixels and stitching. */
internal class ScreenshotScrollCapture(
    private val context: AccessibilityService, private val windows: WindowManager, private val crop: Rect,
    private val displayWidth: Int, private val displayHeight: Int, private val barTop: Int,
    private val horizontal: Boolean, private val captureAllowed: () -> Boolean,
    private val sendSwipe: (Float, (Boolean) -> Unit) -> Boolean,
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
    private var previewView: ScreenshotScrollRangeView? = null
    private var bandInFlight = false
    private var pendingBand: Pair<Int, Int>? = null
    private var hasFrame = false
    private var saving = false
    private lateinit var finishButton: ImageButton
    private lateinit var autoButton: ImageButton
    private lateinit var infoView: TextView
    private lateinit var thumbnailView: ImageView
    private var trim: ScreenshotScrollTrim? = null
    private var resultWidth = 0
    private var resultHeight = 0
    private var autoRunning = false
    private var swipePending = false
    /** 最近一次自动滑动收尾时刻：区分"我们自己的手势"与用户的手动介入。 */
    private var lastSwipeDoneAt = 0L
    private var foreignScrolls = 0
    private val preferences = ScreenTextSettingsActivity.preferences(context)
    private var autoIntervalMs = preferences.getInt("scroll.autoIntervalMs",200).coerceIn(128,1000).toLong()
    private var growth = 0
    private var stalledSteps = 0
    private var duplicateSteps = 0
    private var unmatchedSteps = 0
    /** 自动模式下「滑了但画面没动」的连续次数：两次即判定到底，不再抽搐。 */
    private var coveredSteps = 0
    /** Auto mode: last measured distance as a fraction of the viewport, fed back into the next swipe. */
    private var autoStep = 1f / 3f
    private var lastAcceptedExtent = 0
    private var lastScrollAt = 0L
    private var gapDetected = false
    private var limitReached = false
    private var borderWindow: View? = null
    /** 分段续拍：到达单段上限时待推送的当前帧（tail 重置后第一个正常入帧）。 */
    private var pendingFrame: Bitmap? = null
    private var segmentIndex = 0
    /** 已落盘的已拼段落（文件, 行数），按顺序无缝衔接；行号以整幅长图为坐标系。 */
    private val parts = mutableListOf<Pair<File, Int>>()
    private var baseRows = 0L
    private var sessionStamp = 0L
    private val autoTick = Runnable { runAutoStep() }
    private val density = context.resources.displayMetrics.density
    /** One viewport along the stitch axis; auto-step feedback is expressed as a fraction of it. */
    private val frameExtent = if (horizontal) crop.width() else crop.height()
    private fun dp(value: Int) = (value * density).toInt().coerceAtLeast(1)

    fun start() {
        try {
            sessionStamp = System.currentTimeMillis()
            cleanupParts()
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
                check(buffer.width == displayWidth && buffer.height == displayHeight)
                val hardware = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, result.colorSpace))
                val software: Bitmap = try { checkNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false)) }
                    finally { hardware.recycle() }
                val engine = stitch ?: ScreenshotStitch(crop.width(), crop.height(), horizontal).also { stitch = it }
                if (engine.atLimit) {
                    // 单段上限：当前帧暂存为续段首帧，落盘已拼部分后无缝续段——手动模式同样
                    // 续段，不再强制进预览（2026-11-09 用户反馈第一段截完就被拉进预览）。
                    pendingFrame = software
                    main.post {
                        frameInFlight = false
                        if (phase == Phase.CAPTURING && !runPendingAction()) rollSegment()
                        else { pendingFrame?.recycle(); pendingFrame = null }
                    }
                    return
                }
                val event = try {
                    val bytes = software.byteCount
                    val data = pixels?.takeIf { it.capacity() >= bytes } ?: ByteBuffer.allocateDirect(bytes).also { pixels = it }
                    data.clear()
                    software.copyPixelsToBuffer(data)
                    engine.push(data, software.rowBytes, crop.top * software.rowBytes + crop.left * 4)
                } finally { software.recycle() }
                val preview = if (event == ScreenshotStitch.INITIAL || event in listOf(1, 2, 6, 7)) engine.render(240) else null
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
                        val previousExtent = lastAcceptedExtent
                        trim?.grow(extent, event == 1 || event == 6)
                            ?: run { trim = ScreenshotScrollTrim(extent) }
                        lastAcceptedExtent = extent
                        growth++
                        if (autoRunning && event in listOf(1, 2, 6, 7)) {
                            // Closed loop: target 35–45% overlap, i.e. 55–65% new content per step.
                            // Both directions count (prepend grows the canvas upward just the same).
                            val added = kotlin.math.abs(extent - previousExtent).toFloat() / frameExtent
                            if (added > 0.78f) autoStep = (autoStep * 0.65f).coerceAtLeast(0.18f)
                            else if (added < 0.28f) autoStep = (autoStep * 1.25f).coerceAtMost(0.45f)
                        }
                        unmatchedSteps = 0
                        duplicateSteps = 0
                        coveredSteps = 0
                        finishButton.isEnabled = true
                        autoButton.isEnabled = true
                        if (gapDetected) {
                            infoView.text = "有缺口，可回滑补齐"
                            thumbnailView.stateDescription = "有缺口，向回滑一点补齐"
                        }
                        thumbnail?.recycle()
                        thumbnail = preview
                        updateThumbnail()
                    } else if (event == ScreenshotStitch.UNMATCHED) {
                        unmatchedSteps++
                        gapDetected = true
                        infoView.text = "未匹配，请回滑一点补拍"
                        thumbnailView.stateDescription = "有缺口，向回滑一点补齐"
                        if (autoRunning) autoStep = (autoStep * 0.65f).coerceAtLeast(0.18f)
                    } else if (event == ScreenshotStitch.DUPLICATE) {
                        duplicateSteps++
                    } else if (event == 3) {
                        // Covered：这一帧的全部内容都已在画布里 = 滑动没有让页面移动。
                        coveredSteps++
                        if (autoRunning) {
                            // 第一次滑不动就先把步长收小，第二次试探更轻，避免到底后大力抽搐。
                            autoStep = (autoStep * 0.6f).coerceAtLeast(0.15f)
                        }
                    } else {
                        stalledSteps++
                    }
                    val metrics = stitch?.lastMetrics
                    android.util.Log.i("VoxFix", "SCROLL_FRAME event=$event extent=${if (horizontal) width else height} " +
                        "step=$autoStep unmatched=$unmatchedSteps duplicate=$duplicateSteps stalled=$stalledSteps " +
                        "offset=${metrics?.getOrNull(4)} score=${metrics?.getOrNull(5)} " +
                        "second=${metrics?.getOrNull(6)} coverage=${metrics?.getOrNull(7)} inliers=${metrics?.getOrNull(9)}")
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
        showBorder()
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
        tile.addView(thumbnailView, FrameLayout.LayoutParams(-1, -1))
        // 2026-11-09 用户反馈：缩略图本身太小看不清内容，规格字挤在缩略图底部被遮挡。
        // 规格改为控制条中央独立大字：尺寸 + 段号 + 采集中状态，不依赖点开预览。
        infoView = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setSingleLine()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
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
            addView(infoView, LinearLayout.LayoutParams(0, -1, 1f))
            addView(autoButton, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(finishButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        addWindow(bar, dp(BAR_DP), barTop, focusable = false)
        updateThumbnail()
    }

    private fun updateThumbnail() {
        val range = trim ?: return
        thumbnailView.setImageBitmap(thumbnail)
        val total = baseRows + range.size
        val width = if (horizontal) total.toInt() else resultWidth
        val height = if (horizontal) resultHeight else total.toInt()
        val limit = stitch?.limitExtent
        infoView.text = if (hasFrame) {
            "第 ${segmentIndex + 1} 段 · $width × $height · 采集中"
        } else "准备中…"
        thumbnailView.stateDescription = "整幅 $width × $height 像素，当前段上限 $limit"
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
        if (message != null || autoRunning) {
            android.util.Log.i("VoxFix", "SCROLL_AUTO_STOP reason=${message ?: "toggle"} " +
                "growth=$growth unmatched=$unmatchedSteps duplicate=$duplicateSteps " +
                "covered=$coveredSteps stalled=$stalledSteps " +
                "step=$autoStep extent=${if (horizontal) resultWidth else resultHeight}")
        }
        autoRunning = false
        main.removeCallbacks(autoTick)
        if (::autoButton.isInitialized) {
            autoButton.setImageResource(android.R.drawable.ic_media_play)
            autoButton.contentDescription = "自动滚动"
            autoButton.tooltipText = autoButton.contentDescription
        }
        if (message != null) {
            infoView.text = "手动模式"
            thumbnailView.stateDescription = message
            thumbnailView.stateDescription = message
            toast(message)
        }
    }

    private fun runAutoStep() {
        if (!autoRunning || phase != Phase.CAPTURING || swipePending || frameInFlight) return
        swipePending = true
        foreignScrolls = 0
        val sent = runCatching {
            sendSwipe(autoStep) { completed ->
                swipePending = false
                lastSwipeDoneAt = SystemClock.uptimeMillis()
                if (phase == Phase.CAPTURING) {
                    if (!completed && autoRunning) stopAuto("滑动已中断，可手动滑动")
                    else scheduleAutoCapture()
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
        if (autoRunning && coveredSteps >= 2) {
            // 每次滑动只取一帧、等回弹完全静止后再取：连续两次「整帧无增长」说明这个容器
            // 已无法继续滚动（到底或不可滚）。过拉伸动效的中间帧在这里已经归位，不会被采到。
            stopAuto("已到底或无法继续滚动")
        } else if (autoRunning && duplicateSteps >= 3) {
            // 无论是否已有增长：连续 3 帧与上一帧完全相同，说明滑动没有生效（到底/被页面拦截）。
            stopAuto("页面没有继续滚动")
        } else if (autoRunning && stalledSteps >= 3) {
            stopAuto("画面未继续增长，可预览或手动滑动")
        }
        if (autoRunning) main.post(autoTick)
        // A still page repeats the same frame. Keep polling, but slowly: some list views do not
        // emit TYPE_VIEW_SCROLLED, so stopping completely would miss the next manual scroll.
        else scheduleFrame(
            if (duplicateSteps < IDLE_DUPLICATE_LIMIT) FRAME_INTERVAL_MS else IDLE_POLL_MS
        )
    }

    fun onHostScrolled() {
        lastScrollAt = SystemClock.uptimeMillis()
        if (phase != Phase.CAPTURING || frameInFlight || swipePending) return
        // 2026-11-09 用户反馈：自动模式下手滑完全打断不了，很反直觉。我们自己的手势收尾后
        // 有短暂回弹窗口；超出窗口还持续来滚动事件，说明是用户在手动滑——立刻让出控制权。
        if (autoRunning) {
            if (SystemClock.uptimeMillis() - lastSwipeDoneAt > AUTO_SETTLE_MS + AUTO_MAX_WAIT_MS) {
                if (++foreignScrolls >= 3) stopAuto("检测到手动滑动，自动滚动已暂停")
            }
            return
        }
        // Manual capture keeps the maximum rate: waiting for the fling to settle would skip whole
        // screens. Auto owns the gesture, so it captures exactly one frame per swipe, after the
        // page — including any overscroll stretch/bounce — has come fully to rest.
        scheduleFrame(0)
    }

    /** 用户手指碰到宿主页面：自动滚动立刻让位（来自服务的 TYPE_TOUCH_INTERACTION_START）。 */
    fun onUserTouch() {
        if (!autoRunning || phase != Phase.CAPTURING || swipePending) return
        // 我们自己注入的手势也会产生触摸事件，靠手势进行中/刚收尾的宽限窗区分。
        if (SystemClock.uptimeMillis() - lastSwipeDoneAt <= 600) return
        stopAuto("检测到手动触摸，自动滚动已暂停")
    }

    private fun scheduleFrame(delay: Long) {
        scheduleAt(SystemClock.uptimeMillis() + delay)
    }

    /** Absolute schedule; never violates the system's minimum interval between screenshots. */
    private fun scheduleAt(dueAt: Long) {
        val due = maxOf(dueAt, lastCaptureAt + MIN_CAPTURE_GAP_MS)
        if (due >= scheduledAt) return
        main.removeCallbacks(captureTick)
        scheduledAt = due
        main.postAtTime(captureTick, due)
    }

    /** Auto step: wait for scroll events to go quiet, but never stall longer than [AUTO_MAX_WAIT_MS]. */
    private fun scheduleAutoCapture() {
        if (phase != Phase.CAPTURING) return
        val now = SystemClock.uptimeMillis()
        val quiet = maxOf(lastScrollAt + AUTO_SETTLE_MS, now + 40)
        scheduleAt(minOf(quiet, now + AUTO_MAX_WAIT_MS))
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
        showPreview()
    }

    /**
     * 分段续拍：到达单段上限时，把画布 [0, extent-视口) 流式落盘为一个 PNG 段，
     * 拼接器以最后一段视口（tail）作为首帧重置，之后继续无缝追加；
     * 最终保存时所有段 + 当前段按行合成一张无上限长图（见 [save]）。
     */
    private fun rollSegment() {
        if (phase != Phase.CAPTURING || saving) { pendingFrame?.recycle(); pendingFrame = null; return }
        val wasAuto = autoRunning
        pauseCapture()
        saving = true
        val pending = pendingFrame
        pendingFrame = null
        val index = ++segmentIndex
        val partDir = File(context.filesDir, "scroll-parts/$sessionStamp")
        worker?.post {
            try {
                val old = checkNotNull(stitch)
                val extent = if (horizontal) old.width else old.height
                val tailStart = (extent - frameExtent).coerceAtLeast(0)
                val dir = partDir.apply { mkdirs() }
                if (tailStart > 0) {
                    val partFile = File(dir, "part-%03d.png".format(index))
                    PngWriter(partFile, crop.width(), tailStart.toLong()).use { writer ->
                        var s = 0
                        while (s < tailStart) {
                            val e = minOf(s + BAND_ROWS, tailStart)
                            old.render(start = s, end = e)?.let { writer.write(it); it.recycle() }
                            s = e
                        }
                    }
                    parts.add(partFile to tailStart)
                    baseRows += tailStart
                }
                val tail = old.render(start = tailStart, end = extent)
                old.close()
                stitch = null
                val fresh = ScreenshotStitch(crop.width(), crop.height(), horizontal).also { stitch = it }
                trim = ScreenshotScrollTrim(frameExtent)
                lastAcceptedExtent = 0
                growth = 0
                stalledSteps = 0; duplicateSteps = 0; unmatchedSteps = 0; coveredSteps = 0
                gapDetected = false; limitReached = false
                hasFrame = false
                // 新段首帧只用 pending（当前实时屏）：tail 与 pending 内容相同，若两张都推，
                // "位移 0" 会被接受成连续性先验，第二段起自动滑动的真实位移全被仲裁拒成
                // UNMATCHED、extent 冻结（2026-11-09 用户实测）。pending 就是 tail 的同内容
                // 实时帧，先验回到无状态，与第一段开局一致；无 pending 才回退推 tail。
                if (pending != null) {
                    tail.recycle()
                    android.util.Log.i("VoxFix", "SCROLL_SEGMENT push pending only")
                    pushBitmap(fresh, pending, false)
                } else {
                    android.util.Log.i("VoxFix", "SCROLL_SEGMENT push tail")
                    pushBitmap(fresh, tail, true)
                    tail.recycle()
                }
                trim?.grow(if (horizontal) fresh.width else fresh.height, false)
                lastAcceptedExtent = if (horizontal) fresh.width else fresh.height
                hasFrame = true
                resultWidth = fresh.width
                resultHeight = fresh.height
                thumbnail?.recycle(); thumbnail = fresh.render(240)
                android.util.Log.i("VoxFix", "SCROLL_SEGMENT $index done parts=${parts.size} " +
                    "base=$baseRows fresh=${fresh.width}x${fresh.height}")
            } catch (error: Exception) {
                android.util.Log.w("VoxFix", "SCROLL_SEGMENT_FAILED", error)
                main.post { cancel("分段续拍失败，已保留当前段") }
                return@post
            } finally { pending?.recycle() }
            main.post {
                saving = false
                if (phase == Phase.CLOSED) return@post
                phase = Phase.CAPTURING
                if (wasAuto) {
                    autoRunning = true
                    autoButton.setImageResource(android.R.drawable.ic_media_pause)
                    autoButton.contentDescription = "暂停自动滚动"
                    autoButton.tooltipText = autoButton.contentDescription
                }
                updateThumbnail()
                toast("第 $index 段已缓存（总高 ${baseRows + (if (horizontal) resultWidth else resultHeight)}px）")
                scheduleAfterFrame()
            }
        }
    }

    /**
     * [bitmap] 为整屏帧时 [cropped]=false（按 crop 在源位图中的位置取数据）；
     * 为拼接器/渲染输出（已按会话宽高裁好，如 tail 段）时 [cropped]=true（offset 必须为 0，
     * 否则 `crop.left` 的行内偏移会触发 JNI 的跨行保护直接拒绝）。
     */
    private fun pushBitmap(stitch: ScreenshotStitch, bitmap: Bitmap, cropped: Boolean) {
        val bytes = bitmap.byteCount
        val data = pixels?.takeIf { it.capacity() >= bytes } ?: ByteBuffer.allocateDirect(bytes).also { pixels = it }
        data.clear()
        bitmap.copyPixelsToBuffer(data)
        val offset = if (cropped) 0 else crop.top * bitmap.rowBytes + crop.left * 4
        stitch.push(data, bitmap.rowBytes, offset)
    }

    private fun showPreview() {
        removeWindow()
        val range = checkNotNull(trim)
        val info = TextView(context).apply { gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
        val save = icon(R.drawable.snow_save, "保存长截图") { save() }
        val resume = icon(android.R.drawable.ic_media_play, "继续手动滚动") { resume() }
        val close = icon(R.drawable.snow_close, "取消长截图") { cancel() }
        val cross = if (horizontal) resultHeight else resultWidth
        val view = ScreenshotScrollRangeView(context, horizontal, range, cross, baseRows, ::requestPreviewBand) {
            info.text = trimSizeLabel(range)
        }
        previewView = view
        info.text = trimSizeLabel(range)
        // 1:1 像素尺寸：看到的就是成图本身，不做任何预览缩放。
        val total = baseRows + range.extent
        val width = if (horizontal) total.toInt() + dp(48) else cross
        val height = if (horizontal) cross else total.toInt() + dp(48)
        val size = FrameLayout.LayoutParams(width, height, Gravity.CENTER_HORIZONTAL or Gravity.TOP)
        val scroller = if (horizontal) HorizontalScrollView(context).apply { addView(view, size) }
            else ScrollView(context).apply { addView(view, size) }
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
        if (limitReached) {
            toast("已达单段上限，已自动无缝续段，可继续滚动", long = true)
        }
    }

    /** 预览窗口渲染：跨"已落盘段 + 当前段"合成可见 ±1200px 的 1:1 位图；工作线程出图，主线程上屏。 */
    private fun requestPreviewBand(start: Int, end: Int) {
        if (bandInFlight) { pendingBand = start to end; return }
        bandInFlight = true
        val cross = if (horizontal) resultHeight else resultWidth
        worker?.post {
            val out = runCatching {
                val bitmap = Bitmap.createBitmap(crop.width(), end - start, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val top = start.toLong(); val bottom = end.toLong()
                var partBase = 0L
                for ((file, rows) in parts) {
                    val p1 = partBase + rows
                    val a = maxOf(top, partBase); val b = minOf(bottom, p1)
                    if (b > a) decodeBand(file, (a - partBase).toInt(), (b - partBase).toInt())?.let {
                        canvas.drawBitmap(it, null,
                            RectF(0f, (a - top).toFloat(), crop.width().toFloat(), (a - top).toFloat() + it.height), null)
                        it.recycle()
                    }
                    partBase = p1
                }
                val extentNow = stitch?.let { if (horizontal) it.width.toLong() else it.height.toLong() } ?: 0L
                val c1 = baseRows + extentNow
                val a = maxOf(top, baseRows); val b = minOf(bottom, c1)
                if (b > a) stitch?.render(start = (a - baseRows).toInt(), end = (b - baseRows).toInt())?.let {
                    canvas.drawBitmap(it, null,
                        RectF(0f, (a - top).toFloat(), crop.width().toFloat(), (a - top).toFloat() + it.height), null)
                    it.recycle()
                }
                bitmap
            }.getOrNull()
            main.post {
                bandInFlight = false
                val view = previewView
                if (out == null) {
                    android.util.Log.w("VoxFix", "SCROLL_BAND_FAILED start=$start end=$end")
                } else if (view != null) {
                    view.updateBand(out, start)
                } else {
                    out.recycle()
                }
                val next = pendingBand
                pendingBand = null
                if (next != null && view != null) requestPreviewBand(next.first, next.second)
            }
        }
    }

    private fun decodeBand(file: File, start: Int, end: Int): Bitmap? = runCatching {
        val decoder = android.graphics.BitmapRegionDecoder.newInstance(file.path, false)
        try {
            decoder.decodeRegion(Rect(0, start, decoder.width, minOf(end, decoder.height)),
                android.graphics.BitmapFactory.Options())
        } finally { decoder.recycle() }
    }.getOrNull()

    private fun trimSizeLabel(range: ScreenshotScrollTrim): String {
        val total = baseRows + range.size
        val width = if (horizontal) total.toInt() else resultWidth
        val height = if (horizontal) resultHeight else total.toInt()
        return "$width × $height"
    }

    private fun resume() {
        if (phase != Phase.PREVIEW || saving) return
        if (!captureAllowed()) {
            toast("请返回原页面继续滚动")
            return
        }
        removeWindow()
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
        val hasParts = parts.isNotEmpty()
        worker?.post {
            val result = runCatching {
                if (!hasParts) {
                    val output = checkNotNull(stitch).render(start = start, end = end)
                    try {
                        // 单段长截图同样要留工程，否则「编辑」里改过的内容下次打不开。
                        val doc = ScreenshotDocument.full(output.width, output.height)
                        ScreenshotStore(context).saveProject(doc, output, source = output)
                    } finally { output.recycle() }
                    "长截图已保存到图片剪贴板"
                } else {
                    // 多段合成：所有已落盘段 + 当前段按行流式写入一张 PNG，高度无上限。
                    val total = baseRows + (end - start).toLong()
                    val file = File(context.cacheDir, "longshot-$sessionStamp.png")
                    var clipped = false
                    PngWriter(file, crop.width(), total).use { writer ->
                        for ((file_, rows) in parts) {
                            var s = 0
                            while (s < rows) {
                                val e = minOf(s + BAND_ROWS, rows)
                                decodeBand(file_, s, e)?.let { writer.write(it); it.recycle() }
                                s = e
                            }
                        }
                        var s = start
                        while (s < end) {
                            val e = minOf(s + BAND_ROWS, end)
                            checkNotNull(stitch).render(start = s, end = e)?.let { writer.write(it); it.recycle() }
                            s = e
                        }
                    }
                    // 2026-11-09 用户明确期望：限额内（16MP/16MB）的长截图进图片剪贴板，
                    // 只有真正超出限额的才落到相册。此前多段一律进相册，≤16MP 的也进去了。
                    // 2026-11-13：入库时同时建工程，长截图才和普通截图一样能反复点「编辑」改之前的内容。
                    val longWidth = crop.width()
                    val longHeight = total.toInt()
                    ScreenshotStore(context).savePng(file, longWidth, total,
                        document = ScreenshotDocument.full(longWidth, longHeight))?.let {
                        clipped = true
                        cleanupParts()
                    }
                    if (clipped) "长截图已保存到图片剪贴板" else saveToGallery(file, total)
                }
            }
            main.post {
                if (phase == Phase.CLOSED) return@post
                saving = false
                if (result.isSuccess) {
                    cleanupParts()
                    cancel(result.getOrDefault("长截图已保存"))
                } else {
                    toast(result.exceptionOrNull()?.message ?: "保存失败，请重试")
                    if (live) {
                        phase = Phase.CAPTURING
                        scheduleAfterFrame()
                    }
                }
            }
        }
    }

    /** 无上限长图不入图片剪贴板（其单张上限是 16MP），直接落到系统相册 Pictures/VoxFix。 */
    private fun saveToGallery(file: File, total: Long): String {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "长截图-$sessionStamp.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_PICTURES + "/VoxFix")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法写入相册")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("无法写入相册")
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        } finally { file.delete() }
        return "长截图（${crop.width()}×$total）已保存到相册 Pictures/VoxFix"
    }

    /** 与 ScreenTextService.toast 一致：浅色应用主题会让系统 Toast 文字变黑、落在深色背景上看不清。 */
    private fun toast(message: String, long: Boolean = false) {
        val themed = android.view.ContextThemeWrapper(context, android.R.style.Theme_Material)
        android.widget.Toast.makeText(themed, message,
            if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun cleanupParts() {
        parts.forEach { it.first.delete() }
        parts.clear()
        baseRows = 0
        segmentIndex = 0
        File(context.filesDir, "scroll-parts/$sessionStamp").delete()
    }
    /**
     * Recording-style red frame around the captured region. Drawn outside the crop, so it is never
     * part of the stitched pixels (same geometry as [ScreenRecordingAreaView]).
     */
    private fun showBorder() {
        if (borderWindow != null) return
        val frame = ScreenRecordingAreaView(context, Rect(crop)).apply {
            setState(ScreenRecordingAreaView.State.RECORDING)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        try {
            windows.addView(frame, WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                setFitInsetsTypes(0)
                windowAnimations = 0
            })
            borderWindow = frame
        } catch (error: Exception) {
            android.util.Log.w("VoxFix", "SCROLL_BORDER_FAILED", error)
        }
    }

    private fun removeBorder() {
        borderWindow?.let { runCatching { windows.removeViewImmediate(it) } }
        borderWindow = null
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
        removeBorder()
        previewView?.dispose()
        previewView = null
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
        cleanupParts()
        worker?.post {
            stitch?.close(); stitch = null
            pixels = null
        }
        thread?.quitSafely()
        thread = null
    }

    companion object {
        const val BAR_DP = 56
        private const val FRAME_INTERVAL_MS = 460L
        private const val MIN_CAPTURE_GAP_MS = 350L
        private const val AUTO_SETTLE_MS = 220L
        private const val AUTO_MAX_WAIT_MS = 480L
        private const val IDLE_DUPLICATE_LIMIT = 3
        private const val IDLE_POLL_MS = 1000L
        private const val BAND_ROWS = 2000
        data class Swipe(val startX: Float, val startY: Float, val endX: Float, val endY: Float)

        fun swipe(left: Int, top: Int, right: Int, bottom: Int, horizontal: Boolean, fraction: Float = 1f / 3f): Swipe {
            val width = right - left
            val height = bottom - top
            val x = left + width / 2f
            val y = top + height / 2f
            val share = fraction.coerceIn(0.15f, 0.6f)
            return if (horizontal) Swipe(left + width * (0.5f + share / 2f),y,left + width * (0.5f - share / 2f),y)
            else Swipe(x,top + height * (0.5f + share / 2f),x,top + height * (0.5f - share / 2f))
        }

        fun controlTop(cropTop: Int, cropBottom: Int, screenTop: Int, screenBottom: Int, barHeight: Int): Int? {
            if (cropTop - screenTop >= barHeight) return screenTop
            if (screenBottom - cropBottom >= barHeight) return screenBottom - barHeight
            return null
        }
    }
}
