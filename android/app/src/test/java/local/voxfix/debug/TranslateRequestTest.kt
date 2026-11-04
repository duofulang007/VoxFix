package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateRequestTest {
    @Test fun streamingUtf8WaitsForWholeCharacters() {
        val text="中😀文"
        val bytes=text.toByteArray(Charsets.UTF_8)
        for (count in 0..bytes.size) {
            val partial=TranslateRequest.decodePartial(bytes.copyOf(count))
            assertTrue(text.startsWith(partial))
            assertFalse(partial.contains('\uFFFD'))
        }
        assertEquals(text,TranslateRequest.decodePartial(bytes))
    }
    @Test fun languagePreferenceFallsBack() {
        assertEquals(TranslateLanguage.JA,TranslateLanguage.fromPreference("ja",TranslateLanguage.EN))
        assertEquals(TranslateLanguage.EN,TranslateLanguage.fromPreference(null,TranslateLanguage.EN))
        assertEquals(TranslateLanguage.EN,TranslateLanguage.fromPreference("fr",TranslateLanguage.EN))
    }

    @Test fun autoKeepsChosenTargetAndSwapIsUseful() {
        assertEquals(TranslatePair(TranslateLanguage.AUTO,TranslateLanguage.EN),
            TranslatePair(TranslateLanguage.AUTO,TranslateLanguage.EN).normalized())
        assertEquals(TranslatePair(TranslateLanguage.ZH,TranslateLanguage.EN),
            TranslatePair(TranslateLanguage.AUTO,TranslateLanguage.ZH).swapped())
        assertEquals(TranslatePair(TranslateLanguage.EN,TranslateLanguage.ZH),
            TranslatePair(TranslateLanguage.ZH,TranslateLanguage.EN).swapped())
    }

    @Test fun joinedBlocksKeepLineBreaksAndSkipBlanks() {
        val blocks = listOf(
            ScreenshotTextBlock(0,0,10,10,"first"),
            ScreenshotTextBlock(0,10,10,20,"  "),
            ScreenshotTextBlock(0,20,10,30,"second")
        )
        assertEquals("first\nsecond",TranslateRequest.joinBlocks(blocks))
    }

    @Test fun chunkRespectsLimitAndPreservesText() {
        val text = "a".repeat(10)
        val chunks = TranslateRequest.chunk(text,4)
        assertEquals(listOf("aaaa","aaaa","aa"),chunks)
        assertEquals(text,chunks.joinToString(""))
    }

    @Test fun chunkNeverSplitsASurrogatePair() {
        val text = "😀😀😀"
        val chunks = TranslateRequest.chunk(text,3)
        assertEquals(text,chunks.joinToString(""))
        for (chunk in chunks) {
            assertFalse(chunk.first().isLowSurrogate())
            assertFalse(chunk.last().isHighSurrogate())
        }
    }

    @Test fun chunkPrefersSentenceEnds() {
        val chunks = TranslateRequest.chunk("第一句。第二句。",6)
        assertEquals(listOf("第一句。","第二句。"),chunks)
    }

    @Test fun chunkPartsKeepParagraphBoundaries() {
        val parts=TranslateRequest.chunkParts("第一段。\n\n第二段。",100)
        assertEquals(2,parts.size)
        assertTrue(parts[0].paragraphStart)
        assertTrue(parts[1].paragraphStart)
        // A single newline is a wrapped line inside one paragraph, not a new paragraph.
        val wrapped=TranslateRequest.chunkParts("上一行\n下一行",100)
        assertEquals(1,wrapped.size)
    }

    @Test fun chunkPartsMarkContinuationAfterSentenceCut() {
        val parts=TranslateRequest.chunkParts("第一句。第二句。第三句。",6)
        assertEquals(3,parts.size)
        assertTrue(parts.first().paragraphStart)
        assertTrue(parts.drop(1).none { it.paragraphStart })
        assertEquals("第一句。第二句。第三句。",parts.joinToString("") { it.text })
    }

    @Test fun joinTranslatedKeepsLatinSpacingAndCjkTight() {
        val en=TranslatePair(TranslateLanguage.ZH,TranslateLanguage.EN)
        assertEquals("Hi there.",TranslateRequest.joinTranslated("Hi","there.",en,false))
        assertEquals("Hi\nthere.",TranslateRequest.joinTranslated("Hi","there.",en,true))
        val zh=TranslatePair(TranslateLanguage.EN,TranslateLanguage.ZH)
        assertEquals("你好世界。",TranslateRequest.joinTranslated("你好","世界。",zh,false))
        assertEquals("只有一段",TranslateRequest.joinTranslated("","只有一段",en,false))
    }

    @Test fun chunkTimeoutScalesWithChunkSizeAndStaysBounded() {
        assertEquals(45_000L,TranslateRequest.chunkTimeoutMs(10))
        assertTrue(TranslateRequest.chunkTimeoutMs(320) > TranslateRequest.chunkTimeoutMs(100))
        assertEquals(120_000L,TranslateRequest.chunkTimeoutMs(10_000))
    }

    @Test fun promptNamesTheTargetLanguageAndCarriesTheTemplate() {
        val prompt = TranslateRequest.buildPrompt(
            TranslatePair(TranslateLanguage.ZH,TranslateLanguage.KO),"你好")
        assertTrue(prompt.contains("韩文"))
        assertTrue(prompt.contains("你好"))
        assertTrue(prompt.startsWith("<｜hy_begin▁of▁sentence｜><｜hy_User｜>"))
        assertTrue(prompt.endsWith("<｜hy_Assistant｜>"))
    }

    @Test fun sanitizeDropsTemplateTokensAndLeadingLabel() {
        assertEquals("你好",TranslateRequest.sanitize("<|im_start|>翻译：你好<|im_end|>"))
        assertEquals("",TranslateRequest.sanitize("<|im_end|>"))
    }
}
