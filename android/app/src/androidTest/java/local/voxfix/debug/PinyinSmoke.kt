package local.voxfix.debug

import android.content.Context
import org.json.JSONObject

internal object PinyinSmoke {
    fun run(context: Context): String {
        val root = context.cacheDir.resolve("pinyin-smoke-${System.nanoTime()}")
        val seed = root.resolve("pinyin-personal").apply { check(mkdirs()) }
        seed.resolve("voxfix_personal.dict.yaml").writeText("---\nname: voxfix_personal\nversion: '1'\nsort: by_weight\nuse_preset_vocabulary: false\n...\n锦\tjin\t1\n葵\tkui\t1\n试\tshi\t1\n词\tci\t1\n锦葵试词\tjin kui shi ci\t1\n")
        var engine = PinyinEngine(context.assets, root)
        fun words(raw: String): List<String> {
            val rows = JSONObject(engine.candidates(raw, 2000)).getJSONArray("words")
            return List(rows.length()) { rows.getString(it) }
        }
        try {
            check(words("nihao").first() == "你好")
            check(words("women").first() == "我们")
            check(words("c").all { it.codePointCount(0, it.length) == 1 })
            check(words("woxianghebeikafei").first() == "我想喝杯咖啡")
            check("锦葵试词" in words("jinkuishici")) { "Personal seed dictionary must be available" }
            words("nihaoq")
            var committed = ""
            val partial = JSONObject(engine.choose("nihaoq", "你好", false) { committed = it; true })
            check(partial.optBoolean("committed")) { "Partial choose failed: $partial" }
            check(committed == "你好" && partial.getString("remaining") == "q")
            val original = words("ni")
            check("泥" in original)
            check(engine.choose("ni", "泥", true) { false } == "{}")
            check(words("ni") == original) { "Declined commit must not change ranking" }
            repeat(3) {
                words("ni")
                val selected = JSONObject(engine.choose("ni", "泥", true) { true })
                check(selected.optBoolean("committed")) {
                    val session = engine.javaClass.getDeclaredField("session").apply { isAccessible = true }.getLong(engine)
                    val entries = engine.javaClass.getDeclaredField("candidates").apply { isAccessible = true }.get(engine) as Map<*, *>
                    val preview = engine.javaClass.getDeclaredMethod("nativePreview", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
                    val rows = preview.invoke(engine, session, entries["泥"]) as Array<*>
                    "Learning choose $it failed: ${rows.contentToString()} / $selected"
                }
            }
            check(words("ni").first() == "泥") { "Successful choices must be learned" }
            words("nihao")
            var staleCommitted = false
            check(engine.choose("ni", "泥", true) { staleCommitted = true; true } == "{}")
            check(!staleCommitted)
            engine.close()
            engine = PinyinEngine(context.assets, root)
            check(words("ni").first() == "泥") { "Learning must survive engine restart" }
            engine.clearLearning()
            check(words("ni").first() == original.first()) { "Clear must reset only user learning" }
            return "ARM64_PINYIN_PREVIEW_LEARNING_PASS"
        } finally { engine.close() }
    }
}
