package local.voxfix.debug.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UpdateCore 纯逻辑回归：清单解析的字段校验、坏输入拒绝、versionCode 比较。 */
class UpdateCoreTest {

    private val valid = """
        {
          "versionCode": 5,
          "versionName": "0.4.1",
          "date": "2026-11-20",
          "changelog": "修复若干问题",
          "apkSizeBytes": 104857600,
          "apkSha256": "cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93",
          "apkUrls": ["https://github.com/o/VoxFix/releases/download/v0.4.1/VoxFix-0.4.1-5-20261120-a1b2c3d.apk", "https://mirror.example.com/voxfix/app.apk"]
        }
    """.trimIndent()

    @Test
    fun `parses a valid manifest`() {
        val m = UpdateCore.parse(valid)
        assertNotNull(m)
        m!!
        assertEquals(5, m.versionCode)
        assertEquals("0.4.1", m.versionName)
        assertEquals(104857600L, m.apkSizeBytes)
        assertEquals(2, m.apkUrls.size)
    }

    @Test
    fun `rejects broken json missing fields bad sha and http urls`() {
        assertNull(UpdateCore.parse("not json at all"))
        assertNull(UpdateCore.parse("{}"))
        assertNull(UpdateCore.parse("""{"versionCode":0}"""))
        assertNull(UpdateCore.parse("""{"versionCode":5}"""))
        assertNull(UpdateCore.parse(valid.replace("apkSha256", "apkSha265")))
        // sha256 非法（长度/字符）
        assertNull(UpdateCore.parse(valid.replace("cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93", "deadbeef")))
        // 全是 http 的 apkUrls 视为无有效地址
        assertNull(UpdateCore.parse(valid.replace("https://mirror.example.com/voxfix/app.apk", "http://mirror.example.com/voxfix/app.apk")
            .replace("https://github.com/o/VoxFix/releases/download/v0.4.1/VoxFix-0.4.1-5-20261120-a1b2c3d.apk", "http://github.com/x/y.apk")))
    }

    @Test
    fun `insecure http urls are only accepted when explicitly allowed`() {
        // 正式源（allowInsecureHttp=false）拒绝一切 http。
        assertNull(UpdateCore.parse(valid.replace("https://mirror.example.com/voxfix/app.apk", "http://mirror.example.com/voxfix/app.apk")
            .replace("https://github.com/o/VoxFix/releases/download/v0.4.1/VoxFix-0.4.1-5-20261120-a1b2c3d.apk", "http://github.com/x/y.apk")))
        // debug 联调（allowInsecureHttp=true）放行局域网 http。
        val lan = UpdateCore.parse(
            valid.replace("https://mirror.example.com/voxfix/app.apk", "http://192.168.1.4:18080/voxfix-test.apk"),
            allowInsecureHttp = true
        )
        assertNotNull(lan)
        assertEquals("http://192.168.1.4:18080/voxfix-test.apk", lan!!.apkUrls[1])
    }

    @Test
    fun `compares only versionCode`() {
        assertTrue(UpdateCore.isNewer(5, 4))
        assertFalse(UpdateCore.isNewer(4, 4))
        assertFalse(UpdateCore.isNewer(3, 4))
    }

    @Test
    fun `sha256 gate accepts lowercase hex of length 64`() {
        assertTrue(UpdateCore.isSha256("cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93"))
        assertFalse(UpdateCore.isSha256("deadbeef"))
        assertFalse(UpdateCore.isSha256("cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a9".padEnd(63, '0')))
        // 'g' 不在 0-9a-f 内，64 个 g 是非法 sha256（注意 "c"*64 反而合法）。
        assertFalse(UpdateCore.isSha256("g".repeat(64)))
    }
}
