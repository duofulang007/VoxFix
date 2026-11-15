package local.voxfix.debug

import org.json.JSONObject
import java.io.File

/**
 * 键盘前端 localStorage 的镜像与一次性下发（备份恢复用）。
 *
 * WebView 的 localStorage 是压缩过的 leveldb，原生读不出来，所以前端保存时把同一份 JSON
 * 交过来落到 `files/web-mirror/`；恢复时把这些文件放回原处并写一个 `.restore` 标记，
 * 由键盘每次弹出时读取并交给前端合并。
 *
 * 2026-11-15 从 [VoxFixImeService] 抽出：服务本身已顶到体积预算，镜像职责与输入法生命周期
 * 无关，放独立文件更清楚；顺带把「标记判断」收成 [pending]，调用点不再手拼文件名。
 */
object WebMirror {
    /** 允许镜像的前端数据键：文本剪贴板+常用语、键盘 9/26 键与夜间、外观设置。 */
    val KEYS = setOf("snippets", "keyboard", "layout")

    /** 镜像目录；恢复标记是同名 + ".restore"。 */
    const val DIR = "web-mirror"

    private const val MAX_JSON = 512 * 1024

    fun pending(filesDir: File): Boolean = File(filesDir, "$DIR.restore").isFile

    /** 只收白名单键并限制体积：这是输入法进程，不接受任意键任意大小写文件。 */
    fun write(filesDir: File, key: String, json: String) {
        if (key !in KEYS || json.length > MAX_JSON) return
        val dir = File(filesDir, DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return
        runCatching { File(dir, "$key.json").writeText(json, Charsets.UTF_8) }
    }

    /** 读取并消耗 `.restore` 标记；无标记、标记损坏或镜像文件全缺时返回 null。 */
    fun consume(filesDir: File): String? {
        val marker = File(filesDir, "$DIR.restore")
        if (!marker.isFile) return null
        val text = runCatching { marker.readText(Charsets.UTF_8) }.getOrNull()
        marker.delete()
        if (text.isNullOrBlank()) return null
        val keys = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val payload = JSONObject()
        for (key in keys.keys()) {
            if (key !in KEYS) continue
            val source = File(File(filesDir, DIR), "$key.json")
            if (!source.isFile) continue
            runCatching { payload.put(key, JSONObject(source.readText(Charsets.UTF_8))) }
        }
        return if (payload.length() == 0) null else payload.toString()
    }
}
