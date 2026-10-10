package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class NativePolisherTest {
    @Test fun streamingWaitsForCompleteUtf8() {
        val bytes = "鞋头".toByteArray(Charsets.UTF_8)
        assertEquals(null, NativePolisher.decodeStream(bytes.copyOf(2)))
        assertEquals("鞋", NativePolisher.decodeStream(bytes.copyOf(3)))
        assertEquals(null, NativePolisher.decodeStream(bytes.copyOf(4)))
        assertEquals("鞋头", NativePolisher.decodeStream(bytes))
    }
    @Test fun doesNotFormatNumbersInsideNamesAndPhrases() {
        for (text in listOf("我联系张三。", "我的同事王一", "我喜欢十三香", "一心一意跑步"))
            assertEquals(text, NativePolisher.guarded(text, text))
    }
    @Test fun keepsSignedValues() {
        assertEquals("温度是-10度不要改", NativePolisher.guarded("温度是-10度不要改", "温度是10度不要改"))
        assertEquals("温度是-10度。", NativePolisher.guarded("温度是-10.0度", "温度是-10度。"))
    }
    @Test fun acceptsEquivalentQuantityFormatsButNotDifferentValues() {
        val raw = "重量是一百九十二克，不是二百零五克"
        val formatted = "重量是192克，不是205克。"
        val canonicalRaw = "重量是192克，不是205克"
        assertEquals(formatted, NativePolisher.guarded(raw, formatted, canonicalRaw, formatted))
        assertEquals(raw, NativePolisher.guarded(raw, "重量是190克，不是205克。", canonicalRaw, "重量是190克，不是205克。"))
        assertEquals("不要跑10公里。", NativePolisher.guarded("不要跑十公里", "不要跑10公里。", "不要跑10公里", "不要跑10公里。"))
    }
    @Test fun retainsAmbiguousAndOpaqueNumbers() {
        for ((raw, changed) in listOf("重量一百九克" to "重量190克", "一万二元" to "12000元", "跑四五公里" to "跑45公里", "编号001" to "编号1"))
            assertEquals(raw, NativePolisher.guarded(raw, changed))
        assertEquals("我回家记录。", NativePolisher.guarded("我回家记录一下", "我回家记录。"))
    }
    @Test fun keepsNumbersNegationEnglishAndQuotes() {
        for ((raw, changed) in listOf("不要跑10公里" to "要跑10公里", "跑10公里" to "跑15公里", "打开Obsidian" to "打开Office", "他说“不要跑”" to "他说“不要走”"))
            assertEquals(raw, NativePolisher.guarded(raw, changed))
    }
    @Test fun acceptsMinorContextCorrectionAndRepetitionCleanup() {
        assertEquals("这双鞋的鞋头比较宽，重量是192克。", NativePolisher.guarded("这双鞋的斜头比较宽重量是192个", "这双鞋的鞋头比较宽，重量是192克。"))
        assertEquals("我明天去跑步，然后回家记录。", NativePolisher.guarded("我明天去跑步然后然后回家记录", "我明天去跑步，然后回家记录。"))
    }
    @Test fun rejectsEmptyReasoningAndLargeRewrite() {
        assertEquals("明天去跑步", NativePolisher.guarded("明天去跑步", "<think>推理</think>"))
        assertEquals("明天去跑步", NativePolisher.guarded("明天去跑步", ""))
        assertEquals("明天去跑步", NativePolisher.guarded("明天去跑步", "今晚在公司加班"))
    }
}
