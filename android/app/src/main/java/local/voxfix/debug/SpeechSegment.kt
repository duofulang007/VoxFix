package local.voxfix.debug

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/** A second pass may improve a segment, but must never hold up live text or release. */
internal class SpeechSegment(private var text: String, private var second: Future<String>?) {
    fun current(): String {
        val pending = second ?: return text
        if (!pending.isDone) return text
        text = try { SpeechEngine.refine(text, pending.get()) }
        catch (_: ExecutionException) { text }
        catch (_: CancellationException) { text }
        second = null
        return text
    }

    fun finish(): String {
        val result = current()
        second?.cancel(false)
        second = null
        return result
    }
}
