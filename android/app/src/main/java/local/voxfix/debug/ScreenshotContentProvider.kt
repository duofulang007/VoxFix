package local.voxfix.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

/** Images leave private storage only through an explicit, temporary URI read grant. */
class ScreenshotContentProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): java.io.File {
        require(uri.authority == "${requireNotNull(context).packageName}.screenshots")
        if(uri.pathSegments.size==2 && uri.pathSegments[0]=="exports") {
            val name=uri.pathSegments[1]
            require(name.matches(Regex("[a-f0-9]{32}\\.png")))
            return java.io.File(requireNotNull(context).cacheDir,"screenshot-exports/$name")
        }
        require(uri.pathSegments.size == 1)
        return ScreenshotStore(requireNotNull(context)).file(uri.lastPathSegment.orEmpty())
    }
    override fun getType(uri: Uri): String { file(uri); return "image/png" }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val image = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) { OpenableColumns.DISPLAY_NAME -> image.name; OpenableColumns.SIZE -> image.length(); else -> null } }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
