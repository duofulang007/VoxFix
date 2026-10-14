package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class SelectionStepsTest {
    @Test fun turningHorizontalDiscardsOldVerticalRemainder() {
        val steps = SelectionSteps()
        assertEquals(0 to -1, steps.take(0f, -50f))
        steps.clear()
        assertEquals(1 to 0, steps.take(11f, 0f))
        assertEquals(0 to 1, steps.take(0f, 28f))
    }
    @Test fun diagonalAndReversedMotionKeepBothAxes() {
        val steps = SelectionSteps()
        assertEquals(2 to -1, steps.take(22f, -28f))
        assertEquals(0 to -1, steps.take(0f, -28f))
        assertEquals(-1 to 1, steps.take(-11f, 28f))
    }
    @Test fun fractionalMotionAccumulatesButDoesNotTrapAReversal() {
        val steps = SelectionSteps()
        assertEquals(0 to 0, steps.take(5f, -20f))
        assertEquals(1 to 0, steps.take(6f, 0f))
        assertEquals(0 to 1, steps.take(0f, 28f))
        steps.clear()
        assertEquals(0 to 0, steps.take(0f, 1f))
    }
}
