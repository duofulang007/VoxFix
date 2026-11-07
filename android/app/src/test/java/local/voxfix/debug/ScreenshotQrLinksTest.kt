package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 识码结果的链接判定，对照上游 `ScreenshotQrController::webUrl`（固定提交）。 */
class ScreenshotQrLinksTest {
    @Test fun plainHttpUrlsPass() {
        assertEquals("https://example.com", ScreenshotQrLinks.webUrl("https://example.com"))
        assertEquals("http://example.com/a/b?q=1#f", ScreenshotQrLinks.webUrl(" http://example.com/a/b?q=1#f "))
        assertEquals("HTTPS://例子.测试/路径", ScreenshotQrLinks.webUrl("HTTPS://例子.测试/路径"))
    }

    /** 夹在句子里的链接、带空白的多段文本都不算：原版先 trim 再逐字符排除空白。 */
    @Test fun proseWithSpacesIsNotAUrl() {
        assertNull(ScreenshotQrLinks.webUrl("访问 https://example.com"))
        assertNull(ScreenshotQrLinks.webUrl("https://example.com 复制"))
        assertNull(ScreenshotQrLinks.webUrl(""))
    }

    /** scheme 只认 http／https，其它一律不给打开入口。 */
    @Test fun otherSchemesAreRejected() {
        assertNull(ScreenshotQrLinks.webUrl("ftp://example.com"))
        assertNull(ScreenshotQrLinks.webUrl("file:///sdcard/a.png"))
        assertNull(ScreenshotQrLinks.webUrl("mailto:a@b.com"))
        assertNull(ScreenshotQrLinks.webUrl("example.com"))
    }

    /** host 为空（只有 scheme 或只有路径）不成立。 */
    @Test fun emptyHostIsRejected() {
        assertNull(ScreenshotQrLinks.webUrl("https://"))
        assertNull(ScreenshotQrLinks.webUrl("https:///path"))
        assertNull(ScreenshotQrLinks.webUrl("http:///"))
    }
}
