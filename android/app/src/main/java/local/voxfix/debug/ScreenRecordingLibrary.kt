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
 * Android has no file clipboard entry, so the phone build inserts into `Movies/VoxFix` (MP4) or
 * `Pictures/VoxFix` (GIF / APNG / WebP) and falls back to the app-specific folder when MediaStore
 * refuses.
 */
internal object ScreenRecordingLibrary {
    data class Saved(val uri: Uri?, val displayPath: String)

    private data class Target(val video: Boolean, val mime: String, val directory: String)

    private fun targetOf(file: File): Target = when (file.extension.lowercase()) {
        "gif" -> Target(false, "image/gif", Environment.DIRECTORY_PICTURES)
        "apng" -> Target(false, "image/apng", Environment.DIRECTORY_PICTURES)
        "webp" -> Target(false, "image/webp", Environment.DIRECTORY_PICTURES)
        else -> Target(true, "video/mp4", Environment.DIRECTORY_MOVIES)
    }

    private fun mimeOf(file: File): String = targetOf(file).mime

    fun save(context: Context, source: File): Saved? {
        if (!source.isFile || source.length() <= 0) return null
        val target = targetOf(source)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = if (target.video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, target.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${target.directory}/VoxFix")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
            if (uri != null) {
                val copied = runCatching {
                    resolver.openOutputStream(uri).use { output ->
                        requireNotNull(output) { "无法写入相册" }
                        source.inputStream().use { it.copyTo(output) }
                    }
                }.isSuccess
                if (copied) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    runCatching { resolver.update(uri, values, null, null) }
                    source.delete()
                    return Saved(uri, "相册/VoxFix/${source.name}")
                }
                runCatching { resolver.delete(uri, null, null) }
            }
        }
        val root = if (target.video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val directory = File(context.getExternalFilesDir(root) ?: context.filesDir, "VoxFix")
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val destination = File(directory, source.name)
        val moved = runCatching { source.renameTo(destination) || (source.copyTo(destination, overwrite = true).length() > 0) }
            .getOrDefault(false)
        if (!moved) return null
        source.delete()
        return Saved(null, destination.absolutePath)
    }

    fun open(context: Context, uri: Uri?, path: String?): Boolean = try {
        val file = path?.let { File(it) }
        val mime = file?.let { mimeOf(it) } ?: "video/mp4"
        val intent = if (uri != null) {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(checkNotNull(file)), mime)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Upstream "open recording folder": Android has no folder browser.
     *
     * Viewing the MediaStore video collection resolved to a chooser of video players on the K60
     * (Tencent Video, NetEase Music, …), which is not a folder view at all. Open the system gallery
     * first — that is where `Movies/VoxFix` and `Pictures/VoxFix` show up — and keep the collection
     * view as a fallback.
     */
    fun openFolder(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // No resolveActivity() gate here: Android 11+ package visibility hides the gallery and
            // would silently push every device onto the player-chooser fallback.
            val gallery = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(gallery) }.isSuccess) return true
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
