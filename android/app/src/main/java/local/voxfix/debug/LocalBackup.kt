package local.voxfix.debug

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 卸载不会丢的本地备份。
 *
 * 为什么不放应用私有目录：`/data/data/<pkg>` 与 `Android/data/<pkg>` 都随卸载一起消失，
 * 而 MediaStore 的 `Download/VoxFix/` 是公共下载区，卸载 App 不会动它——只有用户在文件
 * 管理器里、或在本页点「删除」才会消失。API 29+ 对 Downloads 集合允许免权限写入，且
 * 查询只会回读自己创建的条目，所以既不申请存储权限，也看不到别人的文件。
 *
 * 内容是 zip（v2 起）：`backup.json` 放词库与语音词典，`clipboard/` 放剪贴板图片与
 * 截图工程（含 document.json，恢复后仍可继续编辑），`import/` 放电脑推来的剪贴板历史。
 * 翻译模型 441 MB 不进备份——「离线翻译」页能重新下载。
 */
object LocalBackup {
    const val FOLDER = "VoxFix"
    const val FORMAT = "voxfix-local-backup"
    const val VERSION = 2

    /** 只保留最近两份：带图片后单份可达几十 MB，轮换太慢会把下载目录塞满。 */
    private const val KEEP = 2
    private const val MAX_BYTES = 48L * 1024 * 1024
    private const val MAX_UNPACKED = 256L * 1024 * 1024

    private const val CLIPBOARD = "screenshot-clipboard"
    private const val IMPORTS = "clipboard-import"

    /** 只备份这 8 个真设置的 xml：其余 shared_prefs 是 WebView/MLKit 内部与仪表化测试残留。 */
    private val PREF_NAMES = setOf(
        "screen-text", "screenshot-region-type", "screenshot-snap-config", "screenshot-tool-memory",
        "voxfix-app", "voxfix-modules", "voxfix-recording", "voxfix-update"
    )
    private const val INDEX = "index.json"

    data class Archive(val uri: Uri, val name: String, val bytes: Long, val modifiedAt: Long) {
        /** `VoxFix-20261115-145912.zip` → `2026-11-15 14:59`，秒只用来防重名。 */
        val label: String
            get() {
                val raw = name.removePrefix("VoxFix-").substringBeforeLast('.')
                val parts = raw.split('-')
                return if (parts.size >= 2 && parts[0].length == 8 && parts[1].length >= 4) {
                    "${parts[0].take(4)}-${parts[0].substring(4, 6)}-${parts[0].substring(6)} " +
                        "${parts[1].take(2)}:${parts[1].substring(2, 4)}"
                } else raw
            }
    }

    private fun collection(): Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    private fun folder(): String = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER"

