package local.voxfix.debug

import android.graphics.Bitmap
import android.graphics.Rect

/** Unsaved working document. OCR, translation and editing consume crops/text without disk I/O. */
class ScreenshotSession(source: Bitmap) : AutoCloseable {
    private var bitmap: Bitmap? = source
    val image: Bitmap get() = checkNotNull(bitmap) { "截图会话已结束" }
    var text: String = ""
        private set
    var region: Rect? = null
        private set
    fun select(requested: Rect): Rect {
        val source = image
        val safe = Rect(requested)
        require(safe.intersect(0,0,source.width,source.height) && safe.width() >= 2 && safe.height() >= 2) { "请选择有效截图区域" }
        region = Rect(safe)
        return safe
    }
    fun crop(requested: Rect): Bitmap {
        val source = image
        val safe = select(requested)
        val crop = Bitmap.createBitmap(source,safe.left,safe.top,safe.width(),safe.height())
        // Async consumers own their bitmap even when the selected region is the full image.
        return if (crop === source) source.copy(Bitmap.Config.ARGB_8888,false) ?: error("截图复制失败") else crop
    }
    fun updateText(value: String) { image; text = value }
    override fun close() { bitmap?.recycle(); bitmap = null; text = ""; region = null }
}
