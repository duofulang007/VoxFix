package local.voxfix.debug

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.SharedPreferences
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/** Screenshot-only extraction also sees text inside images beside ordinary UI labels. */
private const val DEBUG_CAPTURE = "local.voxfix.debug.DEBUG_CAPTURE"
/** Debug builds only: records a fixed region so crop geometry can be checked without tapping the toolbar. */
private const val DEBUG_RECORD = "local.voxfix.debug.DEBUG_RECORD"

class ScreenTextService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val main = Handler(Looper.getMainLooper())
    private val preferences by lazy { ScreenTextSettingsActivity.preferences(this) }
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private var handle: ScreenTextHandle? = null
    private var overlay: ScreenTextOverlay? = null
    private var screenshot: ScreenshotCaptureOverlay? = null
    private var scrolling: ScreenshotScrollCapture? = null
    private var scrollWindowId: Int? = null
    private var scrollNotificationTimeout: Long? = null
    private val screenshotWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var session: ScreenshotSession? = null
    private val frame: Bitmap? get() = session?.image
    private var busy = false
    private var captureWindowRevision = 0L
    @Volatile private var generation = 0
    private var connected = false
    private var ocrInFlight = false
    /** Set while the editor was opened from the image clipboard, so save can report back. */
    private var editorDone: ((Boolean) -> Unit)? = null
    private val editorHost = object : ScreenshotEditorHost.Handler {
        override fun open(id: String, done: (Boolean) -> Unit): Boolean {
            if (!connected || busy || overlay != null || screenshot != null) return false
            openSavedProject(id, done)
            return true
        }
    }
    private val screenState = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) { liveRecorder?.stop(); ScreenshotPins.closeAll(); closeSession(); removeHandle(); TranslateRuntime.releaseNow() } else showHandle()
        }
    }
    private val debuggable get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    // Debug builds only: lets the agent drive a capture without the arc gesture.
    private val debugCapture = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DEBUG_CAPTURE) { android.util.Log.i("VoxFix","DEBUG_CAPTURE"); capture() }
        }
    }
    private val debugRecord = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DEBUG_RECORD) return
            if (intent.getStringExtra("action") == "stop") { liveRecorder?.stop(); return }
            if (intent.getStringExtra("action") == "annotate") {
                // Debug-only path so the annotation layer can be exercised without hunting the bar.
                val params = recordingAnnotationParams
                val touchable = params != null &&
                    params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
                setAnnotationDrawing(!touchable)
                return
            }
            clearStaleRecordingBusy()
            if (liveRecorder != null || busy || recordingSetup != null) {
                android.util.Log.i("VoxFix", "RECORDING_SETUP_SKIPPED busy=$busy setup=${recordingSetup != null}")
                return
            }
            val metrics = resources.displayMetrics
            debugRecordingRegion = intent.getStringExtra("region")?.split(',')?.mapNotNull { it.toIntOrNull() }
                ?.takeIf { it.size == 4 }
                ?.let { Rect(it[0], it[1], it[2], it[3]) }
                ?: Rect(60, 960, 1020, 1660)
            val options = recordingPreferences.load()
            recordingDebugAuto = true
            enterRecordingSetup(Rect(debugRecordingRegion), metrics.widthPixels,
                metrics.heightPixels, options)
            // `setup` stops at the idle stage so the adjustable frame can be captured for review.
            if (intent.getStringExtra("action") == "setup") return
            requestRecordingStart()
        }
    }
    private var debugRecordingRegion = Rect()
    private val expire = Runnable {
        if (screenshot?.isEditing == true || screenshot?.isProjectEditor == true) keepSession() else retainText()
    }
    private val captureTimeout = Runnable { captureFailed("截图超时，请重试") }

    override fun onServiceConnected() {
        connected = true
        live = this
        adoptRunningRecording()
        TranslateRuntime.init(this)
        ScreenshotEditorHost.register(editorHost)
        preferences.registerOnSharedPreferenceChangeListener(this)
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenState, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenState, filter)
        if (debuggable) {
            val debugFilter = IntentFilter(DEBUG_CAPTURE).apply { addAction(DEBUG_RECORD) }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(debugCapture, debugFilter, RECEIVER_EXPORTED)
                registerReceiver(debugRecord, debugFilter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(debugCapture, debugFilter)
                registerReceiver(debugRecord, debugFilter)
            }
        }
        showHandle()
    }
    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key == "enabled") { closeSession(); showHandle() }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (event.windowId == scrollWindowId) scrolling?.onHostScrolled()
            return
        }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString() != packageName) captureWindowRevision++
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            closeSession(); removeHandle()
        } else showHandle()
    }
    override fun onInterrupt() { closeSession() }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        TranslateRuntime.trim(level)
    }
    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        // The capture frame is fixed in screen pixels, so a rotation invalidates it (upstream keeps
        // the area fixed too). This is the earliest signal; the poll in [watchRecordingRotation]
        // covers ROMs that never deliver it.
        checkRecordingRotation()
        ScreenshotPins.relayout()
        retainText(); removeHandle(); showHandle()
    }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (overlay?.imeVisible == true) return false
        if ((overlay != null || screenshot != null || busy) && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                val scroll = scrolling
                if (scroll != null) scroll.back()
                else if (screenshot?.back() != true) overlay?.closeAnimated() ?: closeSession()
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && handle?.expanded == true) {
            if (event.action == KeyEvent.ACTION_UP) handle?.collapse()
            return true
        }
        return false
    }
    override fun onDestroy() {
        // The system can rebuild this service mid-recording. Cancelling there threw the capture away
        // (the file was deleted with no trace), so finalise instead: the user keeps what was recorded.
        if (liveRecorder != null) android.util.Log.i("VoxFix", "RECORDING_FINALISED_ON_DESTROY")
        liveRecorder?.stop()
        recorder = null
        // The system can recreate this service while the projection consent dialog is open; drop
        // the live-instance handle so late callbacks never post onto the shut-down worker below.
        if (live === this) live = null
        ScreenshotProjection.abandonUnlessUiOpen()
        removeRecordingControl()
        ScreenshotPins.closeAll()
        connected = false
        ScreenshotEditorHost.register(null)
        editorDone = null
        TranslateRuntime.cancel(); TranslateRuntime.releaseNow()
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        runCatching { unregisterReceiver(screenState) }
        if (debuggable) {
            runCatching { unregisterReceiver(debugCapture) }
            runCatching { unregisterReceiver(debugRecord) }
        }
        closeSession(); removeHandle(); main.removeCallbacksAndMessages(null)
        screenshotWorker.shutdown()
        super.onDestroy()
    }
    private fun showHandle() {
        if (!connected || !ScreenTextSettingsActivity.enabled(this) || handle != null || busy || overlay != null || screenshot != null ||
            !getSystemService(android.os.PowerManager::class.java).isInteractive ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val next = ScreenTextHandle(this) { capture() }
        runCatching { next.show(); handle = next }
            .onFailure { android.util.Log.w("VoxFix", "SCREEN_TEXT_HANDLE_FAILED", it) }
    }
    private fun removeHandle() { handle?.let { runCatching { it.dismiss() } }; handle = null }
    private fun capture(replacing: ScreenshotCaptureOverlay? = null) {
        if (!connected || busy || overlay != null || screenshot !== replacing) return
        if (ocrInFlight) { Toast.makeText(this, "上次识别正在结束，请稍后重试", Toast.LENGTH_SHORT).show(); return }
        if (replacing != null && !replacing.hideForRecapture()) return
        busy = true; removeHandle(); ScreenshotPins.visible(false)
        main.removeCallbacks(expire)
        val token = ++generation
        main.postDelayed(captureTimeout, 4000)
        main.postDelayed({
            if (token != generation || !connected) return@postDelayed
            try {
                val windowRevision = captureWindowRevision
                val hitSnapshot = runCatching { ScreenshotSmartSelection.snapshot(this) }.getOrNull()
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        var unowned: Bitmap? = null
                        try {
                            if (token != generation) return
                            main.removeCallbacks(captureTimeout)
                            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: error("No screenshot bitmap")
                            val image = try { checkNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false)) } finally { hardware.recycle() }
                            unowned = image
                            val snapshot = hitSnapshot?.takeIf {
                                captureWindowRevision == windowRevision &&
                                    runCatching { it.matchesWindows(this@ScreenTextService) }.getOrDefault(false)
                            }
                            if (replacing != null) {
                                val current = checkNotNull(session)
                                if (image.width != current.image.width || image.height != current.image.height) {
                                    captureFailed("屏幕尺寸已变化，已保留当前截图")
                                    return
                                }
                                replacing.replaceCaptureSource(image, snapshot)
                                current.replaceFrame(image).recycle()
                                unowned = null
                                replacing.restoreAfterRecapture(null)
                            } else {
                                session = ScreenshotSession(image)
                                unowned = null
                                val editor = ScreenshotCaptureOverlay(this@ScreenTextService,image,::saveScreenshot,::recognizeScreenshot,::updateText,::closeSession,::keepSession,TranslateRuntime,ScreenshotToolMemory(this@ScreenTextService))
                                editor.onRecaptureRequested = { capture(editor) }
                                editor.onScrollRequested = { region, top, horizontal -> startScroll(editor,region,top,horizontal) }
                                editor.onRecordRequested = { region -> startRecording(editor, region) }
                                screenshot = editor
                                editor.enableSmartSelection(snapshot)
                                editor.show(windows)
                            }
                            busy = false
                            keepSession()
                        } catch (error: Exception) { if (token == generation) captureFailed("无法打开截图，请重试", error) }
                        finally { unowned?.recycle(); buffer.close() }
                    }
                    override fun onFailure(errorCode: Int) {
                        if (token == generation) captureFailed(if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) "此页面禁止截图" else "截图失败，请重试或检查无障碍服务")
                    }
                })
            } catch (error: Exception) { captureFailed("截图不可用，请检查无障碍服务", error) }
        }, 120)
    }
    private fun captureFailed(message: String, error: Exception? = null) {
        val editor = screenshot?.takeIf { it.isRecapturing }
        if (editor == null) {
            fail(message, error)
            return
        }
        error?.let { android.util.Log.w("VoxFix", "SCREEN_TEXT_RECAPTURE_FAILED", it) }
        ++generation
        main.removeCallbacks(captureTimeout)
        busy = false
        editor.restoreAfterRecapture(message)
        keepSession()
    }

    private fun startScroll(editor: ScreenshotCaptureOverlay, region: Rect, top: Int, horizontal: Boolean) {
        if (screenshot !== editor || busy || ocrInFlight || scrolling != null) return
        val image = frame ?: return
        val center = listOf(region.centerX() to region.centerY())
        val targetWindow = scrollTargetAt(center, ignoreOverlay = true) ?: run {
            editor.setScrollCaptureActive(false, "请在应用页面中选择长截图区域")
            return
        }
        main.removeCallbacks(expire)
        busy = true
        editor.setScrollCaptureActive(true)
        val next = ScreenshotScrollCapture(this,windows,region,image.width,image.height,top,horizontal,
            { targetWindow == scrollTargetAt(center, ignoreOverlay = true) },
            { done -> scrollStep(region,horizontal,targetWindow,done) }) { message ->
            scrolling = null
            listenToScrollEvents(null)
            busy = false
            if (screenshot === editor) {
                editor.setScrollCaptureActive(false,message)
                keepSession()
            }
        }
        scrolling = next
        listenToScrollEvents(targetWindow)
        next.start()
    }

    // ---- 录屏（T28）：选区录制、暂停恢复、成品进相册 ----

    private val recordingPreferences by lazy { ScreenRecordingPreferences(this) }
    private var recorder: ScreenRecorder? = null
    private var recordingControl: ScreenRecordingControl? = null
    private var recordingControlParams: WindowManager.LayoutParams? = null
    private var recordingArea: ScreenRecordingAreaView? = null
    private var recordingAnnotation: ScreenRecordingAnnotation? = null
    private var recordingAnnotationParams: WindowManager.LayoutParams? = null
    private var recordingSetup: ScreenRecordingSetupView? = null
    private var recordingSetupParams: WindowManager.LayoutParams? = null
    private var recordingSetupAttached = false
    private var recordingRegion: Rect? = null
    private var recordingScreen: Pair<Int, Int>? = null
    private var recordingOptions: ScreenRecordingOptions? = null
    private var recordingSaved: ScreenRecordingLibrary.Saved? = null
    private var recordingRotation = -1
    private var recordingBarWarned = false
    private var recordingBarManual = false
    /** Debug broadcast path: skip the idle wait, but still honour consent and cancel cleanly. */
    private var recordingDebugAuto = false
    private var recordingDisplay: android.hardware.display.DisplayManager.DisplayListener? = null

    /**
     * Rotation watch while the setup frame or a recording is live.
     *
     * `DisplayManager.DisplayListener` never fired on K60 — the display rotated to 2400×1080 and the
     * capture kept running against a crop that no longer matched the screen. The listener is kept
     * (it is the documented path and costs nothing) but the poll is what actually ends the session.
     */
    private val rotationPoll = object : Runnable {
        override fun run() {
            if (liveRecorder == null && recordingSetup == null) return
            checkRecordingRotation()
            if (liveRecorder != null || recordingSetup != null) main.postDelayed(this, ROTATION_POLL_MS)
        }
    }

    /**
     * Upstream `startScreenshotRecording` (screenshotrecordingworkflow.h): the capture overlay is
     * closed and the recording area opens in `Idle`, where the frame can still be moved or resized
     * and the toolbar shows start instead of capture controls. The phone used to request projection
     * consent and start capturing from this action directly, so the idle stage never existed.
     */
    private fun startRecording(editor: ScreenshotCaptureOverlay, region: Rect) {
        clearStaleRecordingBusy()
        if (screenshot !== editor || busy || ocrInFlight || scrolling != null || liveRecorder != null ||
            recordingSetup != null || recordingControl != null) return
        val image = frame ?: return
        if (region.width() < 16 || region.height() < 16) {
            editor.setScrollCaptureActive(false, "录制区域太小")
            return
        }
        val options = recordingPreferences.load()
        val width = image.width
        val height = image.height
        busy = true
        // Upstream opens the recording area on the next event-loop turn after tearing down the
        // capture UI (`screenshotrecordingworkflow.h`: QTimer::singleShot(0, …)); this keeps the
        // overlay's own click handler from removing the view that is still dispatching the tap.
        main.post {
            if (!connected) {
                busy = false
                return@post
            }
            enterRecordingSetup(Rect(region), width, height, options)
        }
    }

    private fun enterRecordingSetup(region: Rect, screenWidth: Int, screenHeight: Int,
                                    options: ScreenRecordingOptions) {
        android.util.Log.i("VoxFix", "RECORDING_SETUP_ENTER $region ${screenWidth}x$screenHeight")
        closeSession()
        busy = true
        removeHandle()
        recordingRegion = region
        recordingScreen = screenWidth to screenHeight
        recordingOptions = options
        // Created before the setup frame and the bar: overlay windows stack in add order, so the
        // annotation canvas must go in first to leave the controls on top while drawing.
        showRecordingAnnotation()
        showRecordingSetup(region, options)
        watchRecordingRotation()
    }

    /** User pressed start: consent is a platform requirement, everything after it mirrors upstream. */
    private fun requestRecordingStart() {
        val region = recordingRegion ?: return
        val screen = recordingScreen ?: return
        val options = recordingOptions ?: return
        if (liveRecorder != null) return
        setRecordingSetupAttached(false)
        ScreenshotProjection.request(this, "录屏", { projection, stop ->
            // Consent can outlive this instance: the system recreates the accessibility service while
            // the permission dialog is showing, so deliver to the instance that is alive now.
            val service = live
            if (service != null) {
                service.beginRecording(projection, stop, Rect(region), screen.first, screen.second, options)
            } else {
                runCatching { stop() }
            }
        }, { message ->
            val service = live
            if (service == null) {
                android.util.Log.w("VoxFix", "RECORDING_FAILED_GONE reason=$message")
            } else if (service.recordingDebugAuto) {
                service.cancelRecordingSetup(message)
            } else {
                service.busy = true
                service.setRecordingSetupAttached(true)
                Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
            }
        }, microphone = options.audioEnabled && options.microphone)
    }

    private fun beginRecording(projection: android.media.projection.MediaProjection, stopProjection: () -> Unit,
                               region: Rect, screenWidth: Int, screenHeight: Int, options: ScreenRecordingOptions) {
        busy = true
        removeRecordingSetup()
        // After a service rebuild the transport bar from the previous instance is gone; recreate it
        // so a granted consent still leaves the user able to pause or stop.
        if (recordingControl == null) showRecordingControl(options)
        showRecordingArea(region, ScreenRecordingAreaView.State.COUNTDOWN)
        setRecordingSetupAttached(true)
        // 标注层只在真正录制时存在：设置阶段与倒计时里没有它。
        showRecordingAnnotation()
        // The setup stage armed the watch on the instance that owned it; consent can rebuild this
        // service, so arm it again on the instance that now owns the capture.
        watchRecordingRotation()
        val control = recordingControl
        val launch = {
            val rotation = currentRotation()
            if (ScreenRecordingGeometry.rotationSuperseded(recordingRotation, rotation)) {
                runCatching(stopProjection)
                busy = false
                recordingControl?.showFailure("屏幕已旋转，请重新选择录制区域")
                removeRecordingArea()
                showHandle()
            } else {
                updateRecordingArea(ScreenRecordingAreaView.State.RECORDING)
                launchRecorder(projection, stopProjection, region, screenWidth, screenHeight, options)
            }
        }
        if (options.startDelaySeconds > 0 && control != null) {
            control.startCountdown(options.startDelaySeconds, {
                control.start(options.audioEnabled && options.microphone, options.format.hasAudio)
                launch()
            }) {
                runCatching(stopProjection)
                busy = false
                removeRecordingControl()
                showHandle()
            }
        } else {
            control?.start(options.audioEnabled && options.microphone, options.format.hasAudio)
            launch()
        }
    }

    private fun launchRecorder(projection: android.media.projection.MediaProjection, stopProjection: () -> Unit,
                               region: Rect, screenWidth: Int, screenHeight: Int, options: ScreenRecordingOptions) {
        val (width, height) = ScreenRecordingGeometry.outputSize(region.width(), region.height(), options.effectiveClarity)
        android.util.Log.i("VoxFix", "RECORDING_LAUNCH format=${options.format.extension} size=${width}x$height " +
            "fps=${options.effectiveFrameRate} loop=${options.loopAnimated}")
        val config = ScreenRecorder.Config(
            crop = region, screenWidth = screenWidth, screenHeight = screenHeight,
            outputWidth = width, outputHeight = height,
            frameRate = options.effectiveFrameRate,
            bitrate = ScreenRecordingGeometry.bitrate(width, height, options.effectiveFrameRate, options.quality),
            microphone = options.audioEnabled && options.microphone,
            systemAudio = options.audioEnabled && options.systemAudio && !options.microphone,
            format = options.format,
            quality = options.quality,
            loopAnimated = options.loopAnimated
        )
        val active = ScreenRecorder(this, projection, config) { file, duration, message ->
            main.post { finishRecording(file, duration, message, stopProjection) }
        }
        recorder = active
        sharedRecorder = active
        sharedRegion = region
        sharedScreen = screenWidth to screenHeight
        sharedOptions = options
        sharedStartedAtMs = android.os.SystemClock.elapsedRealtime()
        val submitted = runCatching {
            screenshotWorker.execute {
                val error = active.start()
                main.post {
                    if (error != null) {
                        recorder = null
                        sharedRecorder = null
                        busy = false
                        runCatching(stopProjection)
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                        removeRecordingControl()
                        showHandle()
                    }
                }
            }
        }.isSuccess
        if (!submitted) {
            // The worker is shut down when the service is destroyed; failing here keeps a late
            // consent result from taking the process down with it.
            recorder = null
            sharedRecorder = null
            busy = false
            runCatching { active.cancel() }
            runCatching(stopProjection)
            android.util.Log.w("VoxFix", "SCREEN_RECORD_WORKER_GONE")
            removeRecordingControl()
            showHandle()
        }
    }

    private fun finishRecording(file: java.io.File?, duration: Long, message: String?, stopProjection: () -> Unit) {
        recorder = null
        sharedRecorder = null
        sharedRegion = null
        sharedScreen = null
        sharedOptions = null
        busy = false
        runCatching(stopProjection)
        stopWatchingRecordingRotation()
        removeRecordingArea()
        removeRecordingAnnotation()
        if (file == null) {
            recordingControl?.showFailure(message ?: "录屏已取消")
            message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        } else {
            val saved = ScreenRecordingLibrary.save(this, file)
            if (saved == null) {
                recordingControl?.showFailure("录屏保存失败")
                Toast.makeText(this, "录屏保存失败，请检查可用空间", Toast.LENGTH_SHORT).show()
            } else {
                recordingSaved = saved
                recordingControl?.showSaved(saved.displayPath, saved.uri)
                Toast.makeText(this, "已保存录屏 ${ScreenRecordingGeometry.formatDuration(duration)}", Toast.LENGTH_SHORT).show()
            }
        }
        // A session ended by rotation leaves the bar in the old orientation's coordinates; re-place
        // it so the saved-file row stays reachable.
        recordingControl?.let { control ->
            recordingControlParams?.let { params ->
                control.post { if (recordingControl === control) placeRecordingControl(control, params) }
            }
        }
        showHandle()
    }

    /** Border around the recorded region so the user can see what is being captured. */
    private fun showRecordingArea(region: Rect, state: ScreenRecordingAreaView.State) {
        if (recordingArea != null) {
            recordingArea?.setState(state)
            return
        }
        val view = ScreenRecordingAreaView(this, Rect(region)).apply { setState(state) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        runCatching { windows.addView(view, params) }.onSuccess { recordingArea = view }
    }

    private fun updateRecordingArea(state: ScreenRecordingAreaView.State) {
        recordingArea?.setState(state)
    }
    private fun removeRecordingArea() {
        recordingArea?.let { runCatching { windows.removeView(it) } }
        recordingArea = null
    }

    /**
     * 录制中标注层：上游 `recordingDrawingMode` 把绘制工具接在选区窗口的画布上。
     *
     * 手机侧单独一层全屏透明覆盖窗（默认穿透），绘制时切为可触摸；笔画落在覆盖层上，被 MediaProjection
     * 一起录进视频。工具条画在这层顶部，Android 没有窗口级“排除出录制”，所以它可见时会入镜。
     */
    private fun showRecordingAnnotation() {
        if (recordingAnnotation != null) return
        val screen = recordingScreen
            ?: (resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels)
        val view = ScreenRecordingAnnotation(this, screen.first, screen.second)
        view.onExitRequested = { setAnnotationDrawing(false) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (runCatching { windows.addView(view, params) }.isFailure) {
            runCatching { view.release() }
            return
        }
        recordingAnnotation = view
        recordingAnnotationParams = params
    }

    /** 标注层是否接收触摸：绘制时可点（笔画入镜），其余时间穿透回下面的应用。 */
    private fun setAnnotationDrawing(active: Boolean) {
        val view = recordingAnnotation ?: return
        val params = recordingAnnotationParams ?: return
        view.setDrawing(active)
        params.flags = if (active)
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { windows.updateViewLayout(view, params) }
        recordingControl?.setAnnotationEnabled(active)
        android.util.Log.i("VoxFix", "RECORDING_ANNOTATION active=$active")
    }

    private fun removeRecordingAnnotation() {
        recordingAnnotation?.let { view ->
            runCatching { windows.removeView(view) }
            runCatching { view.release() }
        }
        recordingAnnotation = null
        recordingAnnotationParams = null
    }

    private fun showRecordingSetup(region: Rect, options: ScreenRecordingOptions) {
        if (recordingSetup != null || recordingControl != null) return
        val screen = recordingScreen ?: return
        val bounds = ScreenRecordingRegion(0, 0, screen.first, screen.second)
        val framed = ScreenRecordingRegionEdit.clamp(
            ScreenRecordingRegion.of(region), bounds, dp(ScreenRecordingSetupView.MINIMUM_DP)
        )
        recordingRegion = framed.toRect()
        recordingBarWarned = false
        recordingBarManual = false
        val box = setupWindowBounds(framed.toRect(), screen)
        val view = ScreenRecordingSetupView(this, framed, bounds)
        view.place(framed, box[0], box[1])
        view.onRegionChanged = { next -> applySetupRegion(next) }
        val params = WindowManager.LayoutParams(
            box[2], box[3], WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = box[0]
            y = box[1]
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        recordingSetupParams = params
        recordingSetup = view
        recordingSetupAttached = false
        showRecordingControl(options)
        // The idle bar needs a region to dock against; the area view only exists once recording
        // starts, so the setup frame owns that geometry until then.
        recordingControl?.showSetup(options.audioEnabled && options.microphone, options.format.hasAudio)
        val attached = setRecordingSetupAttached(true)
        if (!attached) {
            android.util.Log.w("VoxFix", "RECORDING_SETUP_NOT_ATTACHED")
            // The overlay window was refused; leaving busy set would block every later capture.
            removeRecordingControl()
            busy = false
            if (!ScreenTextSettingsActivity.enabled(this)) removeHandle() else showHandle()
            Toast.makeText(this, "无法显示录屏设置", Toast.LENGTH_SHORT).show()
            return
        }
        recordingControl?.post {
            val control = recordingControl
            val controlParams = recordingControlParams
            if (control != null && controlParams != null) placeRecordingControl(control, controlParams)
        }
    }

    private fun setupWindowBounds(region: Rect, screen: Pair<Int, Int>): IntArray {
        val margin = dp(ScreenRecordingSetupView.MARGIN_DP)
        val left = (region.left - margin).coerceAtLeast(0)
        val top = (region.top - margin).coerceAtLeast(0)
        val right = (region.right + margin).coerceAtMost(screen.first)
        val bottom = (region.bottom + margin).coerceAtMost(screen.second)
        return intArrayOf(left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
    }

    /** Frame moved or resized in the setup stage: re-anchor its window and the control bar. */
    private fun applySetupRegion(next: ScreenRecordingRegion) {
        val view = recordingSetup ?: return
        val screen = recordingScreen ?: return
        val bounds = ScreenRecordingRegion(0, 0, screen.first, screen.second)
        val framed = ScreenRecordingRegionEdit.clamp(next, bounds, dp(ScreenRecordingSetupView.MINIMUM_DP))
        recordingRegion = framed.toRect()
        val box = setupWindowBounds(framed.toRect(), screen)
        view.place(framed, box[0], box[1])
        recordingSetupParams?.let { params ->
            params.x = box[0]
            params.y = box[1]
            params.width = box[2]
            params.height = box[3]
            if (recordingSetupAttached) runCatching { windows.updateViewLayout(view, params) }
        }
        recordingControl?.let { control ->
            recordingControlParams?.let { params -> placeRecordingControl(control, params) }
        }
    }

    /** Idle-stage teardown: no recording was started. */
    /**
     * Re-attaches the bar and border to a recording that outlived its service instance.
     *
     * The system rebuilds this service on rotation and around system dialogs; without adoption the
     * recorder kept running with no visible controls and no rotation watch.
     */
    private fun adoptRunningRecording() {
        val running = sharedRecorder ?: return
        if (running.isFinishing()) return
        if (recordingControl != null) return
        val region = sharedRegion ?: return
        val options = sharedOptions ?: return
        // A rotation while this instance was being rebuilt: the frame no longer matches the screen,
        // so close the session instead of re-attaching a border to the wrong pixels.
        val rotation = currentRotation()
        if (ScreenRecordingGeometry.rotationSuperseded(sharedRotation, rotation)) {
            android.util.Log.i("VoxFix", "RECORDING_ROTATION_END adopted=${sharedRotation}->${rotation}")
            Toast.makeText(this, "屏幕已旋转，录屏结束并保存", Toast.LENGTH_SHORT).show()
            running.stop()
            return
        }
        android.util.Log.i("VoxFix", "RECORDING_ADOPTED $region")
        recordingRegion = region
        recordingScreen = sharedScreen ?: recordingScreen
        recordingOptions = options
        busy = true
        showRecordingArea(region, ScreenRecordingAreaView.State.RECORDING)
        showRecordingControl(options)
        val elapsed = (android.os.SystemClock.elapsedRealtime() - sharedStartedAtMs).coerceAtLeast(0)
        recordingControl?.start(options.audioEnabled && options.microphone, options.format.hasAudio, elapsed)
        watchRecordingRotation()
        main.post { recordingControl?.let { control ->
            recordingControlParams?.let { params -> placeRecordingControl(control, params) }
        } }
    }

    /**
     * A consent result that never comes back leaves [busy] set with no window at all, which makes
     * every later recording attempt bail out silently. Recover when nothing is actually live.
     */
    private fun clearStaleRecordingBusy() {
        if (!busy) return
        if (recordingSetup != null || recordingControl != null || liveRecorder != null) return
        if (screenshot != null || scrolling != null || ocrInFlight) return
        android.util.Log.w("VoxFix", "RECORDING_BUSY_STALE_RESET")
        busy = false
    }

    private fun cancelRecordingSetup(message: String? = null) {
        android.util.Log.i("VoxFix", "RECORDING_SETUP_CANCELLED reason=${message ?: "none"}")
        removeRecordingControl()
        busy = false
        if (!ScreenTextSettingsActivity.enabled(this)) removeHandle() else showHandle()
        message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
    }

    private fun removeRecordingSetup() {
        setRecordingSetupAttached(false)
        recordingSetup = null
        recordingSetupParams = null
    }

    /**
     * The projection consent is a system activity; an accessibility overlay above it would cover
     * its buttons, so the setup windows step aside and come back if the user declines.
     */
    private fun setRecordingSetupAttached(attached: Boolean): Boolean {
        if (attached == recordingSetupAttached) return true
        recordingSetupAttached = attached
        val view = recordingSetup
        val params = recordingSetupParams
        val control = recordingControl
        val controlParams = recordingControlParams
        if (attached) {
            val viewAdded = if (view != null && params != null)
                runCatching { windows.addView(view, params) }
                    .onFailure { android.util.Log.w("VoxFix", "RECORDING_SETUP_ATTACH_FAILED", it) }
                    .isSuccess else true
            val barAdded = if (control != null && controlParams != null)
                runCatching { windows.addView(control, controlParams) }
                    .onFailure { android.util.Log.w("VoxFix", "RECORDING_BAR_ATTACH_FAILED", it) }
                    .isSuccess else true
            if (control != null && controlParams != null && barAdded) control.post {
                if (recordingControl === control) placeRecordingControl(control, controlParams)
            }
            return viewAdded && barAdded
        } else {
            if (view != null) runCatching { windows.removeView(view) }
            if (control != null) runCatching { windows.removeView(control) }
            return true
        }
    }

    private fun showRecordingControl(options: ScreenRecordingOptions) {
        if (recordingControl != null) return
        val control = ScreenRecordingControl(this)
        control.onStop = { liveRecorder?.stop() }
        control.onCancel = { liveRecorder?.cancel() }
        control.onStart = { requestRecordingStart() }
        control.onClose = { cancelRecordingSetup("已关闭录屏设置") }
        control.onTogglePause = { paused ->
            if (paused) liveRecorder?.pause() else liveRecorder?.resume()
            updateRecordingArea(if (paused) ScreenRecordingAreaView.State.PAUSED
            else ScreenRecordingAreaView.State.RECORDING)
        }
        control.setOptions(options)
        control.onOptionsChanged = { updated ->
            recordingOptions = updated
            recordingPreferences.save(updated)
            Toast.makeText(this, "已应用", Toast.LENGTH_SHORT).show()
        }
        control.onOpenFolder = {
            if (!ScreenRecordingLibrary.openFolder(this))
                Toast.makeText(this, "没有可打开相册的应用", Toast.LENGTH_SHORT).show()
        }
        control.onOpenSaved = {
            val saved = recordingSaved
            if (saved == null || !ScreenRecordingLibrary.open(this, saved.uri, saved.displayPath))
                Toast.makeText(this, "找不到录屏文件", Toast.LENGTH_SHORT).show()
        }
        control.onToggleAnnotation = { active -> setAnnotationDrawing(active) }
        control.onReleased = { removeRecordingControl() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        control.onLayoutChanged = {
            // The settings panel changes the bar height; re-place after the new measure pass so
            // the expanded panel stays on screen instead of hanging off the bottom edge.
            control.post {
                val current = recordingControlParams
                if (recordingControl === control && current != null) placeRecordingControl(control, current)
            }
        }
        control.onDrag = { dx, dy ->
            if (recordingControl === control) {
                val metrics = resources.displayMetrics
                val barWidth = control.width.takeIf { it > 0 } ?: control.measuredWidth
                val barHeight = control.height.takeIf { it > 0 } ?: control.measuredHeight
                params.x = (params.x + dx.toInt()).coerceIn(0, (metrics.widthPixels - barWidth).coerceAtLeast(0))
                params.y = (params.y + dy.toInt()).coerceIn(0, (metrics.heightPixels - barHeight).coerceAtLeast(0))
                recordingBarManual = true
                runCatching { windows.updateViewLayout(control, params) }
            }
        }
        recordingControl = control
        recordingControlParams = params
    }

    private fun placeRecordingControl(control: ScreenRecordingControl, params: WindowManager.LayoutParams) {
        val region = recordingRegion ?: return
        val metrics = resources.displayMetrics
        val barWidth = control.width.takeIf { it > 0 } ?: control.measuredWidth
        val barHeight = control.height.takeIf { it > 0 } ?: control.measuredHeight
        val gap = dp(8)
        if (recordingBarManual) {
            // The user placed the bar; only keep it on screen when the settings row grows.
            params.x = params.x.coerceIn(0, (metrics.widthPixels - barWidth).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (metrics.heightPixels - barHeight).coerceAtLeast(0))
        } else {
            params.x = (region.centerX() - barWidth / 2).coerceIn(gap, maxOf(gap, metrics.widthPixels - barWidth - gap))
            var y = region.bottom + gap
            if (y + barHeight > metrics.heightPixels - gap) y = region.top - barHeight - gap
            if (y < gap) y = (metrics.heightPixels - barHeight - gap).coerceAtLeast(gap)
            params.y = y
        }
        if (recordingSetupAttached) runCatching { windows.updateViewLayout(control, params) }
        if (!recordingBarWarned && Rect(params.x, params.y, params.x + barWidth, params.y + barHeight).intersect(Rect(region))) {
            recordingBarWarned = true
            Toast.makeText(this, "录屏控制条在录制区域内，会被录进画面，可按住左侧拖动移开", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeRecordingControl() {
        recordingControl?.let {
            it.stopCountdown()
            runCatching { windows.removeView(it) }
        }
        recordingControl = null
        recordingControlParams = null
        recordingSaved = null
        removeRecordingSetup()
        removeRecordingArea()
        removeRecordingAnnotation()
        recordingRegion = null
        recordingOptions = null
        recordingScreen = null
        recordingDebugAuto = false
        stopWatchingRecordingRotation()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun watchRecordingRotation() {
        stopWatchingRecordingRotation()
        if (recordingRotation < 0) {
            // A rebuilt instance starts with no baseline; the process-wide one survives the rebuild
            // and is the orientation the frame was actually drawn in.
            recordingRotation = if (sharedRotation >= 0) sharedRotation else currentRotation()
        }
        if (sharedRotation < 0) sharedRotation = recordingRotation
        val manager = getSystemService(android.hardware.display.DisplayManager::class.java)
        if (manager != null) {
            val listener = object : android.hardware.display.DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId != Display.DEFAULT_DISPLAY) return
                    if (liveRecorder == null && recordingSetup == null) return
                    checkRecordingRotation()
                }
            }
            recordingDisplay = listener
            runCatching { manager.registerDisplayListener(listener, main) }
        }
        main.removeCallbacks(rotationPoll)
        main.postDelayed(rotationPoll, ROTATION_POLL_MS)
    }

    /** Current rotation of the default display, or -1 when it cannot be read. */
    private fun currentRotation(): Int = runCatching {
        getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: -1
    }.getOrDefault(-1)

    /**
     * Ends the session when the screen no longer matches the frame the recording was started from.
     *
     * Recording: stop and save (the projection cannot be re-cropped mid-session, and resuming into
     * a different orientation would mix two geometries in one file). Setup stage: cancel and ask for
     * a new region, because the frame is drawn in the old orientation's pixel grid.
     */
    private fun checkRecordingRotation() {
        val rotation = currentRotation()
        if (rotation < 0) return
        if (recordingRotation < 0) { recordingRotation = rotation; return }
        if (!ScreenRecordingGeometry.rotationSuperseded(recordingRotation, rotation)) return
        recordingRotation = rotation
        // No session: `onConfigurationChanged` keeps arriving after the bar is gone, so only move
        // the baseline instead of reporting a rotation that ends nothing.
        if (liveRecorder == null && recordingSetup == null) { sharedRotation = rotation; return }
        android.util.Log.i("VoxFix", "RECORDING_ROTATION_END rotation=$rotation")
        val running = liveRecorder
        if (running != null && !running.isFinishing()) {
            Toast.makeText(this, "屏幕已旋转，录屏结束并保存", Toast.LENGTH_SHORT).show()
            running.stop()
        } else if (recordingSetup != null) {
            cancelRecordingSetup("屏幕已旋转，请重新选择录制区域")
        }
    }

    private fun stopWatchingRecordingRotation() {
        main.removeCallbacks(rotationPoll)
        recordingDisplay?.let { listener ->
            runCatching {
                getSystemService(android.hardware.display.DisplayManager::class.java)
                    ?.unregisterDisplayListener(listener)
            }
        }
        recordingDisplay = null
    }

    private fun listenToScrollEvents(windowId: Int?) {
        scrollWindowId = windowId
        if (windowId == null && scrollNotificationTimeout == null) return
        runCatching {
            val info = serviceInfo ?: return@runCatching
            if (windowId == null) {
                info.eventTypes = info.eventTypes and AccessibilityEvent.TYPE_VIEW_SCROLLED.inv()
                info.notificationTimeout = checkNotNull(scrollNotificationTimeout)
            } else {
                scrollNotificationTimeout = info.notificationTimeout
                info.eventTypes = info.eventTypes or AccessibilityEvent.TYPE_VIEW_SCROLLED
                info.notificationTimeout = 40
            }
            serviceInfo = info
        }
        if (windowId == null) scrollNotificationTimeout = null
    }

    @Suppress("DEPRECATION")
    private fun scrollTargetAt(points: List<Pair<Int,Int>>, ignoreOverlay: Boolean = false): Int? {
        val visible = getWindows()
        try {
            val bounds = android.graphics.Region()
            val ids = points.map { (x,y) ->
                val hit = visible.firstOrNull {
                    (!ignoreOverlay || it.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) &&
                        bounds.also(it::getRegionInScreen).contains(x,y)
                }
                hit?.takeIf { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }?.id
            }
            return ids.distinct().singleOrNull()
        } finally { visible.forEach { it.recycle() } }
    }

    private fun scrollStep(region: Rect, horizontal: Boolean, targetWindow: Int?, done: (Boolean) -> Unit): Boolean {
        if (targetWindow == null || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        val extent = if (horizontal) region.width() else region.height()
        if (extent / 3f <= android.view.ViewConfiguration.get(this).scaledTouchSlop) return false
        val points = ScreenshotScrollCapture.swipe(region.left,region.top,region.right,region.bottom,horizontal)
        if (targetWindow != scrollTargetAt(listOf(points.startX.toInt() to points.startY.toInt(),
                region.centerX() to region.centerY(), points.endX.toInt() to points.endY.toInt()))) return false
        val path = android.graphics.Path().apply { moveTo(points.startX,points.startY); lineTo(points.endX,points.endY) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path,0,440)).build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) { done(true) }
            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) { done(false) }
        }, main)
    }
    private fun recognize(region: Rect) = recognizeImage(region, false)

    private fun recognizeImage(region: Rect, screenshotResult: Boolean) {
        if (busy || ocrInFlight) return
        val image = frame ?: return
        val safe = Rect(region)
        if (!safe.intersect(0,0,image.width,image.height) || safe.width()<2 || safe.height()<2) return
        val token = generation
        busy = true; ocrInFlight = true
        if (!screenshotResult) overlay?.loading()
        keepSession()
        try {
            val crop = Bitmap.createBitmap(image,safe.left,safe.top,safe.width(),safe.height())
            val input = if (crop === image) image.copy(Bitmap.Config.ARGB_8888,false)!! else crop
            try {
                screenshotWorker.execute {
                    try {
                        val blocks = ScreenOcr.recognize(this,input) { token == generation }
                            .map { it.copy(left=it.left+safe.left,top=it.top+safe.top,right=it.right+safe.left,bottom=it.bottom+safe.top) }
                        main.post {
                            if (token == generation) {
                                busy = false
                                if (screenshotResult) screenshot?.textResult(blocks)
                                else overlay?.result(blocks.joinToString("\n") { it.text },null)
                            }
                        }
                    } catch (error: Exception) {
                        android.util.Log.w("VoxFix","SCREEN_OCR_FAILED",error)
                        main.post { if (token == generation) {
                            busy = false
                            if (screenshotResult) screenshot?.failure("识别失败，请重新框选")
                            else overlay?.result("","识别失败，请重新框选")
                        } }
                    } finally { input.recycle(); main.post { ocrInFlight = false } }
                }
            } catch (error: Exception) { input.recycle(); throw error }
        } catch (error: Exception) {
            busy = false; ocrInFlight = false
            if (screenshotResult) screenshot?.failure("识别失败，请重试") else overlay?.result("","识别失败，请重试")
            android.util.Log.w("VoxFix","SCREEN_OCR_FAILED",error)
        }
    }
    private fun keepSession() {
        main.removeCallbacks(expire)
        if (frame != null) main.postDelayed(expire, 90_000)
    }
    private fun retainText() {
        scrolling?.close(); scrolling = null
        listenToScrollEvents(null)
        val state = screenshot?.retainedResult() ?: overlay?.retainedResult()
        if (state == null) { closeSession(); return }
        ++generation; busy = false
        main.removeCallbacks(captureTimeout); main.removeCallbacks(expire)
        TranslateRuntime.cancel(); TranslateRuntime.releaseNow()
        screenshot?.dismiss(windows); screenshot = null
        overlay?.dismiss(windows); overlay = null
        session?.close(); session = null
        val next = ScreenTextOverlay(this, null, ::recognize, ::closeSession, ::keepSession)
        try { next.restoreResult(state); next.show(windows); overlay = next }
        catch (error: Exception) { next.dismiss(windows); fail("无法恢复文字面板", error) }
    }
    private fun fail(message: String, error: Exception? = null) {
        error?.let { android.util.Log.w("VoxFix", "SCREEN_TEXT_CAPTURE_FAILED", it) }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); closeSession()
    }
    private fun closeSession() {
        scrolling?.close(); scrolling = null
        listenToScrollEvents(null)
        ScreenshotPins.visible(true)
        ++generation; busy = false
        TranslateRuntime.cancel(); TranslateRuntime.idle()
        main.removeCallbacks(captureTimeout); main.removeCallbacks(expire)
        overlay?.dismiss(windows); overlay = null
        screenshot?.dismiss(windows); screenshot = null
        session?.close(); session = null
        editorDone?.let { done -> editorDone = null; done(false) }
        if (!ScreenTextSettingsActivity.enabled(this)) removeHandle() else showHandle()
    }
    /** Opens a saved record in the editor that normally belongs to a fresh capture. */
    private fun openSavedProject(id: String, done: (Boolean) -> Unit) {
        busy = true; removeHandle(); ScreenshotPins.visible(false)
        val token = ++generation
        editorDone = done
        screenshotWorker.execute {
            try {
                val store = ScreenshotStore(this)
                val entry = store.entry(id) ?: error("图片已删除，请刷新")
                // A plain image has no project yet: its pixels become the canvas and its first save
                // creates a separate record, leaving the original untouched.
                val document = store.document(id)
                check(entry.kind!=ScreenshotStore.Kind.PROJECT || document!=null) { "截图工程无法读取，原文件已保留" }
                val source = store.sourceBitmap(id) ?: error("图片底图无法读取，原文件已保留")
                if(document!=null && (source.width!=document.canvasWidth || source.height!=document.canvasHeight)) {
                    source.recycle(); error("截图底图与工程不匹配，原文件已保留")
                }
                val project = if (document != null) ScreenshotProject(document.copy(), id, entry.revision, null)
                    else ScreenshotProject(ScreenshotDocument.full(source.width, source.height), null, null, id)
                main.post {
                    if (token != generation || !connected) { source.recycle(); busy = false; editorDone = null; done(false); return@post }
                    try {
                        session = ScreenshotSession(source)
                        val view = ScreenshotCaptureOverlay(this, source, ::saveScreenshot, ::recognizeScreenshot, ::updateText,
                            { closeSession() }, ::keepSession, TranslateRuntime,
                            ScreenshotToolMemory(this), project)
                        view.show(windows)
                        screenshot = view
                        busy = false
                        keepSession()
                    } catch (error: Exception) { fail("无法打开图片编辑", error) }
                }
            } catch (error: Exception) {
                android.util.Log.w("VoxFix", "SCREENSHOT_EDIT_OPEN_FAILED", error)
                main.post {
                    if (token == generation) {
                        val finish = editorDone
                        busy = false; editorDone = null
                        Toast.makeText(this, error.message ?: "无法打开图片编辑", Toast.LENGTH_SHORT).show()
                        closeSession()
                        // The keyboard still shows the preview, but it must clear its opening state.
                        finish?.invoke(false)
                    }
                }
            }
        }
    }
    private fun updateText(value: String) {
        android.util.Log.i("VoxFix","SCREENSHOT_TEXT len=${value.length}")
        runCatching { session?.updateText(value) }
        keepSession()
    }
    private fun recognizeScreenshot(region: Rect) = recognizeImage(region, true)
    private fun saveScreenshot(request: ScreenshotSaveRequest) {
        if (busy) return
        val image = frame ?: return
        val region = Rect(request.region)
        if (!region.intersect(0,0,image.width,image.height) || region.width() < 2 || region.height() < 2) { request.composite?.recycle(); return }
        val token = generation
        busy = true
        try {
            val composite = request.composite ?: requireNotNull(session).crop(region)
            // Only a fresh capture hands its frozen screenshot over as the project's base image.
            val source = image.takeIf { request.replaceId == null && request.copySourceFromId == null }
            try {
                screenshotWorker.execute {
                    try {
                        val store = ScreenshotStore(this)
                        store.saveProject(request.document,composite,source,request.replaceId,request.expectedRevision,request.copySourceFromId)
                        main.post {
                            if (connected && token == generation) {
                                val done = editorDone
                                editorDone = null
                                closeSession()
                                done?.invoke(true)
                                Toast.makeText(this,"已保存到图片剪贴板",Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (error: Exception) {
                        android.util.Log.w("VoxFix","SCREENSHOT_SAVE_FAILED",error)
                        val message = if (error is IllegalStateException || error is IllegalArgumentException) error.message ?: "截图保存失败，请重试" else "截图保存失败，请检查可用空间后重试"
                        main.post { if (connected && token == generation) { busy = false; screenshot?.failure(message) } }
                    } finally { if (composite !== image) composite.recycle() }
                }
            } catch (error: Exception) { if (composite !== image) composite.recycle(); throw error }
        } catch (error: Exception) { busy = false; screenshot?.failure(error.message ?: "截图保存失败，请重试") }
    }

    private companion object {
        /** Newest bound instance; the system may replace it while a system dialog is in front. */
        @Volatile
        var live: ScreenTextService? = null

        /**
         * The recording that is actually running.
         *
         * It outlives the service instance that started it: the system recreates the accessibility
         * service (rotation, system dialogs), and an instance-local field left the recorder running
         * with no bar and no rotation watch — an unstoppable capture.
         */
        @Volatile
        var sharedRecorder: ScreenRecorder? = null
        @Volatile
        var sharedRegion: Rect? = null
        @Volatile
        var sharedScreen: Pair<Int, Int>? = null
        @Volatile
        var sharedOptions: ScreenRecordingOptions? = null
        @Volatile
        var sharedStartedAtMs: Long = 0

        /** Rotation the running capture was framed in; a rebuilt instance compares against it. */
        @Volatile
        var sharedRotation: Int = -1

        /** Rotation poll cadence: fast enough to stop before a rotated frame is written, cheap. */
        const val ROTATION_POLL_MS = 400L
    }

    /** Instance field first, then the process-wide one. */
    private val liveRecorder: ScreenRecorder? get() = recorder ?: sharedRecorder
}
