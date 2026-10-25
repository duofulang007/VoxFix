package local.voxfix.debug

import android.net.Uri
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

internal class MemeSearch {
    private data class Image(val url: String, val thumb: String)
    private val images = object : LinkedHashMap<String, Image>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Image>?) = size > 300
    }
    private val cache = object : android.util.LruCache<String, ByteArray>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    val worker = Executors.newSingleThreadExecutor()

    fun route(uri: Uri): WebResourceResponse? {
        if (uri.scheme != "https" || uri.host != "voxfix.local" || !uri.path.orEmpty().startsWith("/meme/")) return null
        return try {
            if (uri.path == "/meme/search") {
                val json = search(uri.getQueryParameter("q").orEmpty(), uri.getQueryParameter("category").orEmpty(), uri.getQueryParameter("page")?.toIntOrNull() ?: 1)
                response("application/json", json.toString().toByteArray())
            } else if (uri.path.orEmpty().startsWith("/meme/image/")) {
                val bytes = image(uri.lastPathSegment.orEmpty(), uri.getQueryParameter("thumb") == "1")
                response(requireNotNull(MemePayload.imageMime(bytes)), bytes)
            } else response("application/json", "{}".toByteArray(), 404)
        } catch (_: Exception) {
            response("application/json", JSONObject().put("error", "搜索或图片加载失败，请检查网络后重试").toString().toByteArray(), 502)
        }
    }

    fun search(query: String, category: String, page: Int): JSONObject {
        require(query.length <= 80 && page in 1..5)
        require(category in listOf("", "精选", "萌宠", "沙雕", "打工", "开心", "无语", "收到", "动图"))
        val words = listOf(category.takeUnless { it == "精选" }.orEmpty(), query.trim(), "表情包").filter { it.isNotBlank() }.joinToString(" ")
        val url = "https://pic.sogou.com/pics?query=${URLEncoder.encode(words, "UTF-8")}&start=${(page - 1) * 48}&tagQSign=${URLEncoder.encode("表情包,5e604ff6", "UTF-8")}"
        // The old JSON endpoint returns forbid; parse only JSON embedded in the public results page.
        val root = JSONObject(MemePayload.initialState(String(download(url, false), Charsets.UTF_8)))
            .getJSONObject("searchList")
        val rows = root.getJSONArray("searchList")
        val result = JSONArray()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val full = row.optString("locImageLink")
            if (!MemePayload.imageUrl(full)) continue
            val thumb = row.optString("thumbUrl").takeIf(MemePayload::imageUrl) ?: full
            val id = MessageDigest.getInstance("SHA-256").digest(full.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
            synchronized(images) { images[id] = Image(full, thumb) }
            result.put(JSONObject().put("id", id).put("title", row.optString("title", "表情包").take(120)))
        }
        val tags = JSONArray()
        val sourceTags = root.optJSONArray("tagList") ?: JSONArray()
        for (index in 0 until sourceTags.length()) {
            val value = sourceTags.optJSONObject(index)?.optString("value").orEmpty()
            if (value.isNotBlank() && value.length <= 30 && value != "表情包") tags.put(value)
        }
        return JSONObject().put("items", result).put("tags", tags).put("more", rows.length() >= 48 && page < 5)
    }

    fun image(id: String, thumb: Boolean = false): ByteArray {
        require(id.matches(Regex("[a-f0-9]{24}")))
        val item = synchronized(images) { images[id] } ?: error("图片已过期，请重新搜索")
        val key = id + if (thumb) "-thumb" else "-full"
        cache.get(key)?.let { return it }
        val bytes = download(if (thumb) item.thumb else item.url, true)
        require(MemePayload.imageMime(bytes) != null) { "不支持的图片格式" }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) { "图片尺寸过大或无效" }
        cache.put(key, bytes)
        return bytes
    }

    private fun download(address: String, image: Boolean): ByteArray {
        var current = address
        repeat(4) {
            require(if (image) MemePayload.imageUrl(current) else URL(current).let { it.protocol == "https" && it.host == "pic.sogou.com" })
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8000; connection.readTimeout = 10000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.setRequestProperty("Referer", "https://pic.sogou.com/")
                val code = connection.responseCode
                if (code in 300..399) {
                    current = URL(URL(current), connection.getHeaderField("Location") ?: error("无效跳转")).toString()
                } else {
                    require(code == 200)
                    val max = if (image) 8 * 1024 * 1024 else 2 * 1024 * 1024
                    require(connection.contentLengthLong <= max)
                    return connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= max)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }
            } finally { connection.disconnect() }
        }
        error("跳转次数过多")
    }

    private fun response(mime: String, bytes: ByteArray, status: Int = 200) = WebResourceResponse(
        mime, if (mime == "application/json") "UTF-8" else null, status, if (status == 200) "OK" else "Unavailable",
        mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), ByteArrayInputStream(bytes))

    fun close() { worker.shutdownNow(); cache.evictAll(); synchronized(images) { images.clear() } }
}
