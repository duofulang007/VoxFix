package local.voxfix.debug

/** Recover independent Latin terms only when every surrounding Chinese anchor is unchanged. */
internal object SpeechEnglishRecovery {
    private val word = Regex("[A-Za-z]+(?:['’][A-Za-z]+)?")

    fun recover(raw: String, candidate: String, known: (String) -> Boolean): String? {
        if (raw.none { it in '\u4e00'..'\u9fff' }) return null
        val spans = word.findAll(raw).toList()
        val before = SpeechEngine.englishWords(raw)
        val after = SpeechEngine.englishWords(candidate)
        if (before.size != after.size ||
            word.split(raw).map(SpeechEngine::comparable) != word.split(candidate).map(SpeechEngine::comparable)) return null
        val replacements = word.findAll(candidate).toList()
        var result = raw
        for (i in spans.indices.reversed()) {
            if (before[i] != after[i] && !known(before[i]) && known(after[i]))
                result = result.replaceRange(spans[i].range, replacements[i].value)
        }
        return result.takeIf { it != raw }
    }
}
