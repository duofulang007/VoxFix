package local.voxfix.debug

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
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
import java.io.File

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
