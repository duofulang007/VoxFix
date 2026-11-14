package local.voxfix.debug.update

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** 更新 APK 只经显式只读 URI 授权离开私有目录（仿 ScreenshotContentProvider）。 */
class UpdateApkProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val app = requireNotNull(context) as Context
        require(uri.authority == "${app.packageName}.updateapk")
        val segments = uri.pathSegments
        require(segments.size == 2 && segments[0] == "updates")
        val name = segments[1]
        // 只接受受限文件名，防路径穿越；目录固定为 cacheDir/updates。
        require(name.matches(Regex("[A-Za-z0-9._-]+")) && !name.contains(".."))
        val file = File(File(app.cacheDir, "updates"), name)
        if (!file.isFile) throw FileNotFoundException(name)
        return file
    }

    override fun getType(uri: Uri): String {
        file(uri)
        return "application/vnd.android.package-archive"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
