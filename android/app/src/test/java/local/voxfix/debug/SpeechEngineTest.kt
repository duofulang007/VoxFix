package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechEngineTest {
    @Test fun protectsNumbersEnglishAndNegation() {
        for ((raw, changed) in listOf("不要跑10公里" to "要跑10公里", "明天跑10公里" to "明天跑15公里", "打开Obsidian" to "打开Office")) {
            assertEquals(raw, SpeechEngine.refine(raw, changed))
        }
    }
    @Test fun rejectsLargeRewriteAndAcceptsMinorCorrection() {
        assertEquals("明天下午去公园跑步", SpeechEngine.refine("明天下午去公园跑步", "今天去公司上班"))
        assertEquals("明天下午去公园跑步", SpeechEngine.refine("明天下午去公元跑步", "明天下午去公园跑步"))
    }
    @Test fun joinsChineseAndEnglishWithoutLostContent() {
        assertEquals("中文输入", SpeechEngine.clean(" 中 文 输 入 "))
        assertEquals("hello world", SpeechEngine.join("hello", "world"))
        assertEquals("你好。继续", SpeechEngine.join("你好。", "继续"))
    }
}
