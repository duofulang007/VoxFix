package local.voxfix.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject

/** Private images stay behind the keyboard's existing local-only WebView origin. */
internal class ScreenshotKeyboard(context: Context) {
    val store = ScreenshotStore(context)
    fun list(): JSONObject = JSONObject().put("items", JSONArray().apply {
        store.entries().forEach { put(JSONObject().put("id",it.id).put("width",it.width).put("height",it.height).put("createdAt",it.createdAt)) }
    })
    fun route(uri: Uri): WebResourceResponse? {
        if (uri.scheme != "https" || uri.host != "voxfix.local" || !uri.path.orEmpty().startsWith("/screenshots/")) return null
        return try {
            val parts = uri.pathSegments
            require(parts.size == 3 && parts[2] in listOf("thumb","image"))
            val id = parts[1]; ScreenshotStore.validateId(id)
            check(store.entries().any { it.id == id })
            val thumb = parts[2] == "thumb"
            WebResourceResponse(if (thumb) "image/webp" else "image/png",null,200,"OK",mapOf("Cache-Control" to "no-store"),
                (if (thumb) store.thumbnail(id) else store.file(id)).inputStream())
        } catch (_: Exception) { WebResourceResponse("text/plain","UTF-8",404,"Not Found",emptyMap(),java.io.ByteArrayInputStream(byteArrayOf())) }
    }
    fun crop(id: String, left: Int, top: Int, right: Int, bottom: Int): JSONObject {
        val entry = store.entries().firstOrNull { it.id == id } ?: error("截图已删除，请刷新")
        val region = Rect(left,top,right,bottom)
        require(left >= 0 && top >= 0 && right <= entry.width && bottom <= entry.height && region.width() >= 2 && region.height() >= 2) { "请拖动选择裁剪范围" }
        val source = BitmapFactory.decodeFile(store.file(id).path) ?: error("截图无法读取")
        try {
            val cropped = Bitmap.createBitmap(source,left,top,region.width(),region.height())
            try { store.save(cropped) } finally { if (cropped !== source) cropped.recycle() }
        } finally { source.recycle() }
        return list()
    }
}
