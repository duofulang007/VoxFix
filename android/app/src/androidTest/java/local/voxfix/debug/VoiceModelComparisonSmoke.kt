package local.voxfix.debug

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Public/synthetic fixtures and isolated candidate files only; never records or edits user text. */
internal object VoiceModelComparisonSmoke {
    fun run(context: Context, label: String): String {
        check(label in setOf("current", "zip2025", "para3", "sense2025", "sense2024", "sense2024opt",
            "stream-greedy", "stream-beam2", "stream-beam4", "stream-beam8"))
        val folder = context.cacheDir.resolve("voice-quality-20261018")
        val cases = JSONArray(folder.resolve("cases.json").readText())
        check(cases.length() in 1..100)
        val started = SystemClock.elapsedRealtime()
        val online = when (label) {
            // 同一份流式权重只换解码策略：中英混说该用几路 beam，必须由手机而不是电脑决定。
            "stream-greedy", "stream-beam2", "stream-beam4", "stream-beam8" -> OnlineRecognizer(context.assets, OnlineRecognizerConfig(
                modelConfig = OnlineModelConfig(transducer = OnlineTransducerModelConfig(
                    "streaming/encoder.int8.onnx", "streaming/decoder.onnx", "streaming/joiner.int8.onnx"),
                    tokens = "streaming/tokens.txt", numThreads = SpeechEngine.STREAMING_THREADS, modelType = "zipformer",
                    modelingUnit = "cjkchar+bpe", bpeVocab = "streaming/bpe.vocab"),
                decodingMethod = if (label == "stream-greedy") "greedy_search" else "modified_beam_search",
                maxActivePaths = when (label) { "stream-beam2" -> 2; "stream-beam8" -> 8; else -> 4 },
                enableEndpoint = false))
            "current" -> OnlineRecognizer(context.assets, OnlineRecognizerConfig(
                modelConfig = OnlineModelConfig(transducer = OnlineTransducerModelConfig(
                    "streaming/encoder.int8.onnx", "streaming/decoder.onnx", "streaming/joiner.int8.onnx"),
                    tokens = "streaming/tokens.txt", numThreads = 2, modelType = "zipformer"), enableEndpoint = false))
            "zip2025" -> OnlineRecognizer(config = OnlineRecognizerConfig(
                modelConfig = OnlineModelConfig(transducer = OnlineTransducerModelConfig(
                    folder.resolve("zip2025/encoder.int8.onnx").path, folder.resolve("zip2025/decoder.onnx").path,
                    folder.resolve("zip2025/joiner.int8.onnx").path), tokens = folder.resolve("zip2025/tokens.txt").path,
                    numThreads = 2, modelType = "zipformer2"), enableEndpoint = false))
            "para3" -> OnlineRecognizer(config = OnlineRecognizerConfig(
                modelConfig = OnlineModelConfig(paraformer = OnlineParaformerModelConfig(
                    folder.resolve("para3/encoder.int8.onnx").path, folder.resolve("para3/decoder.int8.onnx").path),
                    tokens = folder.resolve("para3/tokens.txt").path, numThreads = 2, modelType = "paraformer"), enableEndpoint = false))
            else -> null
        }
        val offline = when (label) {
            "sense2025", "sense2024", "sense2024opt" -> OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(folder.resolve("$label/model.int8.onnx").path,
                    language = "auto", useInverseTextNormalization = true), tokens = folder.resolve("$label/tokens.txt").path, numThreads = 4)))
            else -> null
        }
        val loadMs = SystemClock.elapsedRealtime() - started
        val rows = JSONArray()
        var peakPss = Debug.getPss()
        try {
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val id = case.getString("id")
                check(id.matches(Regex("[a-z0-9-]+")))
                val bytes = folder.resolve("fixtures/$id.f32").readBytes()
                check(bytes.isNotEmpty() && bytes.size % 4 == 0 && bytes.size <= 16000 * 4 * 40)
                val samples = FloatArray(bytes.size / 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
                check(samples.all { it.isFinite() && it in -1.001f..1.001f })
                val decodeAt = SystemClock.elapsedRealtime()
                var firstAudioMs = -1
                var language = ""
                val text = if (online != null) {
                    val stream = online.createStream()
                    try {
                        for (offset in samples.indices step 320) {
                            val end = minOf(offset + 320, samples.size)
                            stream.acceptWaveform(samples.copyOfRange(offset, end), 16000)
                            while (online.isReady(stream)) online.decode(stream)
                            if (firstAudioMs < 0 && online.getResult(stream).text.isNotBlank()) firstAudioMs = end / 16
                        }
                        stream.acceptWaveform(FloatArray(8000), 16000)
                        stream.inputFinished()
                        while (online.isReady(stream)) online.decode(stream)
                        online.getResult(stream).text
                    } finally { stream.release() }
                } else {
                    val engine = checkNotNull(offline)
                    val stream = engine.createStream()
                    try {
                        stream.acceptWaveform(samples, 16000)
                        engine.decode(stream)
                        engine.getResult(stream).also { language = it.lang }.text
                    } finally { stream.release() }
                }
                rows.put(JSONObject().put("id", id).put("text", text).put("lang", language)
                    .put("ms", SystemClock.elapsedRealtime() - decodeAt).put("firstAudioMs", firstAudioMs))
                peakPss = maxOf(peakPss, Debug.getPss())
                folder.resolve("$label.json").writeText(JSONObject().put("model", label).put("loadMs", loadMs)
                    .put("peakPssKiB", peakPss).put("rows", rows).toString(2))
            }
        } finally { online?.release(); offline?.release() }
        return "VOICE_MODEL_COMPARISON_DONE model=$label cases=${rows.length()}"
    }
}
