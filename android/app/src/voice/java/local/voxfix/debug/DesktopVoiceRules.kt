package local.voxfix.debug

import kotlin.math.sqrt

/** Candidate module: desktop text and gain rules, without storage or models. */
internal object DesktopVoiceRules {
    const val RATE = 16000
    const val FRAME = 1600
    const val MAX_SAMPLES = RATE * 180
    private val marks = "，。！？；：、,.!?;:"
    private val tags = Regex("<\\|[^|]*\\|>|<\\s*(?:FIL|L|IL)\\s*/\\s*>", RegexOption.IGNORE_CASE)
    private val tokens = Regex("[A-Za-z0-9_]+(?:[.'/:+-][A-Za-z0-9_]+)*|[^\\s\\u001c-\\u001f\\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000，。！？；：、,.!?;:]")

    fun space(char: Char) = char.isWhitespace() || char == '\u0085' || char in '\u001c'..'\u001f'
    fun chinese(text: String) = text.any { it in '\u4e00'..'\u9fff' }
    fun clean(text: String): String {
        val cleaned = tags.replace(text, "").trim(::space)
        return cleaned.takeIf { it.any(Char::isLetterOrDigit) }.orEmpty()
    }

    /** A voiced 40 ms frame is required before the short-release VAD retry, never for normal speech. */
    fun hasVoicedFrame(audio: FloatArray, samples: Int): Boolean {
        for (start in 0..samples - 640 step 320) {
            var mean = 0.0
            for (i in start until start + 640) mean += audio[i]
            mean /= 640
            for (lag in 32..320 step 4) {
                var cross = 0.0
                var leftEnergy = 0.0
                var rightEnergy = 0.0
                for (i in start until start + 640 - lag) {
                    val left = audio[i] - mean
                    val right = audio[i + lag] - mean
                    cross += left * right; leftEnergy += left * left; rightEnergy += right * right
                }
                if (cross > 0 && cross > .35 * sqrt(leftEnergy * rightEnergy)) return true
            }
        }
        return false
    }
    fun allowed(text: String) = text.all { char ->
        space(char) || char in "£¥©®°±µ·×÷€" || char.code in 0x20..0x7e ||
            char.code in 0x2000..0x206f || char.code in 0x3000..0x303f ||
            char.code in 0x3400..0x4dbf || char.code in 0x4e00..0x9fff ||
            char.code in 0xfe30..0xfe4f || char.code in 0xff00..0xffef
    }
    private fun alnum(char: Char) = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9'
    fun join(left: String, right: String) = left +
        (if (left.isNotEmpty() && right.isNotEmpty() && alnum(left.last()) && alnum(right.first())) " " else "") + right

    fun punctuationOnly(original: String, result: String): Boolean {
        if (tokens.findAll(original).map { it.value }.toList() != tokens.findAll(result).map { it.value }.toList()) return false
        var position = 0
        for (char in original.filterNot(::space)) {
            position = result.indexOf(char, position)
            if (position < 0) return false
            position++
        }
        return true
    }

    fun punctuate(original: String, result: String): String {
        if (!punctuationOnly(original, result)) return original
        val kept = StringBuilder()
        var position = 0
        result.forEachIndexed { index, char ->
            if (position < original.length && char == original[position]) {
                position++; kept.append(char)
            } else if (!(char in marks && (kept.isNotEmpty() && kept.last() in marks ||
                        index + 1 < result.length && result[index + 1] in marks))) kept.append(char)
        }
        return kept.toString().takeIf { punctuationOnly(original, it) } ?: original
    }
}

internal class DesktopQuietBoost {
    private var level = 0.0
    fun process(audio: FloatArray): FloatArray {
        if (audio.isEmpty()) return audio
        level = maxOf(sqrt(audio.sumOf { it.toDouble() * it } / audio.size), level * .96)
        val peak = audio.maxOf { kotlin.math.abs(it) }
        if (peak == 0f) return audio.copyOf()
        val gain = minOf(32.0, maxOf(1.0, .025 / maxOf(level, 1e-8)), .95 / peak)
        return FloatArray(audio.size) { (audio[it] * gain).toFloat() }
    }
}
