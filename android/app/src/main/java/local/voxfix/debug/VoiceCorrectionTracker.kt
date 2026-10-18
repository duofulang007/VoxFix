package local.voxfix.debug

import android.view.inputmethod.EditorInfo

/** The last voice insertion stays only in memory; anchors never follow another editor. */
internal class VoiceCorrectionTracker {
    private var original = ""
    private var left = ""
    private var right = ""
    private var start = 0
    private var generation = -1L
    private var expires = 0L
    private var known = emptySet<String>()
    private val seen = mutableSetOf<VoiceCorrection>()
    fun clear() { original = ""; left = ""; right = ""; generation = -1; known = emptySet(); seen.clear() }
    fun active() = original.isNotEmpty()

    fun arm(text: String, offset: Int, insertion: Int, result: String, editor: Long, now: Long, englishWords: Set<String> = emptySet()) {
        clear()
        val index = insertion - offset
        if (result.length !in 1..512 || index < 0 || index + result.length > text.length ||
            text.substring(index, index + result.length) != result) return
        original = result; left = text.substring(0, index).takeLast(12)
        right = text.substring(index + result.length).take(12)
        start = insertion; generation = editor; expires = now + 120_000
        known = englishWords
    }

    fun observe(text: String, offset: Int, editor: Long, now: Long, composing: Boolean,
                phonetic: (String) -> String, range: (String, Int, Int) -> IntRange): VoiceCorrection? =
        observeAll(text, offset, editor, now, composing, phonetic, range, false).singleOrNull()

    fun observeAll(text: String, offset: Int, editor: Long, now: Long, composing: Boolean,
                   phonetic: (String) -> String, range: (String, Int, Int) -> IntRange, confirmed: Boolean): List<VoiceCorrection> {
        if (!active()) return emptyList()
        if (editor != generation || now > expires) { clear(); return emptyList() }
        if (composing) return emptyList()
        val index = start - offset
        if (index < 0 || index > text.length || !text.substring(0, index).endsWith(left)) { clear(); return emptyList() }
        val end = if (right.isEmpty()) text.length else text.indexOf(right, index)
        if (end < index || right.isNotEmpty() && text.indexOf(right, end + 1) >= 0) { clear(); return emptyList() }
        val current = text.substring(index, end)
        if (current.length > 512) { clear(); return emptyList() }
        val rules = VoiceEditExtractor.extract(original, current, known, confirmed, phonetic, range)
        if (rules.isNotEmpty()) original = current
        return rules.filter { seen.add(it) }
    }
    companion object {
        fun allowed(inputType: Int, imeOptions: Int): Boolean {
            val kind = inputType and 15; val variation = inputType and 4080
            return imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0 &&
                !(kind == 1 && variation in listOf(128, 144, 224) || kind == 2 && variation == 16)
        }
    }
}
