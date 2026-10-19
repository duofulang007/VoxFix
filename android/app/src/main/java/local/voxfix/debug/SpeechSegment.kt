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
internal class SecondPass(val text: String, val lang: String, val boundaryAgreement: Boolean = false,
                          val cantoneseAgreement: Boolean = false)

/** Live text never waits; final release may give the last segment a bounded correction budget. */
internal class SpeechSegment(private var text: String, private var second: Future<SecondPass>?,
    private val choose: (String, SecondPass) -> String = { raw, candidate ->
        SpeechEngine.refine(raw, candidate.text, candidate.lang, candidate.boundaryAgreement, cantoneseAgreement = candidate.cantoneseAgreement) }) {
    fun current(): String {
        val pending = second ?: return text
        if (!pending.isDone) return text
        text = try { choose(text, pending.get()) }
        catch (_: ExecutionException) { text }
        catch (_: CancellationException) { text }
        second = null
        return text
    }

    fun finish(waitMs: Long = 0, cancel: Boolean = true): String {
        if (waitMs > 0) {
            try { second?.get(waitMs, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) { /* Keep streaming text when the budget expires. */ }
            catch (_: ExecutionException) { /* current() applies the existing fallback. */ }
            catch (_: CancellationException) { /* current() applies the existing fallback. */ }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        val result = current()
        if (cancel) second?.cancel(false)
        if (cancel) second = null
        return result
    }

    /** 二遍结果还未读取；完成与交接同时发生时也必须补发。 */
    fun pending(): Boolean = second != null

    /** 等二遍跑完再给出这段话的最终文本；超时或失败返回 null（调用方保持原样）。 */
    fun awaitRefined(ms: Long): String? {
        val waiting = second ?: return null
        return try {
            val result = choose(text, waiting.get(ms, TimeUnit.MILLISECONDS))
            text = result
            second = null
            result
        } catch (_: TimeoutException) { null }
        catch (_: ExecutionException) { null }
        catch (_: CancellationException) { null }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); null }
    }
}
