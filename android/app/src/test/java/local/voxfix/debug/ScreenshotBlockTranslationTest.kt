package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class ScreenshotBlockTranslationTest {
    private class Translator : ScreenshotTranslator {
        val inputs = mutableListOf<String>()
        var complete: ((String) -> Unit)? = null
        var update: ((String) -> Unit)? = null
        override fun translate(text: String, pair: TranslatePair, onResult: (String) -> Unit, onFailure: (String) -> Unit, onUpdate: (String) -> Unit) {
            inputs.add(text); complete = onResult
            update = onUpdate
        }
        override fun cancel() {}
        override fun available() = true
    }

    @Test fun preservesGeometryAndDraftWhileTranslatingSerially() {
        val translator = Translator()
        val blocks = listOf(ScreenshotTextBlock(1,2,30,40,"原文"), ScreenshotTextBlock(1,50,30,80,"第二行"))
        var result: List<ScreenshotTextBlock>? = null
        val updates=mutableListOf<Pair<Int,String>>()
        translateScreenshotBlocks(translator,blocks,TranslatePair.DEFAULT,{ true },{ result = it },{ fail(it) },
            { index,text -> updates.add(index to text) })
        assertEquals(listOf("原文"),translator.inputs)
        translator.update!!("trans")
        assertEquals(listOf(0 to "trans"),updates)
        translator.complete!!("translated")
        assertEquals(listOf("原文","第二行"),translator.inputs)
        assertNull(result)
        translator.update!!("sec")
        assertEquals(1 to "sec",updates.last())
        translator.complete!!("second")
        assertEquals(listOf(blocks[0].copy(text="translated"),blocks[1].copy(text="second")),result)
        assertEquals("原文",blocks[0].text)
    }

    @Test fun cancellationDiscardsLateResultsAndDoesNotStartNextBlock() {
        val translator = Translator()
        var current = true
        val blocks = List(2) { ScreenshotTextBlock(0,it,10,it+1,"原文") }
        translateScreenshotBlocks(translator,blocks,TranslatePair.DEFAULT,{ current },{ fail("late result") },{ fail(it) },
            { _,_ -> fail("late update") })
        current = false
        translator.update!!("late token")
        translator.complete!!("late")
        assertEquals(1,translator.inputs.size)
    }
}
