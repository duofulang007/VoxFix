package local.voxfix.debug

/**
 * OCR 文本变换，逐行移植自上游 `screenshotocrtexttransform.cpp`（固定提交
 * `afdc340b14e7f92a294a95c374d13ada0d8c7a82`）。
 *
 * 原版结果栏的两个下拉：Formatting（Keep／Remove line breaks）与 Punctuation（Half／Full
 * width）。两者默认都是空串，此时 [apply] 是恒等变换——复制、保存都按识别结果原样输出。
 */
object ScreenshotTextTransforms {
    /** 上游 `convertOcrPunctuation` 的 ASCII 表：32 个半角符号，顺序即全角映射顺序。 */
    private const val ASCII = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    /** 与 [ASCII] 一一对应的全角码位（FF01…FF5E 中真实存在的 32 个）。 */
    private val WIDE = intArrayOf(
        0xFF01, 0xFF02, 0xFF03, 0xFF04, 0xFF05, 0xFF06, 0xFF07, 0xFF08, 0xFF09, 0xFF0A, 0xFF0B,
        0xFF0C, 0xFF0D, 0xFF0E, 0xFF0F, 0xFF1A, 0xFF1B, 0xFF1C, 0xFF1D, 0xFF1E, 0xFF1F, 0xFF20,
        0xFF3B, 0xFF3C, 0xFF3D, 0xFF3E, 0xFF3F, 0xFF40, 0xFF5B, 0xFF5C, 0xFF5D, 0xFF5E
    )

    const val KEEP = "keep"
    const val REMOVE = "remove"
    const val HALF = "half"
    const val FULL = "full"

    /** 上游 `removeOcrLineBreaks`：删掉所有 `\n`，不做空白归一。 */
    fun removeLineBreaks(text: String): String = text.replace("\n", "")

    /** 上游 `convertOcrPunctuation`：两个方向都只动表内 32 个符号，其余字符原样保留。 */
    fun convertPunctuation(text: String, fullWidth: Boolean): String {
        val builder = StringBuilder(text.length)
        for (character in text) {
            val asciiIndex = ASCII.indexOf(character)
            if (fullWidth) {
                if (asciiIndex >= 0) builder.append(WIDE[asciiIndex].toChar()) else builder.append(character)
                continue
            }
            val wideIndex = WIDE.indexOf(character.code)
            if (wideIndex >= 0) builder.append(ASCII[wideIndex]) else builder.append(character)
        }
        return builder.toString()
    }

    /** 上游 `applyOcrTextTransforms`：先处理换行，再处理标点；非法值等于不做。 */
    fun apply(text: String, formatting: String, punctuation: String): String {
        var result = if (formatting == REMOVE) removeLineBreaks(text) else text
        when (punctuation) {
            HALF -> result = convertPunctuation(result, false)
            FULL -> result = convertPunctuation(result, true)
        }
        return result
    }
}

/**
 * 上游 `ScreenshotOcrTextEditingSession` 的 formatting／punctuation 状态机。
 *
 * 关键语义（原版如此，不要"顺手优化"）：
 * - 第一次设置变换时抓当前文本当基线，之后每次都从基线重算，不是叠加；
 * - 两个下拉都清空后丢弃基线；
 * - 手动改字、撤销、重做都会 `clearTransforms()`，即下拉回到空值；
 * - 非法值规范化为空串。
 */
class ScreenshotTextTransformState {
    var formatting: String = ""
        private set
    var punctuation: String = ""
        private set
    private var baseline: String? = null

    val active: Boolean get() = formatting.isNotEmpty() || punctuation.isNotEmpty()

    /** 返回设置后的全文；值未变化时原样返回 [current]。 */
    fun setFormatting(value: String, current: String): String {
        val normalized = when (value) {
            ScreenshotTextTransforms.KEEP, ScreenshotTextTransforms.REMOVE -> value
            else -> ""
        }
        if (normalized == formatting) return current
        return update(current) { formatting = normalized }
    }

    fun setPunctuation(value: String, current: String): String {
        val normalized = when (value) {
            ScreenshotTextTransforms.HALF, ScreenshotTextTransforms.FULL -> value
            else -> ""
        }
        if (normalized == punctuation) return current
        return update(current) { punctuation = normalized }
    }

    fun clear() {
        baseline = null
        formatting = ""
        punctuation = ""
    }

    private inline fun update(current: String, change: () -> Unit): String {
        if (!active) baseline = current
        change()
        val base = baseline ?: current
        val next = ScreenshotTextTransforms.apply(base, formatting, punctuation)
        if (!active) baseline = null
        return next
    }
}
