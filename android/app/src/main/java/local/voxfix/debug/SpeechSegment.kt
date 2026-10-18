package local.voxfix.debug

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 二遍结果：文本 + SenseVoice 内建 LID 报出的语种标签（`<|zh|>`/`<|yue|>` 这类首 token）。
 *
 * 语种由二遍自己判（`language = "auto"`），调用方不再假设是中文：粤语/日/韩的整句采用逻辑
 * 依赖这个标签，见 [SpeechEngine.refine]。
 */
internal class SecondPass(val text: String, val lang: String)

/** Live text never waits; final release may give the last segment a bounded correction budget. */
internal class SpeechSegment(private var text: String, private var second: Future<SecondPass>?,
    private val choose: (String, SecondPass) -> String = { raw, candidate -> SpeechEngine.refine(raw, candidate.text, candidate.lang) }) {
    fun current(): String {
        val pending = second ?: return text
        if (!pending.isDone) return text
        text = try { choose(text, pending.get()) }
        catch (_: ExecutionException) { text }
        catch (_: CancellationException) { text }
        second = null
        return text
    }

    fun finish(waitMs: Long = 0): String {
        if (waitMs > 0) {
            try { second?.get(waitMs, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) { /* Keep streaming text when the budget expires. */ }
            catch (_: ExecutionException) { /* current() applies the existing fallback. */ }
            catch (_: CancellationException) { /* current() applies the existing fallback. */ }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        val result = current()
        second?.cancel(false)
        second = null
        return result
    }
}
