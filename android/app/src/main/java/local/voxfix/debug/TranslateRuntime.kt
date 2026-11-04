package local.voxfix.debug

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** The only surface the screenshot overlay sees, so the toolbar smoke test can inject a fake. */
interface ScreenshotTranslator {
    fun translate(text: String, pair: TranslatePair, onResult: (String) -> Unit, onFailure: (String) -> Unit,
                  onUpdate: (String) -> Unit = {})
    fun cancel()
    fun available(): Boolean
}

/** One JNI session at a time, running off the main thread. */
internal object LlamaBridge {
    init {
        System.loadLibrary("voxfix_translate")
    }

    external fun load(path: String, threads: Int, context: Int, pinCores: Boolean = true,
                      quantizedKv: Boolean = true, batch: Int = 256, flashAttn: Int = -1): Long
    external fun translate(handle: Long, prompt: String, maxTokens: Int, timeoutMs: Long,
                           onUpdate: ((ByteArray) -> Unit)? = null): String
    external fun cancel(handle: Long)
    external fun free(handle: Long)
}

object TranslateRuntime : ScreenshotTranslator {
    private const val THREADS = 4
    private const val CONTEXT_TOKENS = 2048
    /** Measured on the K60: 4 threads on the big cluster beat 6 (which spilled onto the A510s and
     *  produced 0 tokens in 180 s), and a 512-token prefill batch changed nothing for a 150-token
     *  sample, so the 256 default stays. */
    private const val PREFILL_BATCH = 256
    private const val MAX_OUTPUT_TOKENS = 1024
    private const val IDLE_RELEASE_MS = 120_000L

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "VoxFix-translate") }
    private val cancelled = AtomicBoolean(false)
    private val nativeLock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var handle = 0L
    @Volatile private var loadedPath: String? = null
    @Volatile private var active = false
    private val release = Runnable { releaseNow() }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    override fun available(): Boolean = appContext?.let { TranslateModelStore(it).ready() } == true

    fun isBusy(): Boolean = active
    fun occupiesMemory(): Boolean = active || handle != 0L

    /** Voice has priority; wait off the UI thread until the native translator has unwound. */
    fun releaseForVoice() {
        cancel()
        releaseNow()
        worker.submit {}.get(90,java.util.concurrent.TimeUnit.SECONDS)
    }

    override fun translate(
        text: String,
        pair: TranslatePair,
        onResult: (String) -> Unit,
        onFailure: (String) -> Unit,
        onUpdate: (String) -> Unit
    ) {
        if (active) { onFailure("上一次翻译还在进行"); return }
        val context = appContext
        if (context == null) {
            onFailure("翻译尚未初始化")
            return
        }
        if (text.isBlank()) {
            onFailure("没有可翻译的文字")
            return
        }
        val store = TranslateModelStore(context)
        if (!store.ready()) {
            onFailure("翻译模型未下载")
            return
        }
        cancelled.set(false)
        active = true
        main.removeCallbacks(release)
        worker.execute {
            try {
                val session = ensureLoaded(store)
                val chunks = TranslateRequest.chunkParts(text)
                if (chunks.isEmpty()) throw IllegalStateException("没有可翻译的文字")
                val translated = StringBuilder()
                val pending = java.util.ArrayDeque(chunks)
                while (pending.isNotEmpty()) {
                    val chunk = pending.removeFirst()
                    if (cancelled.get()) throw InterruptedException("cancelled")
                    val prompt = TranslateRequest.buildPrompt(pair, chunk.text)
                    val started = System.nanoTime()
                    val timeout = TranslateRequest.chunkTimeoutMs(chunk.text.length)
                    val completed = translated.toString()
                    val output = try { LlamaBridge.translate(session, prompt, MAX_OUTPUT_TOKENS, timeout) { bytes ->
                        val partial = TranslateRequest.sanitize(TranslateRequest.decodePartial(bytes))
                        if (partial.isNotBlank()) {
                            val value = TranslateRequest.joinTranslated(completed, partial, pair, chunk.paragraphStart)
                            main.post { if (!cancelled.get()) onUpdate(value) }
                        }
                    } } catch (error: Exception) {
                        if (error.message in listOf("OUTPUT_LIMIT","PROMPT_TOO_LONG") && chunk.text.length>80) {
                            val parts = TranslateRequest.chunkParts(chunk.text,chunk.text.length/2)
                            if (parts.size>1) {
                                parts.asReversed().forEachIndexed { index, part ->
                                    pending.addFirst(
                                        if (index==parts.lastIndex) part.copy(paragraphStart=chunk.paragraphStart)
                                        else part.copy(paragraphStart=false))
                                }
                                continue
                            }
                        }
                        throw error
                    }
                    val clean = TranslateRequest.sanitize(output)
                    if (clean.isBlank()) throw IllegalStateException("翻译结果为空")
                    if (translated.isNotEmpty()) translated.setLength(0)
                    translated.append(TranslateRequest.joinTranslated(completed, clean, pair, chunk.paragraphStart))
                    Log.i("VoxFix", "TRANSLATE_CHUNK chars=${chunk.text.length} paragraph=${chunk.paragraphStart} " +
                        "timeout_ms=$timeout ms=${(System.nanoTime() - started) / 1_000_000} out=${clean.length}")
                }
                val result = translated.toString()
                main.post {
                    active = false
                    if (!cancelled.get()) onResult(result) else onFailure("翻译已取消")
                    scheduleRelease()
                }
            } catch (error: Exception) {
                Log.w("VoxFix", "TRANSLATE_FAILED", error)
                val message = when {
                    cancelled.get() -> "翻译已取消"
                    error is InterruptedException -> "翻译已取消"
                    error.message == "TIMEOUT" -> "翻译超时，请缩小选区"
                    error.message == "PROMPT_TOO_LONG" -> "文字过长，请缩小选区"
                    error.message == "OUTPUT_LIMIT" -> "译文过长，请缩小选区"
                    error.message == "BUSY" -> "上一次翻译还在进行"
                    error.message == "SESSION" -> "翻译模型未加载"
                    error.message == "VOICE_BUSY" -> "语音正在使用，请结束后翻译"
                    else -> "翻译失败，请重试"
                }
                main.post {
                    active = false
                    onFailure(message)
                    scheduleRelease()
                }
            }
        }
    }

    override fun cancel() {
        cancelled.set(true)
        synchronized(nativeLock) {
            val current = handle
            if (current != 0L) runCatching { LlamaBridge.cancel(current) }
        }
    }

    /** Called by services when the process is under pressure; never frees while a request runs. */
    fun trim(level: Int) {
        if (level >= 10 && !active) releaseNow()
    }

    fun idle() {
        if (handle != 0L && !active) scheduleRelease()
    }

    fun releaseNow() {
        main.removeCallbacks(release)
        worker.execute {
          synchronized(nativeLock) {
            val current = handle
            handle = 0L
            loadedPath = null
            if (current != 0L) {
                runCatching { LlamaBridge.free(current) }
                runCatching { NativeMemory.purge() }
                Log.i("VoxFix", "TRANSLATE_RELEASED")
            }
          }
        }
    }

    private fun scheduleRelease() {
        main.removeCallbacks(release)
        main.postDelayed(release, IDLE_RELEASE_MS)
    }

    private fun ensureLoaded(store: TranslateModelStore): Long {
        val path = store.model.absolutePath
        val existing = handle
        if (existing != 0L && loadedPath == path) return existing
        synchronized(nativeLock) {
            if (handle != 0L && loadedPath == path) return handle
            if (handle != 0L) {
                runCatching { LlamaBridge.free(handle) }
                handle = 0L
            }
            // Translation is a foreground, on-demand action; give its weights room by
            // dropping the speech models first instead of letting both live in one process.
            check(VoxFixImeService.releaseVoiceForTranslation()) { "VOICE_BUSY" }
            val context = appContext ?: throw IllegalStateException("翻译尚未初始化")
            TranslateModelPatcher.ensure(context, store.model)
            val started = System.nanoTime()
            val next = LlamaBridge.load(path, THREADS, CONTEXT_TOKENS, batch = PREFILL_BATCH)
            if (next == 0L) throw IllegalStateException("翻译模型加载失败")
            handle = next
            loadedPath = path
            Log.i("VoxFix", "TRANSLATE_LOAD ms=${(System.nanoTime() - started) / 1_000_000}")
            return next
        }
    }
}
