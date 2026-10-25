package local.voxfix.debug

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Actual PCM -> Android VAD -> desktop candidate; precomputed frames are assertions, never inputs. */
internal object DesktopVoiceLiveSmoke {
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun samples(folder: File, row: JSONObject): FloatArray {
        val id = row.getString("id")
        check(id.matches(Regex("[a-z0-9-]+")))
        val file = folder.resolve("$id.f32")
        check(digest(file) == row.getString("audioSha256"))
        check(file.length() == row.getInt("samples") * 4L && file.length() <= 16000 * 4 * 90)
        val audio = FloatArray(row.getInt("samples"))
        ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(audio)
        check(audio.all { it.isFinite() && it in -1.001f..1.001f })
        return audio
    }

    private fun frameJson(frame: DesktopVoiceFrame) = JSONObject().put("at", frame.at)
        .put("key", frame.key).put("finals", JSONArray().apply {
            frame.finals.forEach { put(JSONArray().put(it.start).put(it.end)) }
        }).put("draft", frame.draft?.let { JSONArray().put(it.start).put(it.end) } ?: JSONObject.NULL)

    private fun sameFrame(left: JSONObject, right: JSONObject): Boolean =
        listOf("at", "key", "finals", "draft").all { left.get(it).toString() == right.get(it).toString() }

    private fun dictionaryChecks(folder: File) {
        val dictionary = DesktopVoiceDictionary.parse("""{"rules":[{"to":"APP","from":["app","app p"]},
            {"to":"JSON","from":["Jason"],"context":"zh"}],"hotwords":["API"," ",1]}""")
        check(dictionary.correct("app p Jason") == "APP Jason")
        check(dictionary.correct("app p Jason", true) == "APP JSON")
        check(dictionary.hotwords == listOf("API"))
        check(DesktopVoiceDictionary.parse("""{"hotwords":["API"]}""").hotwords == listOf("API"))
        val many = JSONArray().apply { repeat(70) { put("word$it") } }
        check(DesktopVoiceDictionary.parse(JSONObject().put("hotwords", many).toString()).hotwords.size == 64)
        val file = folder.resolve("synthetic-dictionary.json")
        check(!file.exists())
        try {
            file.writeText("broken JSON")
            check(DesktopVoiceDictionary.load(file).correct("Jason") == "Jason")
            file.writeText("""{"rules":[{"to":"JSON","from":["Jason"]}]}""")
            check(DesktopVoiceDictionary.load(file).correct("Jason") == "JSON")
            file.writeText("""{"rules":[{"to":"BAD","from":["Jason"]}]} garbage""")
            check(DesktopVoiceDictionary.load(file).correct("Jason") == "Jason")
            file.writeText("""{'rules':[{'to':'BAD','from':['Jason']}]}""")
            check(DesktopVoiceDictionary.load(file).correct("Jason") == "Jason")
        } finally { if (file.exists()) check(file.delete()) }
    }

    private fun lifecycle(context: Context, folder: File, rows: JSONArray, output: File, packaged: Boolean): String {
        val short = (0 until rows.length()).map(rows::getJSONObject).first { it.getString("id") == "ms-new-short-0" }
        val long = (0 until rows.length()).map(rows::getJSONObject).first { it.getString("id") == "ms-long-2-600" }
        val audio = samples(folder, short)
        val engine = DesktopSpeechEngine(context.assets, if (packaged) null else folder)
        var checks = 0
        try {
            check(engine.transcribe({ error("cancelled supplier must not run") }, { error("late cancelled text") }, { true }).isEmpty()); checks++
            fun replay(): String {
                var offset = 0
                return engine.transcribe({ if (offset == audio.size) null else {
                    val end = minOf(offset + 320, audio.size)
                    audio.copyOfRange(offset, end).also { offset = end }
                } }, {})
            }
            val first = replay()
            check(first.isNotEmpty() && first == replay()); checks++
            val cancellation = AtomicBoolean(false)
            val speech = samples(folder, long)
            var offset = 0
            var callbacks = 0
            val cancelled = engine.transcribe({
                if (offset == speech.size) null else {
                    SystemClock.sleep(1)
                    val end = minOf(offset + 320, speech.size)
                    speech.copyOfRange(offset, end).also { offset = end }
                }
            }, {
                check(!cancellation.get()) { "late callback after cancellation" }
                check(runCatching { engine.close() }.exceptionOrNull() is IllegalStateException)
                callbacks++; cancellation.set(true)
            }, cancellation::get)
            check(cancelled.isEmpty() && callbacks == 1); checks++
            check(first == replay()); checks++
            for (bad in listOf(floatArrayOf(Float.NaN), FloatArray(0), FloatArray(16001))) {
                var invalid = false
                try { engine.transcribe({ bad }, {}) }
                catch (error: DesktopVoiceFailure) { invalid = error.retained.isEmpty() }
                check(invalid && first == replay()); checks++
            }
            val word = (0 until rows.length()).map(rows::getJSONObject).first { it.getString("id") == "vq-word-0" }
            val wordAudio = samples(folder, word)
            var wordOffset = 0
            val dictionary = engine.transcribe({
                if (wordOffset == wordAudio.size) null else {
                    val end = minOf(wordOffset + 320, wordAudio.size)
                    wordAudio.copyOfRange(wordOffset, end).also { wordOffset = end }
                }
            }, {})
            check(dictionary.contains("Apple")); checks++
            engine.close(); engine.close()
            var closed = false
            try { replay() } catch (_: IllegalStateException) { closed = true }
            check(closed); checks++
        } finally { engine.close() }
        output.writeText(JSONObject().put("mode", "lifecycle").put("checks", checks).toString(2))
        return "DESKTOP_LIVE_LIFECYCLE_PASS checks=$checks"
    }

