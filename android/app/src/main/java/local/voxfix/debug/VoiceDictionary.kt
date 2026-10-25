package local.voxfix.debug

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * The personal dictionary, kept in the same file and format as the desktop app
 * (`dictionary.json`): every word goes into `hotwords`, and only words that have
 * misheard spellings get a `rules` entry. The recognizer watches the file, so a
 * save applies from the next sentence without a restart.
 *
 * One file, one editor, one consumer. No database copy, no second rule table.
 */
internal class VoiceDictionary(entries: List<Entry> = emptyList()) {
    internal data class Entry(var word: String, var variants: MutableList<String>, var context: String = "")

    /** Words in file order; the first [HOTWORD_LIMIT] take part in decode-time rescue. */
    val entries: MutableList<Entry> = entries.toMutableList()

    fun indexOf(word: String): Int = entries.indexOfFirst { it.word.equals(word, ignoreCase = true) }

    fun ruleCount(): Int = entries.count { it.variants.isNotEmpty() }

    fun rescueCount(): Int = minOf(entries.size, HOTWORD_LIMIT)

    fun conflicts(): String? {
        val owners = HashMap<String, String>()
        for (entry in entries) for (variant in entry.variants) {
            val previous = owners.put(variant.lowercase(Locale.ROOT), entry.word)
            if (previous != null && !previous.equals(entry.word, ignoreCase = true))
                return "错法冲突：$variant 同时属于 $previous 和 ${entry.word}"
        }
        return null
    }

    /** Writes the desktop format: every word in `hotwords`, rules only for words with variants. */
    fun serialize(): String {
        val hotwords = entries.joinToString(", ") { quote(it.word) }
        val rules = entries.filter { it.variants.isNotEmpty() }.joinToString(",") { entry ->
            // Keep the line order the person typed; the matcher sorts for longest-first itself.
            val from = entry.variants.joinToString(", ") { quote(it) }
            "\n    { \"to\": ${quote(entry.word)}, \"from\": [$from]" +
                (if (entry.context.isEmpty()) "" else ", \"context\": ${quote(entry.context)}") + " }"
        }
        return "{\n  \"hotwords\": [$hotwords],\n  \"rules\": [$rules\n  ]\n}\n"
    }

    companion object {
        const val HOTWORD_LIMIT = 64

        private fun quote(value: String): String {
            val text = StringBuilder("\"")
            for (char in value) when {
                char == '"' || char == '\\' -> text.append('\\').append(char)
                char < ' ' -> text.append("\\u").append(String.format(Locale.ROOT, "%04x", char.code))
                else -> text.append(char)
            }
            return text.append('"').toString()
        }

        /**
         * Pasting splits on sentence marks, keeps `(a,b)` / `"x,y"` intact, collapses
         * whitespace, strips one wrapping quote pair and de-duplicates ignoring case.
         * Saving never re-splits existing lines (`paste = false`).
         */
        fun cleanVariants(source: String, paste: Boolean): List<String> {
            val opens = "\"'「“‘([（【"
            val closes = "\"'」”’)]）】"
            val pieces = mutableListOf<String>()
            val piece = StringBuilder()
            val closers = ArrayDeque<Char>()
            for (index in source.indices) {
                val char = source[index]
                val newline = char == '\r' || char == '\n'
                if (paste && !newline) {
                    if (closers.isNotEmpty() && char == closers.last()) {
                        closers.removeLast(); piece.append(char); continue
                    }
                    val quote = opens.indexOf(char)
                    if (quote >= 0 && source.indexOf(closes[quote], index + 1) >= 0 &&
                        (quote >= 5 || piece.toString().trim().isEmpty() || closers.isNotEmpty())) {
                        closers.addLast(closes[quote]); piece.append(char); continue
                    }
                }
                if (newline || (paste && closers.isEmpty() && char in "。！？；，、,;|｜\t")) {
                    pieces.add(piece.toString()); piece.setLength(0); closers.clear()
                } else piece.append(char)
            }
            pieces.add(piece.toString())
            val kept = LinkedHashMap<String, String>()
            for (part in pieces) {
                val compact = StringBuilder()
                var space = false
                for (char in part.trim()) {
                    if (char.isWhitespace() || char == '\u3000') { space = compact.isNotEmpty(); continue }
                    if (space) compact.append(' ')
                    compact.append(char); space = false
                }
                var text = compact.toString()
                if (paste && text.length >= 2) {
                    val quote = opens.substring(0, 5).indexOf(text[0])
                    if (quote >= 0 && text[text.length - 1] == closes[quote]) text = text.substring(1, text.length - 1).trim()
                }
                if (text.isEmpty()) continue
                kept.putIfAbsent(text.lowercase(Locale.ROOT), text)
            }
            return kept.values.toList()
        }

        /** Reads the desktop file; a broken file yields an empty dictionary instead of a crash. */
        fun parse(text: String): VoiceDictionary {
            val root = runCatching { org.json.JSONObject(text.removePrefix("\uFEFF")) }.getOrNull() ?: return VoiceDictionary()
            val entries = mutableListOf<Entry>()
            val words = root.optJSONArray("hotwords")
            if (words != null) for (index in 0 until words.length()) {
                val word = (words.opt(index) as? String)?.trim().orEmpty()
                if (word.isEmpty() || entries.any { it.word.equals(word, ignoreCase = true) }) continue
                entries.add(Entry(word, mutableListOf()))
            }
            val rules = root.optJSONArray("rules")
            if (rules != null) for (index in 0 until rules.length()) {
                val rule = rules.optJSONObject(index) ?: continue
                val word = rule.optString("to").trim()
                if (word.isEmpty()) continue
                var entry = entries.firstOrNull { it.word.equals(word, ignoreCase = true) }
                if (entry == null) { entry = Entry(word, mutableListOf()); entries.add(entry) }
                entry.context = rule.optString("context")
                val from = rule.optJSONArray("from") ?: continue
                for (position in 0 until from.length()) {
                    val variant = (from.opt(position) as? String)?.trim().orEmpty()
                    if (variant.isNotEmpty() && entry.variants.none { it == variant }) entry.variants.add(variant)
                }
            }
            return VoiceDictionary(entries)
        }
    }
}

