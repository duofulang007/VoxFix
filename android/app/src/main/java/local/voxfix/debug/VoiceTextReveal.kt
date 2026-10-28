package local.voxfix.debug

import java.text.BreakIterator
import kotlin.math.ceil

/** Desktop pacing: merge candidates, preserve segment order, account only successful writes. */
internal class VoiceTextReveal(private val apply: (String, Int, Boolean) -> Boolean) {
    private data class Pending(val key: Int, var text: String, var final: Boolean,
                               val started: Long, var shown: String = "", var next: Long = 0)
    private val pending = mutableListOf<Pending>()
    private var completed = -1
    val active get() = pending.isNotEmpty()

    fun offer(text: String, key: Int, final: Boolean, now: Long) {
        if (key <= completed) return
        val entry = pending.find { it.key == key }
        if (entry == null) {
            check(pending.size < 24) { "出字队列积压，请结束本次录音后重试" }
            pending.add(Pending(key, text, final, now)); pending.sortBy { it.key }
        } else if (!entry.final || final) { entry.text = text; entry.final = final }
    }

    /** False means no timer is needed until another candidate arrives. */
    fun tick(now: Long, immediate: Boolean = false): Boolean {
        while (pending.isNotEmpty()) {
            val entry = pending.first()
            if (!entry.final && entry.text.length < entry.shown.length) return false
            if (!immediate && now < entry.next) return true
            if (entry.shown == entry.text && !entry.final) return false
            val text = if (immediate) entry.text else step(entry.shown, entry.text, now - entry.started)
            val done = text == entry.text && entry.final
            if (!apply(text, entry.key, done)) {
                if (!entry.final || done || !apply(entry.text, entry.key, true)) return false
                completed = maxOf(completed, entry.key); pending.removeAt(0)
                continue
            }
            entry.shown = text
            if (done) { completed = maxOf(completed, entry.key); pending.removeAt(0) }
            else {
                entry.next = now + 35
                return text != entry.text
            }
        }
        return false
    }

    fun clear(): Set<Int> = pending.map { it.key }.toSet().also { pending.clear() }
    fun finals(): List<String> = pending.filter { it.final }.map { it.text }
    fun reset() { pending.clear(); completed = -1 }

    companion object {
        // shortcut: no acoustic character timestamps; pace arriving candidates until the model supplies them.
        fun step(shown: String, wanted: String, age: Long): String {
            if (wanted.length <= shown.length) return wanted
            val breaks = BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
            breaks.setText(wanted)
            val ends = mutableListOf<Int>()
            var end = breaks.next()
            while (end != BreakIterator.DONE) { ends.add(end); end = breaks.next() }
            val index = ends.count { it <= shown.length }
            val count = maxOf(1, ceil((ends.size - index) * 35.0 / maxOf(35L, 240L - age)).toInt())
            return wanted.substring(0, ends[minOf(ends.lastIndex, index + count - 1)])
        }
    }
}
