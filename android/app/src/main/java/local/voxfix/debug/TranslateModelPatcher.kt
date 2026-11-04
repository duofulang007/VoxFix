package local.voxfix.debug

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/** The official 1.25-bit GGUF stores tensors as the pre-rename STQ_0 type id (42). Upstream has
 *  since assigned 42 to Q2_0 and moved STQ1_0 to 43, so the pinned file must be relabelled before
 *  llama.cpp can read it. Only 224 four-byte fields change; tensor data is untouched.
 *
 *  Offsets are generated from the SHA-pinned file (tools/prepare_translate_model.py) and shipped
 *  as 896 bytes, which avoids parsing the 120k-token vocabulary on the phone. */
object TranslateModelPatcher {
    private const val LEGACY = 42
    private const val CURRENT = 43
    private const val OFFSETS = "translate/stq-type-offsets.bin"

    /** Idempotent: returns true if this call rewrote anything. */
    fun ensure(context: Context, file: File): Boolean {
        if (!file.isFile) error("模型文件不存在")
        val offsets = context.assets.open(OFFSETS).use { it.readBytes() }
        require(offsets.size % 4 == 0) { "偏移表损坏" }
        var changed = false
        RandomAccessFile(file, "rw").use { handle ->
            var index = 0
            while (index < offsets.size) {
                val offset = (offsets[index].toLong() and 0xff) or
                    ((offsets[index + 1].toLong() and 0xff) shl 8) or
                    ((offsets[index + 2].toLong() and 0xff) shl 16) or
                    ((offsets[index + 3].toLong() and 0xff) shl 24)
                handle.seek(offset)
                val current = readInt(handle)
                when (current) {
                    CURRENT -> {}
                    LEGACY -> {
                        handle.seek(offset)
                        writeInt(handle, CURRENT)
                        changed = true
                    }
                    else -> error("模型张量类型异常：$current @ $offset")
                }
                index += 4
            }
        }
        if (changed) android.util.Log.i("VoxFix", "TRANSLATE_MODEL_RELABELLED tensors=${offsets.size / 4}")
        return changed
    }

    private fun readInt(handle: RandomAccessFile): Int {
        val bytes = ByteArray(4)
        handle.readFully(bytes)
        return (bytes[0].toInt() and 0xff) or
            ((bytes[1].toInt() and 0xff) shl 8) or
            ((bytes[2].toInt() and 0xff) shl 16) or
            ((bytes[3].toInt() and 0xff) shl 24)
    }

    private fun writeInt(handle: RandomAccessFile, value: Int) {
        handle.write(byteArrayOf(
            (value and 0xff).toByte(),
            ((value ushr 8) and 0xff).toByte(),
            ((value ushr 16) and 0xff).toByte(),
            ((value ushr 24) and 0xff).toByte()
        ))
    }
}
