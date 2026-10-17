package local.voxfix.debug

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object PersonalLexiconSmoke {
    fun run(context: Context): String {
        val name = "lexicon-smoke-${System.nanoTime()}.db"
        val root = File(context.cacheDir, name.removeSuffix(".db"))
        try {
            val exported = File(root, "rime-user/lexicon-export").apply { check(mkdirs()) }
            File(exported, "ready").writeText("1")
            File(exported, "voxfix.tsv").writeText("# Rime export\n明天\tming tian\t12\n")
            val personal = File(root, "pinyin-personal").apply { check(mkdirs()) }
            File(personal, "voxfix_personal.dict.yaml").writeText("---\nname: sample\n...\n下午\txia wu\t200000\n")
            File(personal, "personal-english.txt").writeText("sample\n")
            val large = JSONArray()
            for (i in 0..10000) large.put(JSONObject().put("key", "p:cini").put("word", "词${(0x4e00 + i).toChar()}").put("code", "ci ni").put("hits", 2))
            val data = JSONObject().put("format", "voxfix-personal-lexicon").put("version", 1).put("entries", large).toString()
            val snapshot: String
            PersonalLexicon(context, name).use { store ->
                store.migrateNative(root) { it }; store.migrateWeb(JSONArray("""[{"key":"p:ni","word":"你","code":"ni","hits":3}]"""))
                store.migrateWeb(JSONArray("""[{"key":"p:ni","word":"你","code":"ni","hits":100}]"""))
                check(store.lookup("ni").getJSONObject(0).getInt("hits") == 100)
                store.importBackup(data); check(store.count() == 10004L)
                val before = store.exportBackup(); store.importBackup(data); check(store.exportBackup() == before)
                check(store.lookup("cini").length() == 10001 && store.lookup("cini").getJSONObject(0).getString("word") == "词一")
                check(store.lookup("64648426").toString().contains("明天"))
                store.learn(JSONArray("""[{"key":"p:mingtian","word":"明天","code":"ming tian","hits":1}]"""))
                check(store.lookup("mingtian").getJSONObject(0).getInt("hits") == 13)
                check(store.associations("明").toString().contains("明天"))
                for (bad in listOf("明天\tming\t2", "明天\tming tian\t-1", "{\"format\":\"other\"}")) {
                    val saved = store.exportBackup(); check(runCatching { store.importBackup(bad) }.isFailure); check(saved == store.exportBackup())
                }
                store.importBackup("单词\tdan ci\t1")
                check(store.lookup("danci").getJSONObject(0).getBoolean("pinned"))
                File(root,"rime-user/voxfix.userdb").apply { check(mkdirs()); resolve("fixture").writeText("old") }
                check(store.cleanupLegacy(root));check(!File(root,"rime-user/voxfix.userdb").exists());check(!exported.exists())
                snapshot = store.exportBackup()
                check(JSONObject(snapshot).getJSONObject("legacy").getString("personal-english") == "sample\n")
                check(JSONObject(snapshot).getJSONObject("legacy").getString("rime:voxfix.tsv") == "# Rime export\n明天\tming tian\t12\n")
                check(store.exportTsv().contains("明天\tming tian\t13\n"))
                check(File(root, "rime-user/lexicon-migrated").isFile)
            }
            PersonalLexicon(context, name).use { store -> check(store.count() == 10005L); check(store.exportBackup() == snapshot) }
            val restored = name.replace(".db", "-restore.db")
            try {
                PersonalLexicon(context, restored).use { store -> store.importBackup(snapshot); check(store.count() == 10005L); check(store.exportBackup() == snapshot) }
            } finally { context.deleteDatabase(restored) }
            probeNative(context, root)
            return "PASS: SQLite 10000+ retention, indexed recall, native/web migration, no duplicate inflation, rejected-import rollback, TSV/backup restore, reopen persistence and pinned Rime export from isolated copies"
        } finally { context.deleteDatabase(name); check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively() }
    }

    private fun probeNative(context: Context, parent: File) {
        val root = File(parent, "native").apply { check(mkdirs()) }
        val user = File(root, "rime-user").apply { check(mkdirs()) }
        val originals = File(context.filesDir, "rime-user").listFiles().orEmpty().filter { it.name.endsWith(".userdb") }
        fun hashes() = originals.flatMap { directory -> directory.walkTopDown().filter { it.isFile }.map { it.path to java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }.toList() }.toMap()
        val before = hashes()
        for (directory in originals) check(directory.copyRecursively(File(user, directory.name)))
        val personal = File(root, "pinyin-personal").apply { check(mkdirs()) }
        for (name in listOf("voxfix_personal.dict.yaml", "personal-english.txt")) {
            context.assets.open("pinyin-personal/$name").use { input -> File(personal, name).outputStream().use { input.copyTo(it) } }
        }
        val engine = PinyinEngine(context.assets, root)
        val name = parent.name + "-native.db"
        try {
            for (directory in originals) check(File(user, "lexicon-export/${directory.name.removeSuffix(".userdb")}.tsv").isFile) { "Missing historical user dictionary export" }
            PersonalLexicon(context, name).use { store -> store.migrateNative(root) { it }; check(store.count() > 0) }
            val words = JSONObject(engine.candidates("ni", 10)).getJSONArray("words")
            check(words.length() > 0)
            check(JSONObject(engine.choose("ni", words.getString(0)) { true }).getBoolean("committed"))
        } finally { engine.close(); context.deleteDatabase(name) }
        check(hashes() == before) { "Original Rime data changed" }
    }
}
