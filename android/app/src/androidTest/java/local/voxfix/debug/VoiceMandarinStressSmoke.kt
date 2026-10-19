package local.voxfix.debug

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic fixed audio, paced like capture; never opens the microphone or personal lexicon. */
internal object VoiceMandarinStressSmoke {
    fun punctuation(context: Context): String {
        val punctuator = Punctuator(context.assets)
        val rows = JSONArray()
        try {
            punctuator.start()
            for (text in listOf("长度是3.5厘米", "这件衣服128元5角", "长度是三点五厘米")) {
                rows.put(JSONObject().put("input", text).put("output", punctuator.add(text, 5000)))
            }
            context.cacheDir.resolve("mandarin-stress-20261018/punctuation.json").writeText(rows.toString(2))
            val engine = SpeechEngine(context.assets)
            val isolated = JSONArray()
            try {
                val recognizer = engine.secondPass()
                for (id in listOf("new-number-1", "new-number-5", "new-short-0-quiet",
                    "new-short-8-quiet", "new-negation-0-quiet", "new-short-2-fast", "new-short-6-fast")) {
                    val bytes = context.cacheDir.resolve("mandarin-stress-20261018/$id.f32").readBytes()
                    val samples = FloatArray(bytes.size / 4)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(samples, 16000)
                        recognizer.decode(stream)
                        isolated.put(JSONObject().put("id", id).put("fullAudioText", recognizer.getResult(stream).text))
                    } finally { stream.release() }
                }
                context.cacheDir.resolve("mandarin-stress-20261018/isolation.json").writeText(isolated.toString(2))
            } finally { engine.release() }
            return "MANDARIN_PUNCTUATION_DONE cases=${rows.length()}"
        } finally { punctuator.release() }
    }

    fun run(context: Context): String {
        val folder = context.cacheDir.resolve("mandarin-stress-20261018")
        val cases = JSONArray(folder.resolve("cases.json").readText())
        val rows = JSONArray()
        val report = JSONObject().put("status", "running").put("rows", rows)
        val output = folder.resolve("results.json")
        var engine: SpeechEngine? = null
        try {
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val id = case.getString("id")
                check(id.matches(Regex("[a-z0-9-]+")))
                val bytes = folder.resolve("$id.f32").readBytes()
                check(bytes.isNotEmpty() && bytes.size % 4 == 0 && bytes.size <= 16000 * 4 * 90)
                val samples = FloatArray(bytes.size / 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
                check(samples.all { it.isFinite() && it in -1f..1f })
                val cold = case.optBoolean("cold")
                if (cold) { engine?.release(); engine = null }
                val active = engine ?: SpeechEngine(context.assets).also { engine = it }
                if (!cold) active.secondPass()
                val async = !case.optBoolean("sync")
                var offset = 0
                var releaseAt = 0L
                var live = ""
                var firstMs = -1L
                var refined = ""
                var refineMs = -1L
                val ready = CountDownLatch(1)
                val started = SystemClock.elapsedRealtime()
                val callback: ((String) -> Unit)? = if (async) { value ->
                    refined = value
                    refineMs = SystemClock.elapsedRealtime() - releaseAt
                    ready.countDown()
                } else null
                val text = active.transcribe({
                    if (offset == samples.size) {
                        releaseAt = SystemClock.elapsedRealtime()
                        null
                    } else {
                        val end = minOf(offset + 320, samples.size)
                        val wait = started + end / 16 - SystemClock.elapsedRealtime()
                        if (wait > 0) SystemClock.sleep(wait)
                        samples.copyOfRange(offset, end).also { offset = end }
                    }
                }, {
                    if (firstMs < 0) firstMs = SystemClock.elapsedRealtime() - started
                    if (releaseAt == 0L) live = it
                }, { false }, { offset < samples.size }, callback)
                val releaseMs = SystemClock.elapsedRealtime() - releaseAt
                if (async) ready.await(650, TimeUnit.MILLISECONDS)
                // Same 420 ms acceptance window as ordinary input, independent of the observer wait.
                val accepted = refined.isNotEmpty() && refineMs in 0..420
                rows.put(JSONObject().put("id", id).put("text", text).put("liveText", live)
                    .put("refinedText", refined).put("refineMs", refineMs)
                    .put("finalText", if (accepted) refined else text)
                    .put("releaseMs", releaseMs).put("firstMs", firstMs)
                    .put("pssKb", Debug.getPss()))
                output.writeText(report.toString(2))
                if (cold) { active.release(); engine = null }
            }
            check(rows.length() == cases.length())
            report.put("status", "complete")
            output.writeText(report.toString(2))
            return "VOICE_MANDARIN_STRESS_DONE cases=${rows.length()}"
        } catch (error: Throwable) {
            report.put("status", "fail").put("error", error.toString())
            output.writeText(report.toString(2))
            throw error
        } finally { engine?.release() }
    }
}
