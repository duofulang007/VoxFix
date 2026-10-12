package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PcmCaptureTest {
    @Test fun readsAheadDuringDecodeAndPreservesOrder() {
        val read = AtomicInteger()
        val ready = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        PcmCapture(8, {
            val number = read.incrementAndGet()
            if (number <= 6) floatArrayOf(number.toFloat())
            else { ready.countDown(); stopped.await(); null }
        }, { true }, { stopped.countDown() }).use { capture ->
            assertTrue("Capture must progress while the consumer is busy", ready.await(2, TimeUnit.SECONDS))
            assertEquals((1..6).map { it.toFloat() }, (1..6).map { capture.next()!!.single() })
        }
    }

    @Test fun overflowFailsExplicitlyInsteadOfDroppingAudio() {
        val read = AtomicInteger()
        val stopped = CountDownLatch(1)
        PcmCapture(2, { floatArrayOf(read.incrementAndGet().toFloat()) }, { true }, { stopped.countDown() }).use { capture ->
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            try { capture.next(); fail("Overflow must fail") }
            catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("积压")) }
            assertEquals(3, read.get())
        }
    }

    @Test fun sourceFailureIsPropagatedAndCancellationUnblocksRead() {
        val stopped = CountDownLatch(1)
        PcmCapture(2, { throw IllegalStateException("read failed") }, { true }, { stopped.countDown() }).use { capture ->
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            try { capture.next(); fail("Read failure must propagate") }
            catch (expected: IllegalStateException) { assertEquals("read failed", expected.message) }
        }
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        PcmCapture(2, { reading.countDown(); release.await(); null }, { true }, { release.countDown() }).use {
            assertTrue(reading.await(2, TimeUnit.SECONDS))
        }
        assertEquals(0L, release.count)
    }
}
