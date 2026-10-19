package local.voxfix.debug

import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor

/** Only the newest queued utterance is useful; running native decoding is never interrupted. */
internal object SpeechSecondQueue {
    fun submit(worker: ThreadPoolExecutor, block: () -> SecondPass): Future<SecondPass>? {
        val task = FutureTask(block)
        return try {
            worker.execute(task); task
        } catch (_: RejectedExecutionException) {
            (worker.queue.poll() as? Future<*>)?.cancel(false)
            try { worker.execute(task); task } catch (_: RejectedExecutionException) { null }
        }
    }
}
