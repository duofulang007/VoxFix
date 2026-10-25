package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class InputSessionTest {
    @Test fun cancelledOrReplacedInputRejectsLateResults() {
        val session = InputSession()
        val cancelled = session.open()
        session.close()
        assertFalse(session.consume(cancelled))
        val previous = session.open()
        val current = session.open()
        assertFalse(session.consume(previous))
        assertTrue(session.consume(current))
        assertFalse(session.consume(current))
    }
}
