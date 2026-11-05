package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class ScreenshotToolbarLayoutTest {
    @Test fun lineAndWatermarkJoinDrawingWithoutChangingSavedPositions() {
        val old="FILTER/SPOTLIGHT,HIGHLIGHT/ARROW|SAVE"
        val layout=ScreenshotToolbarLayout.decode(old)
        assertEquals(listOf(listOf(ScreenshotAction.FILTER),listOf(ScreenshotAction.SPOTLIGHT,ScreenshotAction.HIGHLIGHT),listOf(ScreenshotAction.ARROW)),layout.columns.take(3))
        assertEquals(listOf(ScreenshotAction.SAVE),layout.hidden)
        for(action in listOf(ScreenshotAction.LINE,ScreenshotAction.WATERMARK)) {
            assertEquals(1,layout.columns.flatten().count { it==action })
            assertTrue(ScreenshotToolbarSection.DRAWING.includes(action))
            assertFalse(ScreenshotToolbarSection.ACTIONS.includes(action))
            assertTrue(ScreenshotToolbarState(hasRegion=true).enabled(action))
            assertFalse(ScreenshotToolbarState(hasRegion=true,editing=true).enabled(action))
        }
        val defaults=ScreenshotToolbarLayout.defaults()
        assertTrue(defaults.columns.contains(listOf(ScreenshotAction.SPOTLIGHT)))
        assertTrue(defaults.columns.contains(listOf(ScreenshotAction.FILTER)))
    }
    @Test fun drawingAndActionsPartitionToolsWithoutOfferingAFakeMovableCancel() {
        val all=ScreenshotToolbarLayout.defaults().hide(ScreenshotAction.FILTER).hide(ScreenshotAction.SHARE)
        val drawing=all.inSection(ScreenshotToolbarSection.DRAWING)
        val actions=all.inSection(ScreenshotToolbarSection.ACTIONS)
        assertEquals(listOf(ScreenshotAction.FILTER),drawing.hidden)
        assertEquals(listOf(ScreenshotAction.SHARE),actions.hidden)
        assertTrue(drawing.columns.flatten().all { ScreenshotToolbarSection.DRAWING.includes(it) })
        assertTrue(actions.columns.flatten().all { ScreenshotToolbarSection.ACTIONS.includes(it) })
        assertEquals(ScreenshotAction.values().toSet()-ScreenshotAction.CLOSE-ScreenshotToolbarState.secondaryActions,
            (drawing.columns.flatten()+drawing.hidden+actions.columns.flatten()+actions.hidden).toSet())
    }
    @Test fun mainSlotsDoNotChangeDuringRecognitionTranslationEditingOrBusyWork() {
        val layout=ScreenshotToolbarLayout.defaults()
        val base=ScreenshotToolbarState(hasRegion=true)
        val expected=layout.main(base)
        for(state in listOf(base.copy(hasText=true),base.copy(busy=true),base.copy(translating=true),
            base.copy(hasText=true,showingTranslation=true),base.copy(hasText=true,editing=true))) assertEquals(expected,layout.main(state))
        assertTrue(expected.flatten().none { it in ScreenshotToolbarState.secondaryActions })
        assertTrue(base.copy(translating=true).mainEnabled(ScreenshotAction.CLOSE))
        assertTrue(base.copy(showingTranslation=true).mainEnabled(ScreenshotAction.PEN))
    }
    @Test fun moveStackHideAndRestoreRoundTrip() {
        val base = ScreenshotToolbarLayout.defaults()
        val stacked = base.move(ScreenshotAction.SAVE, ScreenshotAction.COPY_TEXT, true)
        assertEquals(listOf(ScreenshotAction.SAVE, ScreenshotAction.COPY_TEXT), stacked.columns.first { ScreenshotAction.SAVE in it })
        assertEquals(listOf(ScreenshotAction.COPY_TEXT,ScreenshotAction.SAVE),stacked.visible(ScreenshotToolbarState(hasRegion=true,hasText=true)).first { ScreenshotAction.SAVE in it })
        val moved = stacked.move(ScreenshotAction.SAVE, ScreenshotAction.FULL_SCREEN).hide(ScreenshotAction.UNDO)
        assertEquals(listOf(ScreenshotAction.SAVE), moved.columns.first())
        assertEquals(listOf(ScreenshotAction.UNDO), moved.hidden)
        assertEquals(moved, ScreenshotToolbarLayout.decode(moved.encode()))
        val restored = moved.move(ScreenshotAction.UNDO)
        assertTrue(restored.hidden.isEmpty())
        assertEquals(listOf(ScreenshotAction.UNDO), restored.columns.last())
        assertEquals(ScreenshotAction.values().toSet(), restored.columns.flatten().toSet())
    }

    @Test fun corruptOrOlderPreferencesNeverLoseToolsOrExits() {
        val layout = ScreenshotToolbarLayout.decode("SAVE,SAVE,UNKNOWN/COPY_TEXT|CLOSE,EDIT,TRANSLATE,UNDO")
        assertEquals(ScreenshotAction.values().size, layout.columns.flatten().size + layout.hidden.size)
        assertEquals(listOf(ScreenshotAction.CLOSE,ScreenshotAction.EDIT,ScreenshotAction.TRANSLATE,ScreenshotAction.UNDO), layout.hidden)
        assertTrue(layout.visible(ScreenshotToolbarState(hasRegion=true,hasText=true)).contains(listOf(ScreenshotAction.CLOSE)))
    }

    @Test fun reorderWithinStackAndMoveBesideIt() {
        val base = ScreenshotToolbarLayout.defaults()
        assertEquals(base,base.place(ScreenshotAction.FULL_SCREEN,0,1))
        val layout = ScreenshotToolbarLayout(listOf(listOf(ScreenshotAction.RECOGNIZE,ScreenshotAction.EDIT,ScreenshotAction.SAVE)))
        val reordered = layout.place(ScreenshotAction.SAVE,0,0)
        assertEquals(listOf(ScreenshotAction.SAVE,ScreenshotAction.RECOGNIZE,ScreenshotAction.EDIT),reordered.columns.single())
        assertEquals(listOf(listOf(ScreenshotAction.RECOGNIZE,ScreenshotAction.EDIT),listOf(ScreenshotAction.SAVE)),
            reordered.place(ScreenshotAction.SAVE,1).columns)
    }

    @Test fun separatorCannotJoinAStackAndHiddenToolsCanBeReordered() {
        val base = ScreenshotToolbarLayout.defaults()
        val separator = base.columns.indexOf(listOf(ScreenshotAction.SEPARATOR))
        val moved = base.place(ScreenshotAction.SAVE,separator,0)
        assertTrue(moved.columns.all { ScreenshotAction.SEPARATOR !in it || it.size == 1 })
        assertEquals(listOf(ScreenshotAction.COPY_TEXT,ScreenshotAction.SAVE),
            base.hide(ScreenshotAction.SAVE).hide(ScreenshotAction.COPY_TEXT).hide(ScreenshotAction.COPY_TEXT,0).hidden)
    }

    @Test fun hiddenCompletionActionsReturnOnlyWhileNeeded() {
        val layout = ScreenshotToolbarLayout.defaults().hide(ScreenshotAction.EDIT).hide(ScreenshotAction.TRANSLATE).hide(ScreenshotAction.CLOSE)
        val ready = ScreenshotToolbarState(hasRegion=true,hasText=true)
        assertFalse(layout.visible(ready).flatten().contains(ScreenshotAction.EDIT))
        assertTrue(layout.visible(ready.copy(editing=true)).contains(listOf(ScreenshotAction.EDIT)))
        assertEquals(setOf(listOf(ScreenshotAction.TRANSLATE),listOf(ScreenshotAction.CLOSE)),layout.visible(ready.copy(translating=true)).toSet())
    }

    @Test fun stacksFollowContextAndKeepCompletionAndCancelDirect() {
        val layout = ScreenshotToolbarLayout.defaults()
            .move(ScreenshotAction.CLOSE, ScreenshotAction.SAVE, true)
            .move(ScreenshotAction.EDIT, ScreenshotAction.SAVE, true)
            .move(ScreenshotAction.TRANSLATE, ScreenshotAction.SAVE, true)
        val ready = ScreenshotToolbarState(hasRegion = true, hasText = true)
        assertTrue(layout.visible(ready).contains(listOf(ScreenshotAction.CLOSE)))
        val editing = layout.visible(ready.copy(editing = true))
        assertTrue(editing.contains(listOf(ScreenshotAction.EDIT)))
        assertFalse(editing.flatten().contains(ScreenshotAction.SAVE))
        assertEquals(setOf(listOf(ScreenshotAction.TRANSLATE),listOf(ScreenshotAction.CLOSE)), layout.visible(ready.copy(translating = true)).toSet())
        assertEquals(listOf(listOf(ScreenshotAction.CLOSE)), layout.visible(ready.copy(busy = true)))
    }
}
