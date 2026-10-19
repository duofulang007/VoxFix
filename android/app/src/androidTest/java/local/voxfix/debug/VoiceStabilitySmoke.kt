package local.voxfix.debug

import android.app.Instrumentation
import android.os.Debug
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fixed audio and isolated editors only; exercises repeated reuse and model teardown. */
internal object VoiceStabilitySmoke {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val folder = context.cacheDir.resolve("voice-quality-20261018")
        val output = folder.resolve("stability.json")
        val rounds = JSONArray()
        val report = JSONObject().put("status", "running").put("rounds", rounds)
        val started = SystemClock.elapsedRealtime()
        fun save(stage: String) {
            report.put("stage", stage).put("elapsedMs", SystemClock.elapsedRealtime() - started)
            output.writeText(report.toString(2))
        }
        val bytes = folder.resolve("fixtures/plain-zh-0.f32").readBytes()
        check(bytes.isNotEmpty() && bytes.size % 4 == 0 && bytes.size <= 16000 * 4 * 30)
        val samples = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
        check(samples.all { it.isFinite() && it in -1f..1f })
        val reference = mutableMapOf<String, String>()
        fun normalized(text: String) = text.filter { it.isLetterOrDigit() }.lowercase()
        try {
            repeat(6) { round ->
                save("round-${round + 1}-editors")
                repeat(100) { VoiceInteractionAuditSmoke.regression(instrumentation) }
                val engine = SpeechEngine(context.assets)
                try {
                    repeat(20) { iteration ->
                        var offset = 0
                        var cancelled = false
                        val cut = listOf(0, 1, 8, 20, 80)[iteration % 5] * 320
                        check(engine.transcribe({
                            val end = minOf(offset + 320, samples.size)
                            if (offset >= cut) cancelled = true
                            samples.copyOfRange(offset, end).also { offset = end }
                        }, {}, { cancelled }).isEmpty()) { "Cancelled session returned text" }
                        var silence = 0
                        check(engine.transcribe({ if (silence++ < 15) FloatArray(3200) else null }, {}).isEmpty()) {
                            "Silence retained text from the cancelled session"
                        }
                    }
                } finally { engine.release(); engine.release() }
                save("round-${round + 1}-paced-audio")
                VoicePipelineComparisonSmoke.run(context, "pipeline-fix")
                val rows = JSONObject(folder.resolve("pipeline-fix.json").readText()).getJSONArray("rows")
                check(rows.length() == 7)
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val text = row.getString("refinedText").ifEmpty { row.getString("text") }
                    check(text.isNotBlank()) { "Missing final text: ${row.getString("id")}" }
                    val id = row.getString("id")
                    val value = normalized(text)
                    val previous = reference.putIfAbsent(id, value)
                    check(previous == null || previous == value) { "Repeated final content changed: $id" }
                }
                rounds.put(JSONObject().put("round", round + 1).put("rows", rows)
                    .put("cancelled", 20).put("silence", 20).put("editorGroups", 100)
                    .put("pssReleasedKb", Debug.getPss())
                    .put("javaThreads", Thread.getAllStackTraces().keys.count { it.isAlive })
                    .put("openFds", java.io.File("/proc/self/fd").list()?.size ?: -1))
                save("round-${round + 1}-done")
            }
            save("synchronous-tail")
            VoicePipelineComparisonSmoke.run(context, "pipeline-current")
            report.put("synchronous", JSONObject(folder.resolve("pipeline-current.json").readText()).getJSONArray("rows"))
            save("idle-release")
            folder.resolve("fixtures/plain-zh-0.f32").copyTo(context.cacheDir.resolve("voice-audit.f32"), overwrite = true)
            report.put("idle", JSONObject(VoiceMemorySmoke.run(instrumentation)))
            val first = rounds.getJSONObject(0).getInt("pssReleasedKb")
            val last = rounds.getJSONObject(5).getInt("pssReleasedKb")
            check(last <= first + 64 * 1024) { "Released PSS grew by more than 64 MiB" }
            report.put("status", "pass").put("utterances", 64).put("cancelled", 120)
                .put("silence", 120).put("editorGroups", 600)
            save("complete")
            return "VOICE_STABILITY_PASS utterances=64 cancelled=120 silence=120 editorGroups=600 rounds=6 idle-release=pass"
        } catch (error: Throwable) {
            report.put("status", "fail").put("error", error.toString())
            save("failed")
            throw error
        }
    }
}
