package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class ScreenshotParagraphsTest {
    @Test fun columnsReadDownBeforeAcrossAndDoNotMerge() {
        val a=ScreenshotTextBlock(10,10,200,30,"first wrapped")
        val b=ScreenshotTextBlock(10,36,200,56,"line")
        val c=ScreenshotTextBlock(280,10,470,30,"right column")
        val d=ScreenshotTextBlock(280,36,470,56,"next")
        assertEquals(listOf(a,b,c,d),ScreenshotParagraphs.readingOrder(listOf(c,a,d,b)))
        assertEquals(listOf("first wrapped line","right column next"),ScreenshotParagraphs.merge(listOf(c,a,d,b)).map { it.text })
    }
    @Test fun headingAndChineseWrapKeepTextAndParagraphBoundaries() {
        val blocks=listOf(ScreenshotTextBlock(0,0,500,40,"完整标题"),
            ScreenshotTextBlock(0,70,220,90,"第一行"),ScreenshotTextBlock(0,96,220,116,"续行。"),
            ScreenshotTextBlock(0,160,220,180,"另一段"))
        assertEquals(listOf("完整标题","第一行续行。","另一段"),ScreenshotParagraphs.merge(blocks).map { it.text })
    }

    @Test fun crossColumnHeadlineReadsBeforeBothColumns() {
        val head=ScreenshotTextBlock(0,0,500,40,"跨栏标题")
        val l1=ScreenshotTextBlock(0,60,220,80,"左栏第一行")
        val l2=ScreenshotTextBlock(0,86,220,106,"左栏第二行")
        val r1=ScreenshotTextBlock(280,60,500,80,"右栏第一行")
        val r2=ScreenshotTextBlock(280,86,500,106,"右栏第二行")
        assertEquals(listOf(head,l1,l2,r1,r2),ScreenshotParagraphs.readingOrder(listOf(r1,l1,head,r2,l2)))
    }

    @Test fun centredNarrowHeadlineStillReadsFirst() {
        // A headline narrower than the page must not be filed into the right column by its centre.
        val head=ScreenshotTextBlock(200,0,300,20,"简报")
        val l1=ScreenshotTextBlock(20,40,180,60,"左栏第一行")
        val l2=ScreenshotTextBlock(20,66,180,86,"左栏第二行")
        val r1=ScreenshotTextBlock(320,40,480,60,"右栏第一行")
        val r2=ScreenshotTextBlock(320,66,480,86,"右栏第二行")
        assertEquals(listOf(head,l1,l2,r1,r2),ScreenshotParagraphs.readingOrder(listOf(l2,head,r1,l1,r2)))
    }

    @Test fun threeColumnsReadLeftToRight() {
        val a=ScreenshotTextBlock(0,10,100,30,"左")
        val b=ScreenshotTextBlock(150,10,250,30,"中")
        val c=ScreenshotTextBlock(300,10,400,30,"右")
        assertEquals(listOf(a,b,c),ScreenshotParagraphs.readingOrder(listOf(c,a,b)))
    }
    @Test fun historyDropsRedoOnNewEditAndBoundsMemory() {
        // The history stores whole blocks so a moved line undoes together with its text.
        fun block(text: String, left: Int = 0) = ScreenshotTextBlock(left, 0, 100, 30, text)
        val history=ScreenshotEditHistory(listOf(block("a"),block("b")))
        history.record(listOf(block("a+"),block("b")))
        assertEquals(listOf(block("a"),block("b")),history.undo())
        assertEquals(listOf(block("a+"),block("b")),history.redo())
        history.undo(); history.record(listOf(block("new"),block("b")))
        assertNull(history.redo())
        repeat(80) { history.record(listOf(block("$it",it))) }
        var count=0
        while(history.undo()!=null) count++
        assertEquals(49,count)
    }
    @Test fun resumeRejectsWrongOffsetsAndOversizedRanges() {
        assertTrue(TranslateDownloadRange.valid("bytes 10-99/100",10,100))
        for (value in listOf(null,"bytes 0-99/100","bytes 10-100/100","bytes 10-99/*","bytes 999999999999999999999-99/100"))
            assertFalse(TranslateDownloadRange.valid(value,10,100))
    }
}
