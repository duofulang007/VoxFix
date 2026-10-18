package local.voxfix.debug

import java.util.Locale
import kotlin.math.ln

internal data class VoiceWord(val word: String, val code: String = "", val manual: Boolean = false, val hits: Int = 1)
internal data class VoiceCorrection(val heard: String, val word: String, val left: String, val right: String, val hits: Int = 1)

/** A session snapshot: live decoding never queries SQLite or mutates learning. */
internal class VoiceLexicon(val words: List<VoiceWord> = emptyList(), val corrections: List<VoiceCorrection> = emptyList()) {
    private val latinSpelling = words.filter { (it.manual || it.hits >= 2) && it.word.matches(Regex("[A-Za-z][A-Za-z0-9 '-]{1,59}")) }
        .groupBy { it.word.lowercase(Locale.ROOT) }.values.mapNotNull { group ->
            val manual = group.filter { it.manual }.ifEmpty { group }
            val best = manual.maxOf { it.hits }
            manual.filter { it.hits == best }.singleOrNull()
        }
        .map { Regex("(?i)(?<![A-Za-z0-9'-])" + Regex.escape(it.word) + "(?![A-Za-z0-9'-])") to it.word }
    fun hotwords(tokens: Set<String>): String = words.filter { row ->
        row.word.all { c -> if (c in '\u3400'..'\u9fff') c.toString() in tokens else c == ' ' || c.isDigit() || c in 'a'..'z' || c in 'A'..'Z' || (c == '\'' || c == '-') && c.toString() in tokens }
    }.take(96).joinToString("/") {
        val score = if (it.manual) 3.0 else (0.75 + ln(it.hits.toDouble() + 1) * 0.6).coerceAtMost(3.0)
        "${it.word.uppercase(Locale.ROOT)} :${String.format(Locale.ROOT, "%.2f", score)}"
    }

    fun correct(text: String, phonetic: ((String) -> String)? = null, wordBoundaries: ((String) -> Set<Int>)? = null): String {
        // A unique, context-supported correction; never feed our own output back through rules.
        val edits = mutableListOf<Triple<Int, Int, String>>()
        val confidence = corrections.groupBy { Triple(it.word, it.left, it.right) }
            .mapValues { (_, rows) -> rows.sumOf { it.hits.toLong() } }
        val counts = corrections.groupBy { it.word }.mapValues { (_, rows) -> rows.maxOf { it.hits } }.toMutableMap()
        words.forEach { counts[it.word] = it.hits }
        fun evidenceOf(rule: VoiceCorrection) = minOf(confidence.getValue(Triple(rule.word, rule.left, rule.right)), counts.getValue(rule.word).toLong())
        if (phonetic != null && wordBoundaries != null) {
            val boundaries = wordBoundaries(text)
            val manual = words.filter { row ->
                val evidence = corrections.filter { it.word == row.word }
                (row.manual || row.hits >= 3 && evidence.map { it.left to it.right }.distinct().size >= 2 &&
                    evidence.any { phonetic(it.heard) == phonetic(row.word) }) &&
                    row.word.length in 2..16 && row.word.all { c -> c in '\u3400'..'\u9fff' }
            }
                .groupBy { phonetic(it.word) }.filter { it.key.isNotEmpty() && it.value.size == 1 }
            for ((sound, rows) in manual) {
                val target = rows.single().word
                for (start in boundaries) {
                    val end = start + target.length
                    if (end > text.length || end !in boundaries) continue
                    val heard = text.substring(start, end)
                    if (heard != target && heard.all { it in '\u3400'..'\u9fff' } && phonetic(heard) == sound)
                        edits.add(Triple(start, end, target))
                }
            }
        }
        // Match source spans only; confirmed neighboring edits may support their context.
        for (pass in 0 until 8) {
        val before = edits.distinct().size
        val support = edits.distinct().filter { edit -> edits.none { it != edit && it.first < edit.second && it.second > edit.first } }
        fun context(from: Int, until: Int): String {
            var part = text.substring(from, until)
            for ((start, end, target) in support.filter { it.first >= from && it.second <= until }.sortedByDescending { it.first })
                part = part.replaceRange(start - from, end - from, target)
            return part
        }
        for (rule in corrections) {
            val evidence = evidenceOf(rule)
            val source = rule.heard.trimEnd('.', '。')
            val chinese = (source + rule.word).all { it in '\u3400'..'\u9fff' }
            val a = if (chinese && phonetic != null) phonetic(source) else ""
            val b = if (chinese && phonetic != null) phonetic(rule.word) else ""
            val required = if (a.isNotEmpty() && b.isNotEmpty() &&
                VoiceEditExtractor.distance(a, b).toDouble() / maxOf(a.length, b.length) > 0.3) 3 else 2
            if (evidence < required && words.none { it.word == rule.word && it.manual }) continue
            val competing = corrections.filter { it.heard.equals(rule.heard, true) && it.left == rule.left && it.right == rule.right && it.word != rule.word }
            if (competing.any { evidenceOf(it) >= evidence }) continue
            fun anchored(start: Int, end: Int): Boolean {
                fun matches(left: String, right: String) =
                    (if (rule.left.isEmpty()) start == 0 || !text[start - 1].isLetterOrDigit() else left.trimEnd().endsWith(rule.left)) &&
                    (if (rule.right.isEmpty()) end == text.length || !text[end].isLetterOrDigit() else right.trimStart().startsWith(rule.right))
                return (!rule.heard.any { it in 'A'..'Z' || it in 'a'..'z' } ||
                    (start == 0 || !text[start - 1].let { it in 'A'..'Z' || it in 'a'..'z' }) &&
                    (end == text.length || !text[end].let { it in 'A'..'Z' || it in 'a'..'z' })) &&
                    (matches(text.substring(0, start), text.substring(end)) || support.isNotEmpty() && matches(context(0, start), context(end, text.length)))
            }
            var from = 0
            while (from < text.length) {
                val start = text.indexOf(rule.heard, from, ignoreCase = rule.heard.any { it in 'A'..'Z' || it in 'a'..'z' })
                if (start < 0) break
                val end = start + rule.heard.length
                if (anchored(start, end)) {
                    edits.add(Triple(start, end, rule.word))
                }
                from = end
            }
            // The decoder may alternate 月/悦; keep the learned context, compare only that word's sound.
            if (phonetic != null) {
                val starts = mutableListOf<Int>()
                if (rule.left.isNotEmpty()) {
                    var at = text.indexOf(rule.left)
                    while (at >= 0) { starts.add(at + rule.left.length); at = text.indexOf(rule.left, at + 1) }
                } else if (rule.right.isNotEmpty()) {
                    var at = text.indexOf(rule.right)
                    while (at >= 0) { starts.add(at - rule.heard.length); at = text.indexOf(rule.right, at + 1) }
                } else starts.addAll(text.indices.filter { it == 0 || !text[it - 1].isLetterOrDigit() })
                if (starts.isEmpty()) continue
                val pronunciation = phonetic(rule.heard)
                if (pronunciation.isEmpty()) continue
                for (start in starts) {
                    val end = start + rule.heard.length
                    if (start < 0 || end > text.length || !anchored(start, end)) continue
                    val heard = text.substring(start, end)
                    if (heard != rule.heard && heard != rule.word && heard.trimEnd('.', '。').isNotEmpty() && heard.trimEnd('.', '。').all { it in '\u3400'..'\u9fff' } &&
                        phonetic(heard) == pronunciation) edits.add(Triple(start, end, rule.word))
                }
            }
        }
        if (edits.distinct().size == before) break
        }
        var result = text
        var boundary = text.length
        for ((start, end, target) in edits.distinct().sortedByDescending { it.first }) {
            if (end > boundary || edits.any { it.first == start && it.third != target }) continue
            result = result.replaceRange(start, end, target); boundary = start
        }
        // Restore explicit Latin spelling only at complete word boundaries, not inside another word.
        for ((pattern, spelling) in latinSpelling) {
            result = pattern.replace(result) { spelling }
        }
        return result
    }

