package local.voxfix.debug

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Fixed audio through the production capture queue; never opens a microphone or editor. */
internal object VoiceCaptureStressSmoke {
    fun run(context: Context): String {
        val folder = context.cacheDir.resolve("mandarin-stress-20261018")
        val cases = JSONArray(folder.resolve("capture-cases.json").readText())
        val rows = JSONArray()
        val report = JSONObject().put("status", "running").put("rows", rows)
        val output = folder.resolve("capture-results.json")
        try {
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val id = case.getString("id")
                check(id.matches(Regex("[a-z0-9-]+")))
                val bytes = folder.resolve("$id.f32").readBytes()
                check(bytes.isNotEmpty() && bytes.size % 4 == 0 && bytes.size <= 16000 * 4 * 10)
                val samples = FloatArray(bytes.size / 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
                check(samples.all { it.isFinite() && it in -1f..1f })
                val delayMs = case.getLong("delayMs")
                check(delayMs in -1L..2000L)
                var engine: SpeechEngine? = null
                try {
                    // -1 is a warm control; otherwise capture begins before model construction.
                    if (delayMs == -1L) engine = SpeechEngine(context.assets).also { it.secondPass() }
                    val offset = AtomicInteger()
                    val fed = AtomicInteger()
                    val releaseAt = AtomicLong()
                    val stopped = CountDownLatch(1)
                    val ready = CountDownLatch(1)
                    val refined = AtomicReference<Pair<String, Long>>()
                    val decisions = mutableListOf<JSONObject>()
                    val started = SystemClock.elapsedRealtime()
                    PcmCapture(SpeechEngine.CAPTURE_BUFFER_CHUNKS, {
                        val first = offset.get()
                        if (first == samples.size) null else {
                            val end = minOf(first + 320, samples.size)
                            val wait = started + end / 16 - SystemClock.elapsedRealtime()
                            if (wait > 0) SystemClock.sleep(wait)
                            samples.copyOfRange(first, end).also {
                                offset.set(end)
                                if (end == samples.size) releaseAt.set(SystemClock.elapsedRealtime())
                            }
                        }
                    }, { offset.get() < samples.size }, { stopped.countDown() }).use { capture ->
                        if (delayMs > 0) SystemClock.sleep(delayMs)
                        val active = engine ?: SpeechEngine(context.assets).also { engine = it }
                        active.observeRefinement = { raw, second, chosen ->
                            synchronized(decisions) {
                                decisions.add(JSONObject().put("raw", raw).put("candidate", second.text).put("chosen", chosen)
                                    .put("decisionMs", SystemClock.elapsedRealtime() - releaseAt.get()))
                            }
                        }
                        val bufferedAtLoad = offset.get()
                        val text = active.transcribe({
                            capture.next()?.also { chunk ->
                                val first = fed.getAndAdd(chunk.size)
                                check(first + chunk.size <= samples.size)
                                check(chunk.indices.all { chunk[it] == samples[first + it] }) { "Capture changed audio" }
                            }
                        }, {}, { false }, { offset.get() < samples.size }, { value ->
                            refined.set(value to SystemClock.elapsedRealtime())
                            ready.countDown()
                        })
                        val returnedAt = SystemClock.elapsedRealtime()
                        ready.await(650, TimeUnit.MILLISECONDS)
                        check(stopped.await(1, TimeUnit.SECONDS)) { "Capture did not stop" }
                        check(offset.get() == samples.size && fed.get() == samples.size) { "Capture lost samples" }
                        check(releaseAt.get() > 0)
                        val late = refined.get()
                        val accepted = late != null && late.first.isNotBlank() && late.second <= returnedAt + 420
                        rows.put(JSONObject().put("id", case.getString("runId"))
                            .put("text", text).put("finalText", if (accepted) late!!.first else text)
                            .put("refinedText", late?.first ?: "")
                            .put("refineMs", late?.let { it.second - releaseAt.get() } ?: -1)
                            .put("releaseMs", returnedAt - releaseAt.get()).put("delayMs", delayMs)
                            .put("samplesRead", offset.get()).put("samplesFed", fed.get())
                            .put("bufferedAtLoad", bufferedAtLoad)
                            .put("decisions", JSONArray(synchronized(decisions) { decisions.toList() })))
                        output.writeText(report.toString(2))
                    }
                } finally { engine?.release() }
            }
            check(rows.length() == cases.length())
            report.put("status", "complete")
            output.writeText(report.toString(2))
            return "VOICE_CAPTURE_STRESS_DONE cases=${rows.length()}"
        } catch (error: Throwable) {
            report.put("status", "fail").put("error", error.toString())
            output.writeText(report.toString(2))
            throw error
        }
    }
}
