package local.voxfix.debug

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fixed synthetic fixtures only; separates audio cropping from the production adoption gate. */
internal object VoiceBoundaryProbe {
    fun run(context: Context): String {
        val folder = context.cacheDir.resolve("mandarin-stress-20261018")
        val cases = JSONArray(folder.resolve("diagnosis.json").readText())
        val rows = JSONArray()
        val engine = SpeechEngine(context.assets)
        val config = checkNotNull(getVadModelConfig(SpeechEngine.VAD_SILERO))
        config.numThreads = SpeechEngine.VAD_THREADS
        config.sileroVadModelConfig.threshold = SpeechEngine.VAD_THRESHOLD
        val vad = Vad(context.assets, config)
        try {
            val second = engine.secondPass()
            fun decode(samples: FloatArray): Pair<String, String> {
                if (samples.isEmpty()) return "" to ""
                val stream = second.createStream()
                return try {
                    stream.acceptWaveform(samples, 16000)
                    second.decode(stream)
                    second.getResult(stream).let { SpeechEngine.clean(it.text) to it.lang }
                } finally { stream.release() }
            }
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val id = case.getString("id")
                check(id.matches(Regex("[a-z0-9-]+")))
                val bytes = folder.resolve("$id.f32").readBytes()
                val samples = FloatArray(bytes.size / 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
                check(samples.isNotEmpty() && samples.size <= 16000 * 90 && samples.all { it.isFinite() && it in -1f..1f })
                val ranges = mutableListOf<Pair<Int, Int>>()
                fun drain() {
                    while (!vad.empty()) {
                        val segment = vad.front()
                        ranges.add(segment.start to minOf(samples.size, segment.start + segment.samples.size))
                        vad.pop()
                    }
                }
                for (offset in samples.indices step 320) {
                    vad.acceptWaveform(samples.copyOfRange(offset, minOf(offset + 320, samples.size)))
                    drain()
                }
                vad.flush(); drain(); vad.reset()
                val variants = JSONArray()
                for (padding in listOf(0, 160, 320, -1)) {
                    val parts = mutableListOf<FloatArray>()
                    var previousEnd = 0
                    for ((start, end) in ranges) {
                        val first = maxOf(previousEnd, (start - padding * 16).coerceAtLeast(0))
                        val last = minOf(samples.size, end + padding * 16)
                        if (last > first) { parts.add(samples.copyOfRange(first, last)); previousEnd = last }
                    }
                    val input = if (padding < 0) samples else SpeechEngine.concat(parts)
                    val (text, lang) = decode(input)
                    variants.put(JSONObject().put("paddingMs", padding).put("audioMs", input.size / 16)
                        .put("text", text).put("adopted", SpeechEngine.refine(case.getString("raw"), text, lang)))
                }
                rows.put(JSONObject().put("id", id).put("raw", case.getString("raw"))
                    .put("ranges", JSONArray(ranges.map { listOf(it.first / 16, it.second / 16) }))
                    .put("variants", variants))
                folder.resolve("boundary-probe.json").writeText(rows.toString(2))
            }
        } finally { vad.release(); engine.release() }
        val unformatted = OfflineRecognizer(context.assets, OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig("sensevoice/model.int8.onnx", language = "auto", useInverseTextNormalization = false),
            tokens = "sensevoice/tokens.txt", numThreads = SpeechEngine.SECOND_PASS_THREADS)))
        try {
            val bytes = folder.resolve("new-number-1.f32").readBytes()
            val samples = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
            val stream = unformatted.createStream()
            try {
                stream.acceptWaveform(samples, 16000); unformatted.decode(stream)
                folder.resolve("amount-no-itn.txt").writeText(unformatted.getResult(stream).text)
            } finally { stream.release() }
        } finally { unformatted.release() }
        return "VOICE_BOUNDARY_PROBE_DONE cases=${rows.length()}"
    }
}
