package local.voxfix.debug

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import android.view.inputmethod.ExtractedTextRequest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VoxFixImeService : InputMethodService() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val session = InputSession()
    private val capturing = AtomicBoolean(false)
    @Volatile private var engine: SpeechEngine? = null
    private var normalizer: TextNormalizer? = null
    @Volatile private var handwriting: HandwritingEngine? = null
    private var inkRequest = -1
    private var web: WebView? = null
    @Volatile private var inputGeneration = 0L
    @Volatile private var shown = false
    private var busy = false
    private var ticket = 0L
    private var voiceGeneration = 0L
    private var voiceStart = 0
    private var selectedBeforeVoice = ""
    private var streamedText = ""
    private var selectionStart = 0
    private var selectionEnd = 0

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        worker.execute {
            try {
                if (engine == null) engine = SpeechEngine(assets)
                try { normalizer = TextNormalizer(assets, File(filesDir, "wetext-0.1.8")) }
                catch (error: Throwable) { Log.e("VoxFix", "IME_NORMALIZER_UNAVAILABLE", error) }
                try { handwriting = HandwritingEngine(this) }
                catch (error: Throwable) { Log.e("VoxFix", "IME_HANDWRITING_UNAVAILABLE", error) }
            }
            catch (error: Throwable) { Log.e("VoxFix", "IME_MODELS_UNAVAILABLE", error) }
        }
        return WebView(this).apply {
            web = this
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (312 * resources.displayMetrics.density).toInt())
            setBackgroundColor(0xffedeef2.toInt())
            isFocusable = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            addJavascriptInterface(KeyboardBridge(), "VoxFixIme")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val uri = request.url
                    val path = uri.path.orEmpty().removePrefix("/")
                    if (uri.scheme != "https" || uri.host != "voxfix.local" || !path.startsWith("keyboard/") || path.contains("..")) return missing()
                    val mime = when (path.substringAfterLast('.')) {
                        "html" -> "text/html"
                        "css" -> "text/css"
                        "mjs" -> "text/javascript"
                        "json" -> "application/json"
                        else -> return missing()
                    }
                    return try { WebResourceResponse(mime, "UTF-8", assets.open(path)) }
                    catch (_: java.io.IOException) { missing() }
                }
            }
            loadUrl("https://voxfix.local/keyboard/index.html")
        }
    }

    private fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun script(code: String) { web?.evaluateJavascript(code, null) }
    private fun voiceDone(message: String = "") { script("globalThis.voxfixVoiceDone?.(${JSONObject.quote(message)})") }
    private fun resetKeyboard() {
        script("globalThis.voxfixReset?.()")
        script("globalThis.voxfixInputType?.(${currentInputEditorInfo?.inputType ?: 1})")
    }
    private fun clearComposition() { script("globalThis.voxfixClearComposition?.()") }
    private fun updateClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty().take(10000)
        script("globalThis.voxfixClipboard?.(${JSONObject.quote(text)})")
    }

    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        super.onStartInput(info, restarting)
        inputGeneration++
        cancelRecording()
        resetKeyboard()
        selectionStart = info.initialSelStart
        selectionEnd = info.initialSelEnd
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shown = true
        resetKeyboard()
        updateClipboard()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        shown = false
        inputGeneration++
        cancelRecording()
        resetKeyboard()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        shown = false
        inputGeneration++
        cancelRecording()
        super.onFinishInput()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selectionStart = newSelStart
        selectionEnd = newSelEnd
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) {
            if (session.accepts(ticket)) {
                if (InputSession.isVoiceSelection(voiceStart, streamedText.isNotEmpty(), newSelStart, newSelEnd, candidatesStart, candidatesEnd)) return
                cancelRecording()
                voiceDone("输入位置已变化，本次语音已结束")
            }
            if (candidatesStart < 0) clearComposition()
            else if (newSelStart < candidatesStart || newSelEnd > candidatesEnd) {
                currentInputConnection?.finishComposingText()
                clearComposition()
            }
        }
    }

    private fun cancelRecording(restoreSelection: Boolean = false) {
        if (session.accepts(ticket) && streamedText.isNotEmpty() && voiceGeneration == inputGeneration) {
            currentInputConnection?.let { connection ->
                if (restoreSelection) {
                    connection.setComposingText(selectedBeforeVoice, 1)
                    if (selectedBeforeVoice.isNotEmpty()) connection.setSelection(voiceStart, voiceStart + selectedBeforeVoice.length)
                }
                connection.finishComposingText()
            }
        }
        session.close()
        capturing.set(false)
        engine?.stop()
        streamedText = ""
        selectedBeforeVoice = ""
    }

    private fun beginVoice() {
        if (busy) { voiceDone("上一段语音正在结束，请稍后再试"); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceDone("请打开 VoxFix，允许麦克风权限后再使用语音")
            return
        }
        val type = currentInputEditorInfo?.inputType ?: 0
        val kind = type and 15
        val variation = type and 4080
        if (kind == 1 && variation in listOf(128, 144, 224) || kind == 2 && variation == 16) {
            voiceDone("密码输入框不启用语音")
            return
        }
        val connection = currentInputConnection ?: run { voiceDone(); return }
        connection.finishComposingText()
        selectedBeforeVoice = connection.getSelectedText(0)?.toString().orEmpty()
        if (selectedBeforeVoice.length > 10000) { selectedBeforeVoice = ""; voiceDone("选中文字过长，请先取消选区再说话"); return }
        val extracted = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 1 }, 0)
        voiceStart = extracted?.let { it.startOffset + minOf(it.selectionStart, it.selectionEnd) } ?: minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        streamedText = ""
        voiceGeneration = inputGeneration
        val targetGeneration = voiceGeneration
        busy = true
        val current = session.open()
        ticket = current
        capturing.set(true)
        Log.i("VoxFix", "IME_STREAM_STARTED")
        var updates = 0
        worker.execute {
            var received = ""
            try {
                if (engine == null) engine = SpeechEngine(assets)
                if (capturing.get() && session.accepts(current)) {
                    received = checkNotNull(engine).record({ text ->
                        received = text
                        if (text.isNotBlank()) main.post {
                            if (session.accepts(current) && shown && inputGeneration == targetGeneration) {
                                streamedText = text
                                if (currentInputConnection?.setComposingText(text, 1) == true) updates++
                            }
                        }
                    }, { level -> main.post {
                        if (session.accepts(current) && shown && inputGeneration == targetGeneration) script("globalThis.voxfixVoiceLevel?.($level)")
                    } }, { capturing.get() && session.accepts(current) })
                }
                capturing.set(false)
                val result = try { normalizer?.format(received) ?: received }
                catch (error: Throwable) { Log.e("VoxFix", "IME_NORMALIZE_RETAINED", error); received }
                main.post {
                    if (shown && inputGeneration == targetGeneration && session.consume(current)) {
                        if (result.isNotBlank()) {
                            val written = currentInputConnection?.commitText(result, 1) == true
                            Log.i("VoxFix", "IME_STREAM_FINISHED updates=$updates characters=${result.length} committed=$written")
                            voiceDone(if (written) "" else "当前输入框未接受文字")
                        } else voiceDone("没有识别到文字")
                        streamedText = ""; selectedBeforeVoice = ""
                    }
                }
            } catch (error: Throwable) {
                Log.e("VoxFix", "IME_CAPTURE_FAILED", error)
                val retained = received
                main.post {
                    if (shown && inputGeneration == targetGeneration && session.consume(current)) {
                        if (retained.isNotBlank()) currentInputConnection?.commitText(retained, 1)
                        voiceDone(if (retained.isBlank()) "录音或识别失败，请重试" else "已保留识别结果")
                        streamedText = ""; selectedBeforeVoice = ""
                    }
                }
            } finally { main.post { busy = false } }
        }
    }

    inner class KeyboardBridge {
        private fun editInput(action: () -> Unit) {
            val generation = inputGeneration
            main.post { if (shown && generation == inputGeneration) action() }
        }
        @JavascriptInterface fun ready() { main.post { resetKeyboard(); updateClipboard() } }
        @JavascriptInterface fun refreshClipboard() = editInput { updateClipboard() }
        @JavascriptInterface fun voiceReady() = engine != null
        @JavascriptInterface fun handReady() = handwriting != null
        @JavascriptInterface fun recognizeInk(serial: Int, payload: String) = editInput {
            if (serial < 0 || payload.length > 100000) return@editInput
            inkRequest = serial
            val generation = inputGeneration
            worker.execute {
                if (generation != inputGeneration) return@execute
                val words = try { checkNotNull(handwriting).recognize(payload) }
                catch (error: Throwable) {
                    Log.e("VoxFix", "IME_INK_FAILED", error)
                    main.post { if (generation == inputGeneration && inkRequest == serial) script("globalThis.voxfixInkResult?.($serial,[],\"手写识别失败，请重新书写\")") }
                    return@execute
                }
                main.post { if (shown && generation == inputGeneration && inkRequest == serial) script("globalThis.voxfixInkResult?.($serial,${JSONArray(words)},null)") }
            }
        }
        @JavascriptInterface fun resize(height: Int) {
            if (height !in 220..550) return
            main.post { web?.let { view -> val pixels = (height * resources.displayMetrics.density).toInt(); if (view.layoutParams.height != pixels) { view.layoutParams = view.layoutParams.apply { this.height = pixels } } } }
        }
        @JavascriptInterface fun compose(value: String) { if (value.length <= 1000) editInput { currentInputConnection?.setComposingText(value, 1) } }
        @JavascriptInterface fun commit(value: String) { if (value.length <= 10000) editInput { currentInputConnection?.commitText(value, 1) } }
        @JavascriptInterface fun delete() = editInput {
            val connection = currentInputConnection ?: return@editInput
            if (!connection.getSelectedText(0).isNullOrEmpty()) connection.commitText("", 1)
            else { connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)); connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)) }
        }
        @JavascriptInterface fun enter() = editInput {
            val info = currentInputEditorInfo ?: return@editInput
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) currentInputConnection?.performEditorAction(action)
            else currentInputConnection?.commitText("\n", 1)
        }
        @JavascriptInterface fun hide() = editInput { requestHideSelf(0) }
        @JavascriptInterface fun switcher() = editInput { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        @JavascriptInterface fun startVoice() = editInput { beginVoice() }
        @JavascriptInterface fun stopVoice() = editInput { capturing.set(false); engine?.stop() }
        @JavascriptInterface fun cancelVoice() = editInput { cancelRecording(true) }
        @JavascriptInterface fun moveCursor(amount: Int) = editInput {
            val key = if (amount < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(kotlin.math.abs(amount.coerceIn(-100, 100))) {
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            }
        }
        @JavascriptInterface fun edit(action: String) = editInput {
            val id = when (action) {
                "select-all" -> android.R.id.selectAll
                "copy-text" -> android.R.id.copy
                "cut-text" -> android.R.id.cut
                "cursor-left", "cursor-right" -> { moveCursor(if (action == "cursor-left") -1 else 1); return@editInput }
                else -> return@editInput
            }
            currentInputConnection?.performContextMenuAction(id)
            updateClipboard()
        }
    }

    override fun onDestroy() {
        shown = false
        cancelRecording()
        web?.removeJavascriptInterface("VoxFixIme")
        web?.destroy()
        web = null
        worker.execute { engine?.release(); normalizer?.release(); handwriting?.release() }
        worker.shutdown()
        super.onDestroy()
    }
}
