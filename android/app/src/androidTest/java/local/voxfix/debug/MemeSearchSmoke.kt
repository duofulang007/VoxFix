package local.voxfix.debug

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

internal object MemeSearchSmoke {
    fun run(context: android.content.Context): String {
        val search = MemeSearch()
        try {
            val checks = JSONArray()
            var previous = emptySet<String>()
            for ((category, query, page) in listOf(Triple("精选", "", 1), Triple("萌宠", "", 1), Triple("萌宠", "", 2), Triple("精选", "收到", 1))) {
                val result = search.search(query, category, page)
                val items = result.getJSONArray("items")
                check(items.length() > 0) { "搜索没有返回图片：$category/$page" }
                val ids = (0 until items.length()).map { items.getJSONObject(it).getString("id") }.toSet()
                if (page == 2) check((ids - previous).isNotEmpty()) { "翻页没有返回新图片" }
                previous = ids
                val id = items.getJSONObject(0).getString("id")
                val thumb = search.image(id, true)
                val full = search.image(id)
                check(MemePayload.imageMime(thumb) != null && MemePayload.imageMime(full) != null)
                checks.put(JSONObject().put("category", category).put("query", query).put("page", page)
                    .put("items", items.length()).put("tags", result.getJSONArray("tags").length())
                    .put("thumbnailBytes", thumb.size).put("imageBytes", full.size))
            }
            val response = checkNotNull(search.route(Uri.parse("https://voxfix.local/meme/search?q=x&page=0")))
            check(response.statusCode == 502)
            check(search.route(Uri.parse("https://example.com/meme/search")) == null)
            val selected = search.search("收到", "精选", 1).getJSONArray("items").getJSONObject(0).getString("id")
            var submitted: android.view.inputmethod.InputContentInfo? = null
            val connection = object : android.view.inputmethod.BaseInputConnection(android.view.View(context), false) {
                override fun finishComposingText() = true
                override fun commitContent(content: android.view.inputmethod.InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean {
                    check(flags == android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION)
                    submitted = content
                    return true
                }
            }
            val info = android.view.inputmethod.EditorInfo().apply { contentMimeTypes = arrayOf("image/*") }
            val ready = java.util.concurrent.CountDownLatch(1)
            MemeInsertion.insert(context, search, selected, info, connection, valid = { true }, reply = { _, accepted -> check(accepted); ready.countDown() })
            check(ready.await(20, java.util.concurrent.TimeUnit.SECONDS)) { "隔离图片提交超时" }
            val content = checkNotNull(submitted)
            val stored = context.contentResolver.openInputStream(content.contentUri)?.use { it.readBytes() }
            check(stored?.contentEquals(search.image(selected)) == true) { "共享图片读取失败" }
            submitted = null
            MemeInsertion.insert(context, search, selected, info, connection, valid = { false }, reply = { _, _ -> error("换框取消后不应回调") })
            val drained = java.util.concurrent.CountDownLatch(1)
            search.worker.execute { android.os.Handler(android.os.Looper.getMainLooper()).post { drained.countDown() } }
            check(drained.await(20, java.util.concurrent.TimeUnit.SECONDS) && submitted == null) { "换框取消失败" }
            return JSONObject().put("status", "MEME_SEARCH_PASS").put("checks", checks)
                .put("isolatedContentSubmission", true).put("contentProviderRead", true).put("cancelledSubmissionBlocked", true).toString()
        } finally { search.close() }
    }
}
