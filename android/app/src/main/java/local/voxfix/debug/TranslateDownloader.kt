package local.voxfix.debug

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Downloads the optional 461.9 MB translation model with Range resume and a SHA-256 gate.
 *  Deliberately no foreground service: the accessibility service keeps the process alive while
 *  a capture session is open, and a killed process resumes from the .part file next time.
 *  2026-11-14：传输/校验核心抽取到 DownloadEngine（更新 APK 共用），本对象只保留
 *  状态发布、重试节奏与对外 API。 */
object TranslateDownloader {
    sealed class State {
        object Idle : State()
        data class Running(val downloaded: Long, val total: Long, val verifying: Boolean = false) : State()
        object Ready : State()
        data class Failed(val message: String) : State()
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "VoxFix-translate-download") }
    private val cancelled = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    @Volatile var state: State = State.Idle
        private set
    private var listener: ((State) -> Unit)? = null

    private val sources = listOf(
        "https://hf-mirror.com/tencent/Hy-MT2-1.8B-1.25Bit-GGUF/resolve/main/Hy-MT2-1.8B-1.25Bit.gguf",
        "https://modelscope.cn/models/AngelSlim/Hy-MT2-1.8B-1.25Bit-GGUF/resolve/master/Hy-MT2-1.8B-1.25Bit.gguf"
    )

    fun observe(listener: ((State) -> Unit)?) {
        this.listener = listener
        listener?.let { main.post { it(state) } }
    }

    fun start(context: Context, listener: ((State) -> Unit)? = null) {
        this.listener = listener
        val app = context.applicationContext
        val store = TranslateModelStore(app)
        if (store.ready()) {
            publish(State.Ready)
            return
        }
        if (running.get()) return
        if (store.freeBytes() < TranslateModelStore.MIN_FREE_BYTES) {
            publish(State.Failed("可用空间不足，需要约 620MB"))
            return
        }
        store.ensureDirectory()
        if (!running.compareAndSet(false,true)) return
        cancelled.set(false)
        publish(State.Running(store.partial.length(), TranslateModelStore.BYTES))
        worker.execute {
            try {
                val file = downloadWithRetry(store)
                running.set(false)
                publish(State.Ready)
                android.util.Log.i("VoxFix", "TRANSLATE_MODEL_READY bytes=${file.length()}")
            } catch (error: Exception) {
                running.set(false)
                if (cancelled.get()) publish(State.Idle)
                else publish(State.Failed(message(error)))
                android.util.Log.w("VoxFix", "TRANSLATE_MODEL_FAILED", error)
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
        DownloadEngine.disconnectCurrent()
        if (!running.get()) publish(State.Idle)
    }

    private fun downloadWithRetry(store: TranslateModelStore): File {
        var last: Exception? = null
        val backoff = longArrayOf(2_000L, 8_000L, 30_000L)
        for (attempt in 0..3) {
            try {
                DownloadEngine.download(
                    sources = sources,
                    partial = store.partial,
                    target = store.model,
                    expectedBytes = TranslateModelStore.BYTES,
                    expectedSha256 = TranslateModelStore.SHA256,
                    allowInsecureHttp = false,
                    cancelled = { cancelled.get() },
                    onProgress = { progress ->
                        when (progress) {
                            is DownloadEngine.Progress.Downloading ->
                                publish(State.Running(progress.downloaded, progress.total))
                            DownloadEngine.Progress.Verifying ->
                                publish(State.Running(TranslateModelStore.BYTES, TranslateModelStore.BYTES, verifying = true))
                        }
                    }
                )
                if (cancelled.get()) throw DownloadEngine.Cancelled()
                return store.model
            } catch (error: Exception) {
                last = error
                if (cancelled.get()) throw error
                if (attempt < backoff.size) Thread.sleep(backoff[attempt])
            }
        }
        throw last ?: IllegalStateException("下载失败")
    }

    private fun publish(next: State) {
        state = next
        val target = listener ?: return
        main.post { target(next) }
    }

    private fun message(error: Exception): String = when (error) {
        is InterruptedException -> "下载已取消"
        is DownloadEngine.Cancelled -> "下载已取消"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "下载失败，请重试"
    }

    internal fun validRange(value: String?, offset: Long): Boolean = TranslateDownloadRange.valid(value,offset,TranslateModelStore.BYTES)
}
