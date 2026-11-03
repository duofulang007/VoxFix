package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class ScreenTextHandleGestureTest {
    private val gesture = ScreenTextHandleGesture(8f, 16f)

    @Test fun inwardSwipeOpensOnBothEdgesWithoutDragging() {
        gesture.begin()
        assertEquals(ScreenTextHandleGesture.Move.PULL, gesture.move(18f, 2f, false))
        assertTrue(gesture.shouldOpen)
        assertEquals(18f / 64, gesture.progress, .001f)
        assertEquals(.5f + 18f / 64, gesture.progressFrom(.5f), .001f)
        assertFalse(gesture.longPress())
        gesture.begin()
        assertEquals(ScreenTextHandleGesture.Move.PULL, gesture.move(-18f, 2f, true))
        assertTrue(gesture.shouldOpen)
    }

    @Test fun verticalOrOutwardMovementNeverDragsWithoutLongPress() {
        gesture.begin()
        assertEquals(ScreenTextHandleGesture.Move.NONE, gesture.move(2f, 50f, false))
        assertFalse(gesture.longPress())
        gesture.begin()
        assertEquals(ScreenTextHandleGesture.Move.NONE, gesture.move(-50f, 0f, false))
        assertFalse(gesture.longPress())
    }

    @Test fun stationaryHoldLocksGestureToDragEvenWhenMovingInward() {
        gesture.begin()
        assertEquals(ScreenTextHandleGesture.Move.NONE, gesture.move(3f, 2f, false))
        assertTrue(gesture.longPress())
        assertEquals(ScreenTextHandleGesture.Move.DRAG, gesture.move(50f, 40f, false))
        gesture.end()
        assertEquals(ScreenTextHandleGesture.Move.NONE, gesture.move(80f, 50f, false))
        assertFalse(gesture.longPress())
    }

    @Test fun releaseOrCancellationPreventsLateHoldAndNextTouchStartsClean() {
        gesture.begin(); gesture.end()
        assertFalse(gesture.longPress())
        gesture.begin()
        assertTrue(gesture.longPress())
        gesture.end(); gesture.begin()
        assertEquals(ScreenTextHandleGesture.Move.NONE, gesture.move(0f, 0f, true))
        assertEquals(ScreenTextHandleGesture.Move.PULL, gesture.move(-20f, 0f, true))
    }

    @Test fun reverseAndShortPullReturnWithoutOpeningOrLateLongPress() {
        gesture.begin()
        assertEquals(ScreenTextHandleGesture.Move.PULL, gesture.move(12f, 0f, false))
        assertFalse(gesture.shouldOpen)
        assertFalse(gesture.longPress())
        gesture.move(80f, 0f, false)
        assertEquals(1f, gesture.progress)
        gesture.move(-8f, 0f, false)
        assertEquals(0f, gesture.progress)
        assertFalse(gesture.shouldOpen)
        gesture.end()
        assertFalse(gesture.shouldOpen)
    }
}
