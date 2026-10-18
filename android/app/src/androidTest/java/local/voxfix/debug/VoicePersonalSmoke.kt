package local.voxfix.debug

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Only isolated databases and synthetic PCM; never records or edits a real application. */
internal object VoicePersonalSmoke {
    fun growth(context: Context): String {
        val folder = context.cacheDir.resolve("voice-personal-20261018")
        val before = JSONArray(folder.resolve("device.json").readText())
        val name = "voice-growth-smoke-${System.nanoTime()}.db"
        val learned = JSONArray()
        try {
            PersonalLexicon(context, name).use { store ->
                for (i in 0 until before.length()) {
                    val row = before.getJSONObject(i)
                    if (row.getString("word").isEmpty()) continue
                    val english = row.getString("word").matches(Regex("[A-Za-z]{2,16}"))
                    val keep: (Char) -> Boolean = { if (english) it.isLetter() || it == ' ' else it in '\u3400'..'\u9fff' }
                    val original = row.getString("baseline").filter(keep)
                    val target = row.getString("truth").filter(keep)
                    repeat(3) {
                        val tracker = VoiceCorrectionTracker()
                        tracker.arm(original, 0, 0, original, 1, 0)
                        val rules = tracker.observeAll(target, 0, 1, 100, false, VoiceLanguage::phonetic, VoiceLanguage::wordRange, true)
                        store.learnVoice(rules)
                        if (it == 0) for (rule in rules) learned.put(JSONObject().put("id", row.getString("id")).put("heard", rule.heard).put("word", rule.word))
                    }
                }
            }
            val dictionary = PersonalLexicon(context, name).use { it.voiceSnapshot() }
            check(dictionary.corrections.isNotEmpty())
            check(dictionary.words.none { it.manual })
            val engine = SpeechEngine(context.assets, dictionary)
            val result = JSONArray()
            try {
                for (i in 0 until before.length()) {
                    val row = before.getJSONObject(i); val id = row.getString("id")
                    check(id.matches(Regex("[a-z0-9-]+")))
                    val bytes = folder.resolve("$id.f32").readBytes()
                    val samples = FloatArray(bytes.size / 4)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
                    var offset = 0
                    val started = SystemClock.elapsedRealtime()
                    val text = engine.transcribe({
                        if (offset >= samples.size) null else {
                            val end = minOf(offset + 320, samples.size)
                            val wait = started + end * 1000L / 16000 - SystemClock.elapsedRealtime()
                            if (wait > 0) SystemClock.sleep(wait)
                            samples.copyOfRange(offset, end).also { offset = end }
                        }
                    }, {}, { false }, { offset < samples.size })
                    result.put(JSONObject().put("id", id).put("truth", row.getString("truth")).put("baseline", row.getString("baseline")).put("learned", text))
                    folder.resolve("growth.json").writeText(JSONObject().put("corrections", learned).put("runs", result).toString(2))
                }
            } finally { engine.release() }
            val normalized: (String) -> String = { text -> text.filter { it.isLetterOrDigit() } }
            for (i in 0 until result.length()) {
                val row = result.getJSONObject(i)
                if (row.getString("id").startsWith("negative-")) check(normalized(row.getString("learned")) == normalized(row.getString("baseline")))
            }
            check((0 until result.length()).any { val row = result.getJSONObject(it); row.getString("id").startsWith("name-") && normalized(row.getString("learned")) == normalized(row.getString("truth")) })
            return "VOICE_GROWTH_PASS corrections=${dictionary.corrections.size} cases=${result.length()}; re-opened isolated dictionary affects real pipeline; negative controls unchanged"
        } finally { context.deleteDatabase(name) }
    }

