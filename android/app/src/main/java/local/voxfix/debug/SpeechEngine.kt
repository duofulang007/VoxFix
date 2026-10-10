package local.voxfix.debug

import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class SpeechEngine(assets: AssetManager) {
    private var online: OnlineRecognizer? = null
    private var final: OfflineRecognizer? = null
    private var punctuation: OfflinePunctuation? = null
    private val recording = AtomicBoolean(false)

    init {
        try {
            online = OnlineRecognizer(assets, OnlineRecognizerConfig(
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig("streaming/encoder.onnx", "streaming/decoder.onnx", "streaming/joiner.onnx"),
                    tokens = "streaming/tokens.txt", numThreads = 2, modelType = "zipformer"),
                endpointConfig = EndpointConfig(EndpointRule(false, 1.6f, 0f), EndpointRule(true, 0.8f, 0f), EndpointRule(false, 0f, 20f))))
            final = OfflineRecognizer(assets, OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig("final/model.onnx"),
                tokens = "final/tokens.txt", numThreads = 2)))
            punctuation = OfflinePunctuation(assets, OfflinePunctuationConfig(
                OfflinePunctuationModelConfig("punctuation/model.onnx", numThreads = 2)))
        } catch (error: Throwable) { release(); throw error }
    }

    fun stop() { recording.set(false) }

    fun record(onText: (String) -> Unit, onLevel: (Int) -> Unit, shouldContinue: () -> Boolean = { true }): String {
        val recognizer = checkNotNull(online)
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "设备不支持 16kHz 单声道录音：$minimum" }
        val stream = recognizer.createStream()
        var microphone: AudioRecord? = null
        var complete = ""
        val chunks = mutableListOf<FloatArray>()
        fun finishSegment() {
            val raw = clean(recognizer.getResult(stream).text)
            if (raw.isNotEmpty() && chunks.isNotEmpty()) {
                val samples = FloatArray(chunks.sumOf { it.size })
                var offset = 0
                for (chunk in chunks) { chunk.copyInto(samples, offset); offset += chunk.size }
                var chosen = raw
                if (sqrt(samples.sumOf { (it * it).toDouble() } / samples.size) > 0.002) {
                    val second = checkNotNull(final).createStream()
                    try {
                        second.acceptWaveform(samples, 16000)
                        checkNotNull(final).decode(second)
                        chosen = refine(raw, clean(checkNotNull(final).getResult(second).text))
                    } finally { second.release() }
                }
                complete = join(complete, chosen)
            }
            chunks.clear()
        }
        try {
            if (!shouldContinue()) return ""
            microphone = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 12800))
            check(microphone.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            recording.set(true)
            if (!shouldContinue()) return ""
            microphone.startRecording()
            check(microphone.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风未进入录音状态" }
            val pcm = ShortArray(3200)
            var total = 0
            while (recording.get() && total < 16000 * 360) {
                val count = microphone.read(pcm, 0, pcm.size)
                check(count > 0) { "麦克风采样失败：$count" }
                val samples = FloatArray(count) { pcm[it] / 32768f }
                total += count
                onLevel((sqrt(samples.sumOf { (it * it).toDouble() } / count) * 100).toInt().coerceIn(0, 100))
                chunks.add(samples)
                stream.acceptWaveform(samples, 16000)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                if (recognizer.isEndpoint(stream)) { finishSegment(); recognizer.reset(stream) }
                onText(join(complete, clean(recognizer.getResult(stream).text)))
            }
            microphone.stop()
            stream.acceptWaveform(FloatArray(8000), 16000)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            finishSegment()
            // Acoustic pauses split decoding; punctuation needs the whole utterance after capture ends.
            if (complete.isNotEmpty()) complete = checkNotNull(punctuation).addPunctuation(complete)
            onText(complete)
            return complete
        } finally {
            recording.set(false)
            microphone?.release()
            stream.release()
        }
    }

    fun release() {
        online?.release(); online = null
        final?.release(); final = null
        punctuation?.release(); punctuation = null
    }

    companion object {
        fun clean(value: String) = value.trim().replace(Regex("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])"), "")
        fun join(left: String, right: String): String {
            val space = left.lastOrNull()?.let { it.code < 128 && it.isLetterOrDigit() } == true &&
                right.firstOrNull()?.let { it.code < 128 && it.isLetterOrDigit() } == true
            return left + (if (space) " " else "") + right
        }
        fun refine(raw: String, candidate: String): String {
            fun matches(pattern: String, value: String) = Regex(pattern).findAll(value.lowercase()).map { it.value }.toList()
            if (candidate.isEmpty()) return raw
            for (pattern in listOf("[a-z][a-z0-9]*", "\\d+(?:[.:/]\\d+)*|[零〇一二两三四五六七八九十百千万亿]+", "不|没|未|别|无|勿")) {
                if (matches(pattern, raw) != matches(pattern, candidate)) return raw
            }
            val a = raw.lowercase().filterNot { it.isWhitespace() }
            val b = candidate.lowercase().filterNot { it.isWhitespace() }
            // shortcut: conservative edit distance protects large changes; upgrade only with device accuracy evidence.
            var previous = IntArray(b.length + 1) { it }
            for (i in a.indices) {
                val current = IntArray(b.length + 1); current[0] = i + 1
                for (j in b.indices) current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
                previous = current
            }
            val length = maxOf(a.length, b.length)
            return if (length > 0 && 1.0 - previous[b.length].toDouble() / length >= 0.85) candidate else raw
        }
    }
}
