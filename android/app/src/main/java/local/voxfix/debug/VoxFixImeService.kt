package local.voxfix.debug

import android.Manifest
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.widget.PopupWindow
import android.widget.FrameLayout
import android.widget.TextView
import android.view.KeyCharacterMap
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
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.CursorAnchorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import android.view.inputmethod.ExtractedTextRequest
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

class VoxFixImeService : InputMethodService() {
    private val lexicon by lazy { PersonalLexicon(this) }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val pinyinLoader = Executors.newSingleThreadExecutor()
    // 收起键盘后的选区入口诊断：只打端点/时长/分支，不打正文。
    private val selTag = "VoxFixSel"
    @Volatile private var pinyin: PinyinEngine? = null
    @Volatile private var pinyinFailed = false
    private val session = InputSession()
    private val capturing = AtomicBoolean(false)
    @Volatile private var engine: SpeechEngine? = null
    @Volatile private var web: WebView? = null
    @Volatile private var microphoneCapture: PcmCapture? = null
    private var clipHint: PopupWindow? = null
    /** 最近一次 resize() 请求的键盘像素高度。标签定位只认它，不认 view.height：
     *  网页在同一帧里先调 clipHint() 再调 resize()，此时 layoutParams 刚改、layout 还没跑，
     *  view.height 是旧值，拿它定位标签会差一整轮键盘高度。 */
    private var keyboardHeightPx = 0
    @Volatile private var inputGeneration = 0L
    @Volatile private var shown = false
    @Volatile private var busy = false
    @Volatile private var destroyed = false
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        main.post { if (shown && !destroyed) updateClipboard() }
    }
    private val releaseVoice = Runnable {
        worker.execute {
            if (!busy && !destroyed && engine != null) {
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
    /** 「先上屏、后改写」：松手立刻结束录音，composing 区域留一小段窗口等二遍回来。 */
    private var voiceFinalText = ""
    private var voiceRefineArmed = false
    private val finishVoiceComposition = Runnable {
        if (!voiceRefineArmed) return@Runnable
        voiceRefineArmed = false
        val text = voiceFinalText
        voiceFinalText = ""
        val connection = currentInputConnection
        if (text.isNotBlank() && connection != null) {
            val kept = connection.getTextBeforeCursor(text.length, 0)?.toString() == text
            connection.finishComposingText()
            if (kept && !localVoice) armVoiceLearning(text)
        }
        streamedText = ""
        selectedBeforeVoice = ""
    }
    private var localVoice = false
    private var searchInputActive = false
    private val ownSelections = mutableSetOf<Int>()
    private var composingStart: Int? = null
    private var clipboardSplit: ClipboardSplitState? = null
    private var clipboardSplitId = 0
    private var selectionStart = 0
    private var selectionEnd = 0
    private val selectionDrag = SelectionDrag()
    private var selectionNavigation: SelectionNavigation? = null
    private var selectionLockedAnchor = -1
    private var selectionGestureActive = false
    private var ownSelectionUntil = 0L
    private var hostSelectionOpen: Runnable? = null
    private val hiddenSelection = HostSelectionRequest()
    /** 标注浮层：锁定期间把起点/范围两端画到正文上（宿主自己的手柄这时不会出现）。 */
    private val annotation by lazy { AnnotationOverlay(this).also { it.onHandleDrag = ::onAnnotationDrag } }
    private var annotationDragFixed = -1
    private var annotationDragging = false
    private var anchorFrames = 0
    private var anchorAttempt = 0

    override fun onEvaluateFullscreenMode() = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(clipboardListener)
        pinyinLoader.execute {
            try {
                preparePersonalData()
                val ready = PinyinEngine(assets, filesDir)
                try {
                    if (!lexicon.nativeMigrationComplete()) {
                        val replacements = mutableMapOf<String, String>()
                        for (name in listOf("TSCharacters.txt", "TSPhrases.txt")) {
                            assets.open("pinyin/opencc/$name").bufferedReader().useLines { lines ->
                                lines.filter { it.contains('\t') && !it.startsWith("#") }.forEach { line ->
                                    val pair = line.split('\t'); replacements[pair[0]] = pair[1].substringBefore(' ')
                                }
                            }
                        }
                        val maxLength = replacements.keys.maxOfOrNull { it.length } ?: 1
                        lexicon.migrateNative(filesDir) { text -> buildString {
                            var offset = 0
                            while (offset < text.length) {
                                val count = (minOf(maxLength, text.length - offset) downTo 1).firstOrNull { text.substring(offset, offset + it) in replacements } ?: 1
                                append(replacements[text.substring(offset, offset + count)] ?: text.substring(offset, offset + count)); offset += count
                            }
                        } }
                    }
                } catch (error: Throwable) { ready.close(); throw error }
                main.post {
                    if (destroyed) ready.close()
                    else {
                        pinyin = ready; script("globalThis.voxfixPinyinReady?.()")
                        pinyinLoader.execute { try { lexicon.cleanupLegacy(filesDir) } catch (error: Exception) { Log.w("VoxFix", "Legacy lexicon cleanup deferred", error) } }
                    }
                }
            } catch (error: Throwable) {
                pinyinFailed = true
                Log.e("VoxFix", "IME_PINYIN_UNAVAILABLE", error)
                main.post { if (!destroyed) script("globalThis.voxfixPinyinReady?.(true)") }
            }
        }
    }

    private fun scheduleVoiceRelease() {
        main.removeCallbacks(releaseVoice)
        if (!destroyed && !busy && engine != null) main.postDelayed(releaseVoice, 30_000L)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND && !busy) {
            main.removeCallbacks(releaseVoice)
            releaseVoice.run()
        }
    }

    override fun onCreateInputView(): View {
        destroyKeyboardView()
        keyboardHeightPx = (351 * resources.displayMetrics.density).toInt()
        val keyboard = WebView(this).apply {
            web = this
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
        // 固定 Chromium 画布，只裁切外层：避免窗口扩高时先搬动尚未重绘的旧键盘帧。
        return object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(keyboardHeightPx, View.MeasureSpec.EXACTLY))
            }
        }.apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeightPx)
            addView(keyboard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (550 * resources.displayMetrics.density).toInt(), Gravity.BOTTOM))
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
        lexicon.personalEnglish()?.let { return it.byteInputStream(Charsets.UTF_8) }
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

    /**
     * 「来自剪贴板」标签。官方小米版把它做成挂在 IME 窗口上的独立 popup 窗口，浮在键盘上沿之上：
     * K60 实测 window frame [0,1870][576,1971]、ty=APPLICATION_PANEL、fl=NOT_FOCUSABLE NOT_TOUCHABLE，
     * 可见标签 126.3×28.86 CSS、左贴屏幕边、底部贴着候选条上沿那条线。
     * 关键：它不占键盘高度。画在 WebView 里会把键盘撑高 29dp，宿主输入框随之被顶掉 29dp 空间。
     */
    private fun hideClipHint() {
        clipHint?.let { if (it.isShowing) it.dismiss() }
        clipHint = null
    }

    private fun showClipHint(label: String) {
        val view = web
        if (!shown || label.isEmpty() || view == null || !view.isShown) { hideClipHint(); return }
        clipHint?.takeIf { it.isShowing }?.let { popup ->
            if ((popup.contentView as? TextView)?.text?.toString() == label) { repositionClipHint(); return }
            hideClipHint()
        }
        val density = resources.displayMetrics.density
        val pillHeight = (PILL_HEIGHT_DP * density).toInt()
        val pill = TextView(this).apply {
            setTextColor(0xff000000.toInt())
            textSize = PILL_TEXT_SP
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            val horizontal = (PILL_PADDING_X_DP * density).toInt()
            setPadding(horizontal, 0, horizontal, 0)
            minWidth = (PILL_MIN_WIDTH_DP * density).toInt()
            maxWidth = ((screenWidthDp() - 24).coerceAtLeast(160) * density).toInt()
            text = label
            background = ClipHintBackground(4f * density, CLIP_HINT_FILL, CLIP_HINT_BORDER, density)
        }
        val popup = PopupWindow(pill, ViewGroup.LayoutParams.WRAP_CONTENT, pillHeight).apply {
            isFocusable = false
            isTouchable = false
            setBackgroundDrawable(null)
            elevation = 0f
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        clipHint = popup
        // 从 WebView 底边（= 屏幕底边）往上推「键盘高度 + 标签高度」：标签底边正好压在键盘上沿，
        // 与官方 window frame 一致（K60：标签 [0,1870][576,1971] px，键盘上沿 1971 px）。
        popup.showAsDropDown(view, 0, clipHintOffset())
    }

    /** 标签锚在 WebView 底边，向上偏移「键盘高度 + 标签高度」。 */
    private fun clipHintOffset(): Int {
        val density = resources.displayMetrics.density
        val pillHeight = (PILL_HEIGHT_DP * density).toInt()
        val keyboard = if (keyboardHeightPx > 0) keyboardHeightPx else (web?.height ?: 0)
        return -(keyboard + pillHeight)
    }

    private fun screenWidthDp(): Int = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()

    /** 另一侧：标签已弹出但键盘高度变了（面板切换、候选行增减）时，只挪位置不重建窗口。 */
    private fun repositionClipHint() {
        val view = web ?: return
        val popup = clipHint?.takeIf { it.isShowing } ?: return
        popup.update(view, 0, clipHintOffset(), ViewGroup.LayoutParams.WRAP_CONTENT, popup.height)
    }

    private companion object {
        /** 松手后 composing 区域还能被二遍改写多久；超时就按实时文字定稿。 */
        private const val VOICE_REFINE_WINDOW_MS = 420L
        /**
         * K60 实测官方标签：可见 126.3×28.86 CSS。
         * 19.4 是从官方墨迹反推的：官方 ink 宽 94.57、高 17.14 CSS，我们 20sp 量到 97.43/17.71，
         * 长宽两个方向都恰差 3%（= 720 基准的 34 单位 × 0.5714），所以官方字号是 19.4 而不是 20。
         * 12.1 是「文字推进框」到边的距离——肉眼量的 15.71 是**墨迹**起点，已经含了 1px 描边
         * 和汉字两侧各 ~2.6px 的字面留白，照抄 15.71 会让标签胖 7dp。
         */
        private const val PILL_HEIGHT_DP = 28.86f
        private const val CONTEXT_CHARS = 80
        private const val ANCHOR_RETRY_MS = 900L
        private const val PILL_TEXT_SP = 19.4f
        private const val PILL_PADDING_X_DP = 12.1f
        private const val PILL_MIN_WIDTH_DP = 126.3f
        private const val CLIP_HINT_FILL = 0xfff4f6f9.toInt()
        private const val CLIP_HINT_BORDER = 0xffdddee2.toInt()
    }

    /** 只描上、左、右三边的圆角矩形：官方标签底边压在候选条上沿的 1px 线上，自己不带下边。 */
    private class ClipHintBackground(
        private val radius: Float,
        fillColor: Int,
        strokeColor: Int,
        strokeWidth: Float,
    ) : Drawable() {
        private val outline = Path()
        private val corner = RectF()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor; style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor; style = Paint.Style.STROKE; this.strokeWidth = strokeWidth
        }

        override fun draw(canvas: Canvas) {
            // Drawable 没有 width/height，尺寸只从 bounds 里取；描边按半宽内缩，外沿正好落在 bounds 边上。
            val box = bounds
            val inset = stroke.strokeWidth / 2f
            val left = box.left + inset
            val top = box.top + inset
            val right = box.right - inset
            val bottom = box.bottom.toFloat()
            outline.reset()
            outline.moveTo(left, bottom)
            outline.lineTo(left, top + radius)
            corner.set(left, top, left + radius * 2, top + radius * 2)
            outline.arcTo(corner, 180f, 90f, false)
            outline.lineTo(right - radius, top)
            corner.set(right - radius * 2, top, right, top + radius * 2)
            outline.arcTo(corner, 270f, 90f, false)
            outline.lineTo(right, bottom)
            canvas.drawPath(outline, fill)
            canvas.drawPath(outline, stroke)
        }

        override fun setAlpha(alpha: Int) { fill.alpha = alpha; stroke.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; stroke.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun script(code: String) { web?.evaluateJavascript(code, null) }
    private fun voiceDone(message: String = "") { script("globalThis.voxfixVoiceDone?.(${JSONObject.quote(message)})") }
    private fun setSearchInputActive(active: Boolean) {
        searchInputActive = active
        if (android.os.Build.VERSION.SDK_INT >= 31) currentInputConnection?.setImeConsumesInput(active)
    }
    private fun focusHostEditor(selectionChanged: Boolean = false) {
        setSearchInputActive(false)
        script("globalThis.voxfixHostFocus?.($selectionChanged)")
    }
    private fun resetKeyboard() {
        annotation.hide()
        script("globalThis.voxfixReset?.()")
        script("globalThis.voxfixInputType?.(${currentInputEditorInfo?.inputType ?: 1})")
    }
    private fun clearComposition(keepEnglish: Boolean = false) { if (!keepEnglish) composingStart = null; script("globalThis.voxfixClearComposition?.($keepEnglish)") }
    private fun commitInputText(value: String, voiceEdit: Boolean = false): Boolean {
        val connection = currentInputConnection ?: return false
        if (searchInputActive) ownSelectionUntil = SystemClock.uptimeMillis() + 250
        val extracted = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 1 }, 0)
        val end = composingStart?.plus(value.length) ?: extracted?.let { it.startOffset + minOf(it.selectionStart, it.selectionEnd) + value.length }
        if (!connection.commitText(value, 1)) return false
        if (voiceLearning.active() && value.length <= 64) {
            voiceLearningComposing = false; voiceLearningEdited = true
            voiceLearningConfirmed = voiceEdit || value.any { it.isWhitespace() || it in ".,!?，。！？" }
            queueVoiceLearning()
        }
        selectionDrag.clear(); selectionLockedAnchor = -1; annotation.hide()
        composingStart = null
        if (end != null) { if (ownSelections.size >= 100) ownSelections.clear(); ownSelections.add(end) }
        return true
    }
    private fun splitMatches(connection: InputConnection, state: ClipboardSplitState): Boolean {
        val extracted = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 1 }, 0) ?: return false
        return state.matches(inputGeneration, extracted.startOffset + extracted.selectionStart,
            extracted.startOffset + extracted.selectionEnd, connection.getSelectedText(0)?.toString().orEmpty(),
            state.text?.let { connection.getTextBeforeCursor(it.length, 0)?.toString().orEmpty() }.orEmpty())
    }
    private fun finishClipboardSplit(): Boolean {
        val state = clipboardSplit ?: return false
        clipboardSplit = null
        val connection = currentInputConnection ?: return false
        if (!splitMatches(connection, state)) return false
        composingStart = null
        return connection.finishComposingText()
    }
    private fun startKeyboard(info: EditorInfo, restarting: Boolean) { script("globalThis.voxfixStartInput?.(${info.inputType},$restarting,${info.imeOptions})") }
    private fun updateClipboard(explicit: Boolean = false) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty().take(10000)
        val eventKey = "$text\u0000${clip?.description?.timestamp ?: 0L}"
        val signature = java.security.MessageDigest.getInstance("SHA-256").digest(eventKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        script("globalThis.voxfixClipboard?.(${JSONObject.quote(text)},${JSONObject.quote(signature)},$explicit)")
    }

    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        super.onStartInput(info, restarting)
        if (!restarting || !VoiceCorrectionTracker.allowed(info.inputType, info.imeOptions)) resetVoiceLearning()
        clipboardSplit = null
        if (!restarting) {
            setSearchInputActive(false)
            selectionNavigation?.cancel()
            selectionDrag.clear(); selectionLockedAnchor = -1; selectionGestureActive = false
            annotation.hide()
            inputGeneration++
            ownSelections.clear()
            composingStart = null
            cancelRecording()
        }
        startKeyboard(info, restarting)
        selectionStart = info.initialSelStart
        selectionEnd = info.initialSelEnd
        // 键盘收起时宿主长按出新选区，部分宿主不再回调 onUpdateSelection，只把选区挂在 onStartInput 上。
        // 这里补一个「待显示」请求，由 onStartInputView 按同宿主、同端点核销后才打开编辑框。
        // 只在键盘隐藏时补：已经开着就说明只是普通换输入框，不能顺手弹面板。
        if (!shown) hiddenSelection.fromInitial(info.packageName.orEmpty(), info.initialSelStart, info.initialSelEnd)
        Log.i(selTag, "startInput shown=$shown restart=$restarting init=${info.initialSelStart},${info.initialSelEnd} " +
            "pkg=${info.packageName} pending=${hiddenSelection.pending(info.packageName.orEmpty())} ${hiddenSelection.describe()}")
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shown = true
        startKeyboard(info, restarting)
        val connection = currentInputConnection
        val actual = connection?.getExtractedText(ExtractedTextRequest(), 0)
        val start = actual?.let { it.startOffset + it.selectionStart } ?: selectionStart
        val end = actual?.let { it.startOffset + it.selectionEnd } ?: selectionEnd
        val owner = info.packageName.orEmpty()
        val armed = hiddenSelection.armed()
        // 正常按"同宿主 + 有效期内"核销；宿主换过包名或端点漂了，只要确实有"收起键盘时选过字"的请求、
        // 而且现在宿主报得出一个非空选区，就按现状进编辑框，别因为包名/端点不一致整条丢掉。
        val taken = hiddenSelection.take(owner, start, end)
            ?: if (armed && selectionLockedAnchor < 0 && start >= 0 && end > start) { hiddenSelection.cancel(); start to end } else null
        Log.i(selTag, "startView extracted=${actual != null} start=$start end=$end armed=$armed taken=$taken")
        taken?.let { (anchor, head) ->
            selectionDrag.beginPositions(anchor, head); selectionLockedAnchor = anchor
            // 新 WebView 要 100~400ms 才加载完页面，宿主也可能在 show 过程中把选区收成光标或重报一次。
            // 给足宽限，别让编辑框刚开就被当成"手动改光标"收掉。
            ownSelectionUntil = SystemClock.uptimeMillis() + 900
            script("globalThis.voxfixSelectionOrigin?.()")
            reportSelection(anchor, head)
            showAnnotation()
        }
        updateClipboard()
        anchorFrames = 0; anchorAttempt = 0; requestCursorUpdates()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        super.onViewClicked(focusChanged)
        if (shown && searchInputActive) focusHostEditor()
    }

    /**
     * 「标注」浮层的数据源：请求宿主持续回插入点坐标与字符级边界框。
     *
     * 两个必须遵守的点（都踩过）：
     *   1. **一次只能发一个模式**。连发 MONITOR 和 IMMEDIATE 会让后一次覆盖前一次的模式，
     *      监视实际没开成——只见一帧一次性数据，而且那一帧发生在锁定之前、被丢掉。
     *   2. MONITOR 要在每次 onStartInputView 重新请求（平台要求），锁定那一刻再请求一次，
     *      宿主通常会立刻补发一帧，正好当作"立即给我一次"。
     * 过滤位是 API 33/34 才有的；万一某个宿主不认这些位而从此不发数据，
     * 就用 anchorFrames 计数兜一次：没有任何帧时退回不带过滤位的纯 MONITOR，并留日志。
     */
    private fun requestCursorUpdates() {
        val ic = currentInputConnection ?: return
        val mode = if (anchorAttempt == 0) {
            InputConnection.CURSOR_UPDATE_MONITOR or
                InputConnection.CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS or
                InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER
        } else InputConnection.CURSOR_UPDATE_MONITOR
        Log.i(selTag, "cursor-update request attempt=$anchorAttempt mode=$mode accepted=${ic.requestCursorUpdates(mode)}")
        if (anchorAttempt == 0) {
            main.postDelayed(anchorRetry, ANCHOR_RETRY_MS)
        }
    }

    /** 锁定后一直没帧：退回不带过滤位的请求再试一次（有些宿主不认过滤位就干脆不发）。 */
    private val anchorRetry = Runnable {
        if (anchorFrames > 0 || selectionLockedAnchor < 0) return@Runnable
        anchorAttempt = 1
        Log.i(selTag, "cursor-update no frames, retrying without filters")
        requestCursorUpdates()
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        val start = minOf(cursorAnchorInfo.selectionStart, cursorAnchorInfo.selectionEnd)
        val end = maxOf(cursorAnchorInfo.selectionStart, cursorAnchorInfo.selectionEnd)
        if (start < 0) return
        if (selectionLockedAnchor < 0) { annotation.hide(); return }
        val view = web ?: return
        anchorFrames++
        val glyphs = collectGlyphs(cursorAnchorInfo, start, end)
        val caret = insertionBox(cursorAnchorInfo, glyphs, start)
        annotation.update(start, end, caret, caret != null, glyphs)
        annotation.show(view, if (keyboardHeightPx > 0) keyboardHeightPx else view.height)
        Log.i(selTag, "anchor sel=$start,$end glyphs=${glyphs.size} caret=${caret != null}")
    }

    /**
     * 取端点附近的字符框。选区不长时整段连续取；很长时只取头尾两段——
     * 宿主只为可见字符给边界框，取不到的自然跳过，但也不能为一次全选去查上万次。
     */
    private fun collectGlyphs(cursorAnchorInfo: CursorAnchorInfo, start: Int, end: Int): Map<Int, MarkBox> {
        val map = HashMap<Int, MarkBox>()
        val from = (start - CONTEXT_CHARS).coerceAtLeast(0)
        val to = end + CONTEXT_CHARS
        val headEnd = if (to - from <= CONTEXT_CHARS * 12) to else from + CONTEXT_CHARS * 6
        for (index in from until headEnd) glyphBox(cursorAnchorInfo, index)?.let { map[index] = it }
        if (headEnd < to) {
            for (index in maxOf(headEnd, to - CONTEXT_CHARS * 6) until to) glyphBox(cursorAnchorInfo, index)?.let { map[index] = it }
        }
        return map
    }

    /** 宿主给的是编辑器坐标时用它的矩阵换算到屏幕坐标；单位阵说明已经是屏幕坐标。 */
    private fun glyphBox(cursorAnchorInfo: CursorAnchorInfo, index: Int): MarkBox? {
        val rect = runCatching { cursorAnchorInfo.getCharacterBounds(index) }.getOrNull() ?: return null
        if (rect.isEmpty) return null
        val matrix = cursorAnchorInfo.matrix
        if (matrix.isIdentity) return MarkBox(rect.left, rect.top, rect.right, rect.bottom)
        val points = floatArrayOf(rect.left, rect.top, rect.right, rect.bottom)
        matrix.mapPoints(points)
        return MarkBox(minOf(points[0], points[2]), minOf(points[1], points[3]), maxOf(points[0], points[2]), maxOf(points[1], points[3]))
    }

    /** 竖线位置：优先用宿主报的插入点，报不出来就退回该字框的相应一侧。 */
    private fun insertionBox(cursorAnchorInfo: CursorAnchorInfo, glyphs: Map<Int, MarkBox>, caret: Int): MarkBox? {
        val flags = cursorAnchorInfo.insertionMarkerFlags
        val top = cursorAnchorInfo.insertionMarkerTop
        val bottom = cursorAnchorInfo.insertionMarkerBottom
        if (bottom > top && flags and CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION != 0) {
            val x = cursorAnchorInfo.insertionMarkerHorizontal
            return MarkBox(x, top, x, bottom)
        }
        val after = glyphs[caret]
        val before = if (caret > 0) glyphs[caret - 1] else null
        val box = after ?: before ?: return null
        val edge = if (after != null) after.left else box.right
        return MarkBox(edge, box.top, edge, box.bottom)
    }

    private fun showAnnotation() {
        val view = web ?: return
        requestCursorUpdates()
        annotation.show(view, if (keyboardHeightPx > 0) keyboardHeightPx else view.height)
    }

    /**
     * 拖动水滴：阶段 0 记下"不动的那一端"，之后每一次移动把被拖的那一端直接写到宿主选区上。
     * 未选中（只有起点）时拖动＝把起点挪过去，等价于在新位置重新锁定。
     */
    private fun onAnnotationDrag(which: Int, x: Float, y: Float, phase: Int) {
        if (phase == 0) {
            annotationDragging = true
            annotationDragFixed = if (selectionDrag.anchor == selectionDrag.head) -1
                else if (which == 0) maxOf(selectionDrag.anchor, selectionDrag.head) else minOf(selectionDrag.anchor, selectionDrag.head)
        }
        if (!annotationDragging) return
        if (phase == 2) { annotationDragging = false; return }
        val target = annotation.offsetAt(x, y) ?: return
        if (!shown || destroyed) return
        if (currentInputConnection == null) return
        // 触摸回调本来就在主线程；和滑轨/方向盘共用一个串行队列，避免两边同时改选区。
        val apply = applyLabel@{
            val connection = currentInputConnection ?: return@applyLabel
            ownSelectionUntil = SystemClock.uptimeMillis() + 400
            if (annotationDragFixed < 0) {
                // 未选中：移动光标并在新位置重新标定起点。
                connection.setSelection(target, target)
                selectionDrag.beginPositions(target, target)
                selectionLockedAnchor = target
                reportSelection(target, target)
            } else {
                connection.setSelection(annotationDragFixed, target)
                selectionDrag.beginPositions(annotationDragFixed, target)
                selectionLockedAnchor = annotationDragFixed
                reportSelection(annotationDragFixed, target)
            }
            Log.i(selTag, "handle drag which=$which fixed=$annotationDragFixed target=$target")
        }
        val navigation = selectionNavigation
        if (navigation != null) navigation.run(apply) else apply()
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
        flushVoiceLearning()
        resetVoiceLearning()
        setSearchInputActive(false)
        finishClipboardSplit()
        hiddenSelection.cancel()
        selectionNavigation?.cancel()
        selectionDrag.clear(); selectionLockedAnchor = -1; selectionGestureActive = false
        annotation.hide()
        shown = false
        inputGeneration++
        cancelRecording()
        resetKeyboard()
        hideClipHint()
        scheduleVoiceRelease()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        flushVoiceLearning()
        resetVoiceLearning()
        setSearchInputActive(false)
        finishClipboardSplit()
        hiddenSelection.cancel()
        selectionNavigation?.cancel()
        selectionDrag.clear(); selectionLockedAnchor = -1; selectionGestureActive = false
        annotation.hide()
        shown = false
        inputGeneration++
        cancelRecording()
        hideClipHint()
        scheduleVoiceRelease()
        super.onFinishInput()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        voiceLearningComposing = candidatesStart >= 0
        if (!busy && voiceLearning.active()) voiceLearningEdited = true
        queueVoiceLearning()
        selectionStart = newSelStart
        selectionEnd = newSelEnd
        if (shown && searchInputActive && candidatesStart < 0 && newSelStart >= 0 && newSelEnd >= 0 &&
            (oldSelStart != newSelStart || oldSelEnd != newSelEnd) &&
            !selectionGestureActive && SystemClock.uptimeMillis() > ownSelectionUntil &&
            !(newSelStart == newSelEnd && ownSelections.contains(newSelEnd))) focusHostEditor(true)
        clipboardSplit?.let { state ->
            val connection = currentInputConnection
            if (SystemClock.uptimeMillis() > ownSelectionUntil && (connection == null || !splitMatches(connection, state))) {
                clipboardSplit = null
                script("globalThis.voxfixSplitInvalidated?.()")
            }
        }
        if (selectionNavigation?.observe(newSelStart, newSelEnd) == true) return
        val hostWord = shown && !selectionGestureActive && candidatesStart < 0 &&
            SystemClock.uptimeMillis() > ownSelectionUntil &&
            selectionDrag.beginHostSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd)
        val changed = oldSelStart != newSelStart || oldSelEnd != newSelEnd
        // 收起键盘时只有「长按正文选出的非空选区」才算入口；密码框点一个格子、删一个字符只是
        // 光标在变（start==end），不能据此弹输入法，否则淘宝/支付宝密码框每点一下都呼一次键盘。
        val armHidden = !shown && changed && newSelStart != newSelEnd && candidatesStart < 0 &&
            newSelStart >= 0 && newSelEnd >= 0 &&
            (currentInputEditorInfo?.inputType ?: 0) != 0 && SystemClock.uptimeMillis() > ownSelectionUntil
        // 收起键盘时长按正文的入口：整条判定都打出来（只记端点/时长/分支，不记正文），
        // 这样"选了字却打不开编辑框"能直接看出卡在哪一步。
        if (!shown) Log.i(selTag, "hidden-update old=$oldSelStart,$oldSelEnd new=$newSelStart,$newSelEnd changed=$changed " +
            "cand=$candidatesStart type=${currentInputEditorInfo?.inputType} ownIn=${ownSelectionUntil - SystemClock.uptimeMillis()} " +
            "hostOpen=${hostSelectionOpen != null} arm=$armHidden")
        if (armHidden) {
            hostSelectionOpen?.let { main.removeCallbacks(it) }
            val generation = inputGeneration
            val owner = currentInputEditorInfo?.packageName.orEmpty()
            hiddenSelection.request(owner, newSelStart, newSelEnd)
            val open = Runnable {
                hostSelectionOpen = null
                Log.i(selTag, "hidden-show fire shown=$shown gen=$generation/${inputGeneration} pending=${hiddenSelection.pending(owner)}")
                if (!shown && generation == inputGeneration && currentInputEditorInfo?.packageName.orEmpty() == owner)
                    requestShowSelf(android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            hostSelectionOpen = open; main.postDelayed(open, 120)
        }
        val external = changed && !hostWord && selectionDrag.isExternalChange(newSelStart, newSelEnd, selectionLockedAnchor,
            selectionGestureActive, SystemClock.uptimeMillis() <= ownSelectionUntil)
        if (shown && changed) Log.i(selTag, "shown-update old=$oldSelStart,$oldSelEnd new=$newSelStart,$newSelEnd " +
            "word=$hostWord external=$external anchor=$selectionLockedAnchor gesture=$selectionGestureActive ownIn=${ownSelectionUntil - SystemClock.uptimeMillis()}")
        if (hostWord) selectionLockedAnchor = -1
        if (external) {
            selectionDrag.clear(); selectionLockedAnchor = -1; selectionGestureActive = false
            annotation.hide()
            ownSelectionUntil = 0L
            script("globalThis.voxfixSelectionReleased?.()")
        } else {
            selectionDrag.observe(newSelStart, newSelEnd)
            // 自己刚改的窗口内，宿主的边界重算会让锚点平移：服务侧跟着走，别留下一个对不上的旧锚点。
            if (selectionDrag.anchor >= 0 && SystemClock.uptimeMillis() <= ownSelectionUntil) selectionLockedAnchor = selectionDrag.anchor
        }
        // 宿主会先选一字再扩成词，合并同一次选词的回调后再呼出。
        if (hostWord) {
            hostSelectionOpen?.let { main.removeCallbacks(it) }
            val generation = inputGeneration; val view = web
            val open = Runnable {
                hostSelectionOpen = null
                val ready = selectionDrag.finishHostSelection()
                Log.i(selTag, "word-open fire shown=$shown gen=$generation/${inputGeneration} anchor=$selectionLockedAnchor gesture=$selectionGestureActive ready=$ready")
                if (shown && generation == inputGeneration && web === view && selectionLockedAnchor < 0 &&
                    !selectionGestureActive && ready) {
                    selectionLockedAnchor = selectionDrag.anchor
                    script("globalThis.voxfixSelectionOrigin?.()")
                    reportSelection(selectionStart, selectionEnd)
                    showAnnotation()
                }
            }
            hostSelectionOpen = open
            main.postDelayed(open, 120)
        }
        reportSelection(newSelStart, newSelEnd)
        if (changed) {
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

    private fun reportSelection(start: Int, end: Int) {
        val selected = if (start == end) "" else currentInputConnection?.getSelectedText(0)?.toString()
        val count = selected?.let {
            val breaks = android.icu.text.BreakIterator.getCharacterInstance()
            breaks.setText(it)
            var total = 0
            while (breaks.next() != android.icu.text.BreakIterator.DONE) total++
            total
        } ?: -1
        script("globalThis.voxfixSelection?.($start,$end,$count)")
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
        microphoneCapture?.stop()
        streamedText = ""
        selectedBeforeVoice = ""
        localVoice = false
    }

    private fun beginVoice(local: Boolean = false) {
        if (busy) { voiceDone("上一段语音正在结束，请稍后再试"); return }
        // 上一段可能还在 420ms 的改写窗口里：先把它结清，避免两段文字抢同一个 composing 区域。
        main.removeCallbacks(finishVoiceComposition)
        if (voiceRefineArmed) finishVoiceComposition.run()
        flushVoiceLearning()
        resetVoiceLearning()
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
        main.removeCallbacks(releaseVoice)
        busy = true
        val current = session.open()
        ticket = current
        capturing.set(true)
        Log.i("VoxFix", "IME_STREAM_STARTED")
        var updates = 0
        worker.execute {
            var received = ""
            try {
                if (capturing.get() && session.accepts(current)) {
                    received = SpeechEngine.record({
                        val dictionary = if (allowsVoiceLearning() && !local) runCatching { lexicon.voiceSnapshot() }.getOrElse { VoiceLexicon() } else VoiceLexicon()
                        if (engine?.usesPersonalDecoder != dictionary.words.isNotEmpty()) { engine?.release(); engine = null }
                        (engine ?: SpeechEngine(assets, dictionary).also { engine = it }).also { it.personalize(dictionary) }
                    }, { text ->
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
                    } }, { capturing.get() && session.accepts(current) }, { !session.accepts(current) || inputGeneration != targetGeneration }, { main.post {
                        if (capturing.get() && session.accepts(current) && shown && inputGeneration == targetGeneration)
                            script("globalThis.voxfixVoiceListening?.()")
                    } }, { capture ->
                        microphoneCapture = capture
                        if (!capturing.get()) capture?.stop()
                    }, { refined -> main.post {
                        // 二遍在窗口内跑完：只改还没离开 composing 的那段文字；用户已打字或换框就不动。
                        if (shown && voiceRefineArmed && !localVoice && inputGeneration == targetGeneration &&
                            refined.isNotBlank() && refined != voiceFinalText) {
                            val connection = currentInputConnection
                            val untouched = connection?.getTextBeforeCursor(voiceFinalText.length, 0)?.toString() == voiceFinalText
                            if (untouched && connection?.setComposingText(refined, 1) == true) {
                                voiceFinalText = refined
                                streamedText = refined
                                Log.i("VoxFix", "IME_REFINE_APPLIED characters=${refined.length}")
                            } else Log.i("VoxFix", "IME_REFINE_SKIPPED")
                        }
                    } })
                }
                capturing.set(false)
                val result = received
                main.post {
                    if (shown && inputGeneration == targetGeneration && session.consume(current)) {
                        if (result.isNotBlank()) {
                            if (local) {
                                script("globalThis.voxfixVoiceText?.(${JSONObject.quote(result)})")
                                Log.i("VoxFix", "IME_STREAM_FINISHED updates=$updates characters=${result.length}")
                                voiceDone("")
                                streamedText = ""; selectedBeforeVoice = ""
                            } else {
                                // 先上屏：实时文字原地留在 composing 里，二遍回来再改写（最多 420ms）。
                                val written = currentInputConnection?.setComposingText(result, 1) == true
                                if (written) {
                                    voiceFinalText = result
                                    streamedText = result
                                    voiceRefineArmed = true
                                    main.removeCallbacks(finishVoiceComposition)
                                    main.postDelayed(finishVoiceComposition, VOICE_REFINE_WINDOW_MS)
                                    Log.i("VoxFix", "IME_STREAM_FINISHED updates=$updates characters=${result.length} committed=true")
                                }
                                voiceDone(if (written) "" else "当前输入框未接受文字")
                            }
                        } else {
                            voiceDone("没有识别到文字")
                            streamedText = ""; selectedBeforeVoice = ""
                        }
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
            } finally { main.post { busy = false; scheduleVoiceRelease() } }
        }
    }

    inner class KeyboardBridge(private val owner: WebView) {
        @JavascriptInterface fun searchFocus(active: Boolean) {
            val generation = inputGeneration
            main.post { if (web === owner && shown && generation == inputGeneration) setSearchInputActive(active) }
        }
        private fun inputReply(action: () -> String): String {
            val generation = inputGeneration
            val result = FutureTask {
                if (web !== owner || !shown || generation != inputGeneration) "{}"
                else try { action() } catch (error: Exception) { Log.e("VoxFix", "IME_INPUT_REPLY_FAILED", error); "{}" }
            }
            main.post(result)
            return result.get()
        }
        private fun editInput(marksSelection: Boolean = true, action: () -> Unit) {
            val generation = inputGeneration
            main.post { if (web === owner && shown && generation == inputGeneration) navigation.run { if (web === owner && shown && generation == inputGeneration) {
                if (marksSelection) {
                    hostSelectionOpen?.let { main.removeCallbacks(it) }; hostSelectionOpen = null
                    selectionDrag.cancelHostSelection()
                    ownSelectionUntil = SystemClock.uptimeMillis() + 250
                }
                action()
            } } }
        }
        @JavascriptInterface fun ready() { main.post { if (web === owner) { currentInputEditorInfo?.let { startKeyboard(it, false) } ?: resetKeyboard(); Log.i(selTag, "page-ready anchor=$selectionLockedAnchor drag=${selectionDrag.anchor},${selectionDrag.head}"); if (selectionLockedAnchor >= 0 && selectionDrag.anchor >= 0) { script("globalThis.voxfixSelectionOrigin?.()"); reportSelection(selectionDrag.anchor, selectionDrag.head); showAnnotation() }; updateClipboard(); if (pinyinFailed) script("globalThis.voxfixPinyinReady?.(true)") } } }
        @JavascriptInterface fun pinyinReady() = pinyin != null
        @JavascriptInterface fun lexiconMigrate(value: String): String = lexiconReply {
            require(value.toByteArray().size <= PersonalLexicon.MAX_BYTES)
            lexicon.migrateWeb(org.json.JSONArray(value))
            val cleaned = pinyin != null && runCatching { lexicon.cleanupLegacy(filesDir) }.getOrDefault(false)
            JSONObject().put("ok", true).put("legacyCleaned", cleaned).put("count", lexicon.count())
        }
        @JavascriptInterface fun lexiconStatus(): String = lexiconReply { JSONObject().put("ready", lexicon.migrationComplete()).put("count", lexicon.count()).put("legacyCleaned", lexicon.legacyCleaned()) }
        @JavascriptInterface fun lexiconLookup(raw: String): String = lexiconReply { JSONObject().put("rows", lexicon.lookup(raw)) }
        @JavascriptInterface fun lexiconAssociations(value: String): String = lexiconReply { JSONObject().put("rows", lexicon.associations(value)) }
        @JavascriptInterface fun lexiconLearn(value: String): String = lexiconReply {
            require(value.length <= 20000)
            lexicon.learn(org.json.JSONArray(value)); JSONObject().put("ok", true)
        }
        @JavascriptInterface fun lexiconRevision() = lexicon.revision().toString()
        @JavascriptInterface fun openLexicon() { main.post {
            if (web === owner) startActivity(android.content.Intent(this@VoxFixImeService, LexiconActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } }
        private fun lexiconReply(action: () -> JSONObject): String = try {
            check(web === owner && !destroyed); action().toString()
        } catch (error: Exception) { JSONObject().put("error", error.message ?: "个人词库操作失败").toString() }
        @JavascriptInterface fun pinyinCandidates(value: String, limit: Int): String =
            if (web !== owner || !value.matches(Regex("[a-z']{1,100}"))) "{}"
            else try { pinyin?.candidates(value, limit.coerceIn(1, 2000)) ?: "{}" }
            catch (error: Exception) { Log.e("VoxFix", "IME_PINYIN_QUERY_FAILED", error); "{}" }
        @JavascriptInterface fun pinyinChoose(value: String, word: String, learn: Boolean): String {
            if (!value.matches(Regex("[a-z']{1,100}")) || word.isEmpty() || word.length > 200) return "{}"
            return inputReply { pinyin?.choose(value, word, { commitInputText(it, true) }) ?: "{}" }
        }
        @JavascriptInterface fun pinyinChooseLocal(value: String, word: String): String {
            if (web !== owner || !value.matches(Regex("[a-z']{1,100}")) || word.isEmpty() || word.length > 200) return "{}"
            return try { pinyin?.choose(value, word) { true } ?: "{}" }
            catch (error: Exception) { Log.e("VoxFix", "IME_LOCAL_PINYIN_CHOOSE_FAILED", error); "{}" }
        }
        @JavascriptInterface fun commitAccepted(value: String): String = if (value.length > 10000) "{}"
            else inputReply { JSONObject().put("committed", commitInputText(value, true)).toString() }
        @JavascriptInterface fun splitBegin(): String = inputReply {
            val connection = currentInputConnection ?: return@inputReply "{}"
            finishClipboardSplit()
            val extracted = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 1 }, 0) ?: return@inputReply "{}"
            val start = extracted.startOffset + minOf(extracted.selectionStart, extracted.selectionEnd)
            val end = extracted.startOffset + maxOf(extracted.selectionStart, extracted.selectionEnd)
            val original = connection.getSelectedText(0)?.toString().orEmpty()
            if (start < 0 || original.length != end - start || original.length > 10000) return@inputReply "{}"
            clipboardSplit = ClipboardSplitState(++clipboardSplitId, inputGeneration, start, original)
            JSONObject().put("session", clipboardSplitId).toString()
        }
        @JavascriptInterface fun splitCompose(id: Int, value: String): String = if (value.length > 10000) "{}" else inputReply {
            val state = clipboardSplit ?: return@inputReply "{}"
            val connection = currentInputConnection ?: return@inputReply "{}"
            if (state.id != id || !splitMatches(connection, state)) { clipboardSplit = null; return@inputReply "{}" }
            ownSelectionUntil = SystemClock.uptimeMillis() + 250
            hostSelectionOpen?.let { main.removeCallbacks(it) }; hostSelectionOpen = null
            selectionDrag.clear(); selectionLockedAnchor = -1; annotation.hide()
            val oldStart = if (state.text == null) state.start else state.end
            val oldEnd = state.end
            var updated = false
            connection.beginBatchEdit()
            try {
                connection.finishComposingText()
                if (connection.setSelection(state.start, state.end)) {
                    updated = connection.setComposingText(value.ifEmpty { state.original }, 1)
                    if (updated && value.isEmpty()) {
                        connection.finishComposingText()
                        updated = connection.setSelection(state.start, state.start + state.original.length)
                    }
                    if (!updated) connection.setSelection(oldStart, oldEnd)
                }
            } finally { connection.endBatchEdit() }
            if (updated) { state.accepted(value); composingStart = value.takeIf { it.isNotEmpty() }?.let { state.start } }
            else clipboardSplit = null
            JSONObject().put("updated", updated).toString()
        }
        @JavascriptInterface fun splitFinish(id: Int): String = inputReply {
            JSONObject().put("finished", clipboardSplit?.id == id && finishClipboardSplit()).toString()
        }
        @JavascriptInterface fun refreshClipboard() = editInput { updateClipboard() }
        @JavascriptInterface fun importClipboard() = editInput { updateClipboard(true) }
        /**
         * 网页每次渲染都会回传当前标签文本（无内容时空串）。渲染常发生在键盘高度变化前后，
         * 所以这里既负责首弹，也负责挪位——不能只把「文本相同」当无事发生。
         */
        @JavascriptInterface fun clipHint(label: String) {
            if (label.length > 200) return
            main.post { if (web === owner) showClipHint(label) }
        }
        @JavascriptInterface fun copy(value: String) { if (value.length <= 10000) editInput { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("VoxFix", value)); updateClipboard() } }
        @JavascriptInterface fun voiceReady() = !destroyed
        @JavascriptInterface fun resize(height: Int) {
            if (height !in 220..550) return
            main.post { if (web === owner) owner.let { view ->
                val pixels = (height * resources.displayMetrics.density).toInt()
                keyboardHeightPx = pixels
                val viewport = view.parent as? FrameLayout ?: return@post
                if (viewport.layoutParams.height != pixels) viewport.layoutParams = viewport.layoutParams.apply { this.height = pixels }
                annotation.reposition(view, pixels)
                repositionClipHint()
                restoreKeyboardVisibility(view)
                // Wait for the new DOM to be drawable before invalidating the IME window.
                // 键盘隐藏期间也要照常请求重绘：否则这段时间的 DOM 变化会被丢掉，重新显示时屏幕上会残留旧页。
                view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { if (web === view) view.postInvalidateOnAnimation() }
                })
            } }
        }
        @JavascriptInterface fun compose(value: String) { if (value.length <= 1000) editInput {
            selectionDrag.clear()
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
                try {
                    connection.deleteSurroundingText(prefix.length, 0)
                    if (connection.commitText(word, 1)) { voiceLearningComposing = false; voiceLearningEdited = true; voiceLearningConfirmed = true; queueVoiceLearning() }
                }
                finally { connection.endBatchEdit() }
            }
        }
        @JavascriptInterface fun delete() = editInput {
            selectionDrag.clear(); selectionLockedAnchor = -1; annotation.hide()
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
            flushVoiceLearning()
            selectionDrag.clear(); selectionLockedAnchor = -1; annotation.hide()
            val info = currentInputEditorInfo ?: return@editInput
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) currentInputConnection?.performEditorAction(action)
            else currentInputConnection?.commitText("\n", 1)
        }
        @JavascriptInterface fun hide() = editInput { hiddenSelection.cancel(); annotation.hide(); requestHideSelf(0) }
        @JavascriptInterface fun switcher() = editInput { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        @JavascriptInterface fun startVoice() = editInput { beginVoice() }
        @JavascriptInterface fun startLocalVoice() = editInput { beginVoice(true) }
        private fun stopMicrophone() {
            if (web !== owner || !shown || destroyed) return
            val started = System.nanoTime()
            capturing.set(false)
            microphoneCapture?.stop()
            Log.i("VoxFix", "IME_MIC_STOP_REQUESTED elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        }
        @JavascriptInterface fun stopVoice() = stopMicrophone()
        @JavascriptInterface fun cancelVoice() { stopMicrophone(); editInput { cancelRecording(true) } }
        @JavascriptInterface fun cursorPosition() = editInput {
            currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)?.let { text -> script("globalThis.voxfixCursor?.(${text.selectionEnd},${text.text?.length ?: 0})") }
        }
        @JavascriptInterface fun moveCursor(amount: Int) = editInput {
            if (amount == 0) return@editInput
            selectionDrag.clear()
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
        // 普通光标走宿主方向键跨段落；框选保留固定锚点，右侧编辑框提供编辑操作。
        private var gestureOrigin = -1
        private var gestureSelecting = false
        private var gestureEpoch = 0
        private val selectionSteps = SelectionSteps()
        private var cursorSteps = 0
        private var navigationTicket = 0
        private val navigation by lazy {
            SelectionNavigation(selectionDrag,
                read = {
                    currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)?.let {
                        it.startOffset + it.selectionStart to it.startOffset + it.selectionEnd
                    } ?: (selectionStart to selectionEnd).takeIf { it.first >= 0 && it.second >= 0 }
                },
                keys = { columns, lines, edge -> currentInputConnection?.let { sendNavigationKeys(it, columns, lines, edge, true) } },
                post = { delay, action ->
                    val generation = inputGeneration
                    main.postDelayed({ if (generation == inputGeneration && web === owner && shown) action() }, delay)
                },
                report = { blocked ->
                    if (selectionDrag.anchor < 0) {
                        selectionLockedAnchor = -1; selectionGestureActive = false; ownSelectionUntil = 0L
                        script("globalThis.voxfixSelectionReleased?.()")
                    } else {
                        reportSelection(selectionDrag.anchor, selectionDrag.head)
                        if (blocked) script("globalThis.voxfixSelectionBlocked?.()")
                    }
                },
                canMove = { head, columns, lines ->
                    val forward = if (lines != 0) lines > 0 else columns > 0
                    val actual = currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)
                    val text = actual?.text
                    if (!forward && head == 0) false else if (actual == null || text == null) true else CursorBoundary(
                        hasSelection = false,
                        completeText = actual.partialStartOffset < 0 && actual.partialEndOffset < 0,
                        startOffset = actual.startOffset,
                        selectionStart = head - actual.startOffset,
                        selectionEnd = head - actual.startOffset,
                        textLength = text.length,
                    ).canStep(forward)
                },
            ).also { selectionNavigation?.cancel(); selectionNavigation = it }
        }
        private fun selectionHead(start: Int, end: Int): Int =
            if (selectionDrag.anchor >= 0 && minOf(start, end) == minOf(selectionDrag.anchor, selectionDrag.head) && maxOf(start, end) == maxOf(selectionDrag.anchor, selectionDrag.head)) selectionDrag.head else end
        @JavascriptInterface fun gestureStart(selecting: Boolean) = editInput {
            navigation.resetBoundary()
            val connection = currentInputConnection ?: return@editInput
            val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
            val start = extracted?.let { it.startOffset + it.selectionStart } ?: selectionStart
            val end = extracted?.let { it.startOffset + it.selectionEnd } ?: selectionEnd
            if (selecting) {
                val text = extracted?.text?.toString()
                val anchor = selectionLockedAnchor.takeIf { it >= 0 } ?: start
                val head = selectionHead(start, end)
                if (text != null) selectionDrag.begin(text, extracted.startOffset, anchor, head)
                else selectionDrag.beginPositions(anchor, head)
            }
            gestureOrigin = (if (selecting) selectionDrag.head else maxOf(start, end)).coerceAtLeast(0)
            gestureSelecting = selecting
            selectionGestureActive = selecting
            gestureEpoch++; selectionSteps.clear(); cursorSteps = 0
            if (selecting) {
                reportSelection(selectionDrag.anchor, selectionDrag.head)
                showAnnotation()
            }
        }
        @JavascriptInterface fun gestureMove(steps: Int) = editInput {
            if (gestureOrigin < 0) return@editInput
            navigateSelection(steps - cursorSteps, 0)
            cursorSteps = steps
        }
        @JavascriptInterface fun gestureEnd() = editInput(false) {
            selectionGestureActive = false
            gestureOrigin = -1; gestureSelecting = false; gestureEpoch++; selectionSteps.clear()
            val selected = currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)
            reportSelection(selected?.let { it.startOffset + it.selectionStart } ?: selectionStart, selected?.let { it.startOffset + it.selectionEnd } ?: selectionEnd)
        }
        @JavascriptInterface fun gestureLock() = editInput {
            val connection = currentInputConnection ?: return@editInput
            val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
            val start = extracted?.let { it.startOffset + it.selectionStart } ?: selectionStart
            val end = extracted?.let { it.startOffset + it.selectionEnd } ?: selectionEnd
            val head = selectionHead(start, end)
            if (head < 0) return@editInput
            selectionDrag.lock(extracted?.text?.toString(), extracted?.startOffset ?: 0, head)
            selectionLockedAnchor = head
            connection.setSelection(head, head)
            reportSelection(head, head)
            showAnnotation()
        }
        // 解除起点锁定：把选区收成「活动终点」处的光标。反向拖选后 selectionDrag.head 才是
        // 手指那一端，不能取较大的索引 —— 那会跳到起点那一侧。
        @JavascriptInterface fun gestureUnlock() = editInput {
            val connection = currentInputConnection ?: return@editInput
            val head = if (selectionDrag.anchor >= 0) selectionDrag.head else selectionEnd
            selectionDrag.clear(); selectionLockedAnchor = -1
            annotation.hide()
            selectionGestureActive = false; gestureSelecting = false; gestureOrigin = -1
            gestureEpoch++; selectionSteps.clear(); cursorSteps = 0
            if (head >= 0) { connection.setSelection(head, head); reportSelection(head, head) }
            else reportSelection(selectionStart, selectionEnd)
        }
        @JavascriptInterface fun gestureMove2D(x: Float, y: Float) {
            if (!x.isFinite() || !y.isFinite() || kotlin.math.abs(x) > 100000 || kotlin.math.abs(y) > 100000) return
            editInput {
                if (gestureOrigin < 0) return@editInput
                val (columns, lines) = selectionSteps.take(x, y)
                navigateSelection(columns, lines)
            }
        }
        @JavascriptInterface fun gestureClearSteps() = editInput { selectionSteps.clear() }
        @JavascriptInterface fun selectionStep(x: Int, y: Int, selecting: Boolean) {
            if (x !in -1..1 || y !in -1..1 || x == 0 && y == 0) return
            gestureStart(selecting)
            editInput {
                navigateSelection(if (y == 0) x else 0, y, if (y != 0) x else 0)
                selectionGestureActive = false
            }
        }
        private fun navigateSelection(columns: Int, lines: Int, lineEdge: Int = 0) {
            if (columns == 0 && lines == 0 && lineEdge == 0) return
            val connection = currentInputConnection ?: return
            if (gestureSelecting) { navigation.move(columns, lines, lineEdge); return }
            // 光标贴到文本首/尾后不能再发方向键：宿主会把焦点交给下一个可聚焦的 View，
            // 光标离开输入框后滑轨怎么拖都找不回来（详见 CursorBoundary）。框选共用 keys 的
            // 折叠语义，不受此限。
            val movable = if (columns == 0 || canStepCursor(connection, forward = columns > 0)) columns else 0
            val movableLines = if (lines == 0 || canStepCursor(connection, forward = lines > 0)) lines else 0
            if (movable == 0 && movableLines == 0) return
            val generation = inputGeneration; val epoch = gestureEpoch; val ticket = ++navigationTicket
            val before = selectionStart to selectionEnd
            sendNavigationKeys(connection, movable, movableLines, lineEdge, false)
            // 等宿主处理按键后再确认，不把异步输入连接的旧选区当作失败。
            main.postDelayed({
                if (generation == inputGeneration && epoch == gestureEpoch && web === owner && ticket == navigationTicket) {
                    val actual = currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)
                    val start = actual?.let { it.startOffset + it.selectionStart } ?: selectionStart
                    val end = actual?.let { it.startOffset + it.selectionEnd } ?: selectionEnd
                    selectionDrag.observe(start, end); reportSelection(start, end)
                    actual?.let { script("globalThis.voxfixCursor?.(${it.selectionEnd},${it.text?.length ?: 0})") }
                    if (before == (start to end)) script("globalThis.voxfixSelectionBlocked?.()")
                }
            }, 180)
        }
        private fun sendNavigationKeys(connection: InputConnection, columns: Int, lines: Int, lineEdge: Int, selecting: Boolean) {
            fun key(action: Int, code: Int, meta: Int) {
                val now = SystemClock.uptimeMillis()
                connection.sendKeyEvent(KeyEvent(now, now, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD, android.view.InputDevice.SOURCE_KEYBOARD))
            }
            val meta = if (selecting) KeyEvent.META_SHIFT_ON else 0
            if (selecting) key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, meta)
            try {
                for ((amount, negative, positive) in listOf(Triple(lines, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN), Triple(columns, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT))) {
                    repeat(kotlin.math.abs(amount).coerceAtMost(if (selecting) 20 else 100)) {
                        val code = if (amount < 0) negative else positive
                        key(KeyEvent.ACTION_DOWN, code, meta); key(KeyEvent.ACTION_UP, code, meta)
                    }
                }
                if (lineEdge != 0) {
                    val code = if (lineEdge < 0) KeyEvent.KEYCODE_MOVE_HOME else KeyEvent.KEYCODE_MOVE_END
                    key(KeyEvent.ACTION_DOWN, code, meta); key(KeyEvent.ACTION_UP, code, meta)
                }
            } finally { if (selecting) key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0) }
        }
        // 目标方向还有字符可移动吗。提取文本拿不到时退回「光标前后各取一个字符」的探测；
        // 探测返回 null（宿主没实现）一律放行，宁可不封也不误封。
        private fun canStepCursor(connection: InputConnection, forward: Boolean): Boolean {
            val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
            val text = extracted?.text
            if (extracted != null && text != null) {
                return CursorBoundary(
                    hasSelection = extracted.selectionStart != extracted.selectionEnd,
                    completeText = extracted.partialStartOffset < 0 && extracted.partialEndOffset < 0,
                    startOffset = extracted.startOffset,
                    selectionStart = extracted.selectionStart,
                    selectionEnd = extracted.selectionEnd,
                    textLength = text.length,
                ).canStep(forward)
            }
            val probe = (if (forward) connection.getTextAfterCursor(1, 0) else connection.getTextBeforeCursor(1, 0)) ?: return true
            return probe.isNotEmpty()
        }
        @JavascriptInterface fun edit(action: String) = editInput {
            if (action != "copy-text") selectionDrag.clear()
            if (action != "copy-text" && action != "select-all") { selectionLockedAnchor = -1; annotation.hide() }
            val id = when (action) {
                "select-all" -> android.R.id.selectAll
                "copy-text" -> android.R.id.copy
                "cut-text" -> android.R.id.cut
                "paste-text" -> android.R.id.paste
                "cursor-left", "cursor-right" -> { moveCursor(if (action == "cursor-left") -1 else 1); return@editInput }
                else -> return@editInput
            }
            currentInputConnection?.performContextMenuAction(id)
        }
    }

    override fun onDestroy() {
        resetVoiceLearning()
        setSearchInputActive(false)
        destroyed = true
        getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(clipboardListener)
        main.removeCallbacks(releaseVoice)
        shown = false
        hideClipHint()
        cancelRecording()
        destroyKeyboardView()
        pinyin?.close(); pinyin = null
        pinyinLoader.execute {
            worker.execute { engine?.release(); lexicon.close() }
            worker.shutdown()
        }
        pinyinLoader.shutdown()
        super.onDestroy()
    }

    // Only confirmed local edits of the last voice insertion feed personal learning.
    private val voiceLearning = VoiceCorrectionTracker()
    private var voiceLearningEdited = false
    private var voiceLearningComposing = false
    private var voiceLearningConfirmed = false
    private val learnVoiceEdit = Runnable {
        if (!shown || busy || !voiceLearningEdited || voiceLearningComposing || !allowsVoiceLearning()) return@Runnable
        val surrounding = VoiceEditorSnapshot.read(currentInputConnection) ?: return@Runnable
        val rules = voiceLearning.observeAll(surrounding.text, surrounding.offset, inputGeneration,
            SystemClock.uptimeMillis(), false, VoiceLanguage::phonetic, VoiceLanguage::wordRange, voiceLearningConfirmed)
        if (rules.isNotEmpty()) {
            voiceLearningEdited = false; voiceLearningConfirmed = false
            worker.execute {
                try { lexicon.learnVoice(rules) }
                catch (_: Exception) { main.post { android.widget.Toast.makeText(this@VoxFixImeService, "语音纠正未保存，请稍后重试", android.widget.Toast.LENGTH_SHORT).show() } }
            }
        }
    }
    private fun allowsVoiceLearning(): Boolean {
        val info = currentInputEditorInfo ?: return false
        return !searchInputActive && VoiceCorrectionTracker.allowed(info.inputType, info.imeOptions)
    }
    private fun resetVoiceLearning() {
        main.removeCallbacks(learnVoiceEdit); main.removeCallbacks(expireVoiceLearning)
        voiceLearning.clear(); voiceLearningEdited = false; voiceLearningComposing = false; voiceLearningConfirmed = false
    }
    private fun armVoiceLearning(result: String) {
        if (!allowsVoiceLearning()) return
        val surrounding = VoiceEditorSnapshot.read(currentInputConnection) ?: return
        val known = runCatching { lexicon.voiceWords(96).filter { it.manual }.map { it.word }.toSet() }.getOrDefault(emptySet())
        voiceLearning.arm(surrounding.text, surrounding.offset, voiceStart, result, inputGeneration, SystemClock.uptimeMillis(), known)
        runCatching { currentInputConnection?.getExtractedText(ExtractedTextRequest().apply { token = 7311; hintMaxChars = 1280 }, InputConnection.GET_EXTRACTED_TEXT_MONITOR) }
        main.postDelayed(expireVoiceLearning, 120_000)
    }
    private fun queueVoiceLearning() {
        main.removeCallbacks(learnVoiceEdit)
        if (voiceLearning.active() && voiceLearningEdited) main.postDelayed(learnVoiceEdit, if (voiceLearningConfirmed) 0 else 1200)
    }
    private val expireVoiceLearning = Runnable { resetVoiceLearning() }
    private fun flushVoiceLearning() {
        if (voiceLearning.active()) voiceLearningEdited = true
        voiceLearningConfirmed = true; learnVoiceEdit.run()
    }
    override fun onUpdateExtractedText(token: Int, text: android.view.inputmethod.ExtractedText?) {
        super.onUpdateExtractedText(token, text)
        if (token == 7311 && !busy && voiceLearning.active()) { voiceLearningEdited = true; queueVoiceLearning() }
    }
}
