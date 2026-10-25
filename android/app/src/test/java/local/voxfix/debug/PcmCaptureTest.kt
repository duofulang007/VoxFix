package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class PcmCaptureTest {
    @Test fun stopUnblocksAnInFlightReadWithoutAcceptingLaterAudio() {
        val reading = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        var read = 0
        PcmCapture(150, {
            if (read++ == 0) floatArrayOf(1f) else {
                reading.countDown()
                unblock.await()
                null
            }
        }, { true }, { unblock.countDown() }).use { capture ->
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            capture.stop()
            assertEquals(1f, capture.next()!!.single())
            assertNull("Audio arriving after release must never reach recognition", capture.next())
        }
    }

    @Test fun releaseDuringColdLoadStillDrainsTheOpeningAudio() {
        val recording = AtomicBoolean(true)
        val stopped = CountDownLatch(1)
        var read = 0
        PcmCapture(150, {
            read++
            if (read == 40) recording.set(false)
            floatArrayOf(read.toFloat())
        }, { recording.get() }, { stopped.countDown() }).use { capture ->
            // Simulate model loading until the user has already released the voice key.
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            assertEquals((1..40).map { it.toFloat() }, generateSequence { capture.next()?.single() }.toList())
        }
    }

    @Test fun readsAheadDuringDecodeAndPreservesOrder() {
        val read = AtomicInteger()
        val ready = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        PcmCapture(150, {
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
