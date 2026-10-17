package local.voxfix.debug

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray

internal object PinyinSmoke {
    fun run(context: Context): String {
        val root = context.cacheDir.resolve("pinyin-smoke-${System.nanoTime()}")
        val seed = root.resolve("pinyin-personal").apply { check(mkdirs()) }
        seed.resolve("voxfix_personal.dict.yaml").writeText("---\nname: voxfix_personal\nversion: '1'\nsort: by_weight\nuse_preset_vocabulary: false\n...\n锦\tjin\t1\n葵\tkui\t1\n试\tshi\t1\n词\tci\t1\n锦葵试词\tjin kui shi ci\t1\n")
        seed.resolve("personal-english.txt").writeText("sample\n")
        val database = "${root.name}.db"
        val lexicon = PersonalLexicon(context, database)
        var engine = PinyinEngine(context.assets, root)
        lexicon.migrateNative(root) { it }; lexicon.migrateWeb(JSONArray())
        fun words(raw: String): List<String> {
            val rows = JSONObject(engine.candidates(raw, 2000)).getJSONArray("words")
            return List(rows.length()) { rows.getString(it) }
        }
        try {
            check(words("nihao").first() == "你好")
            check(words("women").first() == "我们")
            check(words("c").all { it.codePointCount(0, it.length) == 1 })
            check(words("woxianghebeikafei").first() == "我想喝杯咖啡")
            check(lexicon.lookup("jinkuishici").toString().contains("锦葵试词")) { "Personal seed dictionary must be available in the unified store" }
            words("nihaoq")
            var committed = ""
            val partial = JSONObject(engine.choose("nihaoq", "你好") { committed = it; true })
            check(partial.optBoolean("committed")) { "Partial choose failed: $partial" }
            check(committed == "你好" && partial.getString("remaining") == "q")
            val original = words("ni")
            check("泥" in original)
            check(engine.choose("ni", "泥") { false } == "{}")
            check(words("ni") == original) { "Declined commit must not change ranking" }
            repeat(3) {
                words("ni")
                val selected = JSONObject(engine.choose("ni", "泥") { true })
                check(selected.optBoolean("committed"))
                lexicon.learn(JSONArray("""[{"key":"p:ni","word":"泥","code":"ni","hits":1}]"""))
            }
            check(lexicon.lookup("ni").getJSONObject(0).getInt("hits") == 3)
            check(words("ni").first() == original.first()) { "The base engine must not keep a second learning store" }
            words("nihao")
            var staleCommitted = false
            check(engine.choose("ni", "泥") { staleCommitted = true; true } == "{}")
            check(!staleCommitted)
            engine.close()
            engine = PinyinEngine(context.assets, root)
            check(words("ni").first() == original.first())
            check(lexicon.lookup("ni").getJSONObject(0).getInt("hits") == 3) { "Unified learning must survive engine restart" }
            check(lexicon.cleanupLegacy(root))
            return "ARM64_PINYIN_PREVIEW_UNIFIED_LEXICON_PASS"
        } finally {
            engine.close(); lexicon.close(); context.deleteDatabase(database)
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively()
        }
    }
}
