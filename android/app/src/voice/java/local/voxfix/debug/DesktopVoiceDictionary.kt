package local.voxfix.debug

import java.io.File
import java.io.StringReader
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject

/** Desktop replacement rules only. This never opens the IME's existing personal lexicon. */
internal class DesktopVoiceDictionary(val entries: List<Entry> = emptyList(), val hotwords: List<String> = emptyList()) {
    internal data class Entry(val to: String, val from: List<String>, val chineseOnly: Boolean = false)
    private val rules = entries.mapNotNull { entry ->
        if (entry.to.isEmpty()) return@mapNotNull null
        val variants = entry.from.sortedByDescending { it.trim(DesktopVoiceRules::space).length }.mapNotNull { raw ->
            val value = raw.trim(DesktopVoiceRules::space)
            if (value.isEmpty()) return@mapNotNull null
            var pattern = Regex.escape(value)
            if (value.all { it.code < 128 && (it.isLetterOrDigit() || DesktopVoiceRules.space(it) || it == '_') })
                pattern = "(?<![A-Za-z0-9_])$pattern(?![A-Za-z0-9_])"
            pattern
        }
        if (variants.isEmpty()) null else Triple(Regex(variants.joinToString("|"), RegexOption.IGNORE_CASE), entry.to, entry.chineseOnly)
    }

    fun correct(text: String, chineseContext: Boolean = false): String = rules.fold(text) { value, (pattern, to, onlyChinese) ->
        if (!onlyChinese || chineseContext || DesktopVoiceRules.chinese(value)) pattern.replace(value) { to } else value
    }

    companion object {
        fun parse(json: String): DesktopVoiceDictionary {
            val value = json.removePrefix("\uFEFF")
            // Android JSONTokener accepts comments/single quotes; desktop files use strict JSON.
            JsonReader(StringReader(value)).use { reader ->
                reader.isLenient = false
                reader.skipValue()
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Invalid dictionary JSON" }
            }
            val parser = org.json.JSONTokener(value)
            val root = parser.nextValue()
            require(parser.nextClean() == '\u0000') { "Invalid dictionary JSON" }
            val rows = (if (root is JSONObject) root.opt("rules") else root) as? JSONArray ?: JSONArray()
            val entries = (0 until rows.length()).mapNotNull { index ->
                val row = rows.opt(index) as? JSONObject ?: return@mapNotNull null
                val to = row.opt("to") as? String ?: return@mapNotNull null
                val from = row.opt("from") as? JSONArray ?: return@mapNotNull null
                Entry(to, (0 until from.length()).mapNotNull { from.opt(it) as? String }, row.opt("context") == "zh")
            }
            val words = (root as? JSONObject)?.optJSONArray("hotwords")
            val hotwords = if (words == null) emptyList() else (0 until words.length()).mapNotNull {
                (words.opt(it) as? String)?.trim(DesktopVoiceRules::space)?.takeIf(String::isNotEmpty)
            }.take(64)
            return DesktopVoiceDictionary(entries, hotwords)
        }

        fun load(file: File?): DesktopVoiceDictionary = try {
            if (file == null || !file.isFile) DesktopVoiceDictionary() else parse(file.readText(Charsets.UTF_8))
        } catch (_: Exception) { DesktopVoiceDictionary() }
    }
}
