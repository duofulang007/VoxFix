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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/** Screenshot-only extraction also sees text inside images beside ordinary UI labels. */
private const val DEBUG_CAPTURE = "local.voxfix.debug.DEBUG_CAPTURE"

class ScreenTextService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val main = Handler(Looper.getMainLooper())
    private val preferences by lazy { ScreenTextSettingsActivity.preferences(this) }
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private var handle: ScreenTextHandle? = null
    private var overlay: ScreenTextOverlay? = null
    private var screenshot: ScreenshotCaptureOverlay? = null
    private val screenshotWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var session: ScreenshotSession? = null
    private val frame: Bitmap? get() = session?.image
    private var busy = false
    private var generation = 0
    private var connected = false
    private var ocrInFlight = false
    private val screenState = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) { closeSession(); removeHandle() } else showHandle()
        }
    }
    private val debuggable get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    // Debug builds only: lets the agent drive a capture without the arc gesture.
    private val debugCapture = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DEBUG_CAPTURE) { android.util.Log.i("VoxFix","DEBUG_CAPTURE"); capture() }
        }
    }
    private val expire = Runnable { if (screenshot?.isEditing == true) keepSession() else retainText() }
    private val captureTimeout = Runnable { fail("截图超时，请重试") }

    override fun onServiceConnected() {
        connected = true
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
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            closeSession(); removeHandle()
        } else showHandle()
    }
    override fun onInterrupt() { closeSession() }
    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        retainText(); removeHandle(); showHandle()
    }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (overlay?.imeVisible == true) return false
        if ((overlay != null || screenshot != null || busy) && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (screenshot?.back() != true) overlay?.closeAnimated() ?: closeSession()
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
        connected = false
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
    private fun capture() {
        if (busy || overlay != null || screenshot != null) return
        if (ocrInFlight) { Toast.makeText(this, "上次识别正在结束，请稍后重试", Toast.LENGTH_SHORT).show(); return }
        busy = true; removeHandle()
        val token = ++generation
        main.postDelayed(captureTimeout, 4000)
        main.postDelayed({
            if (token != generation || !connected) return@postDelayed
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        try {
                            if (token != generation) return
                            main.removeCallbacks(captureTimeout)
                            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: error("No screenshot bitmap")
                            val image = try { hardware.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware.recycle() }
                            session = ScreenshotSession(image ?: error("Screenshot copy failed"))
                            screenshot = ScreenshotCaptureOverlay(this@ScreenTextService,frame!!,::saveScreenshot,::recognizeScreenshot,::updateText,::closeSession,::keepSession)
                            screenshot!!.show(windows)
                            busy = false
                            keepSession()
                        } catch (error: Exception) { if (token == generation) fail("无法打开截图，请重试", error) }
                        finally { buffer.close() }
                    }
                    override fun onFailure(errorCode: Int) {
                        if (token == generation) fail(if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) "此页面禁止截图" else "截图失败，请重试或检查无障碍服务")
                    }
                })
            } catch (error: Exception) { fail("截图不可用，请检查无障碍服务", error) }
        }, 120)
    }
    private fun recognize(region: Rect) {
        if (busy) return
        val image = frame ?: return
        val safe = Rect(region)
        if (!safe.intersect(0, 0, image.width, image.height) || safe.width() < 2 || safe.height() < 2) return
        val token = generation
        busy = true; overlay?.loading()
        keepSession()
        try {
            val crop = Bitmap.createBitmap(image, safe.left, safe.top, safe.width(), safe.height())
            val input = if (crop === image) image.copy(Bitmap.Config.ARGB_8888, false)!! else crop
            val recognizer = try { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
                catch (error: Exception) { input.recycle(); throw error }
            try {
                ocrInFlight = true
                recognizer.process(InputImage.fromBitmap(input, 0))
                    .addOnCompleteListener(mainExecutor) { task ->
                        try {
                            if (token == generation) {
                                busy = false
                                overlay?.result(if (task.isSuccessful) task.result.text else "", if (task.isSuccessful) null else "识别失败，请框选重试")
                                task.exception?.let { android.util.Log.w("VoxFix", "SCREEN_TEXT_OCR_FAILED", it) }
                            }
                        } finally { ocrInFlight = false; recognizer.close(); input.recycle() }
                    }
            } catch (error: Exception) { ocrInFlight = false; recognizer.close(); input.recycle(); throw error }
        } catch (error: Exception) {
            busy = false; overlay?.result("", "识别失败，请框选重试")
            android.util.Log.w("VoxFix", "SCREEN_TEXT_OCR_FAILED", error)
        }
    }
    private fun keepSession() {
        main.removeCallbacks(expire)
        if (frame != null) main.postDelayed(expire, 90_000)
    }
    private fun retainText() {
        val state = overlay?.retainedResult()
        if (state == null) { closeSession(); return }
        ++generation; busy = false
        main.removeCallbacks(captureTimeout); main.removeCallbacks(expire)
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
        ++generation; busy = false
        main.removeCallbacks(captureTimeout); main.removeCallbacks(expire)
        overlay?.dismiss(windows); overlay = null
        screenshot?.dismiss(windows); screenshot = null
        session?.close(); session = null
        if (!ScreenTextSettingsActivity.enabled(this)) removeHandle() else showHandle()
    }
    private fun updateText(value: String) {
        android.util.Log.i("VoxFix","SCREENSHOT_TEXT len=${value.length}")
        runCatching { session?.updateText(value) }
        keepSession()
    }
    private fun recognizeScreenshot(region: Rect) {
        if (busy) return
        val image = frame ?: return
        val safe = Rect(region)
        if (!safe.intersect(0,0,image.width,image.height) || safe.width() < 2 || safe.height() < 2) return
        val token = generation
        busy = true
        keepSession()
        try {
            val crop = Bitmap.createBitmap(image,safe.left,safe.top,safe.width(),safe.height())
            val input = if (crop === image) image.copy(Bitmap.Config.ARGB_8888,false)!! else crop
            val recognizer = try { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
                catch (error: Exception) { input.recycle(); throw error }
            try {
                ocrInFlight = true
                recognizer.process(InputImage.fromBitmap(input,0))
                    .addOnCompleteListener(mainExecutor) { task ->
                        try {
                            if (token == generation) {
                                busy = false
                                if (task.isSuccessful) {
                                    // Bounding boxes stay in the frozen image, so edits land on their own line.
                                    val blocks = task.result.textBlocks.asSequence()
                                        .flatMap { it.lines.asSequence() }
                                        .mapNotNull { line ->
                                            val box = line.boundingBox
                                            if (box == null || box.width() < 2 || box.height() < 2) null
                                            else ScreenshotTextBlock(box.left+safe.left,box.top+safe.top,box.right+safe.left,box.bottom+safe.top,line.text)
                                        }
                                        .take(400)
                                        .toList()
                                    android.util.Log.i("VoxFix","SCREENSHOT_OCR_OK blocks=${blocks.size}")
                                    screenshot?.textResult(blocks)
                                } else screenshot?.failure("识别失败，请重试")
                                task.exception?.let { android.util.Log.w("VoxFix","SCREENSHOT_OCR_FAILED",it) }
                            }
                        } finally { ocrInFlight = false; recognizer.close(); input.recycle() }
                    }
            } catch (error: Exception) { ocrInFlight = false; recognizer.close(); input.recycle(); throw error }
        } catch (error: Exception) {
            busy = false; screenshot?.failure("识别失败，请重试")
            android.util.Log.w("VoxFix","SCREENSHOT_OCR_FAILED",error)
        }
    }
    private fun saveScreenshot(region: Rect, result: Bitmap?) {
        if (busy) return
        val image = frame ?: return
        val safe = Rect(region)
        if (!safe.intersect(0,0,image.width,image.height) || safe.width() < 2 || safe.height() < 2) { result?.recycle(); return }
        val token = generation
        busy = true
        try {
            val input = result ?: requireNotNull(session).crop(safe)
            try {
                screenshotWorker.execute {
                    try {
                        val store = ScreenshotStore(this)
                        store.save(input)
                        main.post {
                            if (connected && token == generation) {
                                closeSession()
                                Toast.makeText(this,"已保存到截图剪贴板",Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (error: Exception) {
                        android.util.Log.w("VoxFix","SCREENSHOT_SAVE_FAILED",error)
                        val message = if (error is IllegalStateException || error is IllegalArgumentException) error.message ?: "截图保存失败，请重试" else "截图保存失败，请检查可用空间后重试"
                        main.post { if (connected && token == generation) { busy = false; screenshot?.failure(message) } }
                    } finally { input.recycle() }
                }
            } catch (error: Exception) { input.recycle(); throw error }
        } catch (error: Exception) { busy = false; screenshot?.failure(error.message ?: "截图保存失败，请重试") }
    }
}
