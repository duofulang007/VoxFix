package local.voxfix.debug

import android.graphics.Bitmap
import android.os.Build
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Serialises captured frames into an animated image for [ScreenRecordingOptions.Format].
 *
 * The recorder thread hands over a frame and immediately returns; encoding happens on this thread
 * so a slow palette or VP8 pass never stalls the capture. At most one frame waits, and a frame that
 * arrives while the writer is busy is dropped — the next frame's delay already covers the gap, so
 * playback keeps real time instead of stretching.
 */
internal class ScreenAnimationWriter(
    private val format: ScreenRecordingOptions.Format,
    private val file: File,
    private val width: Int,
    private val height: Int,
    private val loop: Boolean,
    private val quality: Int
) {
    private val lock = Object()
    private var pending: IntArray? = null
    private var pendingDelayMs = 0
    private var closing = false
    private var closed = false
    private var drained = false
    private var failure: Throwable? = null

    private var stream: BufferedOutputStream? = null
    private var gif: GifEncoder? = null
    private var apng: ApngEncoder? = null
    private var webp: WebpAnimationWriter? = null
    private var quantizer: GifPaletteQuantizer? = null
    private var bitmap: Bitmap? = null

    private lateinit var thread: Thread

    /** Allocates the output and starts the encoder thread. Returns null on success. */
    fun start(): String? {
        try {
            when (format) {
                ScreenRecordingOptions.Format.GIF -> {
                    val output = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
                    stream = output
                    gif = GifEncoder(output, width, height, loop).also { it.writeHeader() }
                    quantizer = GifPaletteQuantizer()
                }
                ScreenRecordingOptions.Format.APNG -> {
                    apng = ApngEncoder(file, width, height, loop).also { it.writeHeader() }
                }
                ScreenRecordingOptions.Format.WEBP -> {
                    webp = WebpAnimationWriter(file, width, height, loop).also { it.writeHeader() }
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }
                ScreenRecordingOptions.Format.MP4 -> return "MP4 不使用动画写出"
            }
        } catch (error: Throwable) {
            android.util.Log.w("VoxFix", "SCREEN_ANIMATION_OPEN_FAILED", error)
            closeContainer()
            release()
            return "无法创建动画录制文件"
        }
        thread = Thread({ run() }, "voxfix-animation-export").apply {
            isDaemon = true
            start()
        }
        return null
    }

    /** Offers a frame; false means the writer was busy and the frame was dropped. */
    fun addFrame(frame: IntArray, delayMs: Int): Boolean {
        synchronized(lock) {
            if (closing || failure != null) return false
            if (pending != null) return false
            pending = frame
            pendingDelayMs = delayMs
            lock.notifyAll()
            return true
        }
    }

    /** Stops accepting frames, drains the queue and finalises the container. */
    fun finish(): Boolean {
        synchronized(lock) {
            closing = true
            lock.notifyAll()
            val deadline = System.currentTimeMillis() + 5_000
            while (!drained && failure == null && System.currentTimeMillis() < deadline) {
                try {
                    lock.wait(50)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return failure == null && file.isFile && file.length() > 0
    }

    fun abort() {
        synchronized(lock) {
            closing = true
            lock.notifyAll()
        }
        runCatching { thread.join(1_000) }
        closeContainer()
        release()
    }

    private fun run() {
        try {
            while (true) {
                val (frame, delay) = synchronized(lock) {
                    while (pending == null && !closing) {
                        try {
                            lock.wait(100)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    val frame = pending
                    val delay = pendingDelayMs
                    pending = null
                    frame to delay
                }
                if (frame != null) {
                    write(frame, delay)
                    continue
                }
                if (closing) break
            }
        } catch (error: Throwable) {
            android.util.Log.w("VoxFix", "SCREEN_ANIMATION_WRITE_FAILED", error)
            failure = error
        } finally {
            // Close the container even after a failure so no handle and no half-written tail is
            // left behind; `finish()` reports the failure through [failure] instead.
            closeContainer()
            release()
            synchronized(lock) {
                drained = true
                lock.notifyAll()
            }
        }
    }

    private fun write(frame: IntArray, delayMs: Int) {
        when (format) {
            ScreenRecordingOptions.Format.GIF -> {
                val quantizer = quantizer ?: return
                val target = gif ?: return
                target.writeFrame(quantizer.convert(frame, width, height), delayMs)
            }
            ScreenRecordingOptions.Format.APNG -> apng?.writeFrame(frame, delayMs)
            ScreenRecordingOptions.Format.WEBP -> {
                val target = webp ?: return
                val canvas = bitmap ?: return
                canvas.setPixels(frame, 0, width, 0, 0, width, height)
                val encoded = ByteArrayOutputStream(1 shl 16)
                val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    Bitmap.CompressFormat.WEBP_LOSSY
                else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                if (!canvas.compress(compressFormat, quality.coerceIn(0, 100), encoded)) {
                    throw IllegalStateException("WebP 帧编码失败")
                }
                target.writeFrame(encoded.toByteArray(), delayMs)
            }
            ScreenRecordingOptions.Format.MP4 -> Unit
        }
    }

    private fun closeContainer() {
        if (closed) return
        closed = true
        when (format) {
            ScreenRecordingOptions.Format.GIF -> {
                runCatching { gif?.finish() }
                runCatching { stream?.flush() }
                runCatching { stream?.close() }
            }
            ScreenRecordingOptions.Format.APNG -> runCatching { apng?.finish() }
            ScreenRecordingOptions.Format.WEBP -> runCatching { webp?.finish() }
            ScreenRecordingOptions.Format.MP4 -> Unit
        }
    }

    private fun release() {
        gif = null
        stream = null
        apng = null
        webp = null
        runCatching { bitmap?.recycle() }
        bitmap = null
    }
}
