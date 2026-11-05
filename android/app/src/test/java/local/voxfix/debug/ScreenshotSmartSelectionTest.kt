package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotSmartSelectionTest {
    private val window = ScreenshotDocRect(0, 0, 100, 100)
    private val panel = ScreenshotDocRect(10, 10, 90, 90)
    private val label = ScreenshotDocRect(20, 30, 50, 40)

    @Test fun textPathTrimsShortLineAndSeparatesParagraphsInOneNativeNode() {
        val node = ScreenshotDocRect(10, 20, 90, 90)
        val short = ScreenshotDocRect(20, 43, 38, 53)
        val later = ScreenshotDocRect(20, 72, 52, 82)
        val geometry = ScreenshotSmartText(listOf(listOf(label, short, later)))
        assertEquals(listOf(short, ScreenshotDocRect(20, 30, 50, 53), node, window),
            geometry.refine(28, 48, listOf(node, window)))
        assertEquals(listOf(later, node, window), geometry.refine(28, 77, listOf(node, window)))
        assertEquals(listOf(node, window), geometry.refine(70, 48, listOf(node, window)))
    }

    @Test fun tapInsideDrillsDownAndLeavesExistingBoundsWhenThereIsNoChild() {
        val path = listOf(label, panel, window)
        assertEquals(2, ScreenshotSmartSelection.targetIndex(path, null, 25, 35, true))
        assertEquals(0, ScreenshotSmartSelection.targetIndex(path, window, 25, 35, true))
        assertEquals(0, ScreenshotSmartSelection.targetIndex(path, panel, 25, 35, false))
        assertEquals(-1, ScreenshotSmartSelection.targetIndex(path, label, 25, 35, false))
        val sibling = ScreenshotDocRect(55, 30, 75, 40)
        assertEquals(0, ScreenshotSmartSelection.targetIndex(listOf(sibling, panel, window), label, 65, 35, false))
    }

    @Test fun dockPrefersBottomCenterAndMovesClearOfContentAndToolbar() {
        assertEquals(ScreenshotDocRect(40, 90, 60, 100), ScreenshotSmartSelection.dock(window, null, null, 20, 10, 2))
        val selected = ScreenshotDocRect(0, 60, 100, 100)
        val toolbar = ScreenshotDocRect(0, 40, 100, 58)
        assertEquals(ScreenshotDocRect(40, 28, 60, 38), ScreenshotSmartSelection.dock(window, selected, toolbar, 20, 10, 2))
        val centerOnly = ScreenshotDocRect(35, 60, 65, 100)
        assertEquals(ScreenshotDocRect(80, 90, 100, 100), ScreenshotSmartSelection.dock(window, centerOnly, toolbar, 20, 10, 2))
    }

    @Test fun deepestHitFirstAndDuplicateContainerOmitted() {
        val content = ScreenshotSmartSelection.Node(panel, listOf(ScreenshotSmartSelection.Node(label)))
        val snapshot = ScreenshotSmartSelection(listOf(
            ScreenshotSmartSelection.Node(window, listOf(ScreenshotSmartSelection.Node(panel, listOf(content))))
        ))
        assertEquals(listOf(label, panel, window), snapshot.path(25, 35, 100, 100))
        assertEquals(listOf(panel, window), snapshot.path(89, 89, 100, 100))
        assertEquals(emptyList<ScreenshotDocRect>(), snapshot.path(100, 89, 100, 100))
    }

    @Test fun topmostWindowWinsAndClippingDeduplicates() {
        val top = ScreenshotDocRect(-10, -10, 30, 25)
        val visible = ScreenshotDocRect(0, 0, 30, 25)
        val snapshot = ScreenshotSmartSelection(listOf(
            ScreenshotSmartSelection.Node(top, listOf(ScreenshotSmartSelection.Node(visible))),
            ScreenshotSmartSelection.Node(window)
        ))
        assertEquals(listOf(visible), snapshot.path(22, 23, 100, 100))
        assertEquals(listOf(window), snapshot.path(30, 25, 100, 100))
    }
}
