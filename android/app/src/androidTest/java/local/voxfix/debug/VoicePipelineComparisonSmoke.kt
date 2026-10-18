package local.voxfix.debug

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.FutureTask

/** Replays public/synthetic audio through the actual paced production pipeline. */
internal object VoicePipelineComparisonSmoke {
    fun run(context: Context, label: String): String {
        check(label in setOf("pipeline-current", "pipeline-sense2024", "pipeline-sense2024opt", "pipeline-cold"))
        val cold = label == "pipeline-cold"
        val folder = context.cacheDir.resolve("voice-quality-20261018")
        val cases = JSONArray(folder.resolve("cases.json").readText())
        val ids = if (cold) setOf("word-0", "word-1", "public-yue-0", "plain-zh-0") else
            setOf("public-yue-0", "public-yue-11", "public-para-yue", "public-yue-long",
                "mix-huihui-1", "mix-huihui-5", "sentence-0", "plain-zh-0", "plain-zh-7")
        val rows = JSONArray()
        val engine = if (cold) null else SpeechEngine(context.assets)
        try {
            if (label.startsWith("pipeline-sense2024")) {
                val model = label.removePrefix("pipeline-")
                val loader = FutureTask {
                    OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(folder.resolve("$model/model.int8.onnx").path,
                            language = "auto", useInverseTextNormalization = true),
                        tokens = folder.resolve("$model/tokens.txt").path, numThreads = SpeechEngine.SECOND_PASS_THREADS)))
                }
                checkNotNull(engine).javaClass.getDeclaredField("secondLoader").apply { isAccessible = true }.set(engine, loader)
            }
            engine?.secondPass()
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val id = case.getString("id")
                if (id !in ids) continue
                val bytes = folder.resolve("fixtures/$id.f32").readBytes()
                check(bytes.isNotEmpty() && bytes.size % 4 == 0 && bytes.size <= 16000 * 4 * 40)
                val original = FloatArray(bytes.size / 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(original)
                check(original.all { it.isFinite() && it in -1.001f..1.001f })
                // Remove only trailing quiet, to reproduce immediate release before VAD's pause.
                val end = minOf(original.size, (original.indexOfLast { kotlin.math.abs(it) > 0.01f } + 801).coerceAtLeast(1))
                for (pauseMs in if (cold) listOf(0) else listOf(0, 600)) {
                    val samples = original.copyOf(end) + FloatArray(pauseMs * 16)
                    var offset = 0
                    var firstTextMs = -1L
                    var releaseAt = 0L
                    val started = SystemClock.elapsedRealtime()
                    val active = engine ?: SpeechEngine(context.assets)
                    var doneAt = 0L
                    val text = try { active.transcribe({
                        if (offset == samples.size) {
                            releaseAt = SystemClock.elapsedRealtime()
                            null
                        } else {
                            val until = minOf(offset + 320, samples.size)
                            val wait = started + until / 16 - SystemClock.elapsedRealtime()
                            if (wait > 0) SystemClock.sleep(wait)
                            samples.copyOfRange(offset, until).also { offset = until }
                        }
                    }, { if (firstTextMs < 0) firstTextMs = SystemClock.elapsedRealtime() - started }, { false }, { offset < samples.size })
                        .also { doneAt = SystemClock.elapsedRealtime() }
                    } finally { if (cold) active.release() }
                    rows.put(JSONObject().put("id", id).put("pauseMs", pauseMs).put("text", text)
                        .put("firstTextMs", firstTextMs).put("releaseMs", doneAt - releaseAt))
                    folder.resolve("$label.json").writeText(JSONObject().put("rows", rows).toString(2))
                }
            }
        } finally { engine?.release() }
        check(rows.length() == ids.size * if (cold) 1 else 2)
        return "VOICE_PIPELINE_COMPARISON_DONE model=$label cases=${rows.length()}"
    }
}
