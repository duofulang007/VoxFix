package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class ClipboardSplitStateTest {
    @Test fun onlyTheCurrentFieldAndOwnedRangeCanBeUpdated() {
        val state = ClipboardSplitState(1, 7, 3, "旧文")
        assertTrue(state.matches(7, 3, 5, "旧文", ""))
        assertTrue(state.matches(7, 5, 3, "旧文", ""))
        assertFalse(state.matches(8, 3, 5, "旧文", ""))
        state.accepted("尊敬用户")
        assertTrue(state.matches(7, 7, 7, "", "尊敬用户"))
        assertFalse(state.matches(7, 6, 6, "", "尊敬用户"))
        assertFalse(state.matches(7, 7, 7, "", "外部修改"))
        assertFalse(state.matches(7, 3, 7, "尊敬用户", ""))
    }

    @Test fun deselectingEverythingRestoresOnlyTheOriginalSelection() {
        val state = ClipboardSplitState(1, 7, 3, "旧文")
        state.accepted("新文")
        state.accepted("")
        assertNull(state.text)
        assertEquals(5, state.end)
        assertTrue(state.matches(7, 3, 5, "旧文", ""))
    }
}
