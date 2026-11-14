package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest

/**
 * DownloadEngine 传输核心回归（本机回环 socket 手写 HTTP 响应，无外网依赖。
 * 注意：com.sun.net.httpserver 不在 Android 单测编译类路径里，这里只能用 java.net）：
 * 多源回退、Range 续传、SHA-256 门禁、loopback HTTP 门禁、大小校验。
 */
class DownloadEngineTest {

    private val payload: ByteArray = ByteArray(3 * 1024 * 1024 + 12345) { (it % 251).toByte() }
    private val sha256: String =
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** path → (Range 头 → status + body)。204/404 等错误状态 body 不被 DownloadEngine 读取。 */    private inner class RawServer(private val routes: Map<String, (String?) -> Pair<Int, ByteArray>>) {
        private val socket = ServerSocket(0, 20, InetAddress.getLoopbackAddress())
        val port: Int get() = socket.localPort
        private var closed = false

        fun start(): RawServer {
            Thread {
                try {
                    while (!closed) handle(socket.accept())
                } catch (_: Exception) {
                    // stop() 关闭 socket 后 accept 抛异常退出，属预期。
                }
            }.apply { isDaemon = true; start() }
            return this
        }

        fun stop() {
            closed = true
            runCatching { socket.close() }
        }

        private fun handle(client: java.net.Socket) {
            runCatching {
                client.use { conn ->
                    val reader = conn.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: return@use
                    var range: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                    }
                    val path = requestLine.split(' ')[1]
                    val handler = routes[path] ?: return@use
                    val (status, body) = handler(range)
                    val reason = when (status) { 200 -> "OK"; 206 -> "Partial Content"; else -> "Not Found" }
                    val out = conn.getOutputStream()
                    out.write("HTTP/1.1 $status $reason\r\n".toByteArray())
                    if (status == 206 && range != null) {
                        val start = range.substringAfter("bytes=").substringBefore('-').toLong()
                        out.write("Content-Range: bytes $start-${payload.size - 1}/${payload.size}\r\n".toByteArray())
                    }
                    out.write("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                    out.flush()
                }
            }
        }
    }

    private fun digestOf(file: File): String =
        MessageDigest.getInstance("SHA-256").let { md ->
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

    @Test
    fun `falls back to second source on 404`() {
        val server = RawServer(mapOf(
            "/missing" to { _ -> Pair(404, ByteArray(0)) },
            "/apk" to { _ -> Pair(200, payload) }
        )).start()
        val dir = File.createTempFile("dltest", "tmp").parentFile
        val partial = File(dir, "test-fallback.part")
        val target = File(dir, "test-fallback.bin")
        try {
            DownloadEngine.download(
                sources = listOf("http://127.0.0.1:${server.port}/missing", "http://127.0.0.1:${server.port}/apk"),
                partial = partial, target = target,
                expectedBytes = payload.size.toLong(), expectedSha256 = sha256,
                allowHttpLoopback = true, cancelled = { false }, onProgress = { })
            assertTrue(target.isFile)
            assertEquals(payload.size.toLong(), target.length())
            assertEquals(sha256, digestOf(target))
        } finally {
            server.stop()
            partial.delete(); target.delete()
        }
    }

    @Test
    fun `resumes from partial via range request`() {
        var sawRange = false
        val server = RawServer(mapOf(
            "/apk" to { range ->
                if (range != null) {
                    sawRange = true
                    val start = range.substringAfter("bytes=").substringBefore('-').toInt()
                    Pair(206, payload.copyOfRange(start, payload.size))
                } else Pair(200, payload)
            }
        )).start()
        val dir = File.createTempFile("dltest", "tmp").parentFile
        val partial = File(dir, "test-resume.part")
        val target = File(dir, "test-resume.bin")
        try {
            // 预置半截 .part（1MB），模拟上次中断。
            partial.writeBytes(payload.copyOfRange(0, 1024 * 1024))
            DownloadEngine.download(
                sources = listOf("http://127.0.0.1:${server.port}/apk"),
                partial = partial, target = target,
                expectedBytes = payload.size.toLong(), expectedSha256 = sha256,
                allowHttpLoopback = true, cancelled = { false }, onProgress = { })
            assertTrue("应发起 Range 续传请求", sawRange)
            assertEquals(payload.size.toLong(), target.length())
            assertEquals(sha256, digestOf(target))
        } finally {
            server.stop()
            partial.delete(); target.delete()
        }
    }

    @Test
    fun `sha256 mismatch deletes partial and fails`() {
        val server = RawServer(mapOf("/apk" to { _ -> Pair(200, payload) })).start()
        val dir = File.createTempFile("dltest", "tmp").parentFile
        val partial = File(dir, "test-badsha.part")
        val target = File(dir, "test-badsha.bin")
        try {
            val error = runCatching {
                DownloadEngine.download(
                    sources = listOf("http://127.0.0.1:${server.port}/apk"),
                    partial = partial, target = target,
                    expectedBytes = payload.size.toLong(),
                    expectedSha256 = "0".repeat(64),
                    allowHttpLoopback = true, cancelled = { false }, onProgress = { })
            }.exceptionOrNull()
            assertTrue("校验失败必须抛错", error != null)
            assertTrue("坏产物必须删除", !partial.exists())
            assertTrue("绝不能落 target", !target.exists())
        } finally {
            server.stop()
            partial.delete(); target.delete()
        }
    }

    @Test
    fun `plain http is refused unless loopback allowed`() {
        val server = RawServer(mapOf("/apk" to { _ -> Pair(200, payload) })).start()
        val dir = File.createTempFile("dltest", "tmp").parentFile
        val partial = File(dir, "test-gate.part")
        val target = File(dir, "test-gate.bin")
        try {
            val error = runCatching {
                DownloadEngine.download(
                    sources = listOf("http://127.0.0.1:${server.port}/apk"),
                    partial = partial, target = target,
                    expectedBytes = payload.size.toLong(), expectedSha256 = sha256,
                    allowHttpLoopback = false, cancelled = { false }, onProgress = { })
            }.exceptionOrNull()
            assertTrue("非放行场景必须拒绝 http", error != null)
            assertTrue(!target.exists())
        } finally {
            server.stop()
            partial.delete(); target.delete()
        }
    }

    @Test
    fun `complete partial skips network and verifies`() {
        val dir = File.createTempFile("dltest", "tmp").parentFile
        val partial = File(dir, "test-complete.part")
        val target = File(dir, "test-complete.bin")
        try {
            partial.writeBytes(payload)
            DownloadEngine.download(
                sources = listOf("http://127.0.0.1:1/unreachable"),
                partial = partial, target = target,
                expectedBytes = payload.size.toLong(), expectedSha256 = sha256,
                allowHttpLoopback = true, cancelled = { false }, onProgress = { })
            assertEquals(sha256, digestOf(target))
        } finally {
            partial.delete(); target.delete()
        }
    }
}