    fun protect(raw: String, refined: String, phonetic: (String) -> String): String {
        if (raw == refined) return raw
        val hasPersonal = words.any { (it.manual || it.hits >= 2) && it.word.any { c -> c in '\u3400'..'\u9fff' } && raw.contains(it.word) && !refined.contains(it.word) }
        return if (hasPersonal && raw.filter { it.isLetterOrDigit() }.length == refined.filter { it.isLetterOrDigit() }.length &&
            phonetic(raw) == phonetic(refined)) raw else refined
    }

    companion object {
        fun validateCorrection(rule: VoiceCorrection) {
            require(rule.heard.length in 1..32 && rule.word.length in 2..32 && rule.heard != rule.word &&
                sourceTerm.matches(rule.heard) && term.matches(rule.word) && rule.word.any { it.isLetter() } &&
                rule.heard.filter { it.isDigit() } == rule.word.filter { it.isDigit() } &&
                negatives(rule.heard) == negatives(rule.word) &&
                rule.left.length <= 4 && rule.right.length <= 4 && (rule.left + rule.right).none { it.isISOControl() } &&
                rule.hits in 1..1000000) { "语音纠正记录无效" }
        }
        fun validateWord(word: String) {
            require(word.length in 2..60 && word.any { it.isLetter() } && word.matches(Regex("[\u3400-\u9fffA-Za-z0-9 '-]+")) && word == word.trim()) { "请输入2至60字的中文、英文词语，可含数字、空格、连字符或撇号" }
        }
        private val term = Regex("[\u3400-\u9fffA-Za-z0-9]+(?:[ '-][\u3400-\u9fffA-Za-z0-9]+)*")
        private val sourceTerm = Regex(term.pattern + "[.。]?")
        private fun negatives(text: String) = Regex("[不没别勿未非]|(?i)(?<![A-Za-z])(?:no|not|never)(?![A-Za-z])").findAll(text).map { it.value.lowercase(Locale.ROOT) }.toList()
    }
}
