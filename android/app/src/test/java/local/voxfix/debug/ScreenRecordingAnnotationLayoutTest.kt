package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRecordingAnnotationLayoutTest {
    /** Upstream `connectDrawingToolbar` connects exactly this set; a missing action means a fallback icon. */
    @Test fun everyBarToolHasAPaletteAction() {
        for ((tool, label) in ScreenRecordingAnnotationLayout.tools) {
            assertTrue("$label 缺少调色板动作", ScreenshotDrawingTools.actions.containsKey(tool))
        }
    }

    /** Order copied from `ScreenshotToolbarLayout.defaults()`; the recording bar must not drift from it. */
    @Test fun barFollowsUpstreamPaletteOrder() {
        assertEquals(
            listOf(
                ScreenshotDrawingTool.SELECT, ScreenshotDrawingTool.PEN, ScreenshotDrawingTool.RECTANGLE,
                ScreenshotDrawingTool.ARROW, ScreenshotDrawingTool.LINE, ScreenshotDrawingTool.FILTER,
                ScreenshotDrawingTool.HIGHLIGHT, ScreenshotDrawingTool.SPOTLIGHT, ScreenshotDrawingTool.TEXT,
                ScreenshotDrawingTool.SERIAL, ScreenshotDrawingTool.ERASER, ScreenshotDrawingTool.WATERMARK
            ),
            ScreenRecordingAnnotationLayout.tools.map { it.first }
        )
        // Upstream wires text, serial, watermark, spotlight and both filter tools in recording; the
        // bar has to keep all of them reachable instead of the pen-only first version.
        assertEquals(12, ScreenRecordingAnnotationLayout.tools.size)
    }

    @Test fun cellsPerRowFitsPhoneWidths() {
        // K60: 1080 px at 2.75 density — the eight-cell cap applies, not the raw division.
        assertEquals(8, ScreenRecordingAnnotationLayout.cellsPerRow(1080, 2.75f))
        // 720 px at 2.0 still fits eight.
        assertEquals(8, ScreenRecordingAnnotationLayout.cellsPerRow(720, 2f))
        // A 240 dp-wide window only fits six 38dp cells.
        assertEquals(6, ScreenRecordingAnnotationLayout.cellsPerRow(240, 1f))
        // Never degenerate to zero cells, however narrow.
        assertEquals(1, ScreenRecordingAnnotationLayout.cellsPerRow(20, 3f))
        assertEquals(1, ScreenRecordingAnnotationLayout.cellsPerRow(1080, 0f))
    }

    @Test fun rowsSplitSixteenCellsIntoTwoRows() {
        // 12 tools + undo/redo/clear/exit.
        val rows = ScreenRecordingAnnotationLayout.rows(16, 8)
        assertEquals(2, rows.size)
        assertEquals((0 until 8).toList(), rows[0])
        assertEquals((8 until 16).toList(), rows[1])
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), ScreenRecordingAnnotationLayout.rows(5, 2))
        assertEquals(emptyList<List<Int>>(), ScreenRecordingAnnotationLayout.rows(0, 8))
        // A zero per-row guard must not divide by zero.
        assertEquals(3, ScreenRecordingAnnotationLayout.rows(3, 0).size)
    }
}
