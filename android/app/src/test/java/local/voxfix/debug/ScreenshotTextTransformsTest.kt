package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结果栏 Formatting／Punctuation 两个下拉的语义，对照上游 `screenshotocrtexttransform.cpp`
 * 与 `screenshotocrtexteditingsession.cpp`（固定提交）。
 */
class ScreenshotTextTransformsTest {
    private val sample = "第一行，Hello!\n第二行 World?\n第三行"

    /** 默认两个设置都是空串 ⇒ 恒等变换，换行原样保留（上游 `applyOcrTextTransforms`）。 */
    @Test fun emptySettingsAreIdentity() {
        assertEquals(sample, ScreenshotTextTransforms.apply(sample, "", ""))
    }

    /** keep 只表示"保留"，与空值输出相同；remove 删掉全部 `\n`，不归一空格。 */
    @Test fun removeDropsOnlyLineBreaks() {
        assertEquals(sample, ScreenshotTextTransforms.apply(sample, ScreenshotTextTransforms.KEEP, ""))
        assertEquals(
            "第一行，Hello!第二行 World?第三行",
            ScreenshotTextTransforms.apply(sample, ScreenshotTextTransforms.REMOVE, "")
        )
    }

    /** 32 个 ASCII 符号与全角一一映射，两个方向都可逆；表外字符原样保留。 */
    @Test fun punctuationTableIsSymmetric() {
        val ascii = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
        val wide = "！＂＃＄％＆＇（）＊＋，－．／：；＜＝＞？＠［＼］＾＿｀｛｜｝～"
        assertEquals(wide, ScreenshotTextTransforms.convertPunctuation(ascii, true))
        assertEquals(ascii, ScreenshotTextTransforms.convertPunctuation(wide, false))
        // 表外字符原样保留：中文句读、书名号、货币与破折号都不在 32 个符号里。
        val untouched = "中文。、《》「」¥€—…"
        assertEquals(untouched, ScreenshotTextTransforms.convertPunctuation(untouched, true))
    }

    /** 变换顺序是先用换行再标点；中文句读里的全角逗号属于表内符号，会被转成半角。 */
    @Test fun punctuationRunsAfterLineBreaks() {
        assertEquals(
            "你好,世界!",
            ScreenshotTextTransforms.apply("你好，世界!\n", ScreenshotTextTransforms.REMOVE,
                ScreenshotTextTransforms.HALF)
        )
    }

    /** 状态机：每次都从基线重算，不是叠加；换回 keep 能恢复原始换行。 */
    @Test fun transformsRecomputeFromBaseline() {
        val state = ScreenshotTextTransformState()
        val removed = state.setFormatting(ScreenshotTextTransforms.REMOVE, sample)
        assertEquals("第一行，Hello!第二行 World?第三行", removed)
        val full = state.setPunctuation(ScreenshotTextTransforms.FULL, removed)
        // 只转标点：字母仍是半角，中文逗号（FF0C）本来就在全角表里，保持不变。
        assertEquals("第一行，Hello！第二行 World？第三行", full)
        val backToKeep = state.setFormatting(ScreenshotTextTransforms.KEEP, full)
        // 从基线重算：换行回来，标点仍是全角。
        assertEquals("第一行，Hello！\n第二行 World？\n第三行", backToKeep)
        assertTrue(state.active)
    }

    /** 两个下拉都清空后丢弃基线，下拉回到空值，文本回到基线内容。 */
    @Test fun clearingBothRestoresBaselineAndForgetsIt() {
        val state = ScreenshotTextTransformState()
        val removed = state.setFormatting(ScreenshotTextTransforms.REMOVE, sample)
        val restored = state.setFormatting("", removed)
        assertEquals(sample, restored)
        assertFalse(state.active)
        assertEquals("", state.formatting)
        assertEquals("", state.punctuation)
    }

    /** 非法值规范化为空串：上游 `setFormatting` 只认 keep／remove。 */
    @Test fun unknownValuesNormalizeToEmpty() {
        val state = ScreenshotTextTransformState()
        assertEquals(sample, state.setFormatting("none", sample))
        assertEquals("", state.formatting)
        assertEquals(sample, state.setPunctuation("fullwidth", sample))
        assertEquals("", state.punctuation)
    }

    /** 手动改字后 `clearTransforms()`：下一次设置变换要把新文本当基线。 */
    @Test fun typingClearsTransformState() {
        val state = ScreenshotTextTransformState()
        state.setFormatting(ScreenshotTextTransforms.REMOVE, sample)
        state.clear()
        assertFalse(state.active)
        val typed = "手改后的两行\n第二行"
        assertEquals("手改后的两行第二行", state.setFormatting(ScreenshotTextTransforms.REMOVE, typed))
    }
}
