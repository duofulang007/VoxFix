package local.voxfix.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Versioned, private image clipboard. Only an explicit save writes a screenshot. */
class ScreenshotStore(private val context: Context) {
    data class Entry(val id: String, val createdAt: Long, val width: Int, val height: Int, val bytes: Long)
    private val directory = File(context.filesDir, "screenshot-clipboard")
    private val index get() = AtomicFile(File(directory, "index.json"))

    fun entries(): List<Entry> = synchronized(lock) { readEntries() }

    private fun readEntries(): List<Entry> {
        if (!File(directory,"index.json").exists() && !File(directory,"index.json.bak").exists()) return emptyList()
        val data = index.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        require(data.getInt("version") == 1) { "截图记录版本不受支持，原文件已保留" }
        val rows = data.getJSONArray("items")
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            Entry(row.getString("id").also(::validateId), row.getLong("createdAt"), row.getInt("width"), row.getInt("height"), row.getLong("bytes"))
        }
    }

    private fun writeEntries(entries: List<Entry>) {
        val rows = JSONArray()
        entries.forEach { rows.put(JSONObject().put("id",it.id).put("createdAt",it.createdAt).put("width",it.width).put("height",it.height).put("bytes",it.bytes)) }
        val stream = index.startWrite()
        try {
            stream.write(JSONObject().put("version",1).put("items",rows).toString().toByteArray(Charsets.UTF_8))
            index.finishWrite(stream)
        } catch (error: Exception) { index.failWrite(stream); throw error }
    }

    fun save(image: Bitmap): Entry {
        require(image.width > 0 && image.height > 0 && image.width.toLong() * image.height <= 16_000_000) { "截图尺寸过大，请缩小选区" }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建截图剪贴板" }
        val id = UUID.randomUUID().toString().replace("-", "")
        val original = file(id)
        val thumb = thumbnail(id)
        var committed = false
        try {
            FileOutputStream(original).use { output ->
                check(image.compress(Bitmap.CompressFormat.PNG,100,output)) { "截图编码失败" }
                output.fd.sync()
            }
            check(original.length() <= 16L * 1024 * 1024) { "截图文件过大，请缩小选区" }
            val scale = minOf(1f,320f / maxOf(image.width,image.height))
            val preview = Bitmap.createScaledBitmap(image,maxOf(1,(image.width*scale).toInt()),maxOf(1,(image.height*scale).toInt()),true)
            try { FileOutputStream(thumb).use { check(preview.compress(Bitmap.CompressFormat.WEBP,80,it)) { "缩略图保存失败" } } }
            finally { if (preview !== image) preview.recycle() }
            val entry = Entry(id,System.currentTimeMillis(),image.width,image.height,original.length())
            synchronized(lock) {
                val old = readEntries()
                check(old.size < 100 && old.sumOf { it.bytes } + entry.bytes <= 128L * 1024 * 1024) { "截图剪贴板已满，请先删除不需要的截图" }
                writeEntries(listOf(entry) + old)
                committed = true
            }
            return entry
        } finally { if (!committed) { original.delete(); thumb.delete() } }
    }

    fun delete(id: String) = synchronized(lock) {
        validateId(id)
        val old = readEntries()
        if (old.none { it.id == id }) return@synchronized
        writeEntries(old.filterNot { it.id == id })
        file(id).delete(); thumbnail(id).delete()
    }

    fun file(id: String): File { validateId(id); return File(directory,"$id.png") }
    fun thumbnail(id: String): File { validateId(id); return File(directory,"$id.webp") }
    fun uri(id: String): Uri { validateId(id); return Uri.parse("content://${context.packageName}.screenshots/$id") }
    fun copy(id: String) {
        check(file(id).isFile) { "截图文件不存在" }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(context.contentResolver,"VoxFix 截图",uri(id)))
    }
    fun share(id: String) {
        check(file(id).isFile) { "截图文件不存在" }
        val uri = uri(id)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(Intent.EXTRA_STREAM,uri)
            clipData = ClipData.newUri(context.contentResolver,"VoxFix 截图",uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send,"分享截图").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    companion object {
        private val lock = Any()
        fun validateId(id: String) { require(id.matches(Regex("[a-f0-9]{32}"))) { "无效的截图编号" } }
    }
}
