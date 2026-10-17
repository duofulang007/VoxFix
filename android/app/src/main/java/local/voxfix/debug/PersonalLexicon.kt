package local.voxfix.debug

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One durable source for personal recall, ranking and continuation. */
internal class PersonalLexicon(context: Context, name: String = "personal-lexicon.db") :
    SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries (key TEXT NOT NULL, word TEXT NOT NULL, code TEXT, hits INTEGER NOT NULL, nine TEXT, pinned INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(key,word))")
        db.execSQL("CREATE INDEX entries_word ON entries(word)")
        db.execSQL("CREATE INDEX entries_nine ON entries(nine)")
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported lexicon version")

    private fun metadata(key: String): String? = readableDatabase.rawQuery("SELECT value FROM metadata WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    private fun putMetadata(key: String, value: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO metadata VALUES (?,?)", arrayOf(key, value))
    }
    fun revision(): Long = metadata("revision")?.toLongOrNull() ?: 0
    fun nativeMigrationComplete() = metadata("native-v1") != null
    fun migrationComplete() = nativeMigrationComplete() && metadata("web-v1") != null
    fun legacyCleaned() = metadata("legacy-cleaned") != null
    fun personalEnglish(): String? = metadata("personal-english")
    private fun changed() = putMetadata("revision", (revision() + 1).toString())
    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM entries", null).use { it.moveToFirst(); it.getLong(0) }

    private fun rows(where: String = "", args: Array<String> = emptyArray()): JSONArray {
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT key,word,code,hits,pinned FROM entries $where ORDER BY hits DESC,key,word", args).use {
            while (it.moveToNext()) {
                val row = JSONObject().put("key", it.getString(0)).put("word", it.getString(1)).put("code", it.getString(2)).put("hits", it.getInt(3))
                if (it.getInt(4) != 0) row.put("pinned", true)
                result.put(row)
            }
        }
        return result
    }
    fun lookup(raw: String): JSONArray {
        require(raw.matches(Regex("[a-z'2-9]{1,60}")))
        val prefix = (if (raw[0].isDigit()) "9:" else "p:") + raw[0]
        if (raw[0].isDigit()) return rows("WHERE (key>=? AND key<?) OR (nine>=? AND nine<?)", arrayOf(prefix, prefix + '\uffff', raw.take(1), raw.take(1) + '\uffff'))
        return rows("WHERE key>=? AND key<?", arrayOf(prefix, prefix + '\uffff'))
    }
    fun associations(context: String): JSONArray {
        val tail = Regex("[\\u3400-\\u9fff]+$").find(context.takeLast(16))?.value ?: return JSONArray()
        val clauses = tail.indices.map { "(word>=? AND word<?)" }
        val args = tail.indices.flatMap { listOf(tail.substring(it), tail.substring(it) + '\uffff') }.toTypedArray()
        return rows("WHERE " + clauses.joinToString(" OR "), args)
    }
    private fun merge(rows: JSONArray, increment: Boolean) {
        validate(rows)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val code = row.optString("code").ifEmpty { null }
            val key = code?.let { "p:" + it.replace(" ", "") } ?: row.getString("key")
            val nine = code?.replace(" ", "")?.map { "22233344455566677778889999"[it - 'a'] }?.joinToString("")
            writableDatabase.execSQL("""INSERT INTO entries(key,word,code,hits,nine,pinned) VALUES (?,?,?,?,?,?)
                ON CONFLICT(key,word) DO UPDATE SET code=COALESCE(excluded.code,entries.code),nine=COALESCE(excluded.nine,entries.nine),pinned=MAX(entries.pinned,excluded.pinned),
                hits=MIN(1000000,${if (increment) "entries.hits+excluded.hits" else "MAX(entries.hits,excluded.hits)"})""",
                arrayOf(key, row.getString("word"), code, row.getInt("hits"), nine, if (row.optBoolean("pinned")) 1 else 0))
        }
    }
    private fun transaction(action: () -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try { action(); changed(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    @Synchronized fun learn(rows: JSONArray) = transaction { merge(rows, true) }
    @Synchronized fun migrateWeb(rows: JSONArray) {
        if (metadata("web-v1") != null && rows.length() == 0) return
        transaction { merge(rows, false); putMetadata("web-v1", "done") }
    }

    @Synchronized fun cleanupLegacy(root: File): Boolean {
        if (metadata("legacy-cleaned") != null) return true
        if (!migrationComplete()) return false
        readableDatabase.rawQuery("PRAGMA quick_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") }
        val user = File(root, "rime-user")
        val old = user.listFiles().orEmpty().filter { it.name.endsWith(".userdb") }
        for (file in old) {
            val source = metadata("rime:" + file.name.removeSuffix(".userdb") + ".tsv") ?: return false
            // Keep the old database if any historical entry cannot yet be recalled by this keyboard.
            for (line in source.lineSequence().filter { !it.startsWith("#") && it.contains('\t') }) {
                val parts = line.split('\t'); val word = parts[0]; val code = parts.getOrNull(1)?.trim().orEmpty()
                if ((parts.getOrNull(2)?.toIntOrNull() ?: 0) < 0) continue
                if (!word.matches(Regex("[\\u3400-\\u9fff]{1,16}")) || !code.matches(Regex("[a-z]{1,6}( [a-z]{1,6}){0,15}")) ||
                    code.split(' ').size != word.length || code.replace(" ", "").length > 60) return false
            }
        }
        for (file in old + File(user, "lexicon-export")) {
            check(file.canonicalFile.parentFile == user.canonicalFile)
            check(!file.exists() || file.deleteRecursively()) { "旧学习库清理未完成，稍后重试" }
        }
        putMetadata("legacy-cleaned", "done")
        return true
    }

    @Synchronized fun migrateNative(root: File, simplify: (String) -> String) {
        if (metadata("native-v1") != null) return
        val exported = File(root, "rime-user/lexicon-export")
        check(File(exported, "ready").isFile) { "旧拼音词库尚未准备好迁移" }
        for (file in File(root, "rime-user").listFiles().orEmpty().filter { it.name.endsWith(".userdb") }) {
            check(File(exported, file.name.removeSuffix(".userdb") + ".tsv").isFile) { "旧拼音词库导出不完整，原数据已保留" }
        }
        transaction {
            fun ingest(text: String, seed: Boolean) {
                val batch = JSONArray()
                for (line in text.lineSequence()) {
                    if (line.startsWith("#") || !line.contains('\t')) continue
                    val parts = line.split('\t')
                    val word = simplify(parts[0]); val code = parts.getOrNull(1)?.trim().orEmpty()
                    val weight = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 1
                    if (weight < 0 || !word.matches(Regex("[\\u3400-\\u9fff]{1,16}")) ||
                        !code.matches(Regex("[a-z]{1,6}( [a-z]{1,6}){0,15}")) || code.split(' ').size != word.length || code.replace(" ", "").length > 60) continue
                    batch.put(JSONObject().put("key", "p:" + code.replace(" ", "")).put("word", word)
                        .put("code", code).put("hits", if (seed) 2 else weight.coerceIn(2, 1000000)))
                }
                merge(batch, false)
            }
            for (file in exported.listFiles().orEmpty().filter { it.extension == "tsv" }) {
                val text = file.readText(); putMetadata("rime:" + file.name, text); ingest(text, false)
            }
            val seed = File(root, "pinyin-personal/voxfix_personal.dict.yaml").readText()
            putMetadata("personal-dictionary", seed); ingest(seed, true)
            putMetadata("personal-english", File(root, "pinyin-personal/personal-english.txt").readText())
            putMetadata("native-v1", "done")
        }
        // Deletion is separate and requires both migrations plus a healthy active database.
        File(root, "rime-user/lexicon-migrated").writeText("1")
    }
    fun exportBackup(): String {
        val db = readableDatabase; db.beginTransaction()
        try {
            val extra = JSONObject()
            db.rawQuery("SELECT key,value FROM metadata WHERE key LIKE 'rime:%' OR key IN ('personal-dictionary','personal-english')", null).use {
                while (it.moveToNext()) extra.put(it.getString(0), it.getString(1))
            }
            return JSONObject().put("format", "voxfix-personal-lexicon").put("version", 1)
                .put("entries", rows()).put("legacy", extra).toString()
        } finally { db.endTransaction() }
    }
    @Synchronized fun importBackup(text: String) {
        require(text.toByteArray().size <= MAX_BYTES) { "词库文件超过32MiB，请分批导入" }
        val entries: JSONArray; val extra: JSONObject
        if (text.trimStart('\ufeff', ' ', '\n', '\r', '\t').startsWith("{")) {
            val value = JSONObject(text.removePrefix("\ufeff"))
            require(value.optString("format") == "voxfix-personal-lexicon" && value.getInt("version") == 1) { "不支持的词库备份版本" }
            entries = value.getJSONArray("entries"); extra = value.optJSONObject("legacy") ?: JSONObject()
            for (key in extra.keys()) require((key.matches(Regex("rime:[a-zA-Z0-9_.-]+\\.tsv")) || key in listOf("personal-dictionary", "personal-english")) && extra.get(key) is String) { "词库附加数据无效" }
        } else { entries = parseTsv(text); extra = JSONObject() }
        validate(entries)
        transaction {
            merge(entries, false)
            for (key in extra.keys()) {
                if (key == "personal-english") {
                    val words = (metadata(key).orEmpty().lineSequence() + extra.getString(key).lineSequence()).filter { it.matches(Regex("[a-zA-Z][a-zA-Z'-]{1,59}")) }.distinct().toList()
                    putMetadata(key, words.joinToString("\n", postfix = if (words.isEmpty()) "" else "\n"))
                } else if (metadata(key) == null) putMetadata(key, extra.getString(key))
            }
        }
    }
    fun exportTsv(): String = buildString {
        append("# VoxFix personal lexicon: word\tpinyin\tfrequency\n")
        readableDatabase.rawQuery("SELECT word,code,MAX(hits) FROM entries WHERE code IS NOT NULL GROUP BY word,code ORDER BY word,code", null).use {
            while (it.moveToNext()) append("${it.getString(0)}\t${it.getString(1)}\t${it.getInt(2)}\n")
        }
    }
    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
        fun validate(rows: JSONArray) {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i); val word = row.getString("word"); val key = row.getString("key")
                require(!row.has("pinned") || row.get("pinned") is Boolean) { "词条来源格式无效" }
                require(key.matches(Regex("(?:p:[a-z']{1,60}|9:[2-9]{1,60})")) && word.length in 1..60 &&
                    word.any { it in '\u3400'..'\u9fff' } && word.none { it.isISOControl() } && row.get("hits") is Number &&
                    row.getDouble("hits") == row.getInt("hits").toDouble() && row.getInt("hits") in 1..1000000) { "选词记录格式无效" }
                if (row.has("code")) {
                    val code = row.getString("code")
                    require(key.startsWith("p:") && word.matches(Regex("[\\u3400-\\u9fff]{1,16}")) &&
                        code.matches(Regex("[a-z]{1,6}( [a-z]{1,6}){0,15}")) && code.split(' ').size == word.length && code.replace(" ", "").length <= 60) { "词条读音格式无效" }
                }
            }
        }
        fun parseTsv(text: String): JSONArray {
            val rows = JSONArray()
            for (line in text.removePrefix("\ufeff").lineSequence()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val parts = line.trimEnd('\r').split('\t')
                require(parts.size in 2..3) { "文本词库须为：词语、空格分隔拼音、可选词频（Tab分隔）" }
                val code = parts[1].trim(); val hits = if (parts.size == 3) parts[2].toIntOrNull() ?: error("词频无效") else 2
                rows.put(JSONObject().put("key", "p:" + code.replace(" ", "")).put("word", parts[0]).put("code", code).put("hits", hits).put("pinned", true))
            }
            validate(rows); return rows
        }
    }
}
