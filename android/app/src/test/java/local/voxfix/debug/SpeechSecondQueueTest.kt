package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class SpeechSecondQueueTest {
    @Test fun replacingQueuedWorkCancelsTheFutureSeenByItsCaller() {
        val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val running = SpeechSecondQueue.submit(worker) {
                started.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                SecondPass("running", "")
            }!!
            assertTrue(started.await(1, TimeUnit.SECONDS))
            val stale = SpeechSecondQueue.submit(worker) { error("Replaced audio must not decode") }!!
            val latest = SpeechSecondQueue.submit(worker) { SecondPass("latest", "") }!!
            assertTrue("The caller must see cancellation instead of waiting forever", stale.isCancelled)
            release.countDown()
            assertEquals("running", running.get(1, TimeUnit.SECONDS).text)
            assertEquals("latest", latest.get(1, TimeUnit.SECONDS).text)
        } finally {
            release.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