    /** 本机已有的备份，最新在前。只返回本应用创建的条目。 */
    fun list(context: Context): List<Archive> {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED
        )
        val result = mutableListOf<Archive>()
        context.contentResolver.query(
            collection(), projection,
            "${MediaStore.Downloads.RELATIVE_PATH} IN (?,?) AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf(folder(), "${folder()}/", "VoxFix-%"),
            "${MediaStore.Downloads.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val timeColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                result += Archive(
                    Uri.withAppendedPath(collection(), cursor.getLong(idColumn).toString()),
                    cursor.getString(nameColumn) ?: "VoxFix-备份.zip",
                    cursor.getLong(sizeColumn),
                    cursor.getLong(timeColumn) * 1000
                )
            }
        }
        return result
    }

    /** 打包当前词库、语音词典、剪贴板图片与截图工程，写成一份新备份。 */
    fun create(context: Context, appVersion: String): Archive {
        val now = System.currentTimeMillis()
        // 秒级时间戳：同一分钟内连点两次也不会撞名，MediaStore 不会给第二个文件加 " (1)"。
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(now))
        val payload = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", now)
            .put("appVersion", appVersion)
            .put("lexicon", lexiconPayload(context))
            .put("dictionary", dictionaryText(context))

        val staging = File(context.cacheDir, "voxfix-backup-staging.zip")
        try {
            ZipOutputStream(BufferedOutputStream(staging.outputStream())).use { zip ->
                zip.putNextEntry(ZipEntry("backup.json"))
                zip.write(payload.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                addTree(zip, File(context.filesDir, CLIPBOARD), "clipboard")
                addTree(zip, File(context.filesDir, IMPORTS), "import")
                addTree(zip, File(context.filesDir, WebMirror.DIR), "web")
                addPrefs(zip, context)
            }
            // 图片把包撑过上限时降级：只保词库与词典，好过交出一份装不下的备份。
            val skippedImages = staging.length() > MAX_BYTES
            if (skippedImages) {
                ZipOutputStream(BufferedOutputStream(staging.outputStream())).use { zip ->
                    zip.putNextEntry(ZipEntry("backup.json"))
                    zip.write(payload.put("imagesSkipped", true).toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            val archive = write(context, "VoxFix-$stamp.zip", staging.readBytes())
            prune(context)
            return archive
        } finally {
            staging.delete()
        }
    }

    /**
     * 恢复一份备份。词库与词典走「合并」：本机多的词留着，备份里的词补进来，已有词的
     * 错法只增不减。剪贴板条目按 id 合并：本机已有的不重复写入，备份里缺的补回来。
     */
    fun restore(context: Context, uri: Uri): String {
        val staging = File(context.cacheDir, "voxfix-restore")
        staging.deleteRecursively()
        check(staging.mkdirs() || staging.isDirectory) { "无法准备恢复目录" }
        try {
            unpack(context, uri, staging)
            val meta = File(staging, "backup.json").takeIf { it.isFile }
                ?.let { runCatching { JSONObject(it.readText(Charsets.UTF_8)) }.getOrNull() }
                ?: error("备份里缺少 backup.json")
            require(meta.optString("format") == FORMAT && meta.optInt("version") in 1..VERSION) {
                "这不是 VoxFix 的本地备份"
            }

            var wordsBefore = 0L
            var wordsAfter = 0L
            meta.optJSONObject("lexicon")?.let { lexicon ->
                PersonalLexicon(context).use { store ->
                    wordsBefore = store.count()
                    store.importBackup(lexicon.toString())
                    wordsAfter = store.count()
                }
            }

            var addedWords = 0
            var addedVariants = 0
            meta.optString("dictionary").takeIf { it.isNotBlank() }?.let { source ->
                val incoming = VoiceDictionary.parse(source)
                val target = VoiceDictionaryStore.file(context)
                val current = if (target.isFile) {
                    runCatching { VoiceDictionary.parse(target.readText(Charsets.UTF_8)) }.getOrNull() ?: VoiceDictionary()
                } else VoiceDictionary()
                val (words, variants) = VoiceDictionary.mergeInto(current, incoming)
                addedWords = words
                addedVariants = variants
                if (words > 0 || variants > 0) {
                    VoiceDictionaryStore.write(target, current)
                    VoiceDictionaryStore.invalidate()
                }
            }

            val images = restoreClipboard(context, File(staging, "clipboard"))
            restoreImports(context, File(staging, "import"))
            val web = restoreWeb(context, File(staging, "web"))
            val prefs = restorePrefs(context, File(staging, "prefs"))

            return buildList {
                add("词库 ${wordsBefore} → ${wordsAfter} 条")
                add("词典新增 $addedWords 个词、$addedVariants 条错法")
                add("剪贴板补回 $images 项")
                if (web) add("文本剪贴板与常用语已写入，键盘下次弹出生效")
                if (prefs > 0) add("$prefs 个设置文件已立即生效")
                if (meta.optBoolean("imagesSkipped")) add("这份备份生成时超限，未含图片")
            }.joinToString("；")
        } finally {
            staging.deleteRecursively()
        }
    }

    fun delete(context: Context, uri: Uri): Boolean =
        context.contentResolver.delete(uri, null, null) > 0

    /** 恢复是否留下了待冷启动生效的系统偏好（prefs-restore/ 有文件）。备份页据此决定自动重启。 */
    fun needsRestart(context: Context): Boolean =
        File(context.filesDir, "prefs-restore").listFiles().orEmpty().any { it.isFile }

    // ───── 打包 ─────

    private fun addTree(zip: ZipOutputStream, root: File, prefix: String) {
        if (!root.isDirectory) return
        for (file in root.walkTopDown()) {
            if (file.isDirectory) continue
            val relative = file.relativeTo(root).invariantSeparatorsPath
            zip.putNextEntry(ZipEntry("$prefix/$relative"))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /** shared_prefs 不在 files/ 下，得单独取：只收白名单里那 8 个真设置。 */
    private fun addPrefs(zip: ZipOutputStream, context: Context) {
        val directory = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!directory.isDirectory) return
        for (name in PREF_NAMES) {
            val file = File(directory, "$name.xml")
            if (!file.isFile) continue
            zip.putNextEntry(ZipEntry("prefs/$name.xml"))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun lexiconPayload(context: Context): JSONObject =
        PersonalLexicon(context).use { JSONObject(it.exportBackup()) }

    private fun dictionaryText(context: Context): String {
        val file = VoiceDictionaryStore.file(context)
        return if (file.isFile) runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?: VoiceDictionary().serialize()
        else VoiceDictionary().serialize()
    }

    private fun write(context: Context, name: String, bytes: ByteArray): Archive {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, folder())
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection(), values) ?: error("无法在 Download/$FOLDER 创建备份")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入备份文件")
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        val actual = runCatching {
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: name
        return Archive(uri, actual, bytes.size.toLong(), System.currentTimeMillis())
    }

    private fun prune(context: Context) {
        val all = list(context)
        if (all.size <= KEEP) return
        for (stale in all.drop(KEEP)) runCatching { delete(context, stale.uri) }
    }

    // ───── 恢复 ─────

    private fun unpack(context: Context, uri: Uri, staging: File) {
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    require(!name.startsWith("/") && !name.contains("..")) { "备份文件路径异常，已中止" }
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    val target = File(staging, name)
                    target.parentFile?.mkdirs() ?: error("无法创建恢复目录")
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            check(total <= MAX_UNPACKED) { "备份解压后超过 256MiB，已中止" }
                            output.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("无法读取备份文件")
        check(total > 0) { "备份文件是空的" }
    }

    /** 按 id 合并剪贴板索引，本机已有条目优先；返回补回的条目数。 */
    private fun restoreClipboard(context: Context, source: File): Int {
        val incoming = File(source, INDEX)
        if (!incoming.isFile) return 0
        val target = File(context.filesDir, CLIPBOARD)
        if (!target.isDirectory && !target.mkdirs()) error("无法创建剪贴板目录")

        val existing = runCatching { JSONObject(File(target, INDEX).readText(Charsets.UTF_8)) }.getOrNull()
        val incomingRoot = runCatching { JSONObject(incoming.readText(Charsets.UTF_8)) }.getOrNull() ?: return 0
        val rows = linkedMapOf<String, JSONObject>()
        fun absorb(root: JSONObject?) {
            val items = root?.optJSONArray("items") ?: return
            for (index in 0 until items.length()) {
                val row = items.optJSONObject(index) ?: continue
                val id = row.optString("id")
                if (id.isEmpty() || rows.containsKey(id)) continue
                rows[id] = row
            }
        }
        // 本机条目先占位：同一 id 不会被备份里的旧版本盖掉。
        absorb(existing)
        val before = rows.size
        absorb(incomingRoot)
        // 统计的是「索引里补回来的条目数」：文件本来就在（比如上次恢复留下的）也算补回，
        // 因为条目已经在列表里可点开了。之前只数复制的文件，会出现「补回 0 项」但其实多了条目。
        val added = rows.size - before

        for ((id, row) in rows) {
            val project = File(source, "projects/$id")
            if (project.isDirectory) {
                val destination = File(target, "projects/$id")
                if (!destination.exists()) project.copyRecursively(destination, overwrite = false)
                row.put("kind", "PROJECT")
                continue
            }
            for (suffix in listOf("png", "webp")) {
                val file = File(source, "$id.$suffix")
                if (!file.isFile) continue
                val destination = File(target, "$id.$suffix")
                if (!destination.exists()) file.copyTo(destination, overwrite = false)
            }
        }

        val merged = JSONObject().put("version", 2).put("items", JSONArray().also { array ->
            rows.values.sortedByDescending { it.optLong("createdAt") }.forEach { array.put(it) }
        })
        // 与 ScreenshotStore 一样走 AtomicFile：写到一半被杀不会留下半个索引。
        val atomic = android.util.AtomicFile(File(target, INDEX))
        val stream = atomic.startWrite()
        try {
            stream.write(merged.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
        return added
    }

    private fun restoreImports(context: Context, source: File) {
        if (!source.isDirectory) return
        val target = File(context.filesDir, IMPORTS)
        if (!target.isDirectory && !target.mkdirs()) return
        for (file in source.listFiles().orEmpty()) {
            if (!file.isFile) continue
            val destination = File(target, file.name)
            if (!destination.exists()) runCatching { file.copyTo(destination, overwrite = false) }
        }
    }

    /**
     * 前端 localStorage 的镜像写回，并留一个一次性标记。
     *
     * 为什么不直接写 localStorage：它是压缩的 leveldb，只能由 WebView 自己读写。
     * 这里把 JSON 放回 files/web-mirror/，下一次键盘创建时前端调 takePendingMirror()
     * 取走并按并集合并，取完即删标记，同一份不会被应用两遍。
     */
    private fun restoreWeb(context: Context, source: File): Boolean {
        if (!source.isDirectory) return false
        val target = File(context.filesDir, WebMirror.DIR)
        if (!target.isDirectory && !target.mkdirs()) return false
        val keys = JSONObject()
        for (file in source.listFiles().orEmpty()) {
            if (!file.isFile || !file.name.endsWith(".json")) continue
            val key = file.name.removeSuffix(".json")
            if (key !in WebMirror.KEYS) continue
            runCatching {
                // 先确认是合法 JSON，坏片段宁可不落地，也不能让键盘启动时解析失败。
                JSONObject(file.readText(Charsets.UTF_8))
                file.copyTo(File(target, file.name), overwrite = true)
                keys.put(key, true)
            }
        }
        if (keys.length() == 0) return false
        runCatching {
            File(context.filesDir, "${WebMirror.DIR}.restore")
                .writeText(keys.toString(), Charsets.UTF_8)
        }
        return true
    }

    /**
     * 把备份里的偏好直接写回运行中的 SharedPreferences。
     *
     * 旧做法是放 `files/prefs-restore/`、等下次冷启动由 [VoxFixApp] 落位；但恢复后自动重启会让
     * HyperOS 把被杀的无障碍服务标成 Crashed，用户必须再授权一次「屏幕取字」——恢复一次授权两次，
     * 步骤反而更繁琐。现在改成在主线程用同一份 Editor 覆盖：监听器当场生效（弧线位置、模块开关），
     * 不杀进程，也不影响输入法与无障碍授权。
     *
     * 注意 commit 必须在主线程：`screen-text` 的监听器会操作弧线 View，在其他线程回调会直接抛
     * "Only the original thread that created a view hierarchy can touch its views"。
     */
    private fun restorePrefs(context: Context, source: File): Int {
        if (!source.isDirectory) return 0
        val parsed = linkedMapOf<String, Map<String, Any>>()
        for (file in source.listFiles().orEmpty()) {
            if (!file.isFile || !file.name.endsWith(".xml")) continue
            val name = file.name.removeSuffix(".xml")
            if (name !in PREF_NAMES) continue
            val values = runCatching { PrefsXml.parse(file) }.getOrNull() ?: continue
            if (values.isNotEmpty()) parsed[name] = values
        }
        if (parsed.isEmpty()) return 0

        fun applyAll(): Int = PrefsStore.replaceAll(context, parsed)

        val applied = if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            applyAll()
        } else {
            var count = 0
            val latch = java.util.concurrent.CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                count = applyAll()
                latch.countDown()
            }
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            count
        }
        // 旧版本可能留下待落位目录：不清掉会在下次冷启动把刚恢复的值覆盖回去。
        File(context.filesDir, "prefs-restore").deleteRecursively()
        return applied
    }
}
