package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotToolbarStateTest {
    @Test fun cancelIsVisibleAndEnabledInEveryTranslationAndEditingState() {
        for(busy in listOf(false,true)) for(translating in listOf(false,true)) for(editing in listOf(false,true)) {
            val state=ScreenshotToolbarState(hasRegion=true,hasText=true,busy=busy,translating=translating,editing=editing)
            assertTrue(state.visible(ScreenshotAction.CLOSE))
            assertTrue(state.enabled(ScreenshotAction.CLOSE))
            assertTrue(state.mainEnabled(ScreenshotAction.CLOSE))
        }
    }
    @Test fun drawingRequiresARegionAndHistoryUsesItsOwnUndo() {
        val region = ScreenshotToolbarState(hasRegion=true)
        for (action in listOf(ScreenshotAction.ARROW,ScreenshotAction.RECTANGLE,ScreenshotAction.PEN)) {
            assertFalse(ScreenshotToolbarState().visible(action))
            assertTrue(region.enabled(action))
            assertFalse(region.copy(editing=true).visible(action))
            assertFalse(region.copy(showingTranslation=true).visible(action))
        }
        assertTrue(region.copy(hasDrawingHistory=true).visible(ScreenshotAction.UNDO))
        assertFalse(region.copy(hasDrawingHistory=true,showingOriginal=true).visible(ScreenshotAction.UNDO))
    }
    @Test fun textActionsAppearOnlyAfterRecognition() {
        val empty = ScreenshotToolbarState(hasRegion = true)
        assertTrue(empty.visible(ScreenshotAction.RECOGNIZE))
        assertTrue(empty.visible(ScreenshotAction.SAVE))
        for (action in listOf(ScreenshotAction.COPY_TEXT,ScreenshotAction.EDIT,ScreenshotAction.SHOW_ORIGINAL))
            assertFalse("$action must wait for text",empty.visible(action))
        val recognized = empty.copy(hasText = true)
        assertFalse(recognized.visible(ScreenshotAction.RECOGNIZE))
        assertFalse(empty.visible(ScreenshotAction.FULL_SCREEN))
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
        assertFalse(editing.visible(ScreenshotAction.SAVE))
        assertTrue(editing.visible(ScreenshotAction.UNDO))
        assertTrue(editing.visible(ScreenshotAction.REDO))
        assertTrue(editing.enabled(ScreenshotAction.COPY_TEXT))
        assertEquals("完成编辑",editing.label(ScreenshotAction.EDIT))
    }

    @Test fun labelsSwitchWithVisibleState() {
        assertEquals("显示原图",ScreenshotToolbarState(hasText = true).label(ScreenshotAction.SHOW_ORIGINAL))
        assertEquals("显示识别结果",ScreenshotToolbarState(hasText = true,showingOriginal = true).label(ScreenshotAction.SHOW_ORIGINAL))
        assertEquals("编辑文字",ScreenshotToolbarState(hasText = true).label(ScreenshotAction.EDIT))
    }

    @Test fun translationStartsFromRegionAndBlocksCompetingActions() {
        val bare = ScreenshotToolbarState(hasRegion = true)
        assertTrue(bare.visible(ScreenshotAction.TRANSLATE))
        assertTrue(bare.enabled(ScreenshotAction.TRANSLATE))
        assertFalse(ScreenshotToolbarState().visible(ScreenshotAction.TRANSLATE))
        val recognizing = bare.copy(busy=true,translating=true)
        assertTrue(recognizing.enabled(ScreenshotAction.TRANSLATE))
        val ready = bare.copy(hasText = true)
        assertTrue(ready.visible(ScreenshotAction.TRANSLATE))
        assertTrue(ready.enabled(ScreenshotAction.TRANSLATE))
        val running = ready.copy(translating = true)
        assertTrue("translation stays visible so it can be cancelled",running.visible(ScreenshotAction.TRANSLATE))
        assertTrue("the same button cancels while running",running.enabled(ScreenshotAction.TRANSLATE))
        for (action in listOf(ScreenshotAction.COPY_TEXT,ScreenshotAction.EDIT,ScreenshotAction.SHOW_ORIGINAL,
                ScreenshotAction.SAVE,ScreenshotAction.RECOGNIZE,ScreenshotAction.FULL_SCREEN))
            assertFalse("$action must be blocked while translating",running.enabled(action))
    }

    @Test fun translationLabelsFollowTheThreeStates() {
        assertEquals("翻译文字",ScreenshotToolbarState(hasText = true).label(ScreenshotAction.TRANSLATE))
        assertEquals("取消翻译",ScreenshotToolbarState(hasText = true,translating = true).label(ScreenshotAction.TRANSLATE))
        assertEquals("显示原文",ScreenshotToolbarState(hasText = true,showingTranslation = true).label(ScreenshotAction.TRANSLATE))
        assertEquals("复制译文",ScreenshotToolbarState(hasText = true,showingTranslation = true).label(ScreenshotAction.COPY_TEXT))
    }
}
