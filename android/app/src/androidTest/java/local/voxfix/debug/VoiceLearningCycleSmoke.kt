package local.voxfix.debug

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Re-decode after every learning round: never train repeatedly on an obsolete baseline. */
internal object VoiceLearningCycleSmoke {
    fun run(context: Context): String {
        val folder = context.cacheDir.resolve("voice-personal-20261018")
        val cases = JSONArray(folder.resolve("cases.json").readText())
        val baseline = JSONArray(folder.resolve("device.json").readText())
        val database = "voice-cycle-${System.nanoTime()}.db"
        val rounds = JSONArray()
        fun normalize(text: String) = text.filter { it.isLetterOrDigit() }.lowercase()
        fun decode(engine: SpeechEngine, id: String): String {
            check(id.matches(Regex("[a-z0-9-]+")))
            val bytes = folder.resolve("$id.f32").readBytes()
            check(bytes.size in 4..(16000 * 4 * 30) && bytes.size % 4 == 0)
            val samples = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
            check(samples.all { it.isFinite() && it in -1f..1f })
            var offset = 0
            val started = SystemClock.elapsedRealtime()
            return engine.transcribe({
                if (offset == samples.size) null else {
                    val end = minOf(offset + 320, samples.size)
                    val wait = started + end * 1000L / 16000 - SystemClock.elapsedRealtime()
                    if (wait > 0) SystemClock.sleep(wait)
                    samples.copyOfRange(offset, end).also { offset = end }
                }
            }, {}, { false }, { offset < samples.size })
        }
        try {
            repeat(3) { round ->
                val dictionary = PersonalLexicon(context, database).use { it.voiceSnapshot() }
                check(dictionary.words.none { it.manual })
                val engine = SpeechEngine(context.assets, dictionary)
                val rows = JSONArray()
                try {
                    for (i in 0 until cases.length()) {
                        val row = cases.getJSONObject(i)
                        if (row.getString("word").isEmpty()) continue
                        val text = decode(engine, row.getString("id"))
                        val old = text.filter { it.isLetterOrDigit() || it == ' ' }
                        val truth = row.getString("text").filter { it.isLetterOrDigit() || it == ' ' }
                        val tracker = VoiceCorrectionTracker()
                        tracker.arm(old, 0, 0, old, 1, 0)
                        val rules = tracker.observeAll(truth, 0, 1, 1, false, VoiceLanguage::phonetic, VoiceLanguage::wordRange, true)
                        PersonalLexicon(context, database).use { it.learnVoice(rules) }
                        rows.put(JSONObject().put("id", row.getString("id")).put("recognized", text).put("savedRules", rules.size))
                    }
                } finally { engine.release() }
                rounds.put(JSONObject().put("round", round + 1).put("runs", rows))
                folder.resolve("cycle.json").writeText(JSONObject().put("rounds", rounds).toString(2))
            }
            val dictionary = PersonalLexicon(context, database).use { it.voiceSnapshot() }
            val engine = SpeechEngine(context.assets, dictionary)
            val result = JSONArray()
            var correct = 0; var total = 0; var negative = 0
            try {
                for (i in 0 until cases.length()) {
                    val row = cases.getJSONObject(i); val id = row.getString("id")
                    val text = decode(engine, id)
                    if (row.getString("word").isNotEmpty()) { total++; if (normalize(text) == normalize(row.getString("text"))) correct++ }
                    else {
                        val original = (0 until baseline.length()).map { baseline.getJSONObject(it) }.single { it.getString("id") == id }
                        check(normalize(text) == normalize(original.getString("baseline"))) { "Negative changed: $id -> $text" }
                        negative++
                    }
                    result.put(JSONObject().put("id", id).put("recognized", text).put("truth", row.getString("text")))
                }
            } finally { engine.release() }
            folder.resolve("cycle.json").writeText(JSONObject().put("rounds", rounds).put("final", result).put("correct", correct).put("total", total).put("negative", negative).toString(2))
            check(correct == total) { "Dynamic learning retained $correct/$total complete target sentences" }
            return "VOICE_CYCLE_PASS rounds=3 targets=$correct/$total negative=$negative; new ASR output captured each round, no manual dictionary"
        } finally { context.deleteDatabase(database) }
    }
}
