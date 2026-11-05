package local.voxfix.debug

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Publishes a finished recording.
 *
 * Snow Shot writes straight into a recording folder and can copy the file to the clipboard.
 * Android has no file clipboard entry, so the phone build inserts into `Movies/VoxFix` (visible in
 * the gallery) and falls back to the app-specific movies folder when MediaStore refuses.
 */
internal object ScreenRecordingLibrary {
    data class Saved(val uri: Uri?, val displayPath: String)

    fun save(context: Context, source: File): Saved? {
        if (!source.isFile || source.length() <= 0) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VoxFix")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = runCatching { resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            if (uri != null) {
                val copied = runCatching {
                    resolver.openOutputStream(uri).use { output ->
                        requireNotNull(output) { "无法写入相册" }
                        source.inputStream().use { it.copyTo(output) }
                    }
                }.isSuccess
                if (copied) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    runCatching { resolver.update(uri, values, null, null) }
                    source.delete()
                    return Saved(uri, "相册/VoxFix/${source.name}")
                }
                runCatching { resolver.delete(uri, null, null) }
            }
        }
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "VoxFix")
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val target = File(directory, source.name)
        val moved = runCatching { source.renameTo(target) || (source.copyTo(target, overwrite = true).length() > 0) }
            .getOrDefault(false)
        if (!moved) return null
        source.delete()
        return Saved(null, target.absolutePath)
    }

    fun open(context: Context, uri: Uri?, path: String?): Boolean = try {
        val intent = if (uri != null) {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(File(checkNotNull(path))), "video/mp4")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
