package local.voxfix.debug

import android.Manifest
import android.content.ClipboardManager
import android.content.ClipData
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
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

class VoxFixImeService : InputMethodService() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val pinyinLoader = Executors.newSingleThreadExecutor()
    @Volatile private var pinyin: PinyinEngine? = null
    @Volatile private var pinyinFailed = false
    private val session = InputSession()
    private val capturing = AtomicBoolean(false)
    @Volatile private var engine: SpeechEngine? = null
    private var web: WebView? = null
    @Volatile private var inputGeneration = 0L
    @Volatile private var shown = false
    @Volatile private var busy = false
    @Volatile private var destroyed = false
    private val releaseVoice = Runnable {
        worker.execute {
            if (!shown && !busy && !destroyed) {
                engine?.release(); engine = null
                Log.i("VoxFix", "IME_MODELS_RELEASED")
            }
        }
    }
    private var ticket = 0L
    private var voiceGeneration = 0L
    private var voiceStart = 0
    private var selectedBeforeVoice = ""
    private var streamedText = ""
    private var localVoice = false
    private val ownSelections = mutableSetOf<Int>()
    private var composingStart: Int? = null
    private var selectionStart = 0
    private var selectionEnd = 0

    override fun onEvaluateFullscreenMode() = false

    override fun onCreate() {
        super.onCreate()
        pinyinLoader.execute {
            try {
                preparePersonalData()
                val ready = PinyinEngine(assets, filesDir)
                main.post {
                    if (destroyed) ready.close()
                    else { pinyin = ready; script("globalThis.voxfixPinyinReady?.()") }
                }
            } catch (error: Throwable) {
                pinyinFailed = true
                Log.e("VoxFix", "IME_PINYIN_UNAVAILABLE", error)
                main.post { if (!destroyed) script("globalThis.voxfixPinyinReady?.(true)") }
            }
        }
        preloadVoice()
        scheduleVoiceRelease()
    }

    private fun preloadVoice() {
        main.removeCallbacks(releaseVoice)
        worker.execute {
            if (!destroyed && engine == null) try { engine = SpeechEngine(assets) }
            catch (error: Throwable) { Log.e("VoxFix", "IME_MODELS_UNAVAILABLE", error) }
        }
    }

    private fun scheduleVoiceRelease() {
        main.removeCallbacks(releaseVoice)
        if (!destroyed) main.postDelayed(releaseVoice, 5 * 60_000L)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND && !shown && !busy) {
            main.removeCallbacks(releaseVoice)
            releaseVoice.run()
        }
    }

    override fun onCreateInputView(): View {
        preloadVoice()
        destroyKeyboardView()
        return WebView(this).apply {
            web = this
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (351 * resources.displayMetrics.density).toInt())
            setBackgroundColor(0xffedeff2.toInt())
            isFocusable = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            addJavascriptInterface(KeyboardBridge(this), "VoxFixIme")
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
                        "txt" -> "text/plain"
                        else -> return missing()
                    }
                    return try { WebResourceResponse(mime, "UTF-8", openKeyboardAsset(path)) }
                    catch (_: java.io.IOException) { missing() }
                }
            }
            loadUrl("https://voxfix.local/keyboard/index.html")
        }
    }

    /** Stages the bundled personal dictionary and English list next to the Rime user data. */
    private fun preparePersonalData() {
        val directory = File(filesDir, "pinyin-personal")
        check(directory.mkdirs() || directory.isDirectory)
        for (name in listOf("voxfix_personal.dict.yaml", "personal-english.txt")) {
            val bytes = assets.open("pinyin-personal/$name").use { it.readBytes() }
            check(bytes.isNotEmpty() && bytes.size <= 1_000_000) { "Unexpected $name" }
            val target = File(directory, name)
            if (target.isFile && target.readBytes().contentEquals(bytes)) continue
            val temporary = File(directory, "$name.tmp")
            temporary.outputStream().use { it.write(bytes) }
            check(temporary.renameTo(target)) { "Cannot stage $name" }
        }
    }

    /** The personal English list is bundled too, so a first run works before staging finishes. */
    private fun openKeyboardAsset(path: String): java.io.InputStream {
        if (path != "keyboard/personal-english.txt") return assets.open(path)
        val staged = File(filesDir, "pinyin-personal/personal-english.txt")
        return if (staged.isFile) staged.inputStream() else assets.open("pinyin-personal/personal-english.txt")
    }

    private fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun destroyKeyboardView() {
        val view = web ?: return
        web = null
        (view.parent as? ViewGroup)?.removeView(view)
        view.removeJavascriptInterface("VoxFixIme")
        view.destroy()
    }
    private fun restoreKeyboardVisibility(view: WebView) {
        // K60 can leave Chromium hidden while the IME window is visible; invalidation alone cannot recover it.
        if (web === view && shown && view.isShown) view.dispatchWindowVisibilityChanged(View.VISIBLE)
    }
    private fun script(code: String) { web?.evaluateJavascript(code, null) }
    private fun voiceDone(message: String = "") { script("globalThis.voxfixVoiceDone?.(${JSONObject.quote(message)})") }
    private fun resetKeyboard() {
        script("globalThis.voxfixReset?.()")
        script("globalThis.voxfixInputType?.(${currentInputEditorInfo?.inputType ?: 1})")
    }
    private fun clearComposition(keepEnglish: Boolean = false) { if (!keepEnglish) composingStart = null; script("globalThis.voxfixClearComposition?.($keepEnglish)") }
    private fun commitInputText(value: String): Boolean {
        val connection = currentInputConnection ?: return false
        val extracted = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 1 }, 0)
        val end = composingStart?.plus(value.length) ?: extracted?.let { it.startOffset + minOf(it.selectionStart, it.selectionEnd) + value.length }
        if (!connection.commitText(value, 1)) return false
        composingStart = null
        if (end != null) { if (ownSelections.size >= 100) ownSelections.clear(); ownSelections.add(end) }
        return true
    }
    private fun startKeyboard(info: EditorInfo, restarting: Boolean) { script("globalThis.voxfixStartInput?.(${info.inputType},$restarting,${info.imeOptions})") }
    private fun updateClipboard(explicit: Boolean = false) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty().take(10000)
        val signature = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        script("globalThis.voxfixClipboard?.(${JSONObject.quote(text)},${JSONObject.quote(signature)},$explicit)")
    }

    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        super.onStartInput(info, restarting)
        if (!restarting) {
            inputGeneration++
            ownSelections.clear()
            composingStart = null
            cancelRecording()
        }
        startKeyboard(info, restarting)
        selectionStart = info.initialSelStart
        selectionEnd = info.initialSelEnd
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shown = true
        preloadVoice()
        startKeyboard(info, restarting)
        updateClipboard()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        web?.let { view -> view.post {
            if (web === view) {
                restoreKeyboardVisibility(view)
                view.postInvalidateOnAnimation()
            }
        } }
        script("globalThis.voxfixRedraw?.()")
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        shown = false
        inputGeneration++
        cancelRecording()
        resetKeyboard()
        scheduleVoiceRelease()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        shown = false
        inputGeneration++
        cancelRecording()
        scheduleVoiceRelease()
        super.onFinishInput()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selectionStart = newSelStart
        selectionEnd = newSelEnd
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) {
            if (session.accepts(ticket) && !localVoice) {
                if (InputSession.isVoiceSelection(voiceStart, streamedText.isNotEmpty(), newSelStart, newSelEnd, candidatesStart, candidatesEnd)) return
                cancelRecording()
                voiceDone("输入位置已变化，本次语音已结束")
            }
            if (candidatesStart < 0) {
                val own = newSelStart == newSelEnd && ownSelections.remove(newSelStart)
                if (!own) ownSelections.clear()
                clearComposition(own)
            }
            else if (newSelStart < candidatesStart || newSelEnd > candidatesEnd) {
                currentInputConnection?.finishComposingText()
                clearComposition()
            }
        }
    }

    private fun cancelRecording(restoreSelection: Boolean = false) {
        if (!localVoice && session.accepts(ticket) && streamedText.isNotEmpty() && voiceGeneration == inputGeneration) {
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
        localVoice = false
    }

    private fun beginVoice(local: Boolean = false) {
        if (busy) { voiceDone("上一段语音正在结束，请稍后再试"); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceDone("请打开 VoxFix，允许麦克风权限后再使用语音")
            return
        }
        val type = currentInputEditorInfo?.inputType ?: 0
        val kind = type and 15
        val variation = type and 4080
        if (!local && (kind == 1 && variation in listOf(128, 144, 224) || kind == 2 && variation == 16)) {
            voiceDone("密码输入框不启用语音")
            return
        }
        val connection = currentInputConnection ?: run { voiceDone(); return }
        if (!local) { connection.finishComposingText(); composingStart = null }
        selectedBeforeVoice = if (local) "" else connection.getSelectedText(0)?.toString().orEmpty()
        if (selectedBeforeVoice.length > 10000) { selectedBeforeVoice = ""; voiceDone("选中文字过长，请先取消选区再说话"); return }
        val extracted = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 1 }, 0)
        voiceStart = extracted?.let { it.startOffset + minOf(it.selectionStart, it.selectionEnd) } ?: minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        streamedText = ""
        localVoice = local
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
                                if (local) { script("globalThis.voxfixVoiceText?.(${JSONObject.quote(text)})"); updates++ }
                                else if (currentInputConnection?.setComposingText(text, 1) == true) updates++
                            }
                        }
                    }, { level -> main.post {
                        if (session.accepts(current) && shown && inputGeneration == targetGeneration) script("globalThis.voxfixVoiceLevel?.($level)")
                    } }, { capturing.get() && session.accepts(current) }, { !session.accepts(current) || inputGeneration != targetGeneration })
                }
                capturing.set(false)
                val result = received
                main.post {
                    if (shown && inputGeneration == targetGeneration && session.consume(current)) {
                        if (result.isNotBlank()) {
                            val written = if (local) { script("globalThis.voxfixVoiceText?.(${JSONObject.quote(result)})"); true }
                            else currentInputConnection?.commitText(result, 1) == true
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
                        if (retained.isNotBlank()) {
                            if (local) script("globalThis.voxfixVoiceText?.(${JSONObject.quote(retained)})")
                            else currentInputConnection?.commitText(retained, 1)
                        }
                        voiceDone(if (retained.isBlank()) "录音或识别失败，请重试" else "已保留识别结果")
                        streamedText = ""; selectedBeforeVoice = ""
                    }
                }
            } finally { main.post { busy = false; if (!shown) scheduleVoiceRelease() } }
        }
    }

    inner class KeyboardBridge(private val owner: WebView) {
        private fun inputReply(action: () -> String): String {
            val generation = inputGeneration
            val result = FutureTask {
                if (web !== owner || !shown || generation != inputGeneration) "{}"
                else try { action() } catch (error: Exception) { Log.e("VoxFix", "IME_INPUT_REPLY_FAILED", error); "{}" }
            }
            main.post(result)
            return result.get()
        }
        private fun editInput(action: () -> Unit) {
            val generation = inputGeneration
            main.post { if (web === owner && shown && generation == inputGeneration) action() }
        }
        @JavascriptInterface fun ready() { main.post { if (web === owner) { currentInputEditorInfo?.let { startKeyboard(it, false) } ?: resetKeyboard(); updateClipboard(); if (pinyinFailed) script("globalThis.voxfixPinyinReady?.(true)") } } }
        @JavascriptInterface fun pinyinReady() = pinyin != null
        @JavascriptInterface fun pinyinCandidates(value: String, limit: Int): String =
            if (web !== owner || !value.matches(Regex("[a-z']{1,100}"))) "{}"
            else try { pinyin?.candidates(value, limit.coerceIn(1, 2000)) ?: "{}" }
            catch (error: Exception) { Log.e("VoxFix", "IME_PINYIN_QUERY_FAILED", error); "{}" }
        @JavascriptInterface fun pinyinChoose(value: String, word: String, learn: Boolean): String {
            if (!value.matches(Regex("[a-z']{1,100}")) || word.isEmpty() || word.length > 200) return "{}"
            return inputReply { pinyin?.choose(value, word, learn, ::commitInputText) ?: "{}" }
        }
        @JavascriptInterface fun commitAccepted(value: String): String = if (value.length > 10000) "{}"
            else inputReply { JSONObject().put("committed", commitInputText(value)).toString() }
        @JavascriptInterface fun pinyinClearLearning(): String = inputReply {
            val ready = pinyin ?: return@inputReply "{}"
            ready.clearLearning()
            JSONObject().put("cleared", true).toString()
        }
        @JavascriptInterface fun refreshClipboard() = editInput { updateClipboard() }
        @JavascriptInterface fun importClipboard() = editInput { updateClipboard(true) }
        @JavascriptInterface fun copy(value: String) { if (value.length <= 10000) editInput { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("VoxFix", value)); updateClipboard() } }
        @JavascriptInterface fun voiceReady() = engine != null
        @JavascriptInterface fun handReady() = false
        @JavascriptInterface fun resize(height: Int) {
            if (height !in 220..550) return
            main.post { if (web === owner) owner.let { view ->
                val pixels = (height * resources.displayMetrics.density).toInt()
                if (view.layoutParams.height != pixels) view.layoutParams = view.layoutParams.apply { this.height = pixels }
                restoreKeyboardVisibility(view)
                // Wait for the new DOM to be drawable before invalidating the IME window.
                // 键盘隐藏期间也要照常请求重绘：否则这段时间的 DOM 变化会被丢掉，重新显示时屏幕上会残留旧页。
                view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { if (web === view) view.postInvalidateOnAnimation() }
                })
            } }
        }
        @JavascriptInterface fun compose(value: String) { if (value.length <= 1000) editInput {
            val connection = currentInputConnection ?: return@editInput
            if (composingStart == null) {
                val text = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 1 }, 0)
                composingStart = text?.let { it.startOffset + minOf(it.selectionStart, it.selectionEnd) }
            }
            connection.setComposingText(value, 1)
            if (value.isEmpty()) composingStart = null
        } }
        @JavascriptInterface fun commit(value: String) { if (value.length <= 10000) editInput { commitInputText(value) } }
        @JavascriptInterface fun completeEnglish(prefix: String, word: String) {
            if (!prefix.matches(Regex("[A-Za-z]{1,60}")) || !word.matches(Regex("[A-Za-z][A-Za-z'-]{0,59}"))) return
            editInput {
                val connection = currentInputConnection ?: return@editInput
                if (connection.getTextBeforeCursor(prefix.length, 0)?.toString() != prefix || !connection.getSelectedText(0).isNullOrEmpty()) return@editInput
                connection.beginBatchEdit()
                try { connection.deleteSurroundingText(prefix.length, 0); connection.commitText(word, 1) }
                finally { connection.endBatchEdit() }
            }
        }
        @JavascriptInterface fun delete() = editInput {
            val connection = currentInputConnection ?: return@editInput
            val selected = !connection.getSelectedText(0).isNullOrEmpty()
            val extracted = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 1 }, 0)
            val start = extracted?.let { it.startOffset + minOf(it.selectionStart, it.selectionEnd) }
            val end = if (selected) start else if (connection.getTextBeforeCursor(1, 0)?.toString()?.matches(Regex("[A-Za-z]")) == true) start?.minus(1) else null
            if (selected) { connection.commitText("", 1); composingStart = null }
            else { connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)); connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)) }
            if (end != null && end >= 0) {
                if (ownSelections.size >= 100) ownSelections.clear()
                ownSelections.add(end)
            }
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
        @JavascriptInterface fun startLocalVoice() = editInput { beginVoice(true) }
        @JavascriptInterface fun stopVoice() = editInput { capturing.set(false); engine?.stop() }
        @JavascriptInterface fun cancelVoice() = editInput { cancelRecording(true) }
        @JavascriptInterface fun cursorPosition() = editInput {
            currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)?.let { text -> script("globalThis.voxfixCursor?.(${text.selectionEnd},${text.text?.length ?: 0})") }
        }
        @JavascriptInterface fun moveCursor(amount: Int) = editInput {
            if (amount == 0) return@editInput
            val connection = currentInputConnection ?: return@editInput
            val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
            val text = extracted?.text?.toString()
            if (extracted != null && text != null && extracted.selectionStart in 0..text.length && extracted.selectionEnd in 0..text.length) {
                val boundaries = android.icu.text.BreakIterator.getCharacterInstance()
                boundaries.setText(text)
                var position = if (amount < 0) minOf(extracted.selectionStart, extracted.selectionEnd) else maxOf(extracted.selectionStart, extracted.selectionEnd)
                if (extracted.selectionStart != extracted.selectionEnd) {
                    connection.setSelection(extracted.startOffset + position, extracted.startOffset + position)
                    script("globalThis.voxfixCursor?.($position,${text.length})")
                    return@editInput
                }
                repeat(kotlin.math.abs(amount.coerceIn(-100, 100))) {
                    val next = if (amount < 0) boundaries.preceding(position) else boundaries.following(position)
                    if (next != android.icu.text.BreakIterator.DONE) position = next
                }
                connection.setSelection(extracted.startOffset + position, extracted.startOffset + position)
                script("globalThis.voxfixCursor?.($position,${text.length})")
                return@editInput
            }
            val key = if (amount < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(kotlin.math.abs(amount.coerceIn(-100, 100))) {
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            }
        }
        // 底部滑轨切入「框选文字」的那一瞬间给一次轻触感提示。
        // performHapticFeedback 不需要 VIBRATE 权限，且自动跟随系统的触感反馈开关与强度，
        // 系统关掉就只留视觉反馈，和官方保持一致。
        @JavascriptInterface fun haptic() {
            val view = web ?: return
            main.post { view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
        }
        // 底部无极滑轨：拖动移动光标，长按后拖动框选文字。
        // 锚点在手势开始时只记一次，之后每次拖动都按「相对锚点的字符步数」重算绝对位置，
        // 来回拖动不会累积误差；框选时保留非折叠选区，宿主输入框会自己弹出复制/粘贴浮框。
        private var gestureAnchor = -1
        private var gestureSelecting = false
        private fun graphemeStep(text: String, from: Int, steps: Int): Int {
            val boundaries = android.icu.text.BreakIterator.getCharacterInstance()
            boundaries.setText(text)
            var position = from.coerceIn(0, text.length)
            repeat(kotlin.math.abs(steps.coerceIn(-400, 400))) {
                val next = if (steps < 0) boundaries.preceding(position) else boundaries.following(position)
                if (next == android.icu.text.BreakIterator.DONE) return position
                position = next
            }
            return position
        }
        @JavascriptInterface fun gestureStart(selecting: Boolean) = editInput {
            val extracted = currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)
            gestureAnchor = extracted?.let {
                it.startOffset + if (selecting) minOf(it.selectionStart, it.selectionEnd) else maxOf(it.selectionStart, it.selectionEnd)
            } ?: -1
            gestureSelecting = selecting
        }
        @JavascriptInterface fun gestureMove(steps: Int) = editInput {
            val anchor = gestureAnchor
            if (anchor < 0) return@editInput
            val connection = currentInputConnection ?: return@editInput
            val extracted = connection.getExtractedText(ExtractedTextRequest(), 0) ?: return@editInput
            val text = extracted.text?.toString() ?: return@editInput
            val offset = anchor - extracted.startOffset
            if (offset !in 0..text.length) return@editInput
            val head = graphemeStep(text, offset, steps)
            if (gestureSelecting) {
                connection.setSelection(extracted.startOffset + minOf(offset, head), extracted.startOffset + maxOf(offset, head))
                script("globalThis.voxfixCursor?.($head,${text.length})")
            } else {
                connection.setSelection(extracted.startOffset + head, extracted.startOffset + head)
                script("globalThis.voxfixCursor?.($head,${text.length})")
            }
        }
        @JavascriptInterface fun gestureEnd() = editInput { gestureAnchor = -1; gestureSelecting = false }
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
        destroyed = true
        main.removeCallbacks(releaseVoice)
        shown = false
        cancelRecording()
        destroyKeyboardView()
        pinyin?.close(); pinyin = null
        pinyinLoader.shutdown()
        worker.execute { engine?.release() }
        worker.shutdown()
        super.onDestroy()
    }
}
