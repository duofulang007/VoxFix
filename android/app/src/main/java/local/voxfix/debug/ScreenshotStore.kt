package local.voxfix.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Versioned, private image clipboard. Only an explicit save writes a screenshot.
 *
 * Index version 2 adds editable records: `<id>.png` stays a plain image, while a project keeps its
 * untouched source plus one revision directory per save. The index is the commit point, so a failed
 * or cancelled write leaves the previous revision readable.
 */
class ScreenshotStore(private val context: Context) {
    enum class Kind { IMAGE, PROJECT }
    data class Entry(
        val id: String, val createdAt: Long, val width: Int, val height: Int, val bytes: Long,
        val kind: Kind = Kind.IMAGE, val revision: Int = 0,
        /** 来源标记：空=手机本地截图，"pc"=电脑推送导入（前端据此画「电脑」标签）。 */
        val source: String = ""
    )
    private val directory = File(context.filesDir, "screenshot-clipboard")
    private val projects get() = File(directory, "projects")
    private val index get() = AtomicFile(File(directory, "index.json"))

    fun entries(): List<Entry> = synchronized(lock) { readEntries() }
    fun entry(id: String): Entry? = entries().firstOrNull { it.id == id }

    private fun readEntries(): List<Entry> {
        if (!File(directory,"index.json").exists() && !File(directory,"index.json.bak").exists()) return emptyList()
        val data = index.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        val version = data.getInt("version")
        require(version == 1 || version == 2) { "截图记录版本不受支持，原文件已保留" }
        val rows = data.getJSONArray("items")
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            Entry(row.getString("id").also(::validateId), row.getLong("createdAt"), row.getInt("width"), row.getInt("height"),
                row.getLong("bytes"),
                if (version >= 2) runCatching { Kind.valueOf(row.optString("kind","IMAGE")) }.getOrDefault(Kind.IMAGE) else Kind.IMAGE,
                if (version >= 2) row.optInt("revision",0) else 0,
                row.optString("source",""))
        }
    }

    private fun writeEntries(entries: List<Entry>) {
        val rows = JSONArray()
        entries.forEach { rows.put(JSONObject().put("id",it.id).put("createdAt",it.createdAt).put("width",it.width)
            .put("height",it.height).put("bytes",it.bytes).put("kind",it.kind.name).put("revision",it.revision)
            .put("source",it.source)) }
        val stream = index.startWrite()
        try {
            stream.write(JSONObject().put("version",2).put("items",rows).toString().toByteArray(Charsets.UTF_8))
            index.finishWrite(stream)
        } catch (error: Exception) { index.failWrite(stream); throw error }
    }

    private fun budget(entries: List<Entry>, entry: Entry, replaced: String?) {
        val others = entries.filterNot { it.id == replaced }
        check(others.size < 100) { "截图剪贴板已满，请先删除不需要的截图" }
        check(others.sumOf { it.bytes } + entry.bytes <= 128L * 1024 * 1024) { "截图剪贴板已满，请先删除不需要的截图" }
    }

    fun save(image: Bitmap, source: String = ""): Entry {
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
            val preview = thumbnailBitmap(image)
            try { FileOutputStream(thumb).use { check(preview.compress(Bitmap.CompressFormat.WEBP,80,it)) { "缩略图保存失败" } } }
            finally { if (preview !== image) preview.recycle() }
            val entry = Entry(id,System.currentTimeMillis(),image.width,image.height,original.length(),source=source)
            synchronized(lock) {
                val old = readEntries()
                budget(old,entry,null)
                writeEntries(listOf(entry) + old)
                committed = true
            }
            return entry
        } finally { if (!committed) { original.delete(); thumb.delete() } }
    }

    /**
     * 已编码的 PNG 文件直接入库（长截图多段合成用，避免为入库再整张解码）。
     * 传入 [document] 时存成可二次编辑的工程（底图 + 首版合成），否则只存普通图片。
     * 超出剪贴板单张限额（16MP / 16MB）时返回 null，由调用方改走相册；入库失败抛错。
     */
    fun savePng(png: File, width: Int, height: Long, source: String = "",
                document: ScreenshotDocument? = null): Entry? {
        if (width <= 0 || height <= 0 || width.toLong() * height > 16_000_000) return null
        check(png.isFile) { "长截图文件不存在" }
        if (png.length() > 16L * 1024 * 1024) return null
        check(directory.isDirectory || directory.mkdirs()) { "无法创建截图剪贴板" }
        val id = UUID.randomUUID().toString().replace("-", "")
        var committed = false
        if (document == null) {
            val original = file(id)
            val thumb = thumbnail(id)
            try {
                val moved = png.renameTo(original)
                check(moved || (png.copyTo(original, overwrite = true).isFile && png.delete())) { "无法入库长截图" }
                val preview = thumbnailFromPng(original, width)
                try { FileOutputStream(thumb).use { check(preview.compress(Bitmap.CompressFormat.WEBP,80,it)) { "缩略图保存失败" } } }
                finally { preview.recycle() }
                val entry = Entry(id,System.currentTimeMillis(),width,height.toInt(),original.length(),source=source)
                synchronized(lock) {
                    val old = readEntries()
                    budget(old,entry,null)
                    writeEntries(listOf(entry) + old)
                    committed = true
                }
                return entry
            } finally { if (!committed) { original.delete(); thumb.delete() } }
        }
        // 工程化长截图：source.png 是未改动的底图，revision 1 是首版合成。
        // 剪贴板点「编辑」时复用截图工程，之前加的对象、文字和工具状态都能继续改。
        val projectDir = File(projects,id)
        val base = File(projectDir,"source.png")
        val revisionDir = File(projectDir,"1")
        val composite = File(revisionDir,"composite.png")
        val thumb = File(revisionDir,"thumbnail.webp")
        try {
            check(projectDir.isDirectory || projectDir.mkdirs()) { "无法创建截图工程" }
            check(revisionDir.isDirectory || revisionDir.mkdirs()) { "无法创建截图副本" }
            val moved = png.renameTo(base)
            check(moved || (png.copyTo(base, overwrite = true).isFile && png.delete())) { "无法入库长截图" }
            base.copyTo(composite,overwrite = true)
            val preview = thumbnailFromPng(base, width)
            try { FileOutputStream(thumb).use { check(preview.compress(Bitmap.CompressFormat.WEBP,80,it)) { "缩略图保存失败" } } }
            finally { preview.recycle() }
            File(revisionDir,"document.json").writeText(ScreenshotDocumentCodec.encode(document),Charsets.UTF_8)
            val bytes = base.length() + composite.length() + thumb.length() + File(revisionDir,"document.json").length()
            val entry = Entry(id,System.currentTimeMillis(),width,height.toInt(),bytes,Kind.PROJECT,1,source)
            synchronized(lock) {
                val old = readEntries()
                budget(old,entry,null)
                writeEntries(listOf(entry) + old)
                committed = true
            }
            return entry
        } finally { if (!committed) projectDir.deleteRecursively() }
    }

    /** 长图缩略图：只解码顶部一条窄带再缩放，不整张解码。 */
    private fun thumbnailFromPng(file: File, width: Int): Bitmap {
        val decoder = android.graphics.BitmapRegionDecoder.newInstance(file.path, false)
        try {
            val band = minOf(decoder.height, width * 3)
            val top = decoder.decodeRegion(Rect(0,0,width,band),BitmapFactory.Options())
            val scale = minOf(1f,320f / maxOf(top.width,top.height))
            val scaled = Bitmap.createScaledBitmap(top,maxOf(1,(top.width*scale).toInt()),maxOf(1,(top.height*scale).toInt()),true)
            if (scaled !== top) top.recycle()
            return scaled
        } finally { decoder.recycle() }
    }

    /** Path of the untouched canvas for a project, or the image itself for a plain record. */
    fun sourceFile(id: String): File? {
        val entry = entry(id) ?: return null
        return if (entry.kind == Kind.PROJECT) File(File(projects,id),"source.png").takeIf { it.isFile } else file(id)
    }

    fun document(id: String): ScreenshotDocument? {
        val entry = entry(id) ?: return null
        if (entry.kind != Kind.PROJECT) return null
        val file = File(revisionDir(entry),"document.json")
        if (!file.isFile) return null
        return try { ScreenshotDocumentCodec.decode(file.readText(Charsets.UTF_8)) }
        catch (error: Exception) { android.util.Log.w("VoxFix","SCREENSHOT_DOCUMENT_INVALID",error); null }
    }

    fun sourceBitmap(id: String): Bitmap? {
        val file = sourceFile(id) ?: return null
        return runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
    }

    /**
     * Publishes one project revision. [replaceId] keeps the record and its revision chain; otherwise
     * a new record is created and the base image is either [source], copied from [copySourceFromId]
     * or, for a record that never had a project, the composite itself.
     *
     * A plain image may be upgraded in place by passing its id as [replaceId]: the record keeps its
     * id (so the clipboard shows one entry, not two) and its PNG becomes the project base image.
     * Without this the user edits a screenshot, saves, taps the same picture again and finds an
     * empty canvas — the marks went into a second, easy-to-miss record.
     */
    fun saveProject(
        document: ScreenshotDocument, composite: Bitmap, source: Bitmap? = null,
        replaceId: String? = null, expectedRevision: Int? = null, copySourceFromId: String? = null
    ): Entry = synchronized(lock) {
        require(composite.width > 0 && composite.height > 0 && composite.width.toLong()*composite.height <= 16_000_000) { "截图尺寸过大，请缩小选区" }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建截图剪贴板" }
        val replaced = replaceId?.let { entry(it) ?: error("图片已删除，请刷新") }
        val upgrading = replaced?.kind == Kind.IMAGE
        val id = replaceId ?: UUID.randomUUID().toString().replace("-","")
        val projectDir = File(projects,id)
        check(projectDir.isDirectory || projectDir.mkdirs()) { "无法创建截图工程" }
        val sourceFile = File(projectDir,"source.png")
        var committed = false
        val revision = (replaced?.revision ?: 0) + 1
        val revisionDir = File(projectDir,revision.toString())
        try {
            if (!sourceFile.isFile) {
                when {
                    replaced == null -> {
                        val copied = when {
                            source != null -> writePng(sourceFile,source)
                            copySourceFromId != null -> {
                                val origin = sourceFile(copySourceFromId) ?: error("原图已删除，请刷新")
                                origin.copyTo(sourceFile,overwrite = true); true
                            }
                            else -> writePng(sourceFile,composite)
                        }
                        check(copied && sourceFile.isFile) { "无法保存截图底图" }
                    }
                    upgrading -> {
                        // 普通图片原地升级：它自己的 PNG 就是底图，原样复制，不动像素。
                        val legacy = File(directory,"$id.png")
                        check(legacy.isFile) { "原图已删除，请刷新" }
                        legacy.copyTo(sourceFile,overwrite = true)
                    }
                    else -> error("截图底图缺失，原工程已保留")
                }
            }
            check(revisionDir.isDirectory || revisionDir.mkdirs()) { "无法创建截图副本" }
            check(writePng(File(revisionDir,"composite.png"),composite)) { "截图编码失败" }
            val preview = thumbnailBitmap(composite)
            try { FileOutputStream(File(revisionDir,"thumbnail.webp")).use {
                check(preview.compress(Bitmap.CompressFormat.WEBP,80,it)) { "缩略图保存失败" } } }
            finally { if (preview !== composite) preview.recycle() }
            File(revisionDir,"document.json").writeText(ScreenshotDocumentCodec.encode(document),Charsets.UTF_8)
            val bytes = sourceFile.length() + File(revisionDir,"composite.png").length() +
                File(revisionDir,"thumbnail.webp").length() + File(revisionDir,"document.json").length()
            val entry = Entry(id,System.currentTimeMillis(),composite.width,composite.height,bytes,Kind.PROJECT,revision)
            synchronized(lock) {
                val old = readEntries()
                val current = old.firstOrNull { it.id == id }
                if (replaced != null) check(current != null && current.revision == (expectedRevision ?: replaced.revision)) {
                    "这张图片已被修改，请重新打开后再保存"
                }
                check(current != null || old.size < 100) { "截图剪贴板已满，请先删除不需要的截图" }
                budget(old,entry,replaceId)
                writeEntries(listOf(entry) + old.filterNot { it.id == id })
                committed = true
            }
            // Only after the index committed may the superseded files disappear.
            if (replaced != null && !upgrading) File(projectDir,replaced.revision.toString()).deleteRecursively()
            if (upgrading) { File(directory,"$id.png").delete(); File(directory,"$id.webp").delete() }
            entry
        } finally {
            if (!committed) {
                revisionDir.deleteRecursively()
                if (replaceId == null || upgrading) projectDir.deleteRecursively()
            }
        }
    }

    /** Crop of a project: the same canvas and objects, a new viewport, saved as a new record. */
    fun cropProject(id: String, left: Int, top: Int, right: Int, bottom: Int, composite: Bitmap): Entry {
        val document = document(id) ?: error("这个图片没有可编辑的工程")
        val viewport = document.viewport
        require(left >= 0 && top >= 0 && right <= viewport.width && bottom <= viewport.height &&
            right - left >= 2 && bottom - top >= 2) { "请拖动选择裁剪范围" }
        val cropped = Bitmap.createBitmap(composite,left,top,right-left,bottom-top)
        try {
            val moved = document.copy(viewport = ScreenshotDocRect(viewport.left+left,viewport.top+top,
                viewport.left+right,viewport.top+bottom))
            return saveProject(moved,cropped,copySourceFromId = id)
        } finally { if (cropped !== composite) cropped.recycle() }
    }

    fun delete(id: String) = synchronized(lock) {
        validateId(id)
        val old = readEntries()
        val entry = old.firstOrNull { it.id == id } ?: return@synchronized
        writeEntries(old.filterNot { it.id == id })
        file(id).delete(); thumbnail(id).delete()
        if (entry.kind == Kind.PROJECT) File(projects,id).deleteRecursively()
    }

    fun file(id: String): File {
        validateId(id)
        val entry = synchronized(lock) { readEntries().firstOrNull { it.id == id } }
        if (entry != null && entry.kind == Kind.PROJECT) return File(revisionDir(entry),"composite.png")
        return File(directory,"$id.png")
    }
    fun thumbnail(id: String): File {
        validateId(id)
        val entry = synchronized(lock) { readEntries().firstOrNull { it.id == id } }
        if (entry != null && entry.kind == Kind.PROJECT) return File(revisionDir(entry),"thumbnail.webp")
        return File(directory,"$id.webp")
    }
    private fun revisionDir(entry: Entry) = File(File(projects,entry.id),entry.revision.toString())
    fun uri(id: String): Uri { validateId(id); return Uri.parse("content://${context.packageName}.screenshots/$id") }
    fun copy(id: String) {
        check(file(id).isFile) { "截图文件不存在" }
        touchToFront(id)
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(context.contentResolver,"VoxFix 截图",uri(id)))
    }

    /** 重复复制已有图片时把它挪到列表最前（与文本剪贴板「最近复制置顶」一致）。 */
    private fun touchToFront(id: String) = synchronized(lock) {
        val old = readEntries()
        val index0 = old.indexOfFirst { it.id == id }
        if (index0 <= 0) return@synchronized
        val moved = old[index0].copy(createdAt = System.currentTimeMillis())
        writeEntries(listOf(moved) + old.filterIndexed { i,_ -> i != index0 })
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
    private fun writePng(target: File,image: Bitmap): Boolean {
        target.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        val done = FileOutputStream(target).use { out ->
            val ok = image.compress(Bitmap.CompressFormat.PNG,100,out)
            out.fd.sync(); ok
        }
        check(target.length() <= 16L*1024*1024) { "截图文件过大，请缩小选区" }
        return done
    }
    private fun thumbnailBitmap(image: Bitmap): Bitmap {
        val scale = minOf(1f,320f / maxOf(image.width,image.height))
        return Bitmap.createScaledBitmap(image,maxOf(1,(image.width*scale).toInt()),maxOf(1,(image.height*scale).toInt()),true)
    }
    companion object {
        private val lock = Any()
        fun validateId(id: String) { require(id.matches(Regex("[a-f0-9]{32}"))) { "无效的截图编号" } }
    }
}
