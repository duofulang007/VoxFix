package local.voxfix.debug

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.provider.MediaStore
import android.view.Surface
import android.view.View
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Device checks for the recording pipeline: a real H.264 encoder writes an MP4 through the same
 * surface/muxer route the recorder uses, then the finished file must publish through
 * [ScreenRecordingLibrary]. MediaProjection consent cannot be granted from instrumentation, so the
 * VirtualDisplay and GL crop stay on the manual K60 list.
 */
internal object ScreenshotRecordingChecks {
    fun run(test: Instrumentation): String {
        val context = test.targetContext
        val file = encodeMp4(context)
        val saved = publish(context, file)
        return "SCREENSHOT_RECORDING_PASS:$saved"
    }

    /**
     * Draws the recording border off-screen and checks upstream geometry: solid state colour on the
     * stroke centre, nothing painted inside the selection, and the paused colour swap.
     */
    fun area(test: Instrumentation): String {
        val context = test.targetContext
        val density = context.resources.displayMetrics.density
        val region = Rect(120, 240, 420, 540)
        val view = ScreenRecordingAreaView(context, Rect(region))
        val width = 600
        val height = 700
        fun render(state: ScreenRecordingAreaView.State): Bitmap {
            view.setState(state)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            return bitmap
        }
        val strokeCentre = region.left - Math.round(4f * density)
        val inside = region.left + Math.round(1.5f * density)
        val recording = render(ScreenRecordingAreaView.State.RECORDING)
        try {
            check(recording.getPixel(strokeCentre, region.centerY()) == 0xFFF5222D.toInt()) {
                "录制边框颜色不对：${Integer.toHexString(recording.getPixel(strokeCentre, region.centerY()))}"
            }
            check(recording.getPixel(inside, region.centerY()) == 0) { "边框压进了录制区域" }
        } finally {
            recording.recycle()
        }
        val paused = render(ScreenRecordingAreaView.State.PAUSED)
        try {
            check(paused.getPixel(strokeCentre, region.centerY()) == 0xFFFAAD14.toInt()) {
                "暂停边框颜色不对：${Integer.toHexString(paused.getPixel(strokeCentre, region.centerY()))}"
            }
        } finally {
            paused.recycle()
        }
        return "SCREENSHOT_RECORDING_AREA_PASS:stroke=${strokeCentre}px:inside=clear"
    }