    fun run(context: Context, paths: Int = 0): String {
        check(paths in 0..4)
        val name = "voice-personal-smoke-${System.nanoTime()}.db"
        val restored = "$name-restore"
        var snapshot = ""
        try {
            // A genuine v1 database must upgrade without deleting any old entries.
            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("CREATE TABLE entries (key TEXT NOT NULL,word TEXT NOT NULL,code TEXT,hits INTEGER NOT NULL,nine TEXT,pinned INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(key,word))")
                db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY,value TEXT NOT NULL)")
                db.execSQL("INSERT INTO entries VALUES ('p:mingtian','明天','ming tian',7,'64648426',0)")
                db.version = 1
            }
            PersonalLexicon(context, name).use { store ->
                check(store.count() == 1L)
                store.addVoiceWord("玄戒芯片", "xuan jie xin pian")
                store.addVoiceWord("张玥", "zhang yue")
                store.addVoiceWord("VoxFix")
                val manual = store.voiceSnapshot()
                for (text in listOf("张悦", "请联系张悦确认时间。", "明天我和张悦一起开会。")) {
                    check(manual.correct(text, VoiceLanguage::phonetic, VoiceLanguage::boundaries) == text.replace("张悦", "张玥")) { "Manual word did not win: $text; boundaries=${VoiceLanguage.boundaries(text)}" }
                }
                for (text in listOf("这张月票下周到期。", "紧张悦耳", "联系李悦确认。")) {
                    check(manual.correct(text, VoiceLanguage::phonetic, VoiceLanguage::boundaries) == text) { "Manual word changed unrelated text: $text" }
                }
                check(store.lookup("zhangyue").toString().contains("张玥"))
                check(store.count() == 3L)
                val tracker = VoiceCorrectionTracker()
                tracker.arm("请联系张月确认时间。", 0, 0, "请联系张月确认时间。", 1, 0)
                val learned = tracker.observe("请联系张玥确认时间。", 0, 1, 20, false, VoiceLanguage::phonetic, VoiceLanguage::wordRange)!!
                check(learned.word.contains("玥"))
                store.learnVoice(learned); store.learnVoice(learned)
                check(store.voiceSnapshot().correct("请联系张月确认时间。") == "请联系张玥确认时间。")
                check(store.voiceSnapshot().correct("这张月票下周到期。") == "这张月票下周到期。")
                check(VoiceLanguage.phonetic("上学") != VoiceLanguage.phonetic("上班"))
                val rules = store.voiceSnapshot().corrections
                check(rules.single().hits == 2)
                store.addVoiceWord("Excel")
                for (heard in listOf("AXCEL", "EX CELL", "艾克塞尔", "Xia", "X要表", "医校")) {
                    val english = VoiceCorrectionTracker()
                    val original = "请用${heard}打开表格。"
                    english.arm(original, 0, 0, original, 1, 0, setOf("Excel"))
                    val rule = english.observe("请用Excel打开表格。", 0, 1, 20, false, VoiceLanguage::phonetic, VoiceLanguage::wordRange)!!
                    store.learnVoice(rule)
                    check(store.voiceSnapshot().correct(original, VoiceLanguage::phonetic, VoiceLanguage::boundaries) == "请用Excel打开表格。")
                }
                snapshot = store.exportBackup()
                store.importBackup(snapshot); check(store.exportBackup() == snapshot)
                val invalid = JSONObject(snapshot).apply { getJSONObject("voice").getJSONArray("words").getJSONObject(0).put("word", "危险/规则") }.toString()
                check(runCatching { store.importBackup(invalid) }.isFailure)
                check(store.exportBackup() == snapshot)
            }
            PersonalLexicon(context, name).use { check(it.exportBackup() == snapshot) }
            PersonalLexicon(context, restored).use { store ->
                store.importBackup(snapshot); check(store.exportBackup() == snapshot)
                store.forgetVoiceWord("张玥")
                check(store.voiceSnapshot().correct("请联系张月确认时间。") == "请联系张月确认时间。")
                check(store.lookup("zhangyue").toString().contains("张玥"))
                store.importBackup("{\"format\":\"voxfix-personal-lexicon\",\"version\":1,\"entries\":[],\"legacy\":{}}")
                check(store.voiceWords().any { it.word == "玄戒芯片" })
            }
            val folder = context.cacheDir.resolve("voice-personal-20261018")
            if (!folder.resolve("cases.json").isFile) return "VOICE_PERSONAL_STORE_PASS: v1 upgrade, shared pinyin, ICU correction capture, persistence, backup, rollback, per-word forgetting"
            val cases = JSONArray(folder.resolve("cases.json").readText())
            val words = (0 until cases.length()).map { cases.getJSONObject(it).getString("word") }.filter { it.isNotEmpty() }.distinct().map { VoiceWord(it, manual = true) }
            val result = JSONArray()
            val base = SpeechEngine(context.assets)
            val personal = SpeechEngine(context.assets, VoiceLexicon(words))
            if (paths > 0) {
                val field = personal.javaClass.getDeclaredField("online").apply { isAccessible = true }
                val original = field.get(personal) as com.k2fsa.sherpa.onnx.OnlineRecognizer
                val replacement = com.k2fsa.sherpa.onnx.OnlineRecognizer(context.assets, original.config.copy(maxActivePaths = paths))
                field.set(personal, replacement); original.release()
            }
            try {
                for (i in 0 until cases.length()) {
                    val case = cases.getJSONObject(i); val id = case.getString("id")
                    check(id.matches(Regex("[a-z0-9-]+")))
                    val bytes = folder.resolve("$id.f32").readBytes()
                    check(bytes.size in 4..(16000 * 4 * 30) && bytes.size % 4 == 0)
                    val samples = FloatArray(bytes.size / 4)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
                    check(samples.all { it.isFinite() && it in -1f..1f })
                    val row = JSONObject().put("id", id).put("truth", case.getString("text")).put("word", case.getString("word"))
                    for ((label, engine) in listOf("baseline" to base, "personal" to personal)) {
                        var offset = 0
                        val started = SystemClock.elapsedRealtime()
                        var first = -1L
                        val text = engine.transcribe({
                            if (offset >= samples.size) null else {
                                val end = minOf(offset + 320, samples.size)
                                val wait = started + end * 1000L / 16000 - SystemClock.elapsedRealtime()
                                if (wait > 0) SystemClock.sleep(wait)
                                samples.copyOfRange(offset, end).also { offset = end }
                            }
                        }, { if (first < 0) first = SystemClock.elapsedRealtime() - started }, { false }, { offset < samples.size })
                        row.put(label, text).put("${label}FirstMs", first).put("${label}TailMs", SystemClock.elapsedRealtime() - started - samples.size / 16)
                    }
                    result.put(row); folder.resolve("device.json").writeText(result.toString(2))
                }
            } finally { base.release(); personal.release() }
            return "VOICE_PERSONAL_PASS cases=${cases.length()} file=cache/voice-personal-20261018/device.json; isolated database/ICU checks passed"
        } finally { context.deleteDatabase(name); context.deleteDatabase(restored) }
    }
}
