package local.voxfix.debug

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthetic, fixed phrases only. Does not open the microphone or write to an editor. */
internal object NumberNormalizationSmoke {
    fun run(context: Context): String {
        val expected = mapOf("time" to "下午3点半。", "distance" to "跑了10公里。",
            "money" to "123元。", "ordinary" to "我们一起去公园散步。")
        val cases = listOf("time", "distance", "money", "ordinary").associateWith { name ->
            val bytes = context.cacheDir.resolve("number-itn/$name.f32").readBytes()
            check(bytes.isNotEmpty() && bytes.size % 4 == 0 && bytes.size <= 16000 * 4 * 10)
            FloatArray(bytes.size / 4).also {
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
                check(it.all { value -> value.isFinite() && value in -1f..1f })
            }
        }
        val rows = JSONArray()
        for (itn in listOf(false, true)) {
            val recognizer = OfflineRecognizer(context.assets, OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig("sensevoice/model.int8.onnx", language = "zh", useInverseTextNormalization = itn),
                tokens = "sensevoice/tokens.txt", numThreads = SpeechEngine.SECOND_PASS_THREADS)))
            try {
                for ((name, samples) in cases) {
                    val stream = recognizer.createStream()
                    val text = try {
                        stream.acceptWaveform(samples, 16000)
                        recognizer.decode(stream)
                        SpeechEngine.clean(recognizer.getResult(stream).text)
                    } finally { stream.release() }
                    check(text.isNotBlank())
                    rows.put(JSONObject().put("case", name).put("path", "secondPass").put("itn", itn).put("text", text))
                }
            } finally { recognizer.release(); NativeMemory.purge() }
        }
        for (streamingOnly in listOf(true, false)) {
          val engine = SpeechEngine(context.assets)
          try {
            if (!streamingOnly) engine.secondPass()
            for (holdSilence in listOf(false, true)) {
                for ((name, speech) in cases) {
                    val samples = if (holdSilence) speech + FloatArray(32000) else speech
                    val started = SystemClock.elapsedRealtime()
                    var offset = 0
                    var lastUpdate = ""
                    val text = engine.transcribe({
                        if (offset == samples.size) null else {
                            val end = minOf(offset + SpeechEngine.AUDIO_CHUNK_SAMPLES, samples.size)
                            val wait = started + end * 1000L / 16000 - SystemClock.elapsedRealtime()
                            if (wait > 0) SystemClock.sleep(wait)
                            samples.copyOfRange(offset, end).also { offset = end }
                        }
                    }, {
                        check(it == SpeechEngine.formatNumbers(it)) { "Unformatted live update: $it" }
                        lastUpdate = it
                    }, { false }, { !streamingOnly && offset < samples.size })
                    check(text == expected.getValue(name)) { "$name: expected ${expected[name]}, got $text" }
                    check(lastUpdate == text) { "Displayed text differs from final result" }
                    rows.put(JSONObject().put("case", name).put("path", "currentPipeline")
                        .put("streamingOnly", streamingOnly)
                        .put("holdSilenceMs", if (holdSilence) 2000 else 0).put("text", text))
                }
            }
          } finally { engine.release() }
        }
        return JSONObject().put("syntheticAudio", true).put("runs", rows).toString()
    }
}
