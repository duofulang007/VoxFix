package local.voxfix.debug

import local.voxfix.debug.ScreenshotSnapping.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grid snapping and mode arbitration, straight from the original's arithmetic. */
class ScreenshotSnappingTest {

    @Test
    fun gridWinsOverObjectAndCtrlInvertsEverything() {
        assertEquals(Mode.GRID, ScreenshotSnapping.resolvePersistent(gridEnabled = true, objectEnabled = true))
        assertEquals(Mode.OBJECT, ScreenshotSnapping.resolvePersistent(gridEnabled = false, objectEnabled = true))
        assertEquals(Mode.NONE, ScreenshotSnapping.resolvePersistent(gridEnabled = false, objectEnabled = false))

        // Ctrl with snapping configured = temporarily off
        assertEquals(Mode.NONE, ScreenshotSnapping.resolveEffective(true, true, ctrlPressed = true))
        assertEquals(Mode.NONE, ScreenshotSnapping.resolveEffective(false, true, ctrlPressed = true))
        // Ctrl with nothing configured = object snapping on
        assertEquals(Mode.OBJECT, ScreenshotSnapping.resolveEffective(false, false, ctrlPressed = true))
        assertEquals(Mode.GRID, ScreenshotSnapping.resolveEffective(true, false, ctrlPressed = false))
    }

    @Test
    fun valuesRoundToTheNearestGridLine() {
        assertEquals(40.0, ScreenshotSnapping.Grid.snapValue(31.0, 20.0), 1e-9)
        assertEquals(30.0, ScreenshotSnapping.Grid.snapValue(25.0, 30.0), 1e-9)
        assertEquals(20.0, ScreenshotSnapping.Grid.snapValue(29.0, 20.0), 1e-9)
        assertEquals(40.0, ScreenshotSnapping.Grid.snapValue(30.0, 20.0), 1e-9)  // half rounds up
        assertEquals(0.0, ScreenshotSnapping.Grid.snapValue(-9.0, 20.0), 1e-9)
        assertEquals(-20.0, ScreenshotSnapping.Grid.snapValue(-11.0, 20.0), 1e-9)
    }

    @Test
    fun aNonPositiveOrBrokenGridIsIgnored() {
        assertEquals(31.0, ScreenshotSnapping.Grid.snapValue(31.0, 0.0), 1e-9)
        assertEquals(31.0, ScreenshotSnapping.Grid.snapValue(31.0, -5.0), 1e-9)
        assertEquals(31.0, ScreenshotSnapping.Grid.snapValue(31.0, Double.NaN), 1e-9)
        assertEquals(Double.POSITIVE_INFINITY, ScreenshotSnapping.Grid.snapValue(Double.POSITIVE_INFINITY, 20.0), 0.0)
    }

    @Test
    fun pointsSnapOnBothAxes() {
        val point = ScreenshotSnapping.Grid.snapPoint(26.0, -34.0, 20.0)
        assertEquals(20.0, point[0], 1e-9)
        assertEquals(-40.0, point[1], 1e-9)
    }

    @Test
    fun rectSnapsOnlyTheRequestedEdges() {
        val rect = ScreenshotSnapping.DrawRect(23.0, 31.0, 117.0, 89.0)
        val minOnly = ScreenshotSnapping.Grid.snapRect(rect, 20.0, snapMinX = true, snapMinY = true)
        assertEquals(20.0, minOnly.minX, 1e-9)
        assertEquals(40.0, minOnly.minY, 1e-9)
        assertEquals(117.0, minOnly.maxX, 1e-9)
        assertEquals(89.0, minOnly.maxY, 1e-9)

        val whole = ScreenshotSnapping.Grid.snapRect(
            rect, 20.0, snapMinX = true, snapMaxX = true, snapMinY = true, snapMaxY = true
        )
        assertEquals(120.0, whole.maxX, 1e-9)
        assertEquals(80.0, whole.maxY, 1e-9)

        val untouched = ScreenshotSnapping.Grid.snapRect(rect, 20.0)
        assertEquals(rect, untouched)
    }

    @Test
    fun moveDeltaSnapsTheTopLeftCorner() {
        val target = ScreenshotSnapping.DrawRect(23.0, 31.0, 117.0, 89.0)
        val delta = ScreenshotSnapping.moveDeltaForGrid(target, 20.0)
        assertEquals(-3.0, delta[0], 1e-9)
        assertEquals(9.0, delta[1], 1e-9)
        // applying the delta puts the corner exactly on the grid
        assertEquals(20.0, target.minX + delta[0], 1e-9)
        assertEquals(40.0, target.minY + delta[1], 1e-9)
    }

    @Test
    fun rectHelpersFollowTheOriginalDefinition() {
        val rect = ScreenshotSnapping.DrawRect.fromCenter(100.0, 50.0, 40.0, 20.0)
        assertEquals(80.0, rect.minX, 1e-9)
        assertEquals(120.0, rect.maxX, 1e-9)
        assertEquals(40.0, rect.width, 1e-9)
        assertEquals(20.0, rect.height, 1e-9)
        assertEquals(100.0, rect.centerX, 1e-9)
        val moved = rect.translate(5.0, -5.0)
        assertEquals(85.0, moved.minX, 1e-9)
        assertEquals(55.0, moved.maxY, 1e-9)
        assertTrue(moved.width == rect.width && moved.height == rect.height)
    }
}
