package local.voxfix.debug

import android.content.res.AssetManager
import java.io.File

class TextNormalizer(assets: AssetManager, directory: File) {
    private var handle: Long
    init {
        System.loadLibrary("voxfix-polish")
        check(directory.isDirectory || directory.mkdirs())
        for (name in listOf("zh_itn_tagger.fst", "zh_itn_tagger_small.fst", "zh_itn_verbalizer.fst", "remove_interjections.fst")) {
            assets.open("text/$name").use { input -> File(directory, name).outputStream().use { input.copyTo(it) } }
        }
        handle = open(directory.absolutePath.toByteArray(Charsets.UTF_8))
        check(handle != 0L)
    }
    private external fun open(directory: ByteArray): Long
    private external fun apply(handle: Long, input: ByteArray, operation: Int): ByteArray
    private external fun close(handle: Long)
    fun removeInterjections(text: String) = apply(handle, text.toByteArray(Charsets.UTF_8), 2).toString(Charsets.UTF_8)
    fun format(text: String, canonical: Boolean = false): String = quantity.replace(text) { match ->
        val operation = if (canonical || match.groups[1]?.value !in listOf("双", "个", "次", "件", "份")) 1 else 0
        val value = apply(handle, match.value.toByteArray(Charsets.UTF_8), operation).toString(Charsets.UTF_8)
        value.ifEmpty { match.value }
    }
    fun release() { if (handle != 0L) { close(handle); handle = 0 } }
    companion object {
        private const val DIGITS = "零〇一二两三四五六七八九十百千万亿"
        private val quantity = Regex("[$DIGITS]+(?=(克|公斤|公里|千米|米|点|时|分|秒|年|月|日|号|元|个|双|次|岁|天|斤|升|件|份))|^[$DIGITS]+$")
    }
}
