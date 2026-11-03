package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotToolbarStateTest {
    @Test fun textActionsAppearOnlyAfterRecognition() {
        val empty = ScreenshotToolbarState(hasRegion = true)
        assertTrue(empty.visible(ScreenshotAction.RECOGNIZE))
        assertTrue(empty.visible(ScreenshotAction.SAVE))
        for (action in listOf(ScreenshotAction.COPY_TEXT,ScreenshotAction.EDIT,ScreenshotAction.SHOW_ORIGINAL))
            assertFalse("$action must wait for text",empty.visible(action))
        val recognized = empty.copy(hasText = true)
        for (action in listOf(ScreenshotAction.COPY_TEXT,ScreenshotAction.EDIT,ScreenshotAction.SHOW_ORIGINAL))
            assertTrue(recognized.visible(action))
    }

    @Test fun saveNeedsRegionButNotText() {
        val fresh = ScreenshotToolbarState()
        assertFalse(fresh.visible(ScreenshotAction.SAVE))
        assertTrue(fresh.visible(ScreenshotAction.FULL_SCREEN))
        assertTrue(fresh.visible(ScreenshotAction.CLOSE))
        // Whole-screen reselection would erase an existing draft.
        assertFalse(ScreenshotToolbarState(hasRegion = true,hasText = true).visible(ScreenshotAction.FULL_SCREEN))
    }

    @Test fun busyLeavesOnlyCancelAvailable() {
        val busy = ScreenshotToolbarState(hasRegion = true,hasText = true,busy = true)
        for (action in ScreenshotAction.values())
            assertEquals("$action",action == ScreenshotAction.CLOSE,busy.enabled(action))
    }

    @Test fun editingHidesRegionChangesThatWouldDropTheDraft() {
        val editing = ScreenshotToolbarState(hasRegion = true,hasText = true,editing = true)
        assertFalse(editing.visible(ScreenshotAction.FULL_SCREEN))
        assertFalse(editing.visible(ScreenshotAction.RECOGNIZE))
        assertTrue(editing.visible(ScreenshotAction.SAVE))
        assertTrue(editing.enabled(ScreenshotAction.COPY_TEXT))
        assertEquals("完成编辑",editing.label(ScreenshotAction.EDIT))
    }

    @Test fun labelsSwitchWithVisibleState() {
        assertEquals("显示原图",ScreenshotToolbarState(hasText = true).label(ScreenshotAction.SHOW_ORIGINAL))
        assertEquals("显示识别结果",ScreenshotToolbarState(hasText = true,showingOriginal = true).label(ScreenshotAction.SHOW_ORIGINAL))
        assertEquals("编辑文字",ScreenshotToolbarState(hasText = true).label(ScreenshotAction.EDIT))
    }
}