/**
 * File plumbing for the dictionary: seed once from the bundled desktop file, atomic
 * saves, external-change detection and a compiled cache keyed by mtime so the
 * recognizer never rebuilds regexes inside a segment.
 */
internal object VoiceDictionaryStore {
    const val NAME = "voice-dictionary.json"
    const val ASSET = "dictionary/desktop-v1.json"
    private const val SEED = "voice-dictionary-seeded"

    @Volatile private var cachedFile: File? = null
    @Volatile private var cachedStamp = -1L
    @Volatile private var cached: DesktopVoiceDictionary = DesktopVoiceDictionary()

    fun file(context: Context): File = File(context.filesDir, NAME)

    /** First run only: the desktop words plus the words the person had added by hand. */
    fun ensure(context: Context, store: PersonalLexicon? = null): File {
        val target = file(context)
        if (target.isFile) return target
        val base = VoiceDictionary.parse(context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) })
        // `manual` words were an explicit choice; everything else in the old voice table was
        // learned evidence, which this module deliberately does not import.
        runCatching { store?.archivedManualWords() }.getOrNull().orEmpty().forEach { word ->
            if (base.indexOf(word) < 0) base.entries.add(VoiceDictionary.Entry(word, mutableListOf()))
        }
        write(target, base)
        return target
    }

    fun write(target: File, dictionary: VoiceDictionary) {
        val temp = File(target.parentFile, "$NAME.tmp")
        temp.writeText(dictionary.serialize(), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(dictionary.serialize(), Charsets.UTF_8)
            temp.delete()
        }
    }

    /** Compiled rules for the recognizer; reloads only when the file changes on disk. */
    fun load(target: File?): DesktopVoiceDictionary {
        if (target == null || !target.isFile) return DesktopVoiceDictionary()
        val stamp = target.lastModified()
        cachedFile?.let { if (it == target && stamp == cachedStamp) return cached }
        val dictionary = DesktopVoiceDictionary.parse(runCatching { target.readText(Charsets.UTF_8) }.getOrNull().orEmpty())
        cachedFile = target; cachedStamp = stamp; cached = dictionary
        return dictionary
    }

    /** Latest compiled snapshot for code that has no path at hand (rescue pool). */
    fun current(target: File?): DesktopVoiceDictionary = load(target)

    fun invalidate() { cachedStamp = -1L }
}
