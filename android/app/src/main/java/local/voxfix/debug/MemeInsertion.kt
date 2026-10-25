package local.voxfix.debug

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import java.io.File

internal object MemeInsertion {
    fun insert(context: Context, search: MemeSearch, id: String, info: EditorInfo, connection: InputConnection,
               valid: () -> Boolean, reply: (String, Boolean) -> Unit) {
        val mimeTypes = info.contentMimeTypes?.copyOf().orEmpty()
        if (mimeTypes.isEmpty()) { reply("当前应用不支持从输入法插入图片", false); return }
        search.worker.execute {
            val result = runCatching {
                val bytes = search.image(id)
                val mime = requireNotNull(MemePayload.imageMime(bytes))
                require(mimeTypes.any { ClipDescription.compareMimeTypes(mime, it) }) { "当前应用不支持这种图片格式" }
                val extension = when(mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"; else -> "webp" }
                val directory = File(context.cacheDir, "memes").apply { mkdirs() }
                // Only this module's expired send copies are removed; keep recent URI grants readable.
                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
                val target = File(directory, "$id.$extension")
                if (!target.exists()) {
                    val pending = File(directory, "${target.name}.tmp")
                    try { pending.writeBytes(bytes); check(pending.renameTo(target)) }
                    finally { pending.delete() }
                }
                InputContentInfo(Uri.parse("content://${context.packageName}.memes/${target.name}"), ClipDescription("表情包", arrayOf(mime)), null)
            }
            Handler(Looper.getMainLooper()).post {
                if (!valid()) return@post
                val content = result.getOrNull()
                if (content == null) { reply(result.exceptionOrNull()?.message ?: "图片下载失败，请重试", false); return@post }
                val accepted = try {
                    connection.finishComposingText()
                    connection.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
                } catch (_: Exception) { false }
                reply(if (accepted) "已交给聊天应用，请确认发送" else "当前应用未接收图片", accepted)
            }
        }
    }
}
