package local.voxfix.debug

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechEngineTest {
    /** 真实词表来自包内 english-words.txt；单元测试注入固定词集，避免读 Android 资产。 */
    @org.junit.Before fun installEnglishVocabulary() {
        EnglishVocabulary.install(setOf("excel", "word", "ppt", "wifi", "api", "bug", "offline", "computer", "recognition"))
    }

    @Test fun formatsSpokenNumbersByDefaultWithoutChangingOrdinaryWords() {
        for ((spoken, written) in listOf(
            "下午三点半" to "下午3点半", "跑了十公里" to "跑了10公里",
            "一百二十三元" to "123元", "负十二点五元" to "-12.5元",
            "三点五公里" to "3.5公里", "一千零五元" to "1005元",
            "上午一点开会" to "上午1点开会", "三点五十分" to "3点50分",
            "二十三点五十九分" to "23点59分", "十点开会" to "10点开会")) {
            assertEquals(spoken, written, SpeechEngine.formatNumbers(spoken))
            assertEquals(written, SpeechEngine.formatNumbers(written))
        }
        for (text in listOf("我们一起去公园散步", "一直一般一心一意", "一点也不好，有一点进展",
            "千万别改，三心二意", "三点五", "二三十公里", "三四公里", "一百二元", "一百二点五元", "一万二元", "十几元", "三点六十分",
            "OpenAI 12.50元，3.5公里", "下午25点半", "万一公里不够")) {
            assertEquals(text, text, SpeechEngine.formatNumbers(text))
        }
    }
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

    /**
     * 回归：交给标点模型前必须剥掉已有句读。
     *
     * 模型对已带标点的文本会叠标点（实测 `你好，请问洗手间在哪里？` →
     * `你好，，请问洗手间在哪里？？`）。剥句读让模型重新断句，但不能动小数点，
     * 否则 `120.5` 会被模型拆成 `120 . 5`。
     */
    @Test fun stripsSentenceMarksBeforePunctuationModelButKeepsDecimals() {
        assertEquals("你好请问洗手间在哪里", SpeechEngine.stripSentencePunctuation("你好，请问洗手间在哪里？"))
        assertEquals("这个一共120.5元", SpeechEngine.stripSentencePunctuation("这个一共120.5元"))
        assertEquals("真的吗", SpeechEngine.stripSentencePunctuation("真的吗？"))
        assertEquals("Hello how are you", SpeechEngine.stripSentencePunctuation("Hello, how are you?"))
    }

    /** 二次防御：模型偶尔在残留标点后追加，相邻重复只能留一个。 */
    @Test fun dedupesStackedPunctuationFromModel() {
        assertEquals("你好，请问洗手间在哪里？", SpeechEngine.dedupePunctuation("你好，，请问洗手间在哪里？？"))
        assertEquals("好的。", SpeechEngine.dedupePunctuation("好的。。"))
        // 不同标点连用是合法写法，不合并。
        assertEquals("真的吗？！", SpeechEngine.dedupePunctuation("真的吗？！"))
    }

    /**
     * 模型只吃含汉字的文本：整句英文会被它加上中文逗号，纯英文继续走句末规则；
     * 含引号括号的正文会被它插入错逗号，直接不交给模型。
     */
    @Test fun onlySendsChineseTextWithoutQuotesToPunctuationModel() {
        assertEquals(true, SpeechEngine.needsPunctuationModel("请帮我打开WIFI设置"))
        assertEquals(true, SpeechEngine.needsPunctuationModel("这个bug已经修好了"))
        assertEquals(false, SpeechEngine.needsPunctuationModel("HELLO WORLD HOW ARE YOU"))
        assertEquals(false, SpeechEngine.needsPunctuationModel("他说“明天见”就走了"))
        assertEquals(false, SpeechEngine.needsPunctuationModel("（备注）"))
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
        // 纯英文没有标点模型覆盖，句首疑问词决定问号，否则陈述句被补成句号。
        assertEquals("COULD YOU TURN OFF THE LIGHT?", SpeechEngine.punctuate("COULD YOU TURN OFF THE LIGHT"))
        assertEquals("Where is the file?", SpeechEngine.punctuate("Where is the file"))
        assertEquals("The door is closed.", SpeechEngine.punctuate("The door is closed"))
    }

    /**
     * 回归：旧实现比较前删掉全部空白，`JSON` 与 `J S O N`、`VOXFIX` 与 `VOX FIX` 成了同一个串，
     * 二遍的错误拆词被当成「内容一致」放进来，固定音频里混合样本因此从 5/16 掉到 4/16。
     */
    @Test fun keepsEnglishWordBoundariesWhenComparingSecondPass() {
        assertEquals("这个 API返回的是 JSON数据",
            SpeechEngine.refine("这个 API返回的是 JSON数据", "这个 API 返回的是 J S O N 数据"))
        assertEquals("我想用 VOXFIX 进行离线语音输入",
            SpeechEngine.refine("我想用 VOXFIX 进行离线语音输入", "我想用 VOX FIX 进行离线语音输入"))
        assertEquals("把文件保存成 PDF格式",
            SpeechEngine.refine("把文件保存成 PDF格式", "把文件保存成 PF 格式"))
        // 词序列一致，只差撇号与标点：仍然采用二遍，拿到它的撇号与标点。
        assertEquals("I DON'T THINK THIS IS THE RIGHT ANSWER.",
            SpeechEngine.refine("I DONT THINK THIS IS THE RIGHT ANSWER", "I DON'T THINK THIS IS THE RIGHT ANSWER."))
    }

    /**
     * 二遍补出流式完全漏掉的英文词，是它最值得采用的一类；但汉字必须逐字相同，
     * 否则就是借「补英文」改写中文（`我今天用脚本 → 我今天用 PYHO写了一个脚本`）。
     */
    @Test fun takesEnglishWordsTheSecondPassHeardInPureChineseSpeech() {
        assertEquals("这个 BUG 已经修好了", SpeechEngine.refine("这个已经修好了", "这个 BUG 已经修好了"))
        assertEquals("请把代码提交到 GITHUB", SpeechEngine.refine("请把代码提交到", "请把代码提交到 GITHUB"))
        // 两个字母、又不在缩写表里，是二遍在换声片段上吐出的半截词，不是补内容。
        assertEquals("这个已经修好了", SpeechEngine.refine("这个已经修好了", "这个 BU 已经修好了"))
        // 已知缩写仍可补：AI 只有两个字母但在缩写表里。
        assertEquals("打开 AI", SpeechEngine.refine("打开", "打开 AI"))
        assertEquals("我今天用脚本", SpeechEngine.refine("我今天用脚本", "我今天用 PYHO写了一个脚本"))
        assertEquals("先运行", SpeechEngine.refine("先运行", "先运行给 STATUS 再执行给T COMMIT"))
    }

    /** 纯英文、字母数不变的改写才采用；漏字/多字（MPUTER、PF、APPI）必须拒绝。 */
    @Test fun onlyTrustsEnglishRewritesThatKeepTheLetterCount() {
        assertEquals("OFFLINE", SpeechEngine.refine("ARE FINE", "OFFLINE"))
        for ((raw, heard) in listOf("COMPUTER" to "MPUTER", "API" to "APPI", "VOX FIX" to "OX FIX",
            "CODE" to "CODE LAN", "I WOULD LIKE TO BOOK A TABLE" to "I WOULD LIKE TO BOOK AABLE")) {
            assertEquals(raw, SpeechEngine.refine(raw, heard))
        }
        // 带中文的句子不走这条放行：PDF→PF 仍按英文内容变化拒绝。
        assertEquals("把文件保存成 PDF格式", SpeechEngine.refine("把文件保存成 PDF格式", "把文件保存成 PF 格式"))
    }

    /**
     * 中文用户读英文术语时，流式按谐音出字（`Excel → 一笑`），二遍反而听得出术语。
     * 真机样本：说「用Excel做一个表格」，流式给出「用一笑做个一个表格」。
     */
    @Test fun takesTheTermTheSecondPassHeardInsteadOfChineseHomophones() {
        assertEquals("用 EXCEL 做一个表格", SpeechEngine.refine("用一笑做个一个表格", "用 EXCEL 做一个表格"))
        // 流式把术语听成半截字母，二遍是真词：采用。
        assertEquals("请帮我打开 EXCEL", SpeechEngine.refine("请帮我打开 EXA", "请帮我打开 EXCEL"))
        assertEquals("EXCEL", SpeechEngine.refine("EXHALE", "EXCEL"))
        // 表外长串不是术语：不能借「换术语」把中文删掉。
        assertEquals("用一笑做个一个表格", SpeechEngine.refine("用一笑做个一个表格", "用 PYHO 做一个表格"))
        // 中文少太多，是改写而不是换术语。
        assertEquals("用一笑做个一个表格", SpeechEngine.refine("用一笑做个一个表格", "用 EXCEL 做"))
        // 流式本来就听对了真词时，二遍改错一律拒绝（PDF→PF、COMPUTER→MPUTER、VOX FIX→OX FIX）。
        assertEquals("请把Excel发给我", SpeechEngine.refine("请把Excel发给我", "请把Word发给我"))
        assertEquals("COMPUTER", SpeechEngine.refine("COMPUTER", "MPUTER"))
        assertEquals("VOX FIX", SpeechEngine.refine("VOX FIX", "OX FIX"))
    }

    /**
     * 语种自适应：二遍用 `language = "auto"`，语种由 SenseVoice 内建 LID 自己报。
     *
     * 流式基座只有中英，说粤/日/韩时它吐的是同音汉字，与正确文本相似度天然极低；
     * 再用 0.85 门判，等于把唯一能给出正确结果的那一路丢掉。
     */
    @Test fun adoptsNonMandarinSecondPassWhenTheLabelIsCorroboratedByTheText() {
        // 粤语：流式把「我哋今日唔使返工」听成普通话同音字。
        assertEquals("我哋今日唔使返工。",
            SpeechEngine.refine("我们今日不是返工", "我哋今日唔使返工。", "<|yue|>"))
        // 日文：二遍带假名才是真的。
        assertEquals("これはテストです。",
            SpeechEngine.refine("这个是测试", "これはテストです。", "<|ja|>"))
        // 韩文：同理。
        assertEquals("안녕하세요.", SpeechEngine.refine("安宁哈塞哟", "안녕하세요.", "<|ko|>"))
    }

    /** 日语仍需假名；普通话及未知标签继续保守比较。 */
    @Test fun ignoresALanguageLabelTheTextDoesNotSupport() {
        assertEquals("今天天气不错", SpeechEngine.refine("今天天气不错", "今天天气很好", ""))
        assertEquals("今天天气不错", SpeechEngine.refine("今天天气不错", "今天天气很好", "<|ja|>"))
        // 中文标签走原路：这条只是确认新增分支没把 zh 也放进「整句采用」。
        assertEquals("今天天气不错", SpeechEngine.refine("今天天气不错", "今天天气很好", "<|zh|>"))
    }

    /** 噪声上二遍爱把一个字复读成整句；数量级异常时不采信。 */
    @Test fun rejectsForeignAdoptionWhenTheLengthIsImplausible() {
        assertEquals("你好", SpeechEngine.refine("你好", "唔哋".repeat(150), "<|yue|>"))
    }

    /** 公开音频真实复现：粤语不一定含多个专用字，双语流式的同音字不可当真值。 */
    @Test fun adoptsGeneralModelCantoneseWithoutRequiringSpecialCharacters() {
        for ((raw, correct) in listOf(
            "隆家输给我都要夜色" to "两只小企鹅都有嘢食",
            "某人连他一圈养猫哥又卡" to "无论你提出任何嘅要求",
            "姚某人自己都忘记我都还挺会干" to "有无人知道湾仔活道系点去")) {
            assertEquals(correct, SpeechEngine.refine(raw, correct, "<|yue|>"))
            assertEquals(raw, SpeechEngine.refine(raw, correct, ""))
        }
    }

    @Test fun punctuationCannotChangeAmountsDecimalsOrWords() {
        assertEquals("这件衣服128元5角。", SpeechEngine.punctuationOnly("这件衣服128元5角。", "这件衣服128元5元角。"))
        assertEquals("长度是3.5厘米。", SpeechEngine.punctuationOnly("长度是3.5厘米。", "长度是3 . 5厘米。"))
        assertEquals("不要删除。", SpeechEngine.punctuationOnly("不要删除。", "要删除。"))
        assertEquals("今天，天气不错。", SpeechEngine.punctuationOnly("今天天气不错", "今天，天气不错。"))
        assertEquals("这个 bug 修好了。", SpeechEngine.punctuationOnly("这个bug修好了", "这个 bug 修好了。"))
    }

    /**
     * 松手预算只给「最后一句 + 二遍已加载」。
     *
     * 冷启动第一句实测：模型还在加载（0.78s），松手却等满了 350ms，文本一字未改。
     */
    @Test fun releaseBudgetOnlyAppliesToTheLastSegmentWithALoadedSecondPass() {
        val deadline = 10_000_000_000L
        // 最后一句且模型已就绪：按剩余时间给预算。
        assertEquals(350L, SpeechEngine.releaseBudget(deadline, true, true, now = deadline - 350_000_000L))
        // 模型还在加载：一秒都不等，否则白等。
        assertEquals(0L, SpeechEngine.releaseBudget(deadline, true, false, now = deadline - 350_000_000L))
        // 不是最后一句：前面几句不再追加等待。
        assertEquals(0L, SpeechEngine.releaseBudget(deadline, false, true, now = deadline - 350_000_000L))
        // 预算已经过期：不再等。
        assertEquals(0L, SpeechEngine.releaseBudget(deadline, true, true, now = deadline + 1_000_000L))
    }
}
