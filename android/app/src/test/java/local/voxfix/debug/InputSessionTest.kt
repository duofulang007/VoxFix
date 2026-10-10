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
    @Test fun streamingSelectionEchoDoesNotCancelButCursorMoveDoes() {
        assertTrue(InputSession.isVoiceSelection(3, false, 3, 3, -1, -1))
        assertTrue(InputSession.isVoiceSelection(3, true, 7, 7, 3, 7))
        assertFalse(InputSession.isVoiceSelection(3, true, 4, 4, 3, 7))
        assertFalse(InputSession.isVoiceSelection(3, true, 0, 2, -1, -1))
    }
}
