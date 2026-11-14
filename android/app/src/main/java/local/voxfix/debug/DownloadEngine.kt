package local.voxfix.debug

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 单任务流式下载核心（2026-11-14 从 TranslateDownloader 抽取，更新 APK 共用）：
 * 多源回退 + Range 断点续传 + 416 重下 + 重定向 + 大小/SHA-256 校验 + 取消。
 * 只负责「把 sources 里的地址依次试到产物原子落盘」；重试节奏与状态发布归调用方。
 *
 * 教训沿用 AdbWireless/TranslateDownloader：验证一律以实际字节与 SHA-256 为准，
 * 绝不乐观置位；cancelled() 需在每段阻塞后复查。
 */
internal object DownloadEngine {

    sealed class Progress {
        data class Downloading(val downloaded: Long, val total: Long) : Progress()
        object Verifying : Progress()
    }

    /** cancelled() 为 true 时抛出；调用方据此映射成「已取消」而不是失败。 */
    class Cancelled : Exception("cancelled")

    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000
    private const val MAX_REDIRECTS = 5

    @Volatile private var connection: HttpURLConnection? = null

    /** 立即断开当前连接（取消下载时调用，让阻塞中的 read 马上退出，而不是等 30s 超时）。 */
    fun disconnectCurrent() {
        runCatching { connection?.disconnect() }
    }

    /**
     * 阻塞下载。成功返回 target（partial 原子重命名），失败抛最后一个异常。
     * partial 已是完整长度时跳过网络直接校验（对应「上次下载完、校验前被打断」的续传场景）。
     */
    fun download(
        sources: List<String>,
        partial: File,
        target: File,
        expectedBytes: Long,
        expectedSha256: String,
        allowHttpLoopback: Boolean,
        cancelled: () -> Boolean,
        onProgress: (Progress) -> Unit
    ) {
        require(sources.isNotEmpty()) { "下载地址不可用" }
        if (!partial.exists() || partial.length() != expectedBytes) {
            var lastError: Exception? = null
            var fetched = false
            for (source in sources) {
                try {
                    fetchFrom(source, partial, expectedBytes, allowHttpLoopback, cancelled, onProgress)
                    fetched = true
                    break
                } catch (error: Exception) {
                    lastError = error
                    if (cancelled()) throw error
                }
            }
            if (!fetched) throw lastError ?: IllegalStateException("下载地址不可用")
        }
        verify(partial, expectedBytes, expectedSha256, cancelled, onProgress)
        if (cancelled()) throw Cancelled()
        if (target.exists() && !target.delete()) error("无法覆盖旧文件")
        if (!partial.renameTo(target)) error("无法重命名下载文件")
    }

    private fun fetchFrom(
        source: String,
        partial: File,
        expectedBytes: Long,
        allowHttpLoopback: Boolean,
        cancelled: () -> Boolean,
        onProgress: (Progress) -> Unit
    ) {
        var offset = if (partial.exists()) partial.length() else 0L
        if (offset > expectedBytes) {
            partial.delete()
            offset = 0
        }
        var url = URL(source)
        var redirects = 0
        while (true) {
            if (cancelled()) throw Cancelled()
            // 正式源强制 HTTPS；loopback HTTP 仅 debug 联调（adb reverse）放行。
            val loopback = url.host == "127.0.0.1" || url.host == "localhost"
            require(url.protocol == "https" || (allowHttpLoopback && loopback)) { "下载需要安全连接" }
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", "VoxFix-download")
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
            if (code == 206 && !TranslateDownloadRange.valid(conn.getHeaderField("Content-Range"), offset, expectedBytes)) {
                conn.disconnect()
                error("下载续传范围不匹配，请重试")
            }
            if (!append) offset = 0
            val handle = FileOutputStream(partial, append)
            try {
                conn.inputStream.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var downloaded = offset
                    while (true) {
                        if (cancelled()) throw Cancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (downloaded + read > expectedBytes) error("下载文件超过预期大小")
                        handle.write(buffer, 0, read)
                        val before = downloaded
                        downloaded += read
                        if (downloaded / (1024 * 1024) != before / (1024 * 1024))
                            onProgress(Progress.Downloading(downloaded, expectedBytes))
                    }
                }
                handle.flush()
                handle.fd.sync()
            } finally {
                handle.close()
                conn.disconnect()
                connection = null
            }
            if (partial.length() != expectedBytes) error("文件长度不符：${partial.length()}")
            return
        }
    }

    private fun verify(
        partial: File,
        expectedBytes: Long,
        expectedSha256: String,
        cancelled: () -> Boolean,
        onProgress: (Progress) -> Unit
    ) {
        if (partial.length() != expectedBytes) error("文件长度不符：${partial.length()}")
        onProgress(Progress.Verifying)
        val digest = MessageDigest.getInstance("SHA-256")
        partial.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                if (cancelled()) throw Cancelled()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        if (expectedSha256.isEmpty()) return
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (actual != expectedSha256) {
            partial.delete()
            error("文件校验失败，已删除，请重新下载")
        }
    }
}
