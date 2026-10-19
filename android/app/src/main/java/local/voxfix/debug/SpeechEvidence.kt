package local.voxfix.debug

/** Extra acoustic agreement narrows exceptions to the usual conservative adoption rules. */
internal object SpeechEvidence {
    private val negation = Regex("不|没|未|别|无|勿")
    private val money = Regex("([-负]?[0-9零〇一二两三四五六七八九十百千万亿]+(?:[.点][0-9零〇一二两三四五六七八九]+)?)(块钱|元|块|角|毛|分)")

    fun corroboratesCantonese(padded: SecondPass, original: SecondPass) =
        padded.text.isNotBlank() && padded.lang == "<|yue|>" && original.lang == padded.lang &&
            SpeechEngine.comparable(padded.text) == SpeechEngine.comparable(original.text)

    fun plausibleCantoneseWordLength(raw: String, candidate: String): Boolean {
        val latin = SpeechEngine.englishWords(raw)
        val letters = candidate.filter { it.isLetter() }
        if (latin.isEmpty() || letters.isEmpty() || letters.any { it !in '\u3400'..'\u9fff' }) return false
        // A hallucinated English word's letters are not separate Cantonese syllables.
        val said = raw.count { it.isLetterOrDigit() } - latin.sumOf { it.length } + latin.size
        val heard = candidate.count { it.isLetterOrDigit() }
        return said > 0 && heard.toDouble() / said in 0.35..2.8
    }

    fun sameMoney(raw: String, candidate: String): Boolean {
        fun units(text: String) = money.findAll(text).map {
            SpeechEngine.numericTokens(it.groupValues[1]).single() to when (val unit = it.groupValues[2]) {
                "块钱", "块" -> "元"
                "毛" -> "角"
                else -> unit
            }
        }.toList()
        return units(raw) == units(candidate)
    }

    fun needsBoundaryConfirmation(raw: String, candidate: String): Boolean {
        val a = SpeechEngine.comparable(raw)
        val b = SpeechEngine.comparable(candidate)
        return a != b && (negations(a) != negations(b) || a.length in 1..4 && b.length in 2..4)
    }

    fun acceptsBoundaryCorrection(a: String, b: String, phonetic: ((String) -> String)?): Boolean {
        if (negations(a) != negations(b)) {
            if (negations(a).isNotEmpty() || negations(b).size != 1) return false
            // Restore a dropped negation or a short negative opening, never remove or double a negation.
            val single = b.length == a.length + 1 && b.indices.any { b[it].toString().matches(negation) && b.removeRange(it, it + 1) == a }
            val prefix = b.length - a.length in 1..3 && a.length >= 2 && b.endsWith(a) &&
                b.dropLast(a.length).all { it in '\u3400'..'\u9fff' }
            return single || prefix
        }
        if (a.length !in 1..4 || b.length !in 2..4 || !(a + b).all { it in '\u3400'..'\u9fff' }) return false
        return b.length == a.length + 1 && (b.startsWith(a) || b.endsWith(a)) ||
            a.length == b.length && phonetic != null && phonetic(a).let { it.isNotEmpty() && it == phonetic(b) }
    }

    private fun negations(text: String) = negation.findAll(text).map { it.value }.toList()
}
