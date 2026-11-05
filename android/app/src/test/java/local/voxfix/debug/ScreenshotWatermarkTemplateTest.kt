package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/** `document.rs:resolve_watermark_template` — placeholder, nesting and timestamp contract. */
class ScreenshotWatermarkTemplateTest {
    private fun calendar(year: Int,month: Int,day: Int,hour: Int,minute: Int,second: Int): Long {
        val c=Calendar.getInstance(); c.clear()
        c.set(year,month,day,hour,minute,second)
        return c.timeInMillis
    }
    @Test fun emptyTemplateKeepsThePlainText() {
        assertEquals("水印",ScreenshotWatermarkTemplates.resolve("","水印",123L))
    }
    @Test fun textPlaceholderIsSubstituted() {
        assertEquals("abc",ScreenshotWatermarkTemplates.resolve("{text}","abc",0L))
        assertEquals("前abc后",ScreenshotWatermarkTemplates.resolve("前{text}后","abc",0L))
    }
    @Test fun unknownAndNestedPlaceholdersStayLiteral() {
        assertEquals("a{foo}b",ScreenshotWatermarkTemplates.resolve("a{foo}b","abc",123L))
        assertEquals("{{text}}",ScreenshotWatermarkTemplates.resolve("{{text}}","abc",123L))
    }
    @Test fun unclosedBraceAppendsTheRest() {
        assertEquals("abc{def",ScreenshotWatermarkTemplates.resolve("abc{def","t",123L))
        assertEquals("a}b",ScreenshotWatermarkTemplates.resolve("a}b","t",123L))
    }
    @Test fun timestampsExpandFromTheApplicationTime() {
        val appliedAt=calendar(2026,Calendar.NOVEMBER,5,14,3,9)
        assertEquals("2026-11-05 14:03:09",
            ScreenshotWatermarkTemplates.resolve("{YYYY}-{MM}-{DD} {HH}:{mm}:{ss}","t",appliedAt))
        // Upper/lower case tokens are distinct: mm is the minute, ss the second.
        assertEquals("03分09秒",ScreenshotWatermarkTemplates.resolve("{mm}分{ss}秒","t",appliedAt))
    }
    @Test fun withoutApplicationTimeTimestampsStayLiteral() {
        assertEquals("{yyyy}",ScreenshotWatermarkTemplates.resolve("{yyyy}","t",0L))
        assertEquals("{YYYY}",ScreenshotWatermarkTemplates.resolve("{YYYY}","t",-5L))
    }
    @Test fun watermarkModelResolvesAndNormalizesTemplate() {
        val mark=ScreenshotWatermark(text="截图",template="{text} {yyyy}",appliedAt=0L)
        // `{text}` resolves to the plain text; `{yyyy}` stays literal without an application time.
        assertEquals("截图 {yyyy}",mark.resolvedText())
        val trimmed=mark.copy(template="  {text}  ",appliedAt=-1L).normalized()
        assertEquals("{text}",trimmed.template)
        assertEquals(0L,trimmed.appliedAt)
    }
}
