package local.voxfix.debug

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/**
 * 二遍结果：文本 + SenseVoice 内建 LID 报出的语种标签（`<|zh|>`/`<|yue|>` 这类首 token）。
 *
 * 语种由二遍自己判（`language = "auto"`），调用方不再假设是中文：粤语/日/韩的整句采用逻辑
 * 依赖这个标签，见 [SpeechEngine.refine]。
 */
internal class SecondPass(val text: String, val lang: String)

/** A second pass may improve a segment, but must never hold up live text or release. */
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

    fun finish(): String {
        val result = current()
        second?.cancel(false)
        second = null
        return result
    }
}
