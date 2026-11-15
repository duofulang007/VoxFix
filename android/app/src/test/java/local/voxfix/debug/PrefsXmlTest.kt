package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 备份里的 SharedPreferences XML 解析：类型必须原样还原，坏文件要被调用方跳过。
 * 恢复改为「运行中直接写回」以后，这里一旦解析错类型，偏好会被写成错误的值。
 */
class PrefsXmlTest {

    private fun temp(text: String): File =
        File.createTempFile("voxfix-prefs", ".xml").apply { writeText(text, Charsets.UTF_8) }

    @Test
    fun `parses every value type SharedPreferences writes`() {
        val file = temp(
            """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="text">a &amp; b</string>
                <int name="count" value="42" />
                <long name="stamp" value="1731000000000" />
                <float name="ratio" value="0.5" />
                <boolean name="enabled" value="true" />
                <set name="words"><string>你好</string><string>世界</string></set>
            </map>
            """.trimIndent()
        )
        try {
            val values = PrefsXml.parse(file)
            assertEquals("a & b", values["text"])
            assertEquals(42, values["count"])
            assertEquals(1_731_000_000_000L, values["stamp"])
            assertEquals(0.5f, values["ratio"])
            assertEquals(true, values["enabled"])
            assertEquals(setOf("你好", "世界"), values["words"])
        } finally {
            file.delete()
        }
    }

    @Test
    fun `keeps empty strings and unknown nodes are ignored`() {
        val file = temp(
            """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="empty"></string>
                <unknown name="ignored" value="1" />
            </map>
            """.trimIndent()
        )
        try {
            val values = PrefsXml.parse(file)
            assertEquals("", values["empty"])
            assertTrue(values.containsKey("ignored").not())
            assertEquals(1, values.size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `malformed xml fails instead of returning half a map`() {
        val file = temp("<map><string name=\"x\">broken")
        try {
            assertThrows(Exception::class.java) { PrefsXml.parse(file) }
        } finally {
            file.delete()
        }
    }
}