    fun run(context: Context, mode: String): String {
        check(mode in setOf("boundaries", "paced", "lifecycle", "rules", "packaged", "installed-lifecycle"))
        val folder = context.getExternalFilesDir(null)!!.resolve("desktop-live-20261025")
        if (mode == "rules") {
            val cases = JSONObject(folder.resolve("rules.json").readText()).getJSONArray("rows")
            for (index in 0 until cases.length()) {
                val row = cases.getJSONObject(index)
                val result: Any = when (row.getString("op")) {
                    "dictionary" -> DesktopVoiceDictionary.parse(JSONObject().put("rules", row.getJSONArray("rules")).toString())
                        .correct(row.getString("text"), row.getBoolean("force"))
                    "punctuation" -> DesktopVoiceRules.punctuate(row.getString("text"), row.getString("result"))
                    "allowed" -> DesktopVoiceRules.allowed(row.getString("text"))
                    "join" -> DesktopVoiceRules.join(row.getString("left"), row.getString("right"))
                    else -> error("Unknown rule case")
                }
                check(result == row.get("expected")) { "desktop rule mismatch index=$index" }
            }
            return "DESKTOP_RULE_PARITY_PASS cases=${cases.length()}"
        }
        val plan = JSONObject(folder.resolve("plan.json").readText())
        val packaged = mode == "packaged" || mode == "installed-lifecycle"
        if (!packaged) {
            check(digest(folder.resolve("model.int8.onnx")) == "a002044fef99b50be903b059c39b012072bc3f131df0ad58e59d548cfde7535d")
            check(digest(folder.resolve("tokens.txt")) == plan.getString("tokensSha256"))
        }
        val rows = plan.getJSONArray("rows")
        check(rows.length() == 36)
        val output = context.cacheDir.resolve("desktop-live-20261025/$mode.json")
        output.parentFile!!.mkdirs()
        dictionaryChecks(output.parentFile!!)
        if (mode == "lifecycle" || mode == "installed-lifecycle") return lifecycle(context, folder, rows, output, packaged)
        val started = SystemClock.elapsedRealtime()
        DesktopSpeechEngine(context.assets, if (packaged) null else folder).use { engine ->
            val report = JSONObject().put("mode", mode).put("loadMs", SystemClock.elapsedRealtime() - started)
                .put("modelSource", if (packaged) "installed APK" else "external fixture")
                .put("planSha256", digest(folder.resolve("plan.json"))).put("scope", "real Android VAD; PCM supplier; no microphone or host input")
            val results = JSONArray()
            val sampling = AtomicBoolean(true)
            val peak = AtomicLong(Debug.getPss())
            val sampler = Thread({ while (sampling.get()) {
                peak.accumulateAndGet(Debug.getPss()) { a, b -> maxOf(a, b) }; SystemClock.sleep(100)
            } }, "desktop-live-pss")
            var totalMismatches = 0
            sampler.start()
            try {
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val audio = samples(folder, row)
                    val frames = JSONArray()
                    engine.observeFrame = { frames.put(frameJson(it)) }
                    val start = SystemClock.elapsedRealtime()
                    val cpu = Process.getElapsedCpuTime()
                    val first = AtomicLong(-1)
                    var releaseAt = 0L
                    var offset = 0
                    val text = engine.transcribe({
                        if (offset == audio.size) { releaseAt = SystemClock.elapsedRealtime(); null }
                        else {
                            val end = minOf(offset + 320, audio.size)
                            if (mode == "paced" || mode == "packaged") {
                                val wait = start + end / 16 - SystemClock.elapsedRealtime()
                                if (wait > 0) SystemClock.sleep(wait)
                            }
                            audio.copyOfRange(offset, end).also { offset = end }
                        }
                    }, { if (it.isNotEmpty()) first.compareAndSet(-1, SystemClock.elapsedRealtime() - start) })
                    val releaseMs = SystemClock.elapsedRealtime() - releaseAt
                    val expected = row.getJSONArray("frames")
                    var mismatches = kotlin.math.abs(frames.length() - expected.length())
                    for (frame in 0 until minOf(frames.length(), expected.length()))
                        if (!sameFrame(frames.getJSONObject(frame), expected.getJSONObject(frame))) mismatches++
                    totalMismatches += mismatches
                    results.put(JSONObject().put("id", row.getString("id")).put("category", row.getString("category"))
                        .put("expected", row.getString("expected")).put("finalText", text).put("firstTextMs", first.get())
                        .put("releaseMs", releaseMs).put("cpuMs", Process.getElapsedCpuTime() - cpu)
                        .put("frameMismatches", mismatches).put("frames", frames))
                    report.put("rows", results).put("peakPssKiB", peak.get()).put("totalFrameMismatches", totalMismatches)
                    output.writeText(report.toString(2))
                }
            } finally { engine.observeFrame = null; sampling.set(false); sampler.join() }
            check(totalMismatches == 0) { "desktop/Android frame mismatch count=$totalMismatches; inspect $output" }
            return "DESKTOP_LIVE_PASS mode=$mode cases=${results.length()} frameMismatches=0 peakPssKiB=${peak.get()}"
        }
    }
}
