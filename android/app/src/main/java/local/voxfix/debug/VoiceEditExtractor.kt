package local.voxfix.debug

/** Bounded Levenshtein alignment; ICU supplies word boundaries at the Android edge. */
internal object VoiceEditExtractor {
    fun distance(a: String, b: String): Int = matrix(a, b).last().last()
    private fun matrix(a: String, b: String): Array<IntArray> {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d
    }

    fun extract(old: String, text: String, known: Set<String>, confirmed: Boolean,
                phonetic: (String) -> String, range: (String, Int, Int) -> IntRange): List<VoiceCorrection> {
        if (old == text || old.length !in 1..512 || text.length !in 1..512) return emptyList()
        val d = matrix(old, text)
        if (d.last().last() > 32) return emptyList()
        val steps = mutableListOf<Pair<Int, Int>>()
        var i = old.length; var j = text.length
        while (i > 0 || j > 0) {
            val step = when {
                i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + if (old[i - 1] == text[j - 1]) 0 else 1 -> 1 to 1
                i > 0 && d[i][j] == d[i - 1][j] + 1 -> 1 to 0
                else -> 0 to 1
            }
            steps.add(step); i -= step.first; j -= step.second
        }
        val low = IntArray(text.length + 1) { Int.MAX_VALUE }
        val high = IntArray(text.length + 1) { -1 }
        val changed = mutableListOf<IntRange>()
        fun mark() { low[j] = minOf(low[j], i); high[j] = maxOf(high[j], i) }
        mark()
        for ((a, b) in steps.asReversed()) {
            val differs = a == 0 || b == 0 || old[i] != text[j]
            if (differs) changed.add(j until j + b)
            i += a; j += b; mark()
        }
        val spans = changed.filter { !it.isEmpty() }.map { change ->
            var start = change.first; var end = change.last + 1
            if (text.substring(start, end).any { it in 'A'..'Z' || it in 'a'..'z' || it.isDigit() }) {
                while (start > 0 && latinPart(text[start - 1])) start--
                while (end < text.length && latinPart(text[end])) end++
            } else {
                val word = range(text, start, end)
                start = word.first; end = word.last + 1
            }
            for (word in known.sortedBy { it.length }) {
                var at = text.indexOf(word)
                while (at >= 0) {
                    if (at <= change.first && at + word.length > change.last) { start = at; end = at + word.length }
                    at = text.indexOf(word, at + 1)
                }
            }
            start until end
        }.filter { it.first >= 0 && it.last < text.length }.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (span in spans) {
            if (merged.isNotEmpty() && span.first <= merged.last().last) {
                val previous = merged.removeAt(merged.lastIndex)
                merged.add(previous.first..maxOf(previous.last, span.last))
            } else merged.add(span)
        }
        if (merged.size > 8) return emptyList()
        val rules = mutableListOf<VoiceCorrection>()
        for (span in merged) {
            var start = span.first; var end = span.last + 1
            var from = low[start]; var until = high[end]
            // Include adjacent deletions and whitespace added around a replacement.
            while (start > 0 && text[start - 1] == ' ') start--
            while (end < text.length && text[end] == ' ') end++
            from = low[start]; until = high[end]
            if (from < 0 || until > old.length || from >= until) continue
            val heard = old.substring(from, until).trim()
            val word = text.substring(start, end).trim()
            if (!confirmed && word !in known && word.any { it in 'A'..'Z' || it in 'a'..'z' }) return emptyList()
            val rule = VoiceCorrection(heard, word,
                text.substring(0, start).trimEnd().takeLastWhile { it.isLetterOrDigit() }.takeLast(4),
                text.substring(end).trimStart().takeWhile { it.isLetterOrDigit() }.take(4))
            if (runCatching { VoiceLexicon.validateCorrection(rule) }.isFailure || heard == word) continue
            val chinese = (heard + word).all { it in '\u3400'..'\u9fff' }
            if (chinese && word !in known) {
                val a = phonetic(heard); val b = phonetic(word)
                val near = a.isNotEmpty() && b.isNotEmpty() && (distance(a, b).toDouble() / maxOf(a.length, b.length) <= 0.3 ||
                    heard.length != word.length && minOf(a.length, b.length) >= 3 && (a.contains(b) || b.contains(a)))
                if (!confirmed && !near) continue
                val prefix = heard.commonPrefixWith(word).length
                val suffix = heard.substring(prefix).commonSuffixWith(word.substring(prefix)).length
                val changedA = phonetic(heard.substring(prefix, heard.length - suffix))
                val changedB = phonetic(word.substring(prefix, word.length - suffix))
                if (!confirmed && changedA.isNotEmpty() && changedB.isNotEmpty() &&
                    distance(changedA, changedB).toDouble() / maxOf(changedA.length, changedB.length) > 0.7) continue
            }
            // Adjacent words may also be corrected: accept both observed and confirmed context.
            val left = old.substring(0, from).trimEnd().takeLastWhile { it.isLetterOrDigit() }.takeLast(4)
            val right = old.substring(until).trimStart().takeWhile { it.isLetterOrDigit() }.take(4)
            for (before in setOf(rule.left, left)) for (after in setOf(rule.right, right)) rules.add(rule.copy(left = before, right = after))
        }
        return rules.distinct()
    }
    private fun latinPart(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c.isDigit() || c == '\'' || c == '-'
}
