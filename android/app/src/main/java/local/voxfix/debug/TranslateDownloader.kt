package local.voxfix.debug

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Downloads the optional 461.9 MB translation model with Range resume and a SHA-256 gate.
 *  Deliberately no foreground service: the accessibility service keeps the process alive while
 *  a capture session is open, and a killed process resumes from the .part file next time. */
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
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile var state: State = State.Idle
        private set
    private var listener: ((State) -> Unit)? = null

    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000
    private const val MAX_REDIRECTS = 5

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
            } finally {
                connection = null
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
        runCatching { connection?.disconnect() }
        connection = null
        if (!running.get()) publish(State.Idle)
    }

    private fun downloadWithRetry(store: TranslateModelStore): File {
        var last: Exception? = null
        val backoff = longArrayOf(2_000L, 8_000L, 30_000L)
        for (attempt in 0..3) {
            try {
                if (store.partial.length() != TranslateModelStore.BYTES) fetch(store)
                verify(store)
                if (cancelled.get()) throw InterruptedException("cancelled")
                if (!store.partial.renameTo(store.model)) error("无法重命名模型文件")
                return store.model
            } catch (error: Exception) {
                last = error
                if (cancelled.get()) throw error
                if (attempt < backoff.size) Thread.sleep(backoff[attempt])
            }
        }
        throw last ?: IllegalStateException("下载失败")
    }

    private fun fetch(store: TranslateModelStore) {
        var lastError: Exception? = null
        for (source in sources) {
            try {
                fetchFrom(source, store.partial)
                return
            } catch (error: Exception) {
                lastError = error
                if (cancelled.get()) throw error
                android.util.Log.w("VoxFix", "TRANSLATE_SOURCE_FAILED $source", error)
            }
        }
        throw lastError ?: IllegalStateException("下载地址不可用")
    }

    private fun fetchFrom(source: String, partial: File) {
        var offset = if (partial.exists()) partial.length() else 0L
        if (offset > TranslateModelStore.BYTES) {
            partial.delete()
            offset = 0
        }
        var url = URL(source)
        var redirects = 0
        while (true) {
            if (cancelled.get()) throw InterruptedException("cancelled")
            require(url.protocol == "https") { "模型下载需要安全连接" }
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", "VoxFix-translate")
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            connection = conn
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: error("重定向缺少地址")
                conn.disconnect()
                if (++redirects > MAX_REDIRECTS) error("重定向次数过多")
                url = URL(url, location)
                continue
            }
            if (code == 416) {
                conn.disconnect()
                if (offset == 0L) error("下载地址拒绝了请求")
                partial.delete()
                offset = 0
                continue
            }
            if (code !in 200..299) {
                val message = "下载失败 HTTP $code"
                conn.disconnect()
                error(message)
            }
            val append = code == 206 && offset > 0
            if (code == 206) {
                val range = conn.getHeaderField("Content-Range")
                if (!validRange(range,offset)) {
                    conn.disconnect()
                    error("下载续传范围不匹配，请重试")
                }
            }
            if (!append) offset = 0
            val handle = FileOutputStream(partial, append)
            try {
                conn.inputStream.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var downloaded = offset
                    while (true) {
                        if (cancelled.get()) throw InterruptedException("cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (downloaded+read > TranslateModelStore.BYTES) error("下载文件超过预期大小")
                        handle.write(buffer, 0, read)
                        val before = downloaded
                        downloaded += read
                        if (downloaded / (1024 * 1024) != before / (1024 * 1024))
                            publish(State.Running(downloaded, TranslateModelStore.BYTES))
                    }
                }
                handle.flush()
                handle.fd.sync()
            } finally {
                handle.close()
                conn.disconnect()
                connection = null
            }
            if (partial.length() != TranslateModelStore.BYTES) {
                error("文件长度不符：${partial.length()}")
            }
            return
        }
    }

    private fun verify(store: TranslateModelStore) {
        publish(State.Running(TranslateModelStore.BYTES, TranslateModelStore.BYTES, verifying = true))
        val digest = MessageDigest.getInstance("SHA-256")
        store.partial.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                if (cancelled.get()) throw InterruptedException("cancelled")
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (actual != TranslateModelStore.SHA256) {
            store.partial.delete()
            error("文件校验失败，已删除，请重新下载")
        }
    }

    private fun publish(next: State) {
        state = next
        val target = listener ?: return
        main.post { target(next) }
    }

    private fun message(error: Exception): String = when (error) {
        is InterruptedException -> "下载已取消"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "下载失败，请重试"
    }

    internal fun validRange(value: String?, offset: Long): Boolean = TranslateDownloadRange.valid(value,offset,TranslateModelStore.BYTES)
}
