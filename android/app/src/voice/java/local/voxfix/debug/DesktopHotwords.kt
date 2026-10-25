package local.voxfix.debug

import java.util.Locale

/**
 * Decode-time hotword rescue for SenseVoice, ported from the desktop
 * `app/hotwords.py` contract (see docs/热词偏置-救援通道-2026-10-22.md):
 * near-miss detection over whole alphanumeric runs, a trie of every vocabulary
 * segmentation of a word, a per-frame logit bonus on the next trie token, and an
 * acceptance guard that only keeps a decode which spells more of the word list.
 */
internal object DesktopHotwords {
    const val BOOST = 3.0f
    const val TAG_TOKENS = 4
    const val SEGMENTATION_EXTRA = 1
    const val SEGMENTATION_CAP = 64

    private val run = Regex("[a-z0-9]+")
    private val vowels = "aeiouy"

    /** Lowercase alphanumerics only, so `J SON` and `json` compare equal. */
    fun compact(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        val out = StringBuilder(lower.length)
        for (char in lower) if (char in 'a'..'z' || char in '0'..'9') out.append(char)
        return out.toString()
    }

    /** Levenshtein distance, giving up as soon as it exceeds `limit`. */
    fun editDistance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val row = IntArray(b.length + 1)
            row[0] = i
            var best = i
            for (j in 1..b.length) {
                val value = minOf(row[j - 1] + 1, previous[j] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                row[j] = value
                if (value < best) best = value
            }
            if (best > limit) return limit + 1
            previous = row
        }
        return previous[b.length]
    }

    /** Word-like runs and adjacent pairs, so `J SON` is still one candidate. */
    private fun candidates(text: String): List<String> {
        val runs = run.findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
        if (runs.isEmpty()) return emptyList()
        return runs + (0 until runs.size - 1).map { runs[it] + runs[it + 1] }
    }

    /** Consonant skeleton, so `Jason` and `json` both collapse to `jsn`. */
    private fun skeleton(text: String): String = text.filter { it !in vowels }

    /** Words the text nearly spells: `pyython`/python, `gitthub`/github, `Jason`/json. */
    fun nearMiss(text: String, words: List<String>): List<String> {
        val low = text.lowercase(Locale.ROOT)
        val candidates = candidates(text)
        if (candidates.isEmpty()) return emptyList()
        val shortText = compact(text).length
        val found = mutableListOf<String>()
        for (word in words) {
            val target = compact(word)
            if (target.isEmpty()) continue
            if (low.contains(target)) continue
            val limit = if (target.length >= 8) 2 else 1
            if (candidates.any { editDistance(it, target, limit) <= limit }) { found.add(word); continue }
            if (target.length >= 4) {
                val bones = skeleton(target)
                if (bones.length >= 2 && candidates.any { skeleton(it) == bones }) { found.add(word); continue }
            }
            // A bare acronym ("API" heard as "a") only when the utterance is that fragment.
            if (shortText <= target.length + 2 && candidates.any { it.isNotEmpty() && target.startsWith(it) }) found.add(word)
        }
        return found
    }

    fun hotwordHits(text: String, words: List<String>): Int {
        val flat = compact(text)
        return words.count { val word = compact(it); word.isNotEmpty() && flat.contains(word) }
    }

    /** Words spelled out contiguously, so `J SON` does not count as `JSON`. */
    fun strongHits(text: String, words: List<String>): Int {
        val low = text.lowercase(Locale.ROOT)
        return words.count { val word = compact(it); word.isNotEmpty() && low.contains(word) }
    }

    /** Keep the biased decode only when it spells more of the list and is not truncated. */
    fun accept(text: String, biased: String, words: List<String>): String {
        val gained = strongHits(biased, words) - strongHits(text, words)
        if (gained < 0) return text
        if (gained == 0 && hotwordHits(biased, words) <= hotwordHits(text, words)) return text
        if (biased.length < 0.6 * text.length) return text
        return biased
    }

    /** Every vocabulary segmentation of `text` within (minimal tokens + extra). */
    fun segmentations(text: String, pieces: Map<String, Int>, extra: Int = SEGMENTATION_EXTRA, cap: Int = SEGMENTATION_CAP): List<List<Int>> {
        val size = text.length
        val best = IntArray(size + 1) { -1 }
        best[0] = 0
        for (i in 0 until size) {
            if (best[i] < 0) continue
            for (j in size downTo i + 1) {
                if (pieces.containsKey(text.substring(i, j))) {
                    val candidate = best[i] + 1
                    if (best[j] < 0 || candidate < best[j]) best[j] = candidate
                }
            }
        }
        if (best[size] < 0) return emptyList()
        val limit = best[size] + extra
        val paths = mutableListOf<List<Int>>()
        fun walk(index: Int, path: MutableList<Int>) {
            if (paths.size >= cap) return
            if (index == size) { paths.add(path.toList()); return }
            for (j in size downTo index + 1) {
                val piece = text.substring(index, j)
                val id = pieces[piece] ?: continue
                if (path.size < limit) { path.add(id); walk(j, path); path.removeAt(path.size - 1) }
            }
        }
        walk(0, mutableListOf())
        return paths
    }

    fun buildTrie(words: List<String>, pieces: Map<String, Int>): HotwordTrie {
        val root = HotwordTrie()
        for (word in words) {
            val variants = linkedSetOf(word, word.lowercase(Locale.ROOT), "\u2581$word", "\u2581" + word.lowercase(Locale.ROOT))
            for (variant in variants) {
                for (path in segmentations(variant, pieces)) {
                    var node = root
                    for (token in path) node = node.children.getOrPut(token) { HotwordTrie() }
                    node.word = word
                }
            }
        }
        return root
    }
}

internal class HotwordTrie {
    val children = HashMap<Int, HotwordTrie>()
    var word: String? = null
}

/** Vocabulary pieces of a SenseVoice `tokens.txt`, with the blank/tag handling the model needs. */
internal class HotwordPieces(text: String) {
    val pieces = HashMap<String, Int>()
    val strings = HashMap<Int, String>()

    init {
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val split = line.lastIndexOf(' ')
            if (split <= 0) continue
            val piece = line.substring(0, split)
            val index = line.substring(split + 1).trim().toIntOrNull() ?: continue
            strings[index] = piece.replace('\u2581', ' ')
            if (!(piece.startsWith("<") && piece.endsWith(">"))) pieces[piece] = index
        }
    }
}
