package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechEvidenceTest {
    @Test fun cantoneseAgreementRequiresBothAudioViewsAndTheSameWordsAndLanguage() {
        val original = SecondPass("包括六堂课同两个测验。", "<|yue|>")
        assertEquals(true, SpeechEvidence.corroboratesCantonese(SecondPass("包括6堂课同2个测验", "<|yue|>"), original))
        assertEquals(false, SpeechEvidence.corroboratesCantonese(SecondPass("包括六堂课同三个测验", "<|yue|>"), original))
        assertEquals(false, SpeechEvidence.corroboratesCantonese(SecondPass(original.text, "<|zh|>"), original))
        assertEquals(false, SpeechEvidence.corroboratesCantonese(original, SecondPass(original.text, "<|zh|>")))
        assertEquals(false, SpeechEvidence.corroboratesCantonese(SecondPass("", "<|yue|>"), SecondPass("", "<|yue|>")))
    }

    @Test fun corroboratedSpeechCanRestoreAClippedNegativePrefixWithoutAddingDoubleNegation() {
        assertEquals("不要打开通知。", SpeechEngine.refine("开通知", "不要打开通知。", boundaryAgreement = true))
        assertEquals("不要删除。", SpeechEngine.refine("删除", "不要删除。", boundaryAgreement = true))
        assertEquals("不要删除", SpeechEngine.refine("不要删除", "不不要删除。", boundaryAgreement = true))
        assertEquals("开通知", SpeechEngine.refine("开通知", "不要打开通知。"))
        assertEquals("删除", SpeechEngine.refine("删除", "请你千万不要删除。", boundaryAgreement = true))
    }

    @Test fun protectsCurrencyUnitsAsWellAsNumericValues() {
        for ((raw, candidate) in listOf(
            "这件衣服128元五角" to "这件衣服128元5元角。",
            "花了5元" to "花了5角。", "花了5角" to "花了5元。")) {
            assertEquals(raw, SpeechEngine.refine(raw, candidate))
        }
        assertEquals("这件衣服128元5角。", SpeechEngine.refine("这件衣服一百二十八元五角", "这件衣服128元5角。"))
        assertEquals("这件衣服128元5角。", SpeechEngine.refine("这件衣服128元5毛", "这件衣服128元5角。"))
    }

    @Test fun corroboratedBoundariesCanRecoverOneMissingNegationButNeverRemoveOrReplaceIt() {
        assertEquals("不要取消订单。", SpeechEngine.refine("要取消订单", "不要取消订单。", boundaryAgreement = true))
        assertEquals("我没有同意删除。", SpeechEngine.refine("我有同意删除", "我没有同意删除。", boundaryAgreement = true))
        for ((raw, candidate) in listOf("不要取消订单" to "要取消订单。", "我没有同意" to "我同意。",
            "别删除" to "不删除。", "已经删除" to "没有删除。", "不要跑10公里" to "不要跑15公里。")) {
            assertEquals(raw, SpeechEngine.refine(raw, candidate, boundaryAgreement = true))
        }
        assertEquals("要取消订单", SpeechEngine.refine("要取消订单", "不要取消订单。"))
    }

    @Test fun corroboratedShortSpeechOnlyAllowsBoundaryCompletionOrHomophones() {
        assertEquals("你好。", SpeechEngine.refine("你", "你好。", boundaryAgreement = true))
        assertEquals("好的。", SpeechEngine.refine("好", "好的。", boundaryAgreement = true))
        val readings = mapOf("辈分" to "beifen", "备份" to "beifen", "你好" to "nihao", "再见" to "zaijian")
        assertEquals("备份。", SpeechEngine.refine("辈分", "备份。", boundaryAgreement = true, phonetic = { readings[it] ?: it }))
        assertEquals("你好", SpeechEngine.refine("你好", "再见。", boundaryAgreement = true, phonetic = { readings[it] ?: it }))
        assertEquals("好", SpeechEngine.refine("好", "好的。"))
        assertEquals("你", SpeechEngine.refine("你", "你现在可以出发了。", boundaryAgreement = true))
    }
}
