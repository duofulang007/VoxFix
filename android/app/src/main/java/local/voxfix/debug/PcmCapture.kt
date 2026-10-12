package local.voxfix.debug

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Keep reading the microphone while a speech segment is being decoded. */
internal class PcmCapture(
    capacity: Int,
    private val read: () -> FloatArray?,
    private val keepRecording: () -> Boolean,
    private val stopRead: () -> Unit
) : AutoCloseable {
    private val queue = ArrayBlockingQueue<FloatArray>(capacity)
    private val closed = AtomicBoolean(false)
    @Volatile private var done = false
    @Volatile private var error: Throwable? = null
    private val thread = Thread({
        try {
            while (!closed.get() && keepRecording()) {
                val samples = read() ?: break
                check(queue.offer(samples)) { "识别积压过多，请分段说话" }
            }
        } catch (failure: Throwable) {
            if (!closed.get()) error = failure
        } finally {
            try { stopRead() } catch (failure: Throwable) { if (!closed.get() && error == null) error = failure }
            done = true
        }
    }, "VoxFix-microphone").apply { start() }

    fun next(): FloatArray? {
        while (!done || queue.isNotEmpty()) {
            error?.let { throw it }
            queue.poll(50, TimeUnit.MILLISECONDS)?.let { return it }
        }
        error?.let { throw it }
        return null
    }

    override fun close() {
        closed.set(true)
        try { stopRead() } finally {
            thread.join(2000)
            check(!thread.isAlive) { "麦克风未及时停止" }
            queue.clear()
        }
    }
}
