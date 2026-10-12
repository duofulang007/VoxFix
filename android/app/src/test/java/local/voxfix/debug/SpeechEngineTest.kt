package local.voxfix.debug

import org.junit.Assert.assertArrayEquals
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
    @Test fun concatenatesVadSegmentsInOrder() {
        assertArrayEquals(
            floatArrayOf(1f, 2f, 3f, 4f),
            SpeechEngine.concat(listOf(floatArrayOf(1f, 2f), floatArrayOf(3f), floatArrayOf(4f))),
            1e-6f)
        assertEquals(0, SpeechEngine.concat(emptyList()).size)
    }

    /**
     * 回归：标点曾被 refine 的编辑距离判死。流式侧没有标点，二遍侧有，若不剥掉标点，
     * 每加一个逗号就算一次编辑，短句相似度必然跌破 0.85 而退回流式文本。
     */
    @Test fun keepsSecondPassPunctuationInsteadOfFallingBackToBareStreaming() {
        for ((streaming, second) in listOf(
            "今天天气不错" to "今天天气不错。",
            "好的我知道了" to "好的，我知道了。",
            "帮我把灯打开然后去阳台看一下" to "帮我把灯打开，然后去阳台看一下。")) {
            assertEquals("二遍的标点必须保留: $streaming", second, SpeechEngine.refine(streaming, second))
        }
    }

    /** ITN 会把「十点」写成「10点」，数字形态变化不等于内容改写，标点不能因此被丢掉。 */
    @Test fun keepsPunctuationWhenItnRewritesChineseNumerals() {
        assertEquals("明天上午10点开会，记得带材料。",
            SpeechEngine.refine("明天上午十点开会记得带材料", "明天上午10点开会，记得带材料。"))
        // 归一只发生在取数值 token 时，绝不能去改文本本身：「一起走」的「一」是词不是数。
        assertEquals(listOf("10", "2", "3"), SpeechEngine.numericTokens("十 和 两 三"))
        assertEquals(listOf("10"), SpeechEngine.numericTokens("10"))
        assertEquals(listOf("1"), SpeechEngine.numericTokens("一起走"))
        assertEquals(emptyList<String>(), SpeechEngine.numericTokens("没有数字"))
    }

    @Test fun punctuateOnlyAddsMissingSentenceEnd() {
        assertEquals("你好。", SpeechEngine.punctuate("你好"))
        assertEquals("你好，今天天气不错。", SpeechEngine.punctuate("你好，今天天气不错。"))
        assertEquals("真的吗？", SpeechEngine.punctuate("真的吗？"))
        assertEquals("", SpeechEngine.punctuate("   "))
        // 片段类收尾不补句号。
        assertEquals("（备注）", SpeechEngine.punctuate("（备注）"))
    }

    @Test fun doesNotStackPunctuationWhenJoiningSegments() {
        assertEquals("你好。继续", SpeechEngine.join("你好。", "，继续"))
        assertEquals("你好。继续", SpeechEngine.join("你好。", "。继续"))
    }

    @Test fun stripsSpacesSenseVoiceLeavesAroundPunctuation() {
        assertEquals("今天，天气不错。", SpeechEngine.clean("今天 ，天气不错 。"))
        assertEquals("中文输入", SpeechEngine.clean(" 中 文 输 入 "))
    }

    @Test fun protectsLargeNumbersDigitSequencesAndDecimals() {
        assertEquals(listOf("120000", "200000", "123", "2026", "120030005", "1000000000000"),
            SpeechEngine.numericTokens("十二万 二十万 一二三 二〇二六 一亿两千万三万零五 一万亿"))
        assertEquals(listOf("-12.5", "12%", "120000"), SpeechEngine.numericTokens("负十二点五 12％ 12万"))
        for ((raw, candidate) in listOf("十二万" to "二十万", "1.2元" to "1,2元", "一二三" to "3")) {
            assertEquals(raw, SpeechEngine.refine(raw, candidate))
        }
        assertEquals("10点。", SpeechEngine.refine("十点", "10点。"))
        assertEquals("12万。", SpeechEngine.refine("十二万", "12万。"))
        assertEquals("一起走", SpeechEngine.refine("一起走", "1起走。"))
    }

    @Test fun usesEnglishSentenceCaseWithoutBreakingAcronymsOrNames() {
        assertEquals("Hello world. I use USB and AI! Don't stop.",
            SpeechEngine.clean("HELLO WORLD. I USE USB AND AI! DON'T STOP."))
        assertEquals("I like Obsidian and OpenAI", SpeechEngine.clean("I LIKE Obsidian AND OpenAI"))
        assertEquals("I'm ready", SpeechEngine.clean("I'M READY"))
        assertEquals("It costs 1.2 dollars. Next sentence", SpeechEngine.clean("IT COSTS 1.2 DOLLARS. NEXT SENTENCE"))
        assertEquals("Hello world.", SpeechEngine.punctuate(SpeechEngine.clean("HELLO WORLD")))
        assertEquals("Hello world.", SpeechEngine.punctuate("Hello world."))
        assertEquals("打开 USB。", SpeechEngine.punctuate(SpeechEngine.clean("打开 USB")))
    }
}
