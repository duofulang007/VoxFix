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
            if (intent?.action == Intent.ACTION_SCREEN_OFF) { recorder?.stop(); ScreenshotPins.closeAll(); closeSession(); removeHandle(); TranslateRuntime.releaseNow() } else showHandle()
        }
    }
    private val debuggable get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    // Debug builds only: lets the agent drive a capture without the arc gesture.
    private val debugCapture = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DEBUG_CAPTURE) { android.util.Log.i("VoxFix","DEBUG_CAPTURE"); capture() }
        }
    }
    private val expire = Runnable {
        if (screenshot?.isEditing == true || screenshot?.isProjectEditor == true) keepSession() else retainText()
    }
    private val captureTimeout = Runnable { captureFailed("截图超时，请重试") }

    override fun onServiceConnected() {
        connected = true
        TranslateRuntime.init(this)
        ScreenshotEditorHost.register(editorHost)
        preferences.registerOnSharedPreferenceChangeListener(this)
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenState, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenState, filter)
        if (debuggable) {
            val debugFilter = IntentFilter(DEBUG_CAPTURE)
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(debugCapture, debugFilter, RECEIVER_EXPORTED)
            else registerReceiver(debugCapture, debugFilter)
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
        recorder?.cancel()
        recorder = null
        removeRecordingControl()
        ScreenshotPins.closeAll()
        connected = false
        ScreenshotEditorHost.register(null)
        editorDone = null
        TranslateRuntime.cancel(); TranslateRuntime.releaseNow()
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        runCatching { unregisterReceiver(screenState) }
        if (debuggable) runCatching { unregisterReceiver(debugCapture) }
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
    private var recordingSaved: ScreenRecordingLibrary.Saved? = null
    private var recordingRotation = -1
    private var recordingBarWarned = false
    private var recordingDisplay: android.hardware.display.DisplayManager.DisplayListener? = null

    private fun startRecording(editor: ScreenshotCaptureOverlay, region: Rect) {
        if (screenshot !== editor || busy || ocrInFlight || scrolling != null || recorder != null) return
        val image = frame ?: return
        if (region.width() < 16 || region.height() < 16) {
            editor.setScrollCaptureActive(false, "录制区域太小")
            return
        }
        val options = recordingPreferences.load()
        busy = true
        editor.setScrollCaptureActive(true)
        ScreenshotProjection.request(this, "录屏", { projection, stop ->
            beginRecording(projection, stop, Rect(region), image.width, image.height, options)
        }, { message ->
            busy = false
            if (screenshot === editor) editor.setScrollCaptureActive(false, message)
            else Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }, microphone = options.microphone)
    }

    private fun beginRecording(projection: android.media.projection.MediaProjection, stopProjection: () -> Unit,
                               region: Rect, screenWidth: Int, screenHeight: Int, options: ScreenRecordingOptions) {
        val (width, height) = ScreenRecordingGeometry.outputSize(region.width(), region.height(), options.clarity)
        val config = ScreenRecorder.Config(
            crop = region, screenWidth = screenWidth, screenHeight = screenHeight,
            outputWidth = width, outputHeight = height,
            frameRate = options.frameRate,
            bitrate = ScreenRecordingGeometry.bitrate(width, height, options.frameRate, options.quality),
            microphone = options.microphone,
            systemAudio = options.systemAudio && !options.microphone
        )
        val active = ScreenRecorder(this, projection, config) { file, duration, message ->
            main.post { finishRecording(file, duration, message, stopProjection) }
        }
        recorder = active
        closeSession()
        busy = true
        screenshotWorker.execute {
            val error = active.start()
            main.post {
                if (error != null) {
                    recorder = null
                    busy = false
                    runCatching(stopProjection)
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    showHandle()
                } else showRecordingControl(region, options)
            }
        }
    }

    private fun finishRecording(file: java.io.File?, duration: Long, message: String?, stopProjection: () -> Unit) {
        recorder = null
        busy = false
        runCatching(stopProjection)
        stopWatchingRecordingRotation()
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
        showHandle()
    }

    private fun showRecordingControl(region: Rect, options: ScreenRecordingOptions) {
        if (recordingControl != null) return
        recordingBarWarned = false
        val control = ScreenRecordingControl(this)
        control.onStop = { recorder?.stop() }
        control.onCancel = { recorder?.cancel() }
        control.onTogglePause = { paused -> if (paused) recorder?.pause() else recorder?.resume() }
        control.onToggleMicrophone = {
            val updated = recordingPreferences.load().let { it.copy(microphone = !it.microphone) }
            recordingPreferences.save(updated)
            control.setMicrophone(updated.microphone, true)
            Toast.makeText(this,
                if (updated.microphone) "下次录屏使用麦克风" else "下次录屏使用系统声音",
                Toast.LENGTH_SHORT).show()
        }
        control.onOpenSaved = {
            val saved = recordingSaved
            if (saved == null || !ScreenRecordingLibrary.open(this, saved.uri, saved.displayPath))
                Toast.makeText(this, "找不到录屏文件", Toast.LENGTH_SHORT).show()
        }
        control.onReleased = { removeRecordingControl() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        val added = runCatching { windows.addView(control, params) }.isSuccess
        if (!added) {
            recorder?.cancel()
            Toast.makeText(this, "无法显示录屏控制条", Toast.LENGTH_SHORT).show()
            return
        }
        recordingControl = control
        control.start(options.microphone, true)
        control.post { placeRecordingControl(control, params, region) }
        watchRecordingRotation()
    }

    private fun placeRecordingControl(control: ScreenRecordingControl, params: WindowManager.LayoutParams, region: Rect) {
        val metrics = resources.displayMetrics
        val barWidth = control.width.takeIf { it > 0 } ?: control.measuredWidth
        val barHeight = control.height.takeIf { it > 0 } ?: control.measuredHeight
        val gap = (8 * metrics.density).toInt()
        params.x = (region.centerX() - barWidth / 2).coerceIn(gap, maxOf(gap, metrics.widthPixels - barWidth - gap))
        var y = region.bottom + gap
        if (y + barHeight > metrics.heightPixels - gap) y = region.top - barHeight - gap
        if (y < gap) y = (metrics.heightPixels - barHeight - gap).coerceAtLeast(gap)
        params.y = y
        runCatching { windows.updateViewLayout(control, params) }
        if (!recordingBarWarned && Rect(params.x, params.y, params.x + barWidth, params.y + barHeight).intersect(Rect(region))) {
            recordingBarWarned = true
            Toast.makeText(this, "选区外没有空间，录屏控制条会进入画面", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeRecordingControl() {
        recordingControl?.let { runCatching { windows.removeView(it) } }
        recordingControl = null
        recordingSaved = null
        stopWatchingRecordingRotation()
    }

    private fun watchRecordingRotation() {
        stopWatchingRecordingRotation()
        val manager = getSystemService(android.hardware.display.DisplayManager::class.java) ?: return
        @Suppress("DEPRECATION")
        recordingRotation = windows.defaultDisplay?.rotation ?: -1
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY || recorder == null) return
                @Suppress("DEPRECATION")
                val rotation = windows.defaultDisplay?.rotation ?: return
                if (rotation == recordingRotation) return
                recordingRotation = rotation
                Toast.makeText(this@ScreenTextService, "屏幕已旋转，录屏结束并保存", Toast.LENGTH_SHORT).show()
                recorder?.stop()
            }
        }
        recordingDisplay = listener
        runCatching { manager.registerDisplayListener(listener, main) }
    }

    private fun stopWatchingRecordingRotation() {
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
}
