package local.voxfix.debug

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Region screen recorder: MediaProjection → VirtualDisplay → SurfaceTexture → EGL crop →
 * MediaCodec(H.264) → MediaMuxer(MP4), with an optional AAC track.
 *
 * Android only exposes whole-display capture, so the selection is cropped in GL before encoding.
 * Pause stops feeding frames and PCM while subtracted pause time keeps the container timeline
 * continuous, matching Snow Shot's paused duration.
 */
internal class ScreenRecorder(
    context: Context,
    private val projection: MediaProjection,
    private val config: Config,
    private val listener: Listener
) {
    data class Config(
        val crop: Rect,
        val screenWidth: Int,
        val screenHeight: Int,
        val outputWidth: Int,
        val outputHeight: Int,
        val frameRate: Int,
        val bitrate: Int,
        val microphone: Boolean,
        val systemAudio: Boolean
    ) {
        val audioRequested: Boolean get() = microphone || systemAudio
    }

    fun interface Listener {
        /** Recording finished; [file] is null when cancelled or when finalisation failed. */
        fun onFinished(file: File?, durationMs: Long, message: String?)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val stateLock = Any()

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0
    private var positionBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var stSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var muxer: MediaMuxer? = null

    private val muxerLock = Any()
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var audioEnabled = false
    private var audioSamples = 0L
    private val sampleRate = 44_100

    @Volatile private var audioRunning = false
    @Volatile private var paused = false
    @Volatile private var stopping = false

    private var startNs = 0L
    private var pauseStartNs = 0L
    private var pausedNs = 0L
    private var lastDrawnNs = 0L
    private var outputFile: File? = null
    private var finalized = false
    private val texMatrix = FloatArray(16)
    private var crop = FloatArray(4)

    fun isStarted(): Boolean = startNs != 0L

    /** Initialises the pipeline. Returns null on success, otherwise a user-facing message. */
    fun start(): String? {
        thread = HandlerThread("voxfix-recorder")
        thread.start()
        handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        var error: String? = null
        handler.post {
            try {
                error = initialize()
            } catch (throwable: Throwable) {
                android.util.Log.w("VoxFix", "SCREEN_RECORD_START_FAILED", throwable)
                error = "无法开始录屏：" + (throwable.message ?: "设备编码不可用")
                releaseAll()
            } finally {
                latch.countDown()
            }
        }
        val done = try { latch.await(8, TimeUnit.SECONDS) } catch (_: InterruptedException) { false }
        if (!done) {
            error = "录屏初始化超时"
            releaseAll()
            return error
        }
        if (error != null) releaseAll()
        return error
    }

    fun pause() {
        if (stopping) return
        synchronized(stateLock) {
            if (paused) return
            pauseStartNs = System.nanoTime()
            paused = true
        }
    }

    fun resume() {
        if (stopping) return
        synchronized(stateLock) {
            if (!paused) return
            val now = System.nanoTime()
            pausedNs = ScreenRecordingGeometry.pausedOffsetMicros(now, pauseStartNs, pausedNs)
            paused = false
            lastDrawnNs = 0L
        }
    }

    /** Stops and finalises the file. */
    fun stop() = finish(cancel = false)

    /** Stops and deletes the partial file. */
    fun cancel() = finish(cancel = true)

    private fun finish(cancel: Boolean) {
        if (stopping) return
        stopping = true
        handler.post { finalizeRecording(cancel) }
    }

    private fun initialize(): String? {
        val file = File(File(appContext.cacheDir, "recordings").apply { mkdirs() },
            ScreenRecordingGeometry.fileName(System.currentTimeMillis()))
        outputFile = file
        crop = ScreenRecordingGeometry.cropFraction(config.crop.left, config.crop.top,
            config.crop.right, config.crop.bottom, config.screenWidth, config.screenHeight)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.outputWidth, config.outputHeight)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()
        videoCodec = codec

        createEgl(inputSurface)
        createProgram()
        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        textureId = texture[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val textureSurface = SurfaceTexture(textureId)
        textureSurface.setDefaultBufferSize(config.screenWidth, config.screenHeight)
        textureSurface.setOnFrameAvailableListener({ handler.post { onFrameAvailable() } }, handler)
        surfaceTexture = textureSurface
        val surface = Surface(textureSurface)
        stSurface = surface

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                // The system revoked capture (user or policy). Finalise whatever is on disk.
                if (!stopping) finish(cancel = false)
            }
        }
        projection.registerCallback(projectionCallback!!, handler)

        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        virtualDisplay = projection.createVirtualDisplay(
            "VoxFix 录屏", config.screenWidth, config.screenHeight,
            appContext.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler
        )
        inputSurface.release()

        startNs = System.nanoTime()
        lastDrawnNs = 0L

        if (config.audioRequested) startAudio()
        return null
    }

    private fun createEgl(inputSurface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "无法初始化显示" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "无法初始化图形环境" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "设备不支持录屏图形配置"
        }
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "无法创建录屏图形上下文" }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], inputSurface,
            intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "无法创建录屏绘制面" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "无法启用录屏绘制面" }
    }

    private fun createProgram() {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, """
            uniform mat4 uTexMatrix;
            uniform vec4 uCrop;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vec2 uv = uCrop.xy + aTexCoord * uCrop.zw;
                vTexCoord = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
            }
        """.trimIndent())
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
        """.trimIndent())
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "录屏着色器链接失败" }
        GLES20.glUseProgram(program)

        positionBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        texCoordBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 8, positionBuffer)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uCrop"), 1, crop, 0)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "录屏着色器编译失败" }
        return shader
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(values); position(0) }

    private fun onFrameAvailable() {
        val texture = surfaceTexture ?: return
        if (stopping) return
        try {
            texture.updateTexImage()
            texture.getTransformMatrix(texMatrix)
        } catch (_: Exception) { return }
        val now = System.nanoTime()
        if (paused) { lastDrawnNs = now; return }
        val interval = 1_000_000_000L / config.frameRate.coerceIn(5, 60)
        if (lastDrawnNs != 0L && now - lastDrawnNs < interval) return
        lastDrawnNs = now
        val ptsUs = ScreenRecordingGeometry.presentationTimeUs(now, startNs, pausedNowNs())
        GLES20.glViewport(0, 0, config.outputWidth, config.outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
        // Re-applied per frame: the crop uniform is the only state that separates a region capture
        // from a full-screen one, so it must not depend on program state surviving a session change.
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uCrop"), 1, crop, 0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 8, positionBuffer)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsUs * 1_000)
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) return
        drainVideo(false)
    }

    private fun pausedNowNs(): Long = synchronized(stateLock) {
        if (paused) pausedNs + (System.nanoTime() - pauseStartNs).coerceAtLeast(0) else pausedNs
    }

    private fun startAudio() {
        val source = if (config.microphone) openMicrophone() else openSystemAudio()
        val record = source ?: return
        audioRecord = record
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        audioCodec = codec
        audioEnabled = true
        audioRunning = true
        record.startRecording()
        audioThread = Thread({ audioLoop(record, codec) }, "voxfix-recorder-audio").apply {
            isDaemon = true
            start()
        }
    }

    private fun openMicrophone(): AudioRecord? = try {
        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(minimum, sampleRate / 2)
        AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, size)
            .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    } catch (error: Exception) {
        android.util.Log.w("VoxFix", "SCREEN_RECORD_MIC_FAILED", error)
        null
    }

    private fun openSystemAudio(): AudioRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val size = maxOf(minimum, sampleRate / 2)
            AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(size)
                .setAudioPlaybackCaptureConfig(capture)
                .build()
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (error: Exception) {
            android.util.Log.w("VoxFix", "SCREEN_RECORD_SYSTEM_AUDIO_FAILED", error)
            null
        }
    }

    private fun audioLoop(record: AudioRecord, codec: MediaCodec) {
        val pcm = ByteArray(maxOf(sampleRate / 2, 4096))
        try {
            while (audioRunning) {
                val read = record.read(pcm, 0, pcm.size)
                if (read <= 0) continue
                if (paused) continue
                var offset = 0
                while (offset < read) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index < 0) { drainAudio(false); continue }
                    val buffer = codec.getInputBuffer(index) ?: break
                    buffer.clear()
                    val count = min(buffer.remaining(), read - offset)
                    buffer.put(pcm, offset, count)
                    val pts = audioSamples * 1_000_000L / sampleRate
                    codec.queueInputBuffer(index, 0, count, pts, 0)
                    audioSamples += count / 2
                    offset += count
                    drainAudio(false)
                }
            }
            val index = codec.dequeueInputBuffer(50_000)
            if (index >= 0) {
                val pts = audioSamples * 1_000_000L / sampleRate
                codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainAudio(true)
        } catch (error: Exception) {
            android.util.Log.w("VoxFix", "SCREEN_RECORD_AUDIO_FAILED", error)
        }
    }

    private fun drainVideo(endOfStream: Boolean) {
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = try { codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0) }
            catch (_: IllegalStateException) { return }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoFormat = codec.outputFormat
                    startMuxerIfReady()
                }
                index >= 0 -> {
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null) writeSample(videoTrack, buffer, info)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            codec.releaseOutputBuffer(index, false)
                            return
                        }
                        codec.releaseOutputBuffer(index, false)
                    } catch (_: IllegalStateException) { return }
                }
            }
        }
    }

    private fun drainAudio(endOfStream: Boolean) {
        val codec = audioCodec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = try { codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0) }
            catch (_: IllegalStateException) { return }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    audioFormat = codec.outputFormat
                    startMuxerIfReady()
                }
                index >= 0 -> {
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null) writeSample(audioTrack, buffer, info)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            codec.releaseOutputBuffer(index, false)
                            return
                        }
                        codec.releaseOutputBuffer(index, false)
                    } catch (_: IllegalStateException) { return }
                }
            }
        }
    }

    private fun startMuxerIfReady() = synchronized(muxerLock) {
        if (muxerStarted) return
        val target = muxer ?: return
        val video = videoFormat ?: return
        if (audioEnabled && audioFormat == null) return
        videoTrack = target.addTrack(video)
        audioFormat?.let { audioTrack = target.addTrack(it) }
        target.start()
        muxerStarted = true
    }

    private fun writeSample(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxerLock) {
            if (!muxerStarted || track < 0) return
            try { muxer?.writeSampleData(track, buffer, info) } catch (_: IllegalStateException) { }
        }
    }

    private fun finalizeRecording(cancel: Boolean) {
        if (finalized) return
        finalized = true
        val file = outputFile
        val duration = durationMs()
        audioRunning = false
        try { audioRecord?.stop() } catch (_: Exception) { }
        try { audioThread?.join(1200) } catch (_: InterruptedException) { }
        audioRecord?.release()
        audioRecord = null
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        try { stSurface?.release() } catch (_: Exception) { }
        stSurface = null
        try { surfaceTexture?.release() } catch (_: Exception) { }
        surfaceTexture = null
        try { videoCodec?.signalEndOfInputStream() } catch (_: Exception) { }
        drainVideo(true)
        try { audioCodec?.stop() } catch (_: Exception) { }
        audioCodec?.release()
        audioCodec = null
        try { videoCodec?.stop() } catch (_: Exception) { }
        videoCodec?.release()
        videoCodec = null
        val finished = synchronized(muxerLock) {
            var ok = false
            try {
                if (muxerStarted) { muxer?.stop(); ok = true }
            } catch (_: Exception) { ok = false }
            try { muxer?.release() } catch (_: Exception) { }
            muxer = null
            muxerStarted = false
            ok
        }
        releaseEgl()
        projectionCallback?.let { runCatching { projection.unregisterCallback(it) } }
        projectionCallback = null
        val ok = file != null && file.isFile && file.length() > 0 && finished
        if (cancel || !ok) {
            file?.delete()
            if (!cancel) android.util.Log.w("VoxFix", "SCREEN_RECORD_EMPTY", IllegalStateException("录制文件为空"))
        }
        val result = if (cancel || !ok) null else file
        val message = when {
            cancel -> null
            !ok -> "录屏保存失败，请检查可用空间"
            else -> null
        }
        handler.post { thread.quitSafely() }
        main.post { listener.onFinished(result, duration, message) }
    }

    private fun durationMs(): Long =
        ScreenRecordingGeometry.presentationTimeUs(System.nanoTime(), startNs, pausedNowNs()) / 1_000

    private fun releaseAll() {
        audioRunning = false
        try { audioRecord?.stop() } catch (_: Exception) { }
        audioRecord?.release()
        audioRecord = null
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        try { stSurface?.release() } catch (_: Exception) { }
        stSurface = null
        try { surfaceTexture?.release() } catch (_: Exception) { }
        surfaceTexture = null
        try { videoCodec?.stop() } catch (_: Exception) { }
        videoCodec?.release()
        videoCodec = null
        try { audioCodec?.stop() } catch (_: Exception) { }
        audioCodec?.release()
        audioCodec = null
        synchronized(muxerLock) {
            try { muxer?.release() } catch (_: Exception) { }
            muxer = null
            muxerStarted = false
        }
        releaseEgl()
        projectionCallback?.let { runCatching { projection.unregisterCallback(it) } }
        projectionCallback = null
        outputFile?.delete()
        if (::thread.isInitialized) handler.post { thread.quitSafely() }
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
