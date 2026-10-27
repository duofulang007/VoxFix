package local.voxfix.debug

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.security.MessageDigest

internal class DesktopVoiceFailure(val retained: String, cause: Throwable) : RuntimeException(cause.message, cause)

/** Real VAD, revisable drafts and ordered finals; no history or disk audio. */
internal class DesktopSpeechEngine(
    private val assets: AssetManager,
    private val modelFolder: File? = null,
    private val dictionaryFile: File? = null,
    cacheDir: File? = null
) : AutoCloseable {
    private var recognizer: OfflineRecognizer? = null
    private var punctuation: OfflinePunctuation? = null
    private var vad: Vad? = null
    private val audio = FloatArray(DesktopVoiceRules.MAX_SAMPLES)
    private var running = false
    private var closed = false
    private val modelCache = cacheDir ?: modelFolder
    // One rescue per process, like the desktop: the extra graph survives an engine release
    // and only goes away after its own idle timeout, so a second dictation does not pay the
    // ~3 s session build again.
    private val rescue = sharedRescue()
    internal var observeFrame: ((DesktopVoiceFrame) -> Unit)? = null

    init {
        val loadedAt = System.nanoTime()
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        // Position tracking is specific to the pinned 512-sample v4 graph and runtime.
        check(assets.open("silero_vad.onnx").use { digest(it.readBytes()) } ==
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6") { "VAD version changed; revalidate segment positions" }
        val model = modelFolder?.resolve("model.int8.onnx")
        val tokens = modelFolder?.resolve("tokens.txt")
        if (model != null) check(model.isFile && model.length() == 239234182L && tokens?.isFile == true) { "R5 assets missing" }
        else check(assets.openFd("sensevoice/model.int8.onnx").use { it.length } == 239234182L) { "R5 asset missing" }
        try {
            recognizer = OfflineRecognizer(if (modelFolder == null) assets else null,
                OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(model?.path ?: "sensevoice/model.int8.onnx",
                        language = "auto", useInverseTextNormalization = false),
                    tokens = tokens?.path ?: "sensevoice/tokens.txt", numThreads = 2)))
            punctuation = OfflinePunctuation(assets, OfflinePunctuationConfig(model = OfflinePunctuationModelConfig(
                ctTransformer = "punctuation/model.int8.onnx", numThreads = 2)))
            val config = VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx", threshold = .30f, minSilenceDuration = .65f,
                minSpeechDuration = .12f, windowSize = 512, maxSpeechDuration = 10f), sampleRate = 16000, numThreads = 1)
            vad = Vad(assets, config)
            Log.i("VoxFix", "ASR_MODEL_READY model=desktop-r5 elapsedMs=${(System.nanoTime() - loadedAt) / 1_000_000}")
        } catch (failure: Throwable) { close(); throw failure }
    }

    private fun decode(bounds: DesktopVoiceBounds, final: Boolean, committed: String): String {
        val engine = checkNotNull(recognizer)
        val raw = audio.copyOfRange(bounds.start, bounds.end)
        if (DesktopHumGuard.isHum(raw)) return ""
        val segment = DesktopQuietBoost().process(raw)
        val stream = engine.createStream()
        var text = try {
            stream.acceptWaveform(segment, 16000)
            engine.decode(stream)
            DesktopVoiceRules.clean(engine.getResult(stream).text)
        } finally { stream.release() }
        // Same order as the desktop: rescue the final only, then the dictionary's explicit
        // rules, then punctuation. One pipeline, one owner per step.
        val dictionary = VoiceDictionaryStore.load(dictionaryFile)
        val pool = dictionary.hotwords.filter(::latin)
        if (final && pool.isNotEmpty()) text = rescue.rescore(segment, text, pool)
        if (dictionary.entries.isNotEmpty()) text = dictionary.correct(text, DesktopVoiceRules.chinese(committed + text))
        if (final && text.isNotEmpty()) {
            val result = try { checkNotNull(punctuation).addPunctuation(text) } catch (_: Exception) { text }
            text = DesktopVoiceRules.punctuate(text, result)
        }
        return text
    }

    /** Rescue only ever looks at Latin words; Chinese has no acoustic path, as on the desktop. */
    private fun latin(word: String) = word.isNotEmpty() &&
        word.any { it.code in 'A'.code..'Z'.code || it.code in 'a'.code..'z'.code } && word.none { it in '\u3400'..'\u9fff' }

    private fun confirmShortSpeech(detector: Vad, samples: Int, isCancelled: () -> Boolean): Boolean {
        // Warm the recurrent VAD and drain its tail; padding never reaches the recognizer.
        detector.reset()
        val padded = FloatArray(1600 + samples + 16000)
        audio.copyInto(padded, 1600, 0, samples)
        for (start in padded.indices step DesktopVoiceRules.FRAME) {
            if (isCancelled()) return false
            detector.acceptWaveform(padded.copyOfRange(start, minOf(start + DesktopVoiceRules.FRAME, padded.size)))
        }
        detector.flush()
        return !detector.empty()
    }

    /**
     * Same PCM supplier shape as PcmCapture.next(); cancellation never emits a late draft.
     *
     * [onSettled] 在一段话真正定稿时给出**这一段自己的文字**，[onDraft] 给出当前这一段还没定稿的草稿。
     * 两个回调都带**这一段的起点采样位置**作为段的身份：同一段的草稿与定稿拿到同一个号，
     * 输入法据此判断「屏幕上这段草稿已经在打字时定稿了，它的正式定稿不要再写一遍」，
     * 既不靠文本相似度猜，也不会误伤排在它后面的新句子。
     */
    fun transcribe(nextChunk: () -> FloatArray?, onText: (String) -> Unit, isCancelled: () -> Boolean = { false },
                   onSettled: (String, Int) -> Unit = { _, _ -> }, onDraft: (String, Int) -> Unit = { _, _ -> }): String {
        synchronized(this) { check(!closed && !running) { "语音模块正在使用或已释放" }; running = true }
        val detector = checkNotNull(vad)
        val positions = DesktopVadPosition()
        val planner = DesktopVoiceSegments(audio)
        val gain = DesktopQuietBoost()
        val monitor = Object()
        val pending = java.util.ArrayDeque<DesktopVoiceBounds>()
        var draft: DesktopVoiceBounds? = null
        var activeKey = -1
        var committed = ""
        var preview = ""
        var emitted = ""
        var ending = false
        var cancelled = false
        var failure: Throwable? = null
        val worker = Thread({
            try {
                while (true) {
                    var final = false
                    val bounds = synchronized(monitor) {
                        while (!cancelled && !ending && pending.isEmpty() && draft == null) monitor.wait(50)
                        if (cancelled || isCancelled()) null
                        else if (pending.isNotEmpty()) { final = true; pending.removeFirst() }
                        else if (ending) null else draft.also { draft = null }
                    } ?: break
                    val before = synchronized(monitor) { committed }
                    val text = decode(bounds, final, before)
                    val output = synchronized(monitor) {
                        if (cancelled || isCancelled()) null
                        else if (!DesktopVoiceRules.allowed(text)) {
                            check(!final) { "识别到中文和英文之外的文字，本次结果未自动输入" }
                            null
                        } else if (final || bounds.start == activeKey && !ending) {
                            if (final) committed = DesktopVoiceRules.join(committed, text) else preview = text
                            val value = DesktopVoiceRules.join(committed, if (final) "" else preview)
                            if (value != emitted) value.also { emitted = it } else null
                        } else null
                    }
                    if (output != null && !isCancelled()) onText(output)
                    if (final && text.isNotEmpty() && !isCancelled()) onSettled(text, bounds.key)
                    if (!final && !isCancelled()) {
                        val live = synchronized(monitor) { if (cancelled) "" else preview }
                        if (live.isNotEmpty()) onDraft(live, bounds.start)
                    }
                }
            } catch (error: Throwable) {
                synchronized(monitor) { failure = error; cancelled = true; pending.clear(); draft = null; monitor.notifyAll() }
            }
        }, "VoxFix-desktop-voice")

        fun completed(): List<DesktopVoiceBounds> {
            val result = mutableListOf<DesktopVoiceBounds>()
            while (!detector.empty()) {
                val segment = detector.front()
                result.add(DesktopVoiceBounds(segment.start, segment.start + segment.samples.size))
                detector.pop()
            }
            return result
        }
        fun publish(frame: DesktopVoiceFrame) {
            observeFrame?.invoke(frame)
            synchronized(monitor) {
                failure?.let { throw it }
                activeKey = frame.key
                if (frame.finals.isNotEmpty()) { draft = null; preview = "" }
                for (bounds in frame.finals) {
                    check(pending.size < 24) { "识别队列积压，请结束本次录音后重试" }
                    pending.add(bounds)
                }
                frame.draft?.let { draft = it }
                monitor.notifyAll()
            }
        }
        fun accept(frame: FloatArray) {
            planner.append(frame)
            detector.acceptWaveform(gain.process(frame))
            val segments = completed()
            positions.accepted(frame.size, detector.isSpeechDetected(), segments)
            publish(planner.frame(positions.start, segments))
        }

        try {
            detector.reset()
            worker.start()
            val frame = FloatArray(DesktopVoiceRules.FRAME)
            var used = 0
            while (!isCancelled()) {
                synchronized(monitor) { failure?.let { throw it } }
                val chunk = nextChunk() ?: break
                require(chunk.isNotEmpty() && chunk.size <= 16000 && chunk.all { it.isFinite() && it in -1.001f..1.001f }) { "无效的PCM音频" }
                var offset = 0
                while (offset < chunk.size) {
                    val size = minOf(frame.size - used, chunk.size - offset)
                    chunk.copyInto(frame, used, offset, offset + size)
                    used += size; offset += size
                    if (used == frame.size) { accept(frame.copyOf()); used = 0 }
                }
            }
            if (!isCancelled()) {
                if (used > 0) accept(frame.copyOf(used))
                detector.flush()
                val finals = completed().toMutableList()
                if (finals.isEmpty() && planner.samples in 1..10400 && DesktopVoiceRules.hasVoicedFrame(audio, planner.samples) &&
                    confirmShortSpeech(detector, planner.samples, isCancelled))
                    finals.add(DesktopVoiceBounds(0, planner.samples))
                publish(planner.frame(-1, finals, flushing = true))
            }
            synchronized(monitor) {
                cancelled = isCancelled(); ending = true; draft = null
                if (cancelled) pending.clear()
                monitor.notifyAll()
            }
            worker.join()
            synchronized(monitor) { failure?.let { throw it } }
            return if (isCancelled()) "" else committed
        } catch (error: Throwable) {
            synchronized(monitor) { cancelled = true; ending = true; draft = null; pending.clear(); monitor.notifyAll() }
            if (worker.isAlive) worker.join()
            if (isCancelled()) return ""
            throw DesktopVoiceFailure(committed, error)
        } finally {
            audio.fill(0f, 0, planner.samples)
            synchronized(this) { running = false }
        }
    }

    @Synchronized override fun close() {
        check(!running) { "先结束录音再释放模型" }
        if (closed) return
        closed = true
        try { recognizer?.release() } finally {
            try { punctuation?.release() } finally {
                vad?.release(); audio.fill(0f)
                if (!NativeMemory.purge()) Log.w("VoxFix", "ASR_NATIVE_PURGE_UNAVAILABLE")
            }
        }
    }

    private fun sharedRescue(): DesktopHotwordRescue {
        shared?.let { return it }
        synchronized(DesktopSpeechEngine::class.java) {
            shared?.let { return it }
            return DesktopHotwordRescue(
                factory = {
                    // A folder-based session (isolated comparison runs) reuses that copy of the
                    // graph instead of dropping another 228 MiB into the cache.
                    val local = modelFolder?.resolve("model.int8.onnx")
                    if (local != null && local.isFile) OrtCtcDecoder(local.absolutePath, DesktopCtcModel.tokens(assets))
                    else DesktopCtcModel.open(assets, checkNotNull(modelCache) { "缺少热词模型缓存目录" }, DesktopCtcModel.tokens(assets))
                },
                log = { Log.i("VoxFix", it) }
            ).also { shared = it }
        }
    }

    companion object {
        @Volatile private var shared: DesktopHotwordRescue? = null

        /** Called when the keyboard goes to the background so the extra graph is not pinned. */
        fun releaseSharedRescue() { shared?.release() }

        fun record(engine: () -> DesktopSpeechEngine, onText: (String) -> Unit, onLevel: (Int) -> Unit,
                   shouldContinue: () -> Boolean = { true }, isCancelled: () -> Boolean = { false },
                   onReady: () -> Unit = {}, onCapture: (PcmCapture?) -> Unit = {},
                   onSettled: (String, Int) -> Unit = { _, _ -> }, onDraft: (String, Int) -> Unit = { _, _ -> },
                   isMuted: () -> Boolean = { false }): String =
            VoiceRecorder.record(onLevel, shouldContinue, isCancelled, onReady, onCapture, DesktopVoiceRules.MAX_SAMPLES,
                isMuted) { next -> engine().transcribe(next, onText, isCancelled, onSettled, onDraft) }
    }
}
