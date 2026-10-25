package local.voxfix.debug

import java.net.URI

internal object MemePayload {
    fun initialState(html: String): String {
        val marker = "window.__INITIAL_STATE__="
        val start = html.indexOf(marker)
        require(start >= 0) { "搜狗页面已变化，请稍后重试" }
        val end = html.indexOf("</script>", start)
        require(end > start) { "搜狗返回数据不完整" }
        val json = html.substring(start + marker.length, end).trim().removeSuffix(";").trim()
        require(json.startsWith("{") && json.endsWith("}")) { "搜狗返回数据无效" }
        return json
    }

    fun imageUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme == "https" && uri.host?.lowercase()?.endsWith(".sogoucdn.com") == true &&
            uri.userInfo == null && uri.port in listOf(-1, 443)
    } catch (_: Exception) { false }

    fun imageMime(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        if (bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) return "image/jpeg"
        if (bytes.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10))) return "image/png"
        val start = String(bytes, 0, 6, Charsets.US_ASCII)
        if (start == "GIF87a" || start == "GIF89a") return "image/gif"
        if (String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP") return "image/webp"
        return null
    }
}