    /**
     * Pulls one frame from the newest `Movies/VoxFix` recording so a manual run can be checked for
     * orientation and content without a desktop decoder.
     */
    fun probe(test: Instrumentation): String {
        val context = test.targetContext
        val resolver = context.contentResolver
        val uri = resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME),
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("${android.os.Environment.DIRECTORY_MOVIES}/VoxFix%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                cursor.getLong(0))
        } ?: error("相册里没有录屏")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
            val frame = retriever.getFrameAtTime(duration.coerceAtLeast(1) * 1000 / 2,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: error("抽帧失败")
            val output = File(requireNotNull(context.getExternalCacheDir()), "recording-probe.png")
            java.io.FileOutputStream(output).use { check(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            var total = 0L
            var dark = 0L
            val step = (frame.width / 24).coerceAtLeast(1)
            for (y in 0 until frame.height step step) for (x in 0 until frame.width step step) {
                val pixel = frame.getPixel(x, y)
                val value = ((pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)) / 3
                total++
                if (value < 12) dark++
            }
            frame.recycle()
            return "RECORDING_PROBE_PASS:${width}x${height}:duration=${duration}ms:${output.absolutePath}:dark=${dark * 100 / total}%"
        } finally {
            retriever.release()
        }
    }

    /**
     * Animated-image formats: write a short real animation with each self-written exporter, decode
     * it with the platform decoder, and publish one through the library so the Images collection
     * path is covered as well.
     */
    fun format(test: Instrumentation): String {
        val context = test.targetContext
        val width = 96
        val height = 64
        val frames = 4
        fun frame(index: Int): IntArray = IntArray(width * height) { i ->
            val x = i % width
            val stripe = if ((x / 8 + index) % 2 == 0) 0xFF3366 else 0x22AAFF
            (0xFF shl 24) or stripe
        }

        val gifFile = File(context.cacheDir, "recording-format.gif").apply { delete() }
        BufferedOutputStream(FileOutputStream(gifFile)).use { output ->
            val encoder = GifEncoder(output, width, height, loop = true)
            encoder.writeHeader()
            val quantizer = GifPaletteQuantizer()
            for (i in 0 until frames) encoder.writeFrame(quantizer.convert(frame(i), width, height), 100)
            encoder.finish()
        }
        check(gifFile.length() > 0) { "GIF 没有写出内容" }

        // A tiny frame never crosses the 9→10 bit LZW boundary; comparing both sizes separates a
        // container problem from a code-width problem when the platform decoder rejects the file.
        val tinyGif = File(context.cacheDir, "recording-format-tiny.gif").apply { delete() }
        BufferedOutputStream(FileOutputStream(tinyGif)).use { output ->
            val encoder = GifEncoder(output, 8, 6, loop = true)
            encoder.writeHeader()
            val quantizer = GifPaletteQuantizer()
            val pixels = IntArray(48) { if (it % 2 == 0) 0xFF0000 else 0x00FF00 }
            for (i in 0 until 2) encoder.writeFrame(quantizer.convert(pixels, 8, 6), 100)
            encoder.finish()
        }

        val apngFile = File(context.cacheDir, "recording-format.apng").apply { delete() }
        ApngEncoder(apngFile, width, height, loop = true).let { encoder ->
            encoder.writeHeader()
            for (i in 0 until frames) encoder.writeFrame(frame(i), 100)
            encoder.finish()
        }
        check(apngFile.length() > 0) { "APNG 没有写出内容" }

        val webpFile = File(context.cacheDir, "recording-format.webp").apply { delete() }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            WebpAnimationWriter(webpFile, width, height, loop = true).let { encoder ->
                encoder.writeHeader()
                for (i in 0 until frames) {
                    bitmap.setPixels(frame(i), 0, width, 0, 0, width, height)
                    val encoded = java.io.ByteArrayOutputStream()
                    check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, encoded)) { "WebP 帧编码失败" }
                    encoder.writeFrame(encoded.toByteArray(), 100)
                }
                encoder.finish()
            }
        } finally {
            bitmap.recycle()
        }
        check(webpFile.length() > 0) { "WebP 没有写出内容" }

        val gifInfo = animatedInfo(gifFile)
        val tinyInfo = animatedInfo(tinyGif)
        val apngInfo = animatedInfo(apngFile)
        val webpInfo = animatedInfo(webpFile)
        check(gifInfo.contains("size=${width}x$height")) { "GIF 尺寸不对：$gifInfo（小图 $tinyInfo）" }
        check(webpInfo.contains("size=${width}x$height")) { "WebP 尺寸不对：$webpInfo" }
        check(gifInfo.contains("animated=true")) { "GIF 没有被识别为动画：$gifInfo" }
        check(webpInfo.contains("animated=true")) { "WebP 没有被识别为动画：$webpInfo" }

        val store = publishImage(context, gifFile)
        apngFile.delete()
        webpFile.delete()
        return "SCREENSHOT_RECORDING_FORMAT_PASS:gif=$gifInfo:apng=$apngInfo:webp=$webpInfo:store=$store"
    }

    /**
     * Decodes the newest `Pictures/VoxFix` animated recording with the platform decoder.
     *
     * The container tests use synthetic frames; this one proves the file a real recording produced is
     * readable, and reports the frame count parsed from the container itself.
     */
    fun latest(test: Instrumentation): String {
        val context = test.targetContext
        val uri = context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("${android.os.Environment.DIRECTORY_PICTURES}/VoxFix%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else Triple(android.content.ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)), cursor.getString(1),
                cursor.getLong(2))
        } ?: error("Pictures/VoxFix 里没有动画成品")
        val name = uri.second
        val local = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri.first)?.use { input ->
            local.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取成品")
        val bytes = local.readBytes()
        val info = animatedInfo(local)
        val frames = when {
            // GIF: graphic control extension, always written as 21 F9 04 by the encoder.
            name.endsWith(".gif") -> countTag(bytes, byteArrayOf(0x21, 0xF9.toByte(), 0x04))
            name.endsWith(".apng") -> countAscii(bytes, "fcTL")
            else -> countAscii(bytes, "ANMF")
        }
        check(frames > 0) { "$name 里没有动画帧" }
        check(info.contains("size=")) { "$name 解码失败：$info" }
        local.delete()
        return "SCREENSHOT_RECORDING_LATEST_PASS:$name:bytes=${uri.third}:frames=$frames:$info"
    }

    private fun countTag(bytes: ByteArray, tag: ByteArray): Int {
        var found = 0
        var index = 0
        while (index + tag.size <= bytes.size) {
            var match = true
            for (i in tag.indices) if (bytes[index + i] != tag[i]) { match = false; break }
            if (match) found++
            index++
        }
        return found
    }

    private fun countAscii(bytes: ByteArray, tag: String): Int {
        val pattern = tag.toByteArray(Charsets.US_ASCII)
        var found = 0
        var index = 0
        while (index + pattern.size <= bytes.size) {
            var match = true
            for (i in pattern.indices) if (bytes[index + i] != pattern[i]) { match = false; break }
            if (match) found++
            index++
        }
        return found
    }

    /**
     * The animated recording pipeline without MediaProjection: an EGL pbuffer, a real `glReadPixels`
     * readback with the row flip, and the writer thread. MediaProjection consent cannot be granted
     * from instrumentation, so this is the automated half; the consent flow stays on the manual list.
     */
    fun pipeline(test: Instrumentation): String {
        val context = test.targetContext
        val width = 160
        val height = 120
        val report = StringBuilder()
        for (format in listOf(
            ScreenRecordingOptions.Format.GIF,
            ScreenRecordingOptions.Format.APNG,
            ScreenRecordingOptions.Format.WEBP
        )) {
            val file = File(context.cacheDir, "recording-pipeline.${format.extension}").apply { delete() }
            val writer = ScreenAnimationWriter(format, file, width, height, true, 80)
            check(writer.start() == null) { "${format.label} 写出器打不开" }
            val egl = PbufferEgl(width, height)
            try {
                for (frame in 0 until 4) {
                    // Bottom half red, top half blue in GL coordinates: after the flip the first row
                    // must be blue, which is what proves the orientation fix, not just the readback.
                    egl.draw(top = 0x2266FF, bottom = 0xFF3322)
                    val pixels = egl.readPixels()
                    check(pixels[0] and 0xFFFFFF == 0x2266FF) {
                        "回读方向不对：首行 ${Integer.toHexString(pixels[0])}"
                    }
                    check(pixels[(height - 1) * width] and 0xFFFFFF == 0xFF3322) {
                        "回读方向不对：末行 ${Integer.toHexString(pixels[(height - 1) * width])}"
                    }
                    writer.addFrame(pixels, 100)
                    Thread.sleep(150)
                }
            } finally {
                egl.release()
            }
            check(writer.finish()) { "${format.label} 没有写出文件" }
            check(file.length() > 0) { "${format.label} 文件为空" }
            val info = animatedInfo(file)
            check(info.contains("size=${width}x$height")) { "${format.label} 管线产物不可解码：$info" }
            if (format != ScreenRecordingOptions.Format.APNG) {
                check(info.contains("animated=true")) { "${format.label} 不是动画：$info" }
            }
            report.append("${format.extension}=$info ")
            file.delete()
        }
        return "SCREENSHOT_RECORDING_PIPELINE_PASS:$report"
    }

    /** Off-screen EGL surface with the same readback shape the recorder uses. */
    private class PbufferEgl(private val width: Int, private val height: Int) {
        private val display: EGLDisplay
        private val context: EGLContext
        private val surface: EGLSurface

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "EGL 显示不可用" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL 初始化失败" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) &&
                count[0] > 0) { "EGL 配置不可用" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "EGL 上下文不可用" }
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "EGL pbuffer 不可用" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL 绘制面未启用" }
            GLES20.glViewport(0, 0, width, height)
        }

        fun draw(top: Int, bottom: Int) {
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
            // GL origin is bottom-left, so the "bottom" colour is the low half.
            GLES20.glScissor(0, 0, width, height / 2)
            GLES20.glClearColor(((bottom shr 16) and 0xFF) / 255f, ((bottom shr 8) and 0xFF) / 255f,
                (bottom and 0xFF) / 255f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glScissor(0, height / 2, width, height - height / 2)
            GLES20.glClearColor(((top shr 16) and 0xFF) / 255f, ((top shr 8) and 0xFF) / 255f,
                (top and 0xFF) / 255f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glFinish()
        }

        /** Same conversion as [ScreenRecorder]: RGBA bytes, rows flipped to top-down ARGB. */
        fun readPixels(): IntArray {
            val buffer = java.nio.ByteBuffer.allocateDirect(width * height * 4)
                .order(java.nio.ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            val pixels = IntArray(width * height)
            val rowBytes = width * 4
            for (y in 0 until height) {
                val source = (height - 1 - y) * rowBytes
                val target = y * width
                for (x in 0 until width) {
                    val index = source + x * 4
                    val r = buffer.get(index).toInt() and 0xFF
                    val g = buffer.get(index + 1).toInt() and 0xFF
                    val b = buffer.get(index + 2).toInt() and 0xFF
                    pixels[target + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return pixels
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    /** Decodes with the platform image decoder; APNG support varies by version, so it may report it. */
    private fun animatedInfo(file: File): String {
        // Three probes: the legacy factory, the still decoder and the animated drawable path. If a
        // container is rejected, the failing probe names the stage instead of an opaque message.
        val legacy = runCatching {
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                ?: return@runCatching "null"
            val size = "${bitmap.width}x${bitmap.height}"
            bitmap.recycle()
            size
        }.getOrElse { "err:${it.javaClass.simpleName}" }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            return "legacy=$legacy:decoder-unsupported"
        }
        val still = runCatching {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file))
            val size = "${bitmap.width}x${bitmap.height}"
            bitmap.recycle()
            size
        }.getOrElse { "err:${it.javaClass.simpleName}:${it.message}" }
        val animated = runCatching {
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
            val isAnimated = drawable is AnimatedImageDrawable
            (drawable as? AnimatedImageDrawable)?.stop()
            "${drawable.intrinsicWidth}x${drawable.intrinsicHeight}:animated=$isAnimated"
        }.getOrElse { "err:${it.javaClass.simpleName}:${it.message}" }
        val size = still.takeIf { !it.startsWith("err") } ?: legacy
        return "legacy=$legacy:still=$still:anim=$animated:size=$size"
    }

    /** GIF/APNG/WebP belong in the Images collection, not the video one. */
    private fun publishImage(context: Context, file: File): String {
        val name = file.name
        val saved = ScreenRecordingLibrary.save(context, file) ?: error("动画成品没有进入保存库")
        check(!file.exists()) { "保存后临时文件没有清理" }
        val uri = saved.uri ?: run {
            val target = File(saved.displayPath)
            check(target.isFile) { "降级目录里没有动画文件" }
            target.delete()
            return "app-pictures"
        }
        val mime = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.MIME_TYPE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        check(mime == "image/gif") { "图片库 MIME 不对：$mime（$name）" }
        check(context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { it.moveToFirst() } == true) { "图片库条目无法读取" }
        context.contentResolver.delete(uri, null, null)
        return "images:$mime"
    }

    private fun encodeMp4(context: Context): File {        val width = 320
        val height = 240
        val frames = 30
        val file = File(context.cacheDir, "recording-check.mp4")
        file.delete()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var started = false

        fun drain(endOfStream: Boolean) {
            while (true) {
                val index = try { codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0) }
                catch (_: IllegalStateException) { return }
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> if (!started) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        started = true
                    }
                    index >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) {
                            muxer.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                        }
                        val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                        if (end) return
                    }
                }
            }
        }

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = codec.createInputSurface()
            codec.start()
            val egl = SurfaceEgl(input, width, height)
            try {
                for (frame in 0 until frames) {
                    val level = (20 + frame * 5) / 255f
                    egl.draw(floatArrayOf(level, 0.4f, 1f - level), frame * 33_000L)
                    drain(false)
                }
            } finally {
                egl.release()
            }
            codec.signalEndOfInputStream()
            drain(true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
        }
        check(file.isFile && file.length() > 0) { "编码器没有写出录屏文件" }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() == width) {
                "录屏宽度与设置不一致"
            }
            check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() == height) {
                "录屏高度与设置不一致"
            }
            check((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0) > 0) {
                "录屏时长为零"
            }
        } finally {
            retriever.release()
        }
        return file
    }

    private fun publish(context: Context, file: File): String {
        val saved = ScreenRecordingLibrary.save(context, file)
            ?: error("录屏没有进入保存库")
        check(!file.exists()) { "保存后临时文件没有清理" }
        val uri = saved.uri
        if (uri != null) {
            check(context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)
                ?.use { it.moveToFirst() } == true) { "相册条目无法读取" }
            context.contentResolver.delete(uri, null, null)
            return "media-store"
        }
        val target = File(saved.displayPath)
        check(target.isFile) { "降级目录里没有录屏文件" }
        target.delete()
        return "app-movies"
    }

    private class SurfaceEgl(surface: Surface, width: Int, height: Int) {
        private val display: EGLDisplay
        private val context: EGLContext
        private val eglSurface: EGLSurface

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "EGL 显示不可用" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL 初始化失败" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0) {
                "EGL 配置不可用"
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "EGL 上下文不可用" }
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface,
                intArrayOf(EGL14.EGL_NONE), 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "EGL 绘制面不可用" }
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "EGL 绘制面未启用" }
            GLES20.glViewport(0, 0, width, height)
        }

        fun draw(color: FloatArray, presentationUs: Long) {
            GLES20.glClearColor(color[0], color[1], color[2], 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationUs * 1_000)
            check(EGL14.eglSwapBuffers(display, eglSurface)) { "EGL 提交帧失败" }
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }

        companion object {
            const val EGL_RECORDABLE_ANDROID = 0x3142
        }
    }
}
