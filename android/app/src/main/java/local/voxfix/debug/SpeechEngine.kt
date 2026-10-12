package local.voxfix.debug

import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.util.concurrent.atomic.AtomicBoolean
import java.math.BigInteger
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.sqrt

/**
 * Two-pass dictation.
 *
 * Pass 1 is a streaming Zipformer: every 100 ms of audio yields more text, so the caller can
 * show text while the user is still talking. Pass 2 re-decodes the whole utterance after the
 * microphone closes, because a streaming hypothesis cannot revise words it already emitted.
 *
 * Silence is detected by silero-vad rather than an RMS threshold. RMS cannot tell a quiet
 * speaker from a noisy room, which previously made whole segments fall back to the streaming
 * text and, on some inputs, made a model hallucinate text with no speech at all.
 */
class SpeechEngine(assets: AssetManager) {
    private var online: OnlineRecognizer? = null
    private var sense: OfflineRecognizer? = null
    private var vad: Vad? = null
    private val recording = AtomicBoolean(false)

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
                    tokens = "streaming/tokens.txt", numThreads = STREAMING_THREADS, modelType = "zipformer"),
                endpointConfig = EndpointConfig(EndpointRule(false, 1.6f, 0f), EndpointRule(true, 0.8f, 0f), EndpointRule(false, 0f, 20f))))
            loaded("streaming")
            // SenseVoice is non-autoregressive: one pass returns the final text with its own
            // punctuation and inverse text normalization, which is why pass 2 can replace the
            // separate paraformer and punctuation models outright. It also needs a symbol table to
            // map ids back to text — without tokens it reads an empty asset path and the process
            // is killed inside native code, not by an exception.
            sense = OfflineRecognizer(assets, OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    "sensevoice/model.int8.onnx", language = "zh", useInverseTextNormalization = true),
                tokens = "sensevoice/tokens.txt", numThreads = SECOND_PASS_THREADS)))
            loaded("second")
            // getVadModelConfig(0) is silero-vad; the helper hardcodes the asset name
            // "silero_vad.onnx" and 16 kHz.
            val vadConfig = checkNotNull(getVadModelConfig(VAD_SILERO)) { "silero-vad 配置不可用" }
            vadConfig.numThreads = VAD_THREADS
            vad = Vad(assets, vadConfig)
            loaded("vad")
        } catch (error: Throwable) { release(); throw error }
    }

    fun stop() { recording.set(false) }

    fun record(onText: (String) -> Unit, onLevel: (Int) -> Unit,
               shouldContinue: () -> Boolean = { true }, isCancelled: () -> Boolean = { false }): String {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "设备不支持 16kHz 单声道录音：$minimum" }
        val microphone = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 12800))
        var capture: PcmCapture? = null
        try {
            check(microphone.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            if (!shouldContinue() || isCancelled()) return ""
            recording.set(true)
            microphone.startRecording()
            check(microphone.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风未进入录音状态" }
            Log.i("VoxFix", "ASR_MIC_STARTED")
            val pcm = ShortArray(AUDIO_CHUNK_SAMPLES)
            var total = 0
            capture = PcmCapture(SAMPLE_RATE * 3 / AUDIO_CHUNK_SAMPLES, {
                val count = microphone.read(pcm, 0, minOf(pcm.size, SAMPLE_RATE * 360 - total))
                check(count > 0) { "麦克风采样失败：$count" }
                total += count
                val samples = FloatArray(count) { pcm[it] / 32768f }
                onLevel((rms(samples) * 100).toInt().coerceIn(0, 100))
                samples
            }, { recording.get() && shouldContinue() && !isCancelled() && total < SAMPLE_RATE * 360 }, {
                if (microphone.recordingState == AudioRecord.RECORDSTATE_RECORDING) microphone.stop()
            })
            return transcribe({ capture.next() }, onText, isCancelled)
        } finally {
            recording.set(false)
            try { capture?.close() } finally { microphone.release() }
        }
    }

    /** Also accepts fixed upstream audio for native regression without recording user speech. */
    internal fun transcribe(nextChunk: () -> FloatArray?, onText: (String) -> Unit,
                            isCancelled: () -> Boolean = { false }): String {
        val recognizer = checkNotNull(online)
        val detector = checkNotNull(vad)
        val stream = recognizer.createStream()
        var complete = ""
        var previousText = ""
        val started = System.nanoTime()
        fun emit(text: String) {
            if (text.isNotBlank() && text != previousText && !isCancelled()) {
                if (previousText.isEmpty()) Log.i("VoxFix", "ASR_FIRST_TEXT elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
                previousText = text
                onText(text)
            }
        }
        // Pass 2 needs a whole utterance, so audio stays buffered until VAD reports a pause.
        val utterance = mutableListOf<FloatArray>()
        var utteranceSamples = 0
        var streamingOnly = false

        fun decodeSecondPass(): String {
            val samples = concat(utterance)
            utterance.clear()
            utteranceSamples = 0
            if (samples.isEmpty()) return ""
            val offline = checkNotNull(sense)
            val second = offline.createStream()
            return try {
                second.acceptWaveform(samples, SAMPLE_RATE)
                offline.decode(second)
                clean(offline.getResult(second).text)
            } finally { second.release() }
        }

        /** A finished speech segment was sitting in the VAD queue; commit it as one utterance. */
        fun commitSegment() {
            if (utterance.isEmpty() && !streamingOnly) return
            val streaming = clean(recognizer.getResult(stream).text)
            val second = if (streamingOnly || isCancelled()) "" else decodeSecondPass()
            streamingOnly = false
            val chosen = if (streaming.isEmpty()) second else refine(streaming, second)
            if (chosen.isNotEmpty()) complete = join(complete, chosen)
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
            }
        }

        /** True when VAD sees a pause long enough to close the utterance. */
        fun paused() = !detector.isSpeechDetected() && (utterance.isNotEmpty() || streamingOnly)

        try {
            while (!isCancelled()) {
                val samples = nextChunk() ?: break
                if (isCancelled()) return ""
                stream.acceptWaveform(samples, SAMPLE_RATE)
                detector.acceptWaveform(samples)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                drainUtterance()
                if (paused()) commitSegment()
                emit(join(complete, clean(recognizer.getResult(stream).text)))
            }
            if (isCancelled()) return ""
            // Flush trailing audio so the final word is not lost when the user stops mid-syllable.
            stream.acceptWaveform(FloatArray(8000), SAMPLE_RATE)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            detector.flush()
            drainUtterance()
            commitSegment()
            if (isCancelled()) return ""
            val tail = clean(recognizer.getResult(stream).text)
            if (tail.isNotEmpty() && complete.isEmpty()) complete = tail
            if (complete.isNotEmpty()) complete = punctuate(complete)
            emit(complete)
            Log.i("VoxFix", "ASR_DECODE_FINISHED elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            return complete
        } finally {
            stream.release()
            detector.reset()
        }
    }

    fun release() {
        online?.release(); online = null
        sense?.release(); sense = null
        vad?.release(); vad = null
    }

    companion object {
        const val SAMPLE_RATE = 16000
        // getVadModelConfig is a switch over an int: 0 is silero-vad, 1 is ten-vad.
        const val VAD_SILERO = 0
        // K60 fixed-audio comparisons favor two streaming threads; tune each model separately.
        const val STREAMING_THREADS = 2
        const val SECOND_PASS_THREADS = 4
        const val VAD_THREADS = 1
        const val AUDIO_CHUNK_SAMPLES = 1600
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
        private val ENGLISH_WORD = Regex("[A-Za-z]+(?:['’][A-Za-z]+)?")
        // shortcut: all-caps speech loses name casing; extend this list when a specific acronym needs preserving.
        private val ACRONYMS = setOf("I", "AI", "USB", "CPU", "GPU", "API", "GPS", "HTTP", "HTTPS", "HTML", "CSS", "JSON", "XML", "URL", "PDF", "SQL", "PC", "OK")
        private val SPACE_BEFORE_PUNCTUATION = Regex("[ \\t]+(?=[，。！？；：、）】》”’])")
        private val SPACE_AFTER_PUNCTUATION = Regex("(?<=[，。！？；：、（【《“‘])[ \\t]+")
        private val SPACE_BETWEEN_CHINESE = Regex("(?<=[\\u4e00-\\u9fff])[ \\t]+(?=[\\u4e00-\\u9fff])")
        private val REPEATED_SPACE = Regex("[ \\t]{2,}")
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
         * ITN 开启时 SenseVoice 会把「十点」改写成「10点」，那是形态变化不是内容改写。
         * 不能直接对文本做数字折叠 ——「一起走」的「一」是词不是数会被误伤；所以只在
         * 取数值 token 时做归一，文本本身不动。
         */
        fun numericTokens(text: String): List<String> {
            return NUMBER_RUN.findAll(text).map { canonicalNumber(it.value) }.toList()
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
         * 关键点：**比较前必须剥掉标点**。流式 Zipformer 不产标点，二遍 SenseVoice 产标点，
         * 若把标点算进编辑距离，每加一个逗号就算一次改写，短句必然跌破阈值而退回流式文本 ——
         * 结果是 SenseVoice 的标点和 ITN 被自己的安全阀全丢掉。数字先折叠同理。
         */
        fun refine(raw: String, candidate: String): String {
            if (candidate.isEmpty()) return raw
            if (raw.isEmpty()) return candidate
            fun comparable(value: String) = NUMBER_RUN.replace(value.lowercase(Locale.ROOT)) {
                // 「一起」是词；这里只折叠比较文本，不改显示文本，也不把它当作数字替换。
                if (it.value == "一" && value.getOrNull(it.range.last + 1) == '起') it.value else canonicalNumber(it.value)
            }.filterNot { it.isWhitespace() || it in PUNCTUATION }
            val a = comparable(raw)
            val b = comparable(candidate)
            // 英文、否定词一旦不同，说明二遍真的动了内容，必须保守退回。
            for (pattern in listOf("[a-z][a-z0-9]*", "不|没|未|别|无|勿")) {
                val left = Regex(pattern).findAll(a).map { it.value }.toList()
                val right = Regex(pattern).findAll(b).map { it.value }.toList()
                if (left != right) return raw
            }
            // 数值按归一后的形态比，「十点」与「10点」应当等价。
            if (numericTokens(raw) != numericTokens(candidate)) return raw
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
            return trimmed + if (trimmed.any { it in '\u4e00'..'\u9fff' }) "。" else "."
        }
    }
}
