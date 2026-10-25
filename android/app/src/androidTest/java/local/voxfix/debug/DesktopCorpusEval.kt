package local.voxfix.debug

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Real-device accuracy and latency run over the desktop ASCEND corpus.
 *
 * Same manifest, same PCM, same 100 ms packet delivery as
 * `VoxFix Desktop/tools/evaluate_sensevoice.py`, so the phone and the desktop can be
 * compared row by row. Text scoring happens on the PC with the desktop's own
 * `p0_score.units/distance/exact`; this harness only records what the engine produced.
 *
 * mode=accuracy: packets are fed as fast as the engine consumes them (desktop parity,
 * not a latency measurement). mode=paced: packets are released on a 16 kHz wall clock so
 * first-text and release latency mean what they mean for a real microphone.
 */
internal object DesktopCorpusEval {
    private const val TAG = "VoxFixEval"
    private const val PACKET = 1600          // 100 ms, the desktop evaluator's packet
    private const val FOLDER = "ascend-eval-20261025"

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun decode(bytes: ByteArray, samples: Int): FloatArray {
        val shorts = ShortArray(samples)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return FloatArray(samples) { shorts[it] / 32768f }
    }

    fun run(context: Context, mode: String, offset: Int, limit: Int): String {
        check(mode == "accuracy" || mode == "paced") { "Unknown mode $mode" }
        val folder = context.getExternalFilesDir(null)!!.resolve(FOLDER)
        val plan = JSONObject(folder.resolve("plan.json").readText())
        val rows = plan.getJSONArray("rows")
        val first = offset.coerceAtLeast(0)
        val last = if (limit <= 0) rows.length() else minOf(rows.length(), first + limit)
        check(first < last) { "Empty selection" }

        val report = JSONObject().put("mode", mode).put("planCount", rows.length())
            .put("offset", first).put("count", last - first)
            .put("modelSource", "installed APK")
            .put("delivery", if (mode == "paced") "16 kHz wall clock" else "unpaced desktop parity")
        val results = JSONArray()
        report.put("rows", results)
        val output = folder.resolve("out-$mode-$first-${last - first}.json")

        val sampling = AtomicBoolean(true)
        val peak = AtomicLong(Debug.getPss())
        val sampler = Thread({
            while (sampling.get()) {
                peak.accumulateAndGet(Debug.getPss()) { a, b -> maxOf(a, b) }
                SystemClock.sleep(200)
            }
        }, "ascend-eval-pss")

        val loadedAt = SystemClock.elapsedRealtime()
        DesktopSpeechEngine(context.assets).use { engine ->
            report.put("loadMs", SystemClock.elapsedRealtime() - loadedAt)
            sampler.start()
            try {
                for (index in first until last) {
                    val row = rows.getJSONObject(index)
                    val identity = row.getString("id")
                    val samples = row.getInt("samples")
                    val bytes = folder.resolve("audio/$identity.pcm").readBytes()
                    if (bytes.size != samples * 2 || digest(bytes) != row.getString("pcmSha256")) {
                        throw IllegalStateException("Corrupt fixture $identity")
                    }
                    val audio = decode(bytes, samples)
                    val cpu = Process.getElapsedCpuTime()
                    val start = SystemClock.elapsedRealtime()
                    val firstText = AtomicLong(-1)
                    var release = 0L
                    var error: String? = null
                    var text = ""
                    try {
                        var cursor = 0
                        text = engine.transcribe({
                            if (cursor == audio.size) { release = SystemClock.elapsedRealtime(); null }
                            else {
                                val end = minOf(cursor + PACKET, audio.size)
                                if (mode == "paced") {
                                    val wait = start + end / 16 - SystemClock.elapsedRealtime()
                                    if (wait > 0) SystemClock.sleep(wait)
                                }
                                audio.copyOfRange(cursor, end).also { cursor = end }
                            }
                        }, { if (it.isNotEmpty()) firstText.compareAndSet(-1, SystemClock.elapsedRealtime() - start) })
                    } catch (failure: Throwable) {
                        // Record, do not abort: a rejected clip (unsupported script, decode failure)
                        // is a result about the phone build, not a reason to lose the other rows.
                        error = failure.toString()
                        text = (failure as? DesktopVoiceFailure)?.retained ?: ""
                    }
                    results.put(JSONObject()
                        .put("id", identity)
                        .put("category", row.getString("category"))
                        .put("expected", row.getString("expected"))
                        .put("seconds", row.getDouble("seconds"))
                        .put("finalText", text)
                        .put("firstTextMs", firstText.get())
                        .put("releaseMs", if (release == 0L) -1L else SystemClock.elapsedRealtime() - release)
                        .put("wallMs", SystemClock.elapsedRealtime() - start)
                        .put("cpuMs", Process.getElapsedCpuTime() - cpu)
                        .put("error", error))
                    report.put("peakPssKiB", peak.get())
                    if ((index - first + 1) % 50 == 0 || index == last - 1) {
                        output.writeText(report.toString())
                        Log.i(TAG, "PROGRESS mode=$mode done=${index - first + 1}/${last - first} id=$identity")
                    }
                }
            } finally {
                sampling.set(false); sampler.join()
            }
        }
        val failures = (0 until results.length()).count { results.getJSONObject(it).optString("error").isNotEmpty() }
        report.put("failures", failures)
        output.writeText(report.toString())
        return "ASCEND_EVAL_DONE mode=$mode cases=${results.length()} failures=$failures peakPssKiB=${peak.get()} output=$output"
    }
}
