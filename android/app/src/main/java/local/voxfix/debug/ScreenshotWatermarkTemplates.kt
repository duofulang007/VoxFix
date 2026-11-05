package local.voxfix.debug

import java.util.Calendar

/**
 * Snow Shot 1.1.8 `document.rs:resolve_watermark_template`: the watermark template may reference
 * `{text}` (the plain watermark text) and timestamp tokens `YYYY MM DD HH mm ss`. A nested or
 * unknown placeholder stays verbatim, an unclosed `{` appends the rest as-is, and timestamp
 * tokens only expand when the template has an application time (the moment the template was set).
 * Pure Kotlin so the JVM suite can pin the exact contract.
 */
internal object ScreenshotWatermarkTemplates {
    private val tokens = listOf("YYYY", "MM", "DD", "HH", "mm", "ss")
    private fun containsTimestampToken(value: String) = tokens.any { value.contains(it) }
    private fun expand(value: String, time: Calendar): String {
        fun token(pattern: String, value: Int) = String.format(java.util.Locale.US, pattern, value)
        return value
            .replace("YYYY", token("%04d", time.get(Calendar.YEAR)))
            .replace("MM", token("%02d", time.get(Calendar.MONTH) + 1))
            .replace("DD", token("%02d", time.get(Calendar.DAY_OF_MONTH)))
            .replace("HH", token("%02d", time.get(Calendar.HOUR_OF_DAY)))
            .replace("mm", token("%02d", time.get(Calendar.MINUTE)))
            .replace("ss", token("%02d", time.get(Calendar.SECOND)))
    }

    /** [appliedAtMillis] is the recorded application time; 0 keeps placeholders literal. */
    fun resolve(template: String,text: String,appliedAtMillis: Long): String {
        if (template.isEmpty()) return text
        val time = if (appliedAtMillis > 0) Calendar.getInstance().apply { timeInMillis = appliedAtMillis } else null
        val out = StringBuilder(template.length + text.length)
        var cursor = 0
        while (true) {
            val open = template.indexOf('{', cursor)
            if (open < 0) break
            out.append(template, cursor, open)
            var depth = 0; var nested = false; var close = -1
            for (relative in open until template.length) {
                when (template[relative]) {
                    '{' -> { depth++; nested = nested || depth > 1 }
                    '}' -> if (depth > 0) { depth--; if (depth == 0) { close = relative; break } }
                }
            }
            if (close < 0) { out.append(template, open, template.length); return out.toString() }
            val placeholder = template.substring(open, close + 1)
            if (nested) out.append(placeholder)
            else {
                val content = template.substring(open + 1, close)
                when {
                    content == "text" -> out.append(text)
                    containsTimestampToken(content) && time != null -> out.append(expand(content, time))
                    else -> out.append(placeholder)
                }
            }
            cursor = close + 1
        }
        out.append(template, cursor, template.length)
        return out.toString()
    }
}
