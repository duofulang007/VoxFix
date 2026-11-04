package local.voxfix.debug

import android.content.Context
import android.os.StatFs
import java.io.File

/** On-disk location of the optional translation weights. The APK never ships them. */
class TranslateModelStore(context: Context) {
    private val directory = File(context.filesDir, "translate")
    val model = File(directory, FILE)
    val partial = File(directory, "$FILE.part")

    fun ready(): Boolean = model.isFile && model.length() == BYTES

    fun installedBytes(): Long = if (model.isFile) model.length() else 0L

    fun freeBytes(): Long = runCatching { StatFs((if (directory.isDirectory) directory else directory.parentFile!!).absolutePath).availableBytes }.getOrDefault(0L)

    /** Only touches files this class owns; a half-downloaded .part is removed too. */
    fun delete(): Boolean {
        var removed = false
        for (file in listOf(model, partial)) {
            if (file.exists()) removed = file.delete() || removed
        }
        return removed || !model.exists()
    }

    fun ensureDirectory() {
        if (!directory.isDirectory) directory.mkdirs()
    }

    companion object {
        const val FILE = "hy-mt2-1.8b-1.25bit.gguf"
        const val BYTES = 461_860_800L
        const val SHA256 = "cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93"
        const val MIN_FREE_BYTES = 620L * 1024 * 1024
    }
}
