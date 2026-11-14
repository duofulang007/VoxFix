package local.voxfix.debug.update

import org.json.JSONObject

/**
 * 更新清单的纯数据与解析；不碰 android 类，JVM 可直接单测。
 * 清单由各源（GitHub Releases / 镜像）托管同一份 manifest.json，镜像只是 URL 前缀不同。
 */
object UpdateCore {
    data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val date: String,
        val changelog: String,
        val apkSizeBytes: Long,
        val apkSha256: String,
        val apkUrls: List<String>
    )

    /** 坏 JSON / 缺关键字段 / 非法 sha256 / 无有效下载地址 → null；宁可「无更新」也不拿脏数据安装。 */
    fun parse(text: String): Manifest? = runCatching {
        val json = JSONObject(text)
        val manifest = Manifest(
            versionCode = json.getInt("versionCode"),
            versionName = json.optString("versionName"),
            date = json.optString("date"),
            changelog = json.optString("changelog"),
            apkSizeBytes = json.optLong("apkSizeBytes", 0L),
            apkSha256 = json.optString("apkSha256").trim().lowercase(),
            apkUrls = json.optJSONArray("apkUrls")?.let { urls ->
                // 正式源必须 https；loopback http 仅 debug 构建联调放行（与 DownloadEngine 门禁一致）。
                (0 until urls.length()).mapNotNull { urls.optString(it).takeIf { u -> isDownloadUrl(u) } }
            } ?: emptyList()
        )
        if (manifest.versionCode <= 0 || !isSha256(manifest.apkSha256) || manifest.apkUrls.isEmpty()) null else manifest
    }.getOrNull()

    private fun isDownloadUrl(url: String): Boolean =
        url.startsWith("https://") ||
            url.startsWith("http://127.0.0.1:") ||
            url.startsWith("http://localhost:")

    /** 只比 versionCode 整数；versionName 仅展示，避免语义化版本比较的歧义。 */
    fun isNewer(manifestCode: Int, currentCode: Int): Boolean = manifestCode > currentCode

    fun isSha256(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
}
