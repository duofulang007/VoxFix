package local.voxfix.debug

import android.icu.text.BreakIterator
import android.icu.text.Transliterator
import java.util.Locale

/** Platform ICU supplies Chinese readings and word boundaries; no extra segmentation model. */
internal object VoiceLanguage {
    private val reading by lazy { Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower") }
    @Synchronized fun phonetic(text: String): String = reading.transliterate(text).replace(Regex("[^a-z0-9]"), "")
    fun boundaries(text: String): Set<Int> {
        val breaks = BreakIterator.getWordInstance(Locale.SIMPLIFIED_CHINESE)
        breaks.setText(text)
        return buildSet {
            var at = breaks.first()
            while (at != BreakIterator.DONE) { add(at); at = breaks.next() }
        }
    }
    fun wordRange(text: String, first: Int, end: Int): IntRange {
        val breaks = BreakIterator.getWordInstance(Locale.SIMPLIFIED_CHINESE)
        breaks.setText(text)
        var start = if (breaks.isBoundary(first)) first else breaks.preceding(first)
        var stop = if (breaks.isBoundary(end)) end else breaks.following(end)
        if (stop - start == 1) {
            if (start > 0 && text[start - 1] in '\u3400'..'\u9fff') start--
            else if (stop < text.length && text[stop] in '\u3400'..'\u9fff') stop++
        }
        return start until stop
    }
}
