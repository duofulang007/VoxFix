package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotFontFamiliesTest {
    @Test fun blankQueryReturnsEveryFamily() {
        assertEquals(ScreenshotFontFamilies.names,ScreenshotFontFamilies.matching(""))
        assertEquals(ScreenshotFontFamilies.names,ScreenshotFontFamilies.matching("   "))
    }

    @Test fun matchesWithoutCaseOrEdgeSpaces() {
        assertTrue("大小写与首尾空格应被忽略",
            ScreenshotFontFamilies.matching("  SANS  ").any { it=="sans-serif" })
        assertTrue("子串匹配失败",
            ScreenshotFontFamilies.matching("SERIF").any { it.contains("serif",ignoreCase=true) })
    }

    @Test fun unknownQueryReturnsEmpty() {
        assertTrue(ScreenshotFontFamilies.matching("font-no-such-family-094321").isEmpty())
    }

    @Test fun matchingNeverInventsFamilies() {
        val all=ScreenshotFontFamilies.names.toSet()
        for(query in listOf("sans","mono","a","")) {
            assertTrue("过滤结果出现了不存在的字体族：$query",
                ScreenshotFontFamilies.matching(query).all { it in all })
        }
    }

    @Test fun resolveRejectsControlCharactersAndOverlongNames() {
        assertEquals(null,ScreenshotFontFamilies.resolve(""))
        assertEquals(null,ScreenshotFontFamilies.resolve("bad\nname"))
        assertEquals(null,ScreenshotFontFamilies.resolve("x".repeat(41)))
        assertEquals("sans-serif",ScreenshotFontFamilies.resolve(" sans-serif "))
    }
}
