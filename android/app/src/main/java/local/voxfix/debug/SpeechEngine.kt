package local.voxfix.debug

import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.math.BigInteger
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Two-pass dictation.
 *
 * Pass 1 is a streaming Zipformer fed in 20 ms chunks, so the caller can
 * show text while the user is still talking. Pass 2 refines completed utterances in the
 * background; release gives the last utterance a bounded correction budget.
 *
 * Silence is detected by silero-vad rather than an RMS threshold. RMS cannot tell a quiet
 * speaker from a noisy room, which previously made whole segments fall back to the streaming
 * text and, on some inputs, made a model hallucinate text with no speech at all.
 */
class SpeechEngine internal constructor(assets: AssetManager, private var personal: VoiceLexicon = VoiceLexicon()) {
    internal val usesPersonalDecoder = personal.words.isNotEmpty()
    private val personalTokens by lazy { assets.open("streaming/tokens.txt").bufferedReader().useLines { lines -> lines.map { it.substringBefore(' ') }.toSet() } }
    internal fun personalize(value: VoiceLexicon) { personal = value }
    private var online: OnlineRecognizer? = null
    private var secondLoader: FutureTask<OfflineRecognizer>? = null
    private var vad: Vad? = null
    private var secondStarted = false
    /** 标点与二遍解耦：模型只吃文本，二遍没跑完时同样能给尾段补标点。 */
    private val punctuator = Punctuator(assets)
    private val secondWorker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1), { task ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "VoxFix-second-pass")
    })

    /**
     * 提交二遍任务；队列只有一格，满了就给最新这段让位。
     *
     * 队列满时旧行为是直接放弃（`RejectedExecutionException` → null），而「最新这段」正是用户刚说完、
     * 正在盯着看的那一句：K60 上 11.7s 粤语长句因此只有前两段被改写、最后一段停在流式同音汉字。
     * 这里丢掉还没开始跑的旧任务（它所属片段自动退回实时文字），把位置让给最新这段。
     */
    private fun submitSecond(block: () -> SecondPass): Future<SecondPass>? {
        val task = FutureTask(block)
        return try {
            secondWorker.submit(task); task
        } catch (_: RejectedExecutionException) {
            // 容量只有一格：摘掉还在排队的那一个（它所属片段会退回实时文字），把位置让给最新这段。
            (secondWorker.queue.poll() as? Future<*>)?.cancel(false)
            try { secondWorker.submit(task); task } catch (_: RejectedExecutionException) { null }
        }
    }

    init {
        var loadedAt = System.nanoTime()
        fun loaded(model: String) {
            Log.i("VoxFix", "ASR_MODEL_READY model=$model elapsedMs=${(System.nanoTime() - loadedAt) / 1_000_000}")
            loadedAt = System.nanoTime()
        }
        try {
            online = OnlineRecognizer(assets, OnlineRecognizerConfig(
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        "streaming/encoder.int8.onnx", "streaming/decoder.onnx", "streaming/joiner.int8.onnx"),
                    tokens = "streaming/tokens.txt", numThreads = STREAMING_THREADS, modelType = "zipformer",
                    modelingUnit = "cjkchar+bpe", bpeVocab = "streaming/bpe.vocab"),
                decodingMethod = if (usesPersonalDecoder) "modified_beam_search" else "greedy_search",
                // 79 条固定音频在电脑上 beam 4 路比 2 路好（混说 14.74% vs 18.16%），但 K60 上只差
                // 1 个点（17.80% vs 18.79%）而解码耗时翻倍（混说样本 1189ms vs 574ms/条）⇒ 取 2 路。
                // 混说真正的杠杆是热词：同一批把术语放进热词表，2 路就能从 18.16% 降到 16.49%。
                maxActivePaths = 2,
                endpointConfig = EndpointConfig(EndpointRule(false, 1.6f, 0f), EndpointRule(true, 0.8f, 0f), EndpointRule(false, 0f, 20f))))
            loaded("streaming")
            // getVadModelConfig(0) is silero-vad; the helper hardcodes the asset name
            // "silero_vad.onnx" and 16 kHz.
            val vadConfig = checkNotNull(getVadModelConfig(VAD_SILERO)) { "silero-vad 配置不可用" }
            vadConfig.numThreads = VAD_THREADS
            // 默认阈值 0.5 会把句首轻声判成静音：79 条固定音频里有 11 条的首段起点晚于真实语音
            // 150ms 以上，二遍因此只拿到后半句（`这个bug已经修好了` → `已经修好了`）。0.4 把这批
            // 混说错误率从 11.7% 压到 8.8%、丢句首 11 条降到 9 条；纯白噪 0.01–0.05 RMS 与
            // 10–15 dB 加噪语音的分段结果不变。
            vadConfig.sileroVadModelConfig.threshold = VAD_THRESHOLD
            vad = Vad(assets, vadConfig)
            loaded("vad")
            // 术语判定用的英文词表（包内 rime-ice 词表）：后台读一次，不占出字路径。
            Thread({
                try { EnglishVocabulary.load(assets) }
                catch (error: Throwable) { Log.w("VoxFix", "ASR_VOCAB_UNAVAILABLE", error) }
            }, "VoxFix-vocab").apply { isDaemon = true; start() }
            // Defer pass 2 until live text has started, so cold streaming gets the CPU first.
            // General SenseVoice supplies LID/ITN; tokens are mandatory to avoid a native crash.
            secondLoader = FutureTask {
                val started = System.nanoTime()
                OfflineRecognizer(assets, OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                    // 语种交给 SenseVoice 内建 LID 自己判，不写死 zh：写死时粤/日/韩只会被强行按
                    // 中文解码。结果首 token 就是 `<|zh|>`/`<|yue|>` 这类标签，见 SecondPass.lang。
                    senseVoice = OfflineSenseVoiceModelConfig(
                        "sensevoice/model.int8.onnx", language = SECOND_PASS_LANGUAGE, useInverseTextNormalization = true),
                    tokens = "sensevoice/tokens.txt", numThreads = SECOND_PASS_THREADS))).also {
                    Log.i("VoxFix", "ASR_MODEL_READY model=second elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
                }
            }
        } catch (error: Throwable) { release(); throw error }
    }

    /** Also accepts fixed upstream audio for native regression without recording user speech. */
    internal fun transcribe(nextChunk: () -> FloatArray?, onText: (String) -> Unit,
                            isCancelled: () -> Boolean = { false },
                            isRecording: () -> Boolean = { true }): String {
        val recognizer = checkNotNull(online)
        val detector = checkNotNull(vad)
        val dictionary = personal
        val stream = recognizer.createStream(if (usesPersonalDecoder) dictionary.hotwords(personalTokens) else "")
        val segments = mutableListOf<SpeechSegment>()
        var finishing = false
        fun complete() = segments.fold("") { text, segment -> join(text, segment.current()) }
        var previousText = ""
        var previousRaw = ""
        val started = System.nanoTime()
        fun emit(rawText: String, corrected: Boolean = false) {
            if (rawText == previousRaw) return
            previousRaw = rawText
            val formatted = formatNumbers(rawText)
            val text = if (corrected) formatted else dictionary.correct(formatted, VoiceLanguage::phonetic, VoiceLanguage::boundaries)
            if (text.isNotBlank() && text != previousText && !isCancelled()) {
                if (previousText.isEmpty()) {
                    Log.i("VoxFix", "ASR_FIRST_TEXT elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
                    // 标点模型与二遍同时开始加载，都在后台，不占实时出字这条路径。
                    punctuator.start()
                    if (!finishing && isRecording() && !secondStarted) {
                        secondStarted = true
                        secondWorker.execute(checkNotNull(secondLoader))
                    }
                }
                previousText = text
                onText(text)
            }
        }
        // Pass 2 needs a whole utterance, so audio stays buffered until VAD reports a pause.
        val utterance = mutableListOf<FloatArray>()
        var utteranceSamples = 0
        var streamingOnly = false
        var fedSamples = 0
        // Silence seen since VAD reported the pause, and how many VAD segments this utterance holds.
        var silenceSamples = 0
        var utteranceSegments = 0
        // Pass 2 may start at the pause so it is more likely finished by release; it is only reused
        // when no further speech joined the utterance.
        var early: Future<SecondPass>? = null
        var earlySegments = -1

        fun decodeSecondPass(samples: FloatArray): SecondPass {
            val offline = secondPass()
            val second = offline.createStream()
            return try {
                second.acceptWaveform(samples, SAMPLE_RATE)
                offline.decode(second)
                val result = offline.getResult(second)
                SecondPass(clean(result.text), result.lang)
            } finally { second.release() }
        }

        /**
         * 把解码器已经收下、但还差最后一个 chunk 才能输出的音频补渲出来。
         *
         * zipformer 每 320ms 才吐一次结果：VAD 报停顿的那一刻，最后一段语音常常还在解码器缓冲里。
         * 直接 getResult 再 reset，这段尾音就永久丢掉了——固定音频里「VoxFix → Vox fi」、
         * 「JSON数据 → gson数」都是这么少的最后一个字。这里补两个 chunk 的静音只让解码器把
         * 已收下的音频算完，不等用户、也不改松手行为。
         */
        fun flushDecoder() {
            // 每个 200ms 静音块都跑完可解码的部分，两块共 400ms，足以跨过一个 320ms 解码块。
            repeat(2) {
                stream.acceptWaveform(FloatArray(SAMPLE_RATE / 5), SAMPLE_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
            }
        }

        fun commitSegment() {
            // inputFinished() 之后不能再投音频，收尾那一刀由 inputFinished 自己补齐。
            if (!finishing) flushDecoder()
            val streaming = clean(recognizer.getResult(stream).text)
            // 既没有 VAD 音频、也没有流式文字时才真的没有东西可提交。
            if (streaming.isBlank() && utterance.isEmpty() && !streamingOnly) return
            val samples = if (!streamingOnly && !isCancelled()) concat(utterance) else FloatArray(0)
            val merged = utteranceSegments
            // Diagnostic only: fixed-audio runs need the segment's audio range and live text.
            Log.i("VoxFix", "ASR_SEG n=${segments.size + 1} fedMs=${fedSamples / 16} audioMs=${utteranceSamples / 16} text=$streaming")
            utterance.clear()
            utteranceSamples = 0
            streamingOnly = false
            silenceSamples = 0
            utteranceSegments = 0
            // Keep live decoding ahead of optional corrections rather than building a backlog.
            val pending = when {
                early != null && earlySegments == merged -> early
                samples.isEmpty() -> null
                else -> submitSecond { decodeSecondPass(samples) }
            }
            early = null
            earlySegments = -1
            segments.add(SpeechSegment(streaming, pending) { raw, second ->
                val refined = refine(raw, second.text, second.lang)
                dictionary.protect(raw, refined, VoiceLanguage::phonetic)
            })
            recognizer.reset(stream)
        }

        /** Pull every completed segment out of VAD and append its audio to the current utterance. */
        fun drainUtterance() {
            while (!detector.empty()) {
                val segment = detector.front().samples
                if (!streamingOnly && segment.isNotEmpty()) {
                    utteranceSamples += segment.size
                    // Continuous speech beyond 30s keeps streaming; do not buffer six minutes for pass 2.
                    if (utteranceSamples > SAMPLE_RATE * 30) {
                        utterance.clear(); utteranceSamples = 0; streamingOnly = true
                    } else utterance.add(segment)
                }
                detector.pop()
                utteranceSegments++
            }
        }

        try {
            while (!isCancelled()) {
                val samples = nextChunk() ?: break
                if (isCancelled()) return ""
                fedSamples += samples.size
                stream.acceptWaveform(samples, SAMPLE_RATE)
                detector.acceptWaveform(samples)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                drainUtterance()
                if (!detector.isSpeechDetected() && (utterance.isNotEmpty() || streamingOnly)) {
                    // 停顿先提交一次整理（松手前尽量拿到结果），但要停顿够久才真的切段。
                    // 长句不再预跑：8s 以上的音频二遍要 400ms 以上，结果还没用上就被下一段顶掉，
                    // 反而把唯一的工作线程占住，让松手时最后一段排不上队。
                    if (!streamingOnly && !finishing && isRecording() && !isCancelled() &&
                        earlySegments != utteranceSegments && utteranceSamples <= EARLY_SECOND_PASS_LIMIT) {
                        // 每一代只试一次：队列满时不必每 20ms 重复抛一次拒绝异常。
                        earlySegments = utteranceSegments
                        early = null
                        val paused = concat(utterance)
                        if (paused.isNotEmpty()) early = submitSecond { decodeSecondPass(paused) }
                    }
                    silenceSamples += samples.size
                    if (silenceSamples >= COMMIT_SILENCE_SAMPLES) commitSegment()
                } else silenceSamples = 0
                emit(join(complete(), clean(recognizer.getResult(stream).text)))
            }
            if (isCancelled()) return ""
            finishing = true
            // Flush trailing audio so the final word is not lost when the user stops mid-syllable.
            stream.acceptWaveform(FloatArray(8000), SAMPLE_RATE)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            detector.flush()
            drainUtterance()
            commitSegment()
            if (isCancelled()) return ""
            // 预算从「补尾完成」之后才起算：补尾 + 补渲本身要 100–250ms，之前把这段时间也算进预算，
            // 长句二遍只剩几十毫秒，粤语因此常常整段停在流式的同音汉字上。
            val finishDeadline = System.nanoTime() + FINAL_SECOND_PASS_BUDGET_MS * 1_000_000
            val tail = clean(recognizer.getResult(stream).text)
            // 二遍模型还没加载完就别等：等待期全花在加载上，预算烧满了也拿不到改写。
            // 冷启动第一句（说话短、加载 0.78s）实测正好会这样白等 350ms。
            val secondLoaded = secondLoader?.isDone == true
            var result = segments.foldIndexed("") { index, text, segment ->
                join(text, segment.finish(releaseBudget(finishDeadline, index == segments.lastIndex, secondLoaded)))
            }
            if (isCancelled()) return ""
            if (tail.isNotEmpty() && result.isEmpty()) result = tail
            // Learned contexts come from committed text: restore punctuation/numbers before matching them.
            if (result.isNotEmpty()) result = dictionary.correct(restorePunctuation(result), VoiceLanguage::phonetic, VoiceLanguage::boundaries)
            emit(result, corrected = true)
            // Diagnostic only: which text each committed segment finally contributed.
            segments.forEachIndexed { index, segment ->
                Log.i("VoxFix", "ASR_SEG_FINAL n=${index + 1} text=${segment.current()}")
            }
            Log.i("VoxFix", "ASR_DECODE_FINISHED elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            return result
        } finally {
            segments.forEach { it.finish() }
            stream.release()
            detector.reset()
        }
    }

    /**
     * 收尾标点：离线标点模型在原始识别文本上补标点，然后才做数字转换与句末兜底。
     *
     * 顺序不能反：模型会破坏阿拉伯数字，实测 `这个一共120.5元` 输出成 `这个一共120 . 5元`；
     * 而识别原文是「这个一共一百二十点五元」，模型处理正常。所以标点必须在 formatNumbers 之前。
     *
     * 模型只用于含汉字的文本。它是中文优先的，整句英文会被加上中文逗号
     * （`HELLO WORLD HOW ARE YOU` → `HELLO WORLD，HOW ARE YOU？`），纯英文仍走原句末规则。
     *
     * 模型不可用或超时时退回流式文本，[punctuate] 仍会补句末 —— 松手不会因为标点而卡住。
     */
    private fun restorePunctuation(text: String): String {
        val marked = if (needsPunctuationModel(text)) {
            val stripped = stripSentencePunctuation(text)
            punctuator.add(stripped)?.let { dedupePunctuation(it) } ?: text
        } else text
        return punctuate(formatNumbers(marked))
    }

    internal fun secondPass(): OfflineRecognizer {
        val loader = checkNotNull(secondLoader)
        loader.run()
        return loader.get()
    }

    fun release() {
        secondWorker.shutdown()
        while (!secondWorker.awaitTermination(1, TimeUnit.SECONDS)) { /* Native decode must finish before release. */ }
        online?.release(); online = null
        // An unused cold second pass must not be loaded just to release it.
        try { secondLoader?.takeIf { it.isDone }?.get()?.release() }
        catch (error: java.util.concurrent.ExecutionException) { Log.e("VoxFix", "ASR_SECOND_LOAD_FAILED", error.cause) }
        finally { secondLoader = null }
        vad?.release(); vad = null
        // 标点模型随引擎一起释放，纳入既有的空闲 30 秒回收，不单独常驻。
        punctuator.release()
        // Return freed inference buffers to Android instead of keeping allocator caches resident.
        if (!NativeMemory.purge()) Log.w("VoxFix", "ASR_NATIVE_PURGE_UNAVAILABLE")
    }

    companion object {
        internal fun record(engine: () -> SpeechEngine, onText: (String) -> Unit, onLevel: (Int) -> Unit,
                   shouldContinue: () -> Boolean = { true }, isCancelled: () -> Boolean = { false },
                   onReady: () -> Unit = {}, onCapture: (PcmCapture?) -> Unit = {}): String {
            val started = System.nanoTime()
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "设备不支持 16kHz 单声道录音：$minimum" }
            val microphone = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 12800))
            var capture: PcmCapture? = null
            val stopFrame = AtomicLong(Long.MAX_VALUE)
            try {
                check(microphone.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
                if (!shouldContinue() || isCancelled()) return ""
                microphone.startRecording()
                check(microphone.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风未进入录音状态" }
                Log.i("VoxFix", "ASR_MIC_STARTED elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
                val pcm = ShortArray(AUDIO_CHUNK_SAMPLES)
                var total = 0
                capture = PcmCapture(CAPTURE_BUFFER_CHUNKS, read@{
                    val received = microphone.read(pcm, 0, minOf(pcm.size, SAMPLE_RATE * 360 - total))
                    check(received > 0) { "麦克风采样失败：$received" }
                    val count = minOf(received.toLong(), (stopFrame.get() - total).coerceAtLeast(0)).toInt()
                    if (count == 0) return@read null
                    total += count
                    if (total == count) {
                        Log.i("VoxFix", "ASR_MIC_READY elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
                        onReady()
                    }
                    val samples = FloatArray(count) { pcm[it] / 32768f }
                    onLevel((rms(samples) * 100).toInt().coerceIn(0, 100))
                    samples
                }, { shouldContinue() && !isCancelled() && total < SAMPLE_RATE * 360 }, {
                    if (microphone.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val stoppedAt = System.nanoTime()
                        val timestamp = AudioTimestamp()
                        if (microphone.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS)
                            stopFrame.set(timestamp.framePosition + (stoppedAt - timestamp.nanoTime).coerceAtLeast(0) * SAMPLE_RATE / 1_000_000_000)
                        microphone.stop()
                    }
                })
                onCapture(capture)
                // Capture keeps the opening audio while the cold models load.
                if (isCancelled()) return ""
                return engine().transcribe({ capture.next() }, onText, isCancelled, shouldContinue)
            } finally {
                onCapture(null)
                try { capture?.close() } finally { microphone.release() }
            }
        }

        const val SAMPLE_RATE = 16000
        // getVadModelConfig is a switch over an int: 0 is silero-vad, 1 is ten-vad.
        const val VAD_SILERO = 0
        // K60 fixed-audio comparisons favor two streaming threads; tune each model separately.
        const val STREAMING_THREADS = 2
        const val SECOND_PASS_THREADS = 4
        /** 只给最后一句一次预算，包含流式补尾耗时；超时退回实时文字。 */
        const val FINAL_SECOND_PASS_BUDGET_MS = 350L
        /**
         * 松手时最后一句还能等多久。
         *
         * 只有「最后一句 + 二遍模型已经加载完」才给预算：模型没加载完时 `decodeSecondPass` 会先
         * 阻塞在加载上（K60 冷启动实测 0.78–0.84s），预算只会白烧，用户白等一次松手。
         */
        internal fun releaseBudget(deadline: Long, isLast: Boolean, secondLoaded: Boolean, now: Long = System.nanoTime()): Long =
            if (isLast && secondLoaded) ((deadline - now) / 1_000_000).coerceAtLeast(0) else 0
        /**
         * 二遍语种。`auto` 是 SenseVoice 自己的 LID 档（模型元数据里有 `lang_auto` 这个 token id，
         * sherpa 只是在 `lang2id` 里查表），不是额外的推理开销：语种 token 本来就由模型自己吐。
         * 可选值只有 auto/zh/en/ja/ko/yue，写别的会被原生层吞掉并退回 0（即 auto）。
         */
        const val SECOND_PASS_LANGUAGE = "auto"
        const val VAD_THREADS = 1
        /** silero 判人声的概率阈值；0.5（上游默认）会吞掉句首轻声，见 init 里的实测。 */
        const val VAD_THRESHOLD = 0.4f
        const val AUDIO_CHUNK_SAMPLES = 320
        /**
         * VAD 报出停顿后还要等多久才真的切段。
         *
         * 这版 silero 的 min_silence_duration 是 0.25s，报出来就切等于「0.25s 换气也算一句话」；
         * 再等 0.25s 才是 0.5s 的整句停顿，句内换气不会被切成两个没有上下文的片段。
         * 停顿那一刻仍会先提交一次二遍整理，所以松手不额外等待；测到 0.9s 会把 1s 内的句子
         * 并成一段，固定音频混合样本反而从 5/16 掉到 4/16，故不采用更长的合并。
         */
        const val COMMIT_SILENCE_SAMPLES = SAMPLE_RATE / 4
        /** 超过这个长度就不再为「停顿」预跑二遍（见 transcribe 里的 early 分支）。 */
        const val EARLY_SECOND_PASS_LIMIT = SAMPLE_RATE * 8
        const val CAPTURE_BUFFER_CHUNKS = SAMPLE_RATE * 3 / AUDIO_CHUNK_SAMPLES
        /** 句段终止符，用于判断 SenseVoice 是否已给出落点、以及拼接时是否要去掉叠加的标点。 */
        val SENTENCE_END = charArrayOf('。', '！', '？', '.', '!', '?', '…')
        /** 比较二遍结果时忽略的标点集合：流式侧不带标点，带上算差异会误杀。 */
        val PUNCTUATION = ("，。！？；：、（）《》【】“”‘’…—～·「」『』〈〉〔〕" +
            ",.!?;:()[]<>\"'~@#$%^&*-_=+|\\/").toSet()
        val CHINESE_DIGIT = mapOf('零' to 0L, '〇' to 0L, '一' to 1L, '二' to 2L, '两' to 2L,
            '三' to 3L, '四' to 4L, '五' to 5L, '六' to 6L, '七' to 7L, '八' to 8L, '九' to 9L)
        val CHINESE_UNIT = mapOf('十' to 10L, '百' to 100L, '千' to 1000L, '万' to 10000L, '亿' to 100000000L)
        const val BIG_UNIT = 10000L
        private val NUMBER_RUN = Regex("[-负]?[0-9零〇一二两三四五六七八九十百千万亿]+(?:[.点][0-9零〇一二两三四五六七八九]+)?[%％]?")
        private const val NUMBER_CHARS = "0-9零〇一二两三四五六七八九十百千万亿"
        private const val CLOCK_DIGITS = "0-9零〇一二两三四五六七八九十"
        private val MEASURE_NUMBER = Regex("(?<![$NUMBER_CHARS.点负-])([-负]?[$NUMBER_CHARS]+(?:[.点][0-9零〇一二两三四五六七八九]+)?)(公里|千米|厘米|毫米|米|千克|公斤|克|斤|吨|毫升|升|元|块钱)")
        private val CLOCK_NUMBER = Regex("(?<![$NUMBER_CHARS.点负-])(凌晨|早上|上午|中午|下午|傍晚|晚上|夜里)?([$CLOCK_DIGITS]+)点(半|钟|整|[$CLOCK_DIGITS]+分(?:[$CLOCK_DIGITS]+秒)?)?(?![$NUMBER_CHARS])")
        private val AMBIGUOUS_NUMBER = Regex("[一二两三四五六七八九]{2}|[百千万亿][一二两三四五六七八九](?:[.点]|$)")
        private val ENGLISH_WORD = Regex("[A-Za-z]+(?:['’][A-Za-z]+)?")
        // shortcut: all-caps speech loses name casing; extend this list when a specific acronym needs preserving.
        private val ACRONYMS = setOf("I", "AI", "USB", "CPU", "GPU", "API", "GPS", "HTTP", "HTTPS", "HTML", "CSS", "JSON", "XML", "URL", "PDF", "SQL", "PC", "OK")
        /** 「中文谐音换术语」时允许二遍顺手少掉的中文字数。再多就是改写，不是换术语。 */
        private const val MAX_TERM_DROPS = 3
        private val SPACE_BEFORE_PUNCTUATION = Regex("[ \\t]+(?=[，。！？；：、）】》”’])")
        private val SPACE_AFTER_PUNCTUATION = Regex("(?<=[，。！？；：、（【《“‘])[ \\t]+")
        private val SPACE_BETWEEN_CHINESE = Regex("(?<=[\\u4e00-\\u9fff])[ \\t]+(?=[\\u4e00-\\u9fff])")
        private val REPEATED_SPACE = Regex("[ \\t]{2,}")
        /** 不在两个 ASCII 字母数字之间的空格没有比较意义；英文词之间的空格要留。 */
        private val LOOSE_SPACE = Regex(" (?![A-Za-z0-9])|(?<![A-Za-z0-9]) ")
        private val HAN = Regex("[\\u4e00-\\u9fff]")
        private val KANA = Regex("[\\u3040-\\u30ff\\u31f0-\\u31ff]")
        private val HANGUL = Regex("[\\u1100-\\u11ff\\uac00-\\ud7af]")
        /** 片段类收尾符号：后面接这些说明还没说完，不补句号。 */
        val CLOSERS = "）)】》」』〉〕”’…~～".toSet()
        fun rms(samples: FloatArray) = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size)
        fun concat(chunks: List<FloatArray>): FloatArray {
            val total = chunks.sumOf { it.size }
            if (total == 0) return FloatArray(0)
            val out = FloatArray(total)
            var offset = 0
            for (chunk in chunks) { chunk.copyInto(out, offset); offset += chunk.size }
            return out
        }
/** SenseVoice 把标点当独立 token 输出，前后常带空格，只清汉字之间的空格清不掉 `今天 ，天气`。 */
        fun clean(value: String) = sentenceCase(value.trim()
            .replace(SPACE_BEFORE_PUNCTUATION, "")
            .replace(SPACE_AFTER_PUNCTUATION, "")
            .replace(SPACE_BETWEEN_CHINESE, "")
            .replace(REPEATED_SPACE, " "))
        private fun sentenceCase(value: String): String {
            var start = true
            var end = 0
            return ENGLISH_WORD.replace(value) { match ->
                val gap = value.substring(end, match.range.first)
                if (gap.indices.any { i -> gap[i] in SENTENCE_END && !(gap[i] == '.' && gap.getOrNull(i - 1)?.isDigit() == true && gap.getOrNull(i + 1)?.isDigit() == true) }) start = true
                else if (gap.any { it.isLetterOrDigit() }) start = false
                val word = match.value
                val upper = word.uppercase(Locale.ROOT)
                var normalized = when {
                    upper in ACRONYMS -> upper
                    word.none { it.isLowerCase() } -> word.lowercase(Locale.ROOT)
                    else -> word
                }
                if (normalized.startsWith("i'") || normalized.startsWith("i’")) normalized = "I" + normalized.drop(1)
                if (start) normalized = normalized.replaceFirstChar { it.uppercaseChar() }
                start = false
                end = match.range.last + 1
                normalized
            }
        }
        fun join(left: String, right: String): String {
            // SenseVoice 给每段收尾都落句号，两段拼接会出现「。 ，」这种叠加标点。
            val tail = if (left.lastOrNull() != null && left.last() in SENTENCE_END)
                right.trimStart { it in PUNCTUATION } else right
            val space = left.lastOrNull()?.let { it.code < 128 && it.isLetterOrDigit() } == true &&
                tail.firstOrNull()?.let { it.code < 128 && it.isLetterOrDigit() } == true
            return left + (if (space) " " else "") + tail
        }
        /**
         * 抽出文本里的数值 token，中文数字与阿拉伯数字都归一到同一个十进制写法。
         *
         * 二遍若把「十点」改写成「10点」，形态变化不等于内容改写。
         * 此处仅做安全比较；默认显示转换由 formatNumbers 根据上下文限定。
         */
        fun numericTokens(text: String): List<String> {
            return NUMBER_RUN.findAll(text).map { canonicalNumber(it.value) }.toList()
        }
        fun formatNumbers(text: String): String {
            // shortcut: only explicit measures/clocks; keep ambiguous colloquial numbers until broader ITN is validated.
            val measured = MEASURE_NUMBER.replace(text) { match ->
                val number = match.groupValues[1]
                val unsigned = number.trimStart('-', '负')
                if (number.none { it in CHINESE_DIGIT || it in CHINESE_UNIT } ||
                    unsigned.first() in "百千万亿" || AMBIGUOUS_NUMBER.containsMatchIn(unsigned)) match.value
                else canonicalNumber(number) + match.groupValues[2]
            }
            return CLOCK_NUMBER.replace(measured) { match ->
                val prefix = match.groupValues[1]
                val hour = canonicalNumber(match.groupValues[2]).toIntOrNull()
                val tail = match.groupValues[3]
                val parts = NUMBER_RUN.findAll(tail).toList()
                if (hour == null || hour !in 0..23 ||
                    (hour == 1 && prefix.isEmpty() && tail.isEmpty()) ||
                    parts.any { canonicalNumber(it.value).toIntOrNull() !in 0..59 }) match.value
                else prefix + hour + "点" + NUMBER_RUN.replace(tail) { canonicalNumber(it.value) }
            }
        }
        private fun canonicalNumber(value: String): String {
            val negative = value.startsWith('-') || value.startsWith('负')
            val percent = value.endsWith('%') || value.endsWith('％')
            val parts = value.trimStart('-', '负').trimEnd('%', '％').split('.', '点')
            val integer = chineseNumeral(parts[0]) ?: return value
            val number = if (parts.size == 1) integer else {
                val fraction = parts[1].map { if (it in '0'..'9') it else CHINESE_DIGIT[it]?.toInt()?.digitToChar() ?: return value }.joinToString("")
                BigDecimal("$integer.$fraction").stripTrailingZeros().toPlainString()
            }
            return (if (negative) "-" else "") + number + (if (percent) "%" else "")
        }
        private fun chineseNumeral(run: String): String? {
            if (run.none { it in CHINESE_UNIT }) return run.map {
                if (it in '0'..'9') it else CHINESE_DIGIT[it]?.toInt()?.digitToChar() ?: return null
            }.joinToString("")
            var total = BigInteger.ZERO
            var section = BigInteger.ZERO
            var number = BigInteger.ZERO
            for (ch in run) {
                val unit = CHINESE_UNIT[ch]
                if (unit != null) {
                    val scale = BigInteger.valueOf(unit)
                    if (unit < BIG_UNIT) section += number.max(BigInteger.ONE) * scale
                    else if (unit == BIG_UNIT) {
                        total += (section + number).max(BigInteger.ONE) * scale
                        section = BigInteger.ZERO
                    } else {
                        total = (total + section + number).max(BigInteger.ONE) * scale
                        section = BigInteger.ZERO
                    }
                    number = BigInteger.ZERO
                } else {
                    val digit = if (ch in '0'..'9') (ch - '0').toLong() else CHINESE_DIGIT[ch] ?: return null
                    number = number * BigInteger.TEN + BigInteger.valueOf(digit)
                }
            }
            return (total + section + number).toString()
        }
        /**
         * 判定二遍结果是否值得采用。
         *
         * 关键点一：**比较前必须剥掉标点**。流式 Zipformer 不产标点，二遍 SenseVoice 产标点，
         * 若把标点算进编辑距离，每加一个逗号就算一次改写，短句必然跌破阈值而退回流式文本 ——
         * 结果是 SenseVoice 的标点和 ITN 被自己的安全阀全丢掉。数字先折叠同理。
         *
         * 关键点二：**英文词界是内容的一部分**。旧实现先把空白全删再比较，`JSON` 与 `J S O N`、
         * `VOXFIX` 与 `VOX FIX` 就成了同一个串，二遍的错误拆词反而被当成「内容一致」放进来；
         * 固定音频里混合样本因此从流式 5/16 掉到 4/16。这里按英文词序列比较，拆词/并词都算差异。
         *
         * 关键点三：英文一旦有差异默认退回流式，因为二遍确实会改错（`PDF→PF`、`computer→MPUTER`、
         * `API→APPI`）。只有两种能自证的情形放行，见 [hearsEnglishOnly] 与 [rewritesEnglishLetters]。
         *
         * 关键点四：**语种不同时这套阈值不适用**。二遍报出粤/日/韩，说明流式基座（只有中英）
         * 那串必然是同音汉字，相似度天然极低；此时按 [adoptsForeign] 走整句采用，不比较相似度。
         * [lang] 为空表示调用方没拿到标签（旧调用、单元测试），行为与只比文本时完全一致。
         */
        fun refine(raw: String, candidate: String, lang: String = ""): String {
            if (candidate.isEmpty()) return raw
            if (raw.isEmpty()) return candidate
            if (adoptsForeign(raw, candidate, lang)) return candidate
            val said = englishWords(raw)
            val heard = englishWords(candidate)
            // 术语替换会顺手少掉中文字（`一笑` 里的 `一` 会被 NUMBER_RUN 当成数字），
            // 所以这一种情形单独用数字子序列判断，其余仍按归一形态严格比。
            val term = said != heard && prefersKnownEnglish(raw, candidate, said, heard)
            // 数值按归一后的形态比，「十点」与「10点」应当等价。
            if (!term && numericTokens(raw) != numericTokens(candidate)) return raw
            if (said != heard) {
                if (!term && !hearsEnglishOnly(raw, candidate, said, heard) &&
                    !rewritesEnglishLetters(raw, candidate, said, heard)) return raw
                return candidate
            }
            val a = comparable(raw)
            val b = comparable(candidate)
            // 否定词一旦不同，说明二遍真的动了内容，必须保守退回。
            if (Regex("不|没|未|别|无|勿").findAll(a).map { it.value }.toList() !=
                Regex("不|没|未|别|无|勿").findAll(b).map { it.value }.toList()) return raw
            if (b.isEmpty()) return raw
            var previous = IntArray(b.length + 1) { it }
            for (i in a.indices) {
                val current = IntArray(b.length + 1); current[0] = i + 1
                for (j in b.indices) current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
                previous = current
            }
            val length = maxOf(a.length, b.length)
            return if (length > 0 && 1.0 - previous[b.length].toDouble() / length >= 0.85) candidate else raw
        }
        /**
         * 通用SenseVoice的粤语LID可用；日/韩再由正文脚本确认。
         *
         * 流式基座（bilingual zh-en）产不出粤/日/韩，所以这三种语种下流式文本没有比较价值，
         * 相似度门会拒绝正确结果。粤语可能没有专用字，不能以字形否决语种。
         * 此契约依赖构建校验的2024通用权重，不适用于LID恒报yue的2025微调权重。
         */
        private fun adoptsForeign(raw: String, candidate: String, lang: String): Boolean {
            val tag = lang.trim().removePrefix("<|").removeSuffix("|>").lowercase(Locale.ROOT)
            val corroborated = when (tag) {
                "ja" -> KANA.containsMatchIn(candidate)
                "ko" -> HANGUL.containsMatchIn(candidate)
                "yue" -> candidate.any { it in '\u3400'..'\u9fff' }
                else -> false
            }
            return corroborated && saneLength(raw, candidate)
        }
        /** 噪声上二遍会把一个字复读成整句；这里只挡数量级异常，正常句子的比值都在 1 附近。 */
        private fun saneLength(raw: String, candidate: String): Boolean {
            val heard = candidate.count { it.isLetterOrDigit() }
            if (heard == 0) return false
            val said = raw.count { it.isLetterOrDigit() }
            if (said == 0) return heard <= 40
            return heard.toDouble() / said in 0.35..2.8
        }
        /**
         * 比较用的折叠文本：剥标点、折数字，但保留英文词之间的空格。
         *
         * 保留空格是为了让 `json` 与 `j s o n` 不等价；句内其它空白照旧删掉，中文段不受影响。
         */
        fun comparable(value: String) = NUMBER_RUN.replace(value.lowercase(Locale.ROOT)) {
            // 「一起」是词；这里只折叠比较文本，不改显示文本，也不把它当作数字替换。
            if (it.value == "一" && value.getOrNull(it.range.last + 1) == '起') it.value else canonicalNumber(it.value)
        }.filterNot { it in PUNCTUATION }
            .replace(REPEATED_SPACE, " ")
            .replace(LOOSE_SPACE, "")
            .trim()
        /** 英文词序列：词界与词数都算内容，撇号折叠是为了让 `DON'T` 与 `DONT` 等价。 */
        fun englishWords(text: String) = ENGLISH_WORD.findAll(text)
            .map { it.value.lowercase(Locale.ROOT).replace("'", "").replace("’", "") }.toList()
        /**
         * 流式只有中文、二遍却听出了英文词（`这个已经修好了 → 这个 BUG 已经修好了`、
         * `请把代码提交到 → 请把代码提交到 GITHUB`）。
         *
         * 这是二遍最值得采用的一类：汉字必须逐字相同，二遍只能补英文，不能借机改写中文。
         * 补进来的英文还必须是完整词（≥3 个字母）或已知缩写：二遍在嘈杂/换声片段上会吐出
         * `BU` 这种半个词，那不是补内容而是加噪声。
         */
        private fun hearsEnglishOnly(raw: String, candidate: String, said: List<String>, heard: List<String>) =
            said.isEmpty() && heard.isNotEmpty() && HAN.containsMatchIn(raw) && hanOf(raw) == hanOf(candidate) &&
                heard.all { word -> word.count { it in 'a'..'z' } >= 3 || word.uppercase(Locale.ROOT) in ACRONYMS }
        /**
         * 二遍给出的英文是**真词**、流式给出的不是（或流式根本没听出英文）时，采用二遍。
         *
         * 中文用户读英文术语时，流式常按中文谐音出字（`Excel → 一笑`）或吐出半截字母
         * （`EXA`、`XL`、`EXHALE`），二遍反而听得出术语。反过来，二遍也会把真词改错
         * （`PDF → PF`、`computer → MPUTER`），所以只有「二遍全是真词、流式不是全真词」才放行。
         *
         * 中文侧仍受保护：要么一字不动（纯补英文），要么二遍的中文是流式的子序列且最多少 3 个字
         * （允许顺手修掉流式的重复字），且数字只能少不能改。
         */
        private fun prefersKnownEnglish(raw: String, candidate: String, said: List<String>, heard: List<String>): Boolean {
            if (heard.isEmpty() || !heard.all { isKnownEnglishTerm(it) }) return false
            if (said.isNotEmpty() && said.all { isKnownEnglishTerm(it) }) return false
            val before = hanOf(raw)
            val after = hanOf(candidate)
            if (before.isEmpty() && after.isEmpty()) return true
            if (before.isEmpty() || after.isEmpty()) return false
            if (after.length > before.length || before.length - after.length > MAX_TERM_DROPS) return false
            if (!isSubsequence(after, before)) return false
            return isSubsequence(numericTokens(candidate), numericTokens(raw))
        }
        /** 词表里的完整英文词，或工程/缩写表里的词。表外的长串（`BU`、`PYHO`、`XCEL`）不算术语。 */
        private fun isKnownEnglishTerm(word: String) =
            word.uppercase(Locale.ROOT) in ACRONYMS || EnglishVocabulary.contains(word)
        private fun isSubsequence(short: String, long: String): Boolean {
            var i = 0
            for (ch in long) if (i < short.length && ch == short[i]) i++
            return i == short.length
        }
        private fun isSubsequence(short: List<String>, long: List<String>): Boolean {
            var i = 0
            for (value in long) if (i < short.size && value == short[i]) i++
            return i == short.size
        }
        /**
         * 纯英文内容、且二遍没改字母总数：只可能是把同一个读音重新切分或换成同长度的写法
         * （`ARE FINE → OFFLINE`）。字母数一变就说明二遍漏字或多字（`MPUTER`、`PF`、`APPI`），
         * 这类必须拒绝。只在本组固定音频上验证过，不代表任意英文都该整段换掉。
         */
        private fun rewritesEnglishLetters(raw: String, candidate: String, said: List<String>, heard: List<String>) =
            said.isNotEmpty() && heard.isNotEmpty() && !HAN.containsMatchIn(raw) && !HAN.containsMatchIn(candidate) &&
                said.sumOf { word -> word.count { it in 'a'..'z' } } == heard.sumOf { word -> word.count { it in 'a'..'z' } }
        private fun hanOf(value: String) = value.filter { it in '\u4e00'..'\u9fff' }
        /**
         * 收尾标点。SenseVoice 自己产标点，但它只在上下文够长时才给句末落点，短句常常光秃秃
         * 一句没有终止符，而中文输入法的习惯是话说完落个句号。这里只在确实缺终止符时补，
         * 已有的标点一个都不动（再叠一层标点模型会双标点）。
         */
        fun punctuate(text: String): String {
            val trimmed = text.trim()
            val last = trimmed.lastOrNull() ?: return trimmed
            if (last in SENTENCE_END) return trimmed
            // 片段类收尾（括号、引号、连字符等）不补句号。
            if (last in CLOSERS) return trimmed
            if (last.code < 128 && !last.isLetterOrDigit()) return trimmed
            // 中文疑问句的问号由标点模型给出；纯英文没有模型覆盖，用句首疑问词判断，
            // 否则 `COULD YOU TURN OFF THE LIGHT` 会被补成陈述句句号。
            return trimmed + when {
                trimmed.any { it in '\u4e00'..'\u9fff' } -> "。"
                ENGLISH_QUESTION_START.containsMatchIn(trimmed) -> "?"
                else -> "."
            }
        }
        /**
         * 只有含汉字、且不带引号括号的文本才交给标点模型。
         *
         * 模型会在引号前插逗号（`他说“明天见”就走了` → `他说“，明天见”就走了`），
         * 这类正文宁可不加标点也不能改坏。
         */
        fun needsPunctuationModel(text: String) =
            text.any { it in '\u4e00'..'\u9fff' } && text.none { it in QUOTE_OR_BRACKET }
        /**
         * 交给模型前剥掉句读类标点。
         *
         * 模型对已经带标点的文本会叠标点：`你好，请问洗手间在哪里？` 会变成
         * `你好，，请问洗手间在哪里？？`。剥成纯文字让它重新断句，位置由模型重算。
         * 不剥 `.`：小数点（`120.5`）会被拆成 `120 . 5`；纯英文句子本来也不走模型。
         */
        fun stripSentencePunctuation(text: String) = SENTENCE_MARKS.replace(text, "")
        /** 二次防御：模型偶尔仍在残留标点后追加，相邻重复只留一个。 */
        fun dedupePunctuation(text: String) = REPEATED_MARK.replace(text, "$1")
        /** 引号与括号：模型处理这些会插错逗号，出现即不交给模型。 */
        private const val QUOTE_OR_BRACKET = "\"'「」『』“”‘’（）()《》【】〈〉〔〕"
        /** 纯英文句首疑问词：没有模型兜底时用它决定问号还是句号。 */
        private val ENGLISH_QUESTION_START = Regex(
            "(?i)^(could|would|can|will|should|shall|do|does|did|is|are|was|were|have|has|had|" +
                "what|where|when|why|how|who|whom|whose|which)\\b")
        private val SENTENCE_MARKS = Regex("[，。？！；：、…,?!;]")
        private val REPEATED_MARK = Regex("([，。？！；：、])\\1+")
    }
}

/**
 * 英文词表：包内 `keyboard/english-words.txt`（rime-ice，21197 词，许可随包）。
 *
 * 只用来判断二遍给出的英文是不是一个真词：`EXCEL` 在表里，`PF`/`MPUTER`/`APPI` 不在。
 * 在后台线程加载；还没加载完时一律返回 false，判定退化成「不放行」，不会误改文本。
 */
internal object EnglishVocabulary {
    @Volatile private var table: Set<String>? = null

    fun load(assets: AssetManager) {
        if (table != null) return
        val words = assets.open(ASSET).bufferedReader().useLines { lines ->
            lines.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toHashSet()
        }
        table = words
    }

    /** 测试注入用：固定词集不需要读资产。 */
    fun install(words: Set<String>) {
        table = words.map { it.lowercase(Locale.ROOT) }.toHashSet()
    }

    fun contains(word: String): Boolean = table?.contains(word.lowercase(Locale.ROOT)) ?: false

    private const val ASSET = "keyboard/english-words.txt"
}
