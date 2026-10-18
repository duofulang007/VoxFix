package local.voxfix.debug

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 离线标点恢复。
 *
 * 通用SenseVoice本身支持标点；二遍超时退回流式文字时仍需要独立补标点。
 *
 * 这个模型只吃文本、不需要音频，因此尾段直接松手、二遍尚未完成时同样能补标点，
 * 不必等慢速二遍。
 */
internal class Punctuator(private val assets: AssetManager) {

    private var loader: FutureTask<OfflinePunctuation>? = null
    private val abandoned = AtomicBoolean(false)

    /** 后台开始加载。重复调用无副作用；模型缺失时只记一次日志，之后一直返回 null。 */
    fun start() {
        if (loader != null) return
        val task = FutureTask {
            val started = System.nanoTime()
            val engine = OfflinePunctuation(assets, OfflinePunctuationConfig(
                model = OfflinePunctuationModelConfig(
                    ctTransformer = MODEL, numThreads = THREADS, debug = false, provider = "cpu")))
            // release() 之后才加载完的对象必须自己收掉，否则 native 内存没人释放。
            if (abandoned.get()) { engine.release(); throw CancellationException("punctuator released") }
            Log.i("VoxFix", "ASR_PUNCT_READY elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            engine
        }
        loader = task
        try { WORKER.execute(task) } catch (_: RejectedExecutionException) { loader = null }
    }

    /**
     * 取标点结果；模型在 [budgetMs] 内没准备好就返回 null，调用方退回流式文本。
     * 此预算只约束加载等待，不包含已就绪模型的同步推理时间。
     */
    fun add(text: String, budgetMs: Long = RELEASE_BUDGET_MS): String? {
        if (text.isBlank()) return null
        val task = loader ?: return null
        return try {
            val engine = if (task.isDone) task.get() else task.get(budgetMs, TimeUnit.MILLISECONDS)
            engine.addPunctuation(text).trim().ifEmpty { null }
        } catch (_: TimeoutException) {
            Log.i("VoxFix", "ASR_PUNCT_TIMEOUT budgetMs=$budgetMs")
            null
        } catch (error: ExecutionException) {
            Log.w("VoxFix", "ASR_PUNCT_FAILED", error.cause)
            loader = null
            null
        } catch (_: CancellationException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /** 是否已经能同步取用，用于决定松手时要不要给预算。 */
    fun ready(): Boolean = loader?.isDone == true && !abandoned.get()

    fun release() {
        val task = loader ?: return
        loader = null
        abandoned.set(true)
        if (!task.isDone) { task.cancel(false); return }
        try { task.get().release() }
        catch (_: ExecutionException) { Log.e("VoxFix", "ASR_PUNCT_LOAD_FAILED") }
        catch (_: CancellationException) { /* 已自行释放 */ }
    }

    companion object {
        const val MODEL = "punctuation/model.int8.onnx"
        /** 标点模型不吃音频，单线程足够；开多线程只会和流式识别抢 CPU。 */
        const val THREADS = 1
        /**
         * 松手后标点模型加载等待上限；超时使用已有文本。
         */
        const val RELEASE_BUDGET_MS = 150L
        private val WORKER = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue(2), { task ->
            Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "VoxFix-punct")
        })
    }
}
