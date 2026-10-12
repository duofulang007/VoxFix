package local.voxfix.debug

import android.content.res.AssetManager
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal class PinyinEngine(assets: AssetManager, root: File) {
    private val shared = File(root, "rime-9377b95")
    private val user = File(root, "rime-user")
    private val staging = File(user, "build")
    private var session = 0L
    private var query = ""
    private var queryLimit = 0
    private var preedit = ""
    private var candidates = linkedMapOf<String, Int>()

    init {
        check(shared.mkdirs() || shared.isDirectory)
        fun copyAssets(source: String, directory: File) {
            check(directory.mkdirs() || directory.isDirectory)
            for (name in assets.list(source).orEmpty()) {
                val path = "$source/$name"
                val destination = File(directory, name)
                if (assets.list(path).orEmpty().isNotEmpty()) copyAssets(path, destination)
                else if (!destination.isFile || name.endsWith(".yaml") &&
                    !assets.open(path).use { it.readBytes() }.contentEquals(destination.readBytes())) {
                    val temporary = File(directory, "$name.tmp")
                    assets.open(path).use { input -> temporary.outputStream().use { input.copyTo(it) } }
                    check(temporary.renameTo(destination))
                }
            }
        }
        copyAssets("pinyin", shared)
        val seed = File(root, "pinyin-personal/voxfix_personal.dict.yaml")
        if (seed.isFile) {
            require(seed.length() in 1..1_000_000)
            val destination = File(shared, seed.name)
            if (!destination.isFile || !seed.readBytes().contentEquals(destination.readBytes())) {
                val temporary = File(shared, "${seed.name}.tmp")
                seed.inputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(temporary.renameTo(destination))
            }
        }
        check(user.mkdirs() || user.isDirectory)
        check(staging.mkdirs() || staging.isDirectory)
        session = nativeStart(shared.path, user.path, staging.path)
        check(session != 0L) { "Rime session unavailable" }
    }

    @Synchronized fun candidates(raw: String, limit: Int): String {
        check(session != 0L)
        require(raw.matches(Regex("[a-z']{1,100}")) && limit in 1..2000)
        if (raw != query || limit > queryLimit) {
            val rows = nativeQuery(session, raw, limit)
            preedit = rows.firstOrNull().orEmpty()
            candidates = linkedMapOf()
            for (index in 1 until rows.size step 2) {
                if (index + 1 < rows.size && rows[index + 1].isNotEmpty() &&
                    (raw.contains('\'') || preedit.trim().contains(' ') || rows[index + 1].codePointCount(0, rows[index + 1].length) == 1))
                    candidates.putIfAbsent(rows[index + 1], rows[index].toInt())
            }
            query = raw
            queryLimit = limit
        }
        return JSONObject().put("words", JSONArray(candidates.keys.take(limit)))
            .put("preedit", preedit).toString()
    }

    @Synchronized fun choose(raw: String, word: String, learn: Boolean, commit: (String) -> Boolean): String {
        if (session == 0L || raw != query) return "{}"
        val index = candidates[word] ?: return "{}"
        val preview = nativePreview(session, index)
        if (preview.size != 3 || preview[0].isNotEmpty() || preview[1] != word || !raw.endsWith(preview[2])) return "{}"
        val consumed = raw.length - preview[2].length
        if (consumed <= 0 || !commit(word)) return "{}"
        if (learn) nativeLearn(session, index, consumed) else nativeReset(session)
        query = ""
        candidates.clear()
        return JSONObject().put("committed", true).put("remaining", preview[2]).toString()
    }

    @Synchronized fun close() {
        if (session != 0L) nativeClose(session)
        session = 0L
    }

    @Synchronized fun clearLearning() {
        close()
        for (file in user.listFiles().orEmpty().filter { it.name.endsWith(".userdb") }) {
            check(file.canonicalFile.parentFile == user.canonicalFile)
            check(file.deleteRecursively())
        }
        session = nativeStart(shared.path, user.path, staging.path)
        check(session != 0L)
        query = ""; queryLimit = 0; candidates.clear()
    }

    private external fun nativeStart(shared: String, user: String, staging: String): Long
    private external fun nativeQuery(session: Long, raw: String, limit: Int): Array<String>
    private external fun nativePreview(session: Long, index: Int): Array<String>
    private external fun nativeLearn(session: Long, index: Int, consumed: Int)
    private external fun nativeClose(session: Long)
    private external fun nativeReset(session: Long)

    companion object { init { System.loadLibrary("voxfix_rime") } }
}
