package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class MemePayloadTest {
    @Test fun extractsDataWithoutExecutingScript() {
        assertEquals("{\"searchList\":{}}", MemePayload.initialState("<script>window.__INITIAL_STATE__={\"searchList\":{}};</script><script>evil()</script>"))
        assertThrows(IllegalArgumentException::class.java) { MemePayload.initialState("<h1>验证页面</h1>") }
        assertThrows(IllegalArgumentException::class.java) { MemePayload.initialState("window.__INITIAL_STATE__=alert(1)</script>") }
    }
    @Test fun acceptsOnlyHttpsSogouImageHosts() {
        assertTrue(MemePayload.imageUrl("https://i03piccdn.sogoucdn.com/a"))
        for (url in listOf("http://i03piccdn.sogoucdn.com/a", "https://sogoucdn.com.evil.org/a", "https://127.0.0.1/a", "file:///etc/passwd", "https://user@i03piccdn.sogoucdn.com/a", "https://i03piccdn.sogoucdn.com:8888/a")) assertFalse(url, MemePayload.imageUrl(url))
    }
    @Test fun rejectsHtmlAndPreservesAnimatedImageTypes() {
        assertNull(MemePayload.imageMime("<html>not an image</html>".toByteArray()))
        assertEquals("image/gif", MemePayload.imageMime("GIF89a000000000".toByteArray()))
        assertEquals("image/webp", MemePayload.imageMime("RIFF0000WEBP0000".toByteArray()))
    }
}
