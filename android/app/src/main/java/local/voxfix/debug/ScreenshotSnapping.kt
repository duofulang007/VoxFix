package local.voxfix.debug

import kotlin.math.round

/**
 * Snapping foundation for T22, ported from Snow Shot 1.1.8
 * `snow-draw-engine-core/src/snap.rs` at `afdc340b14e7f92a294a95c374d13ada0d8c7a82`
 * (Apache-2.0): the mode arbitration and the grid service, plus the `DrawRect` the object
 * snapper works on.
 *
 * The object snapper itself (candidate collection, guide merging, weighted scoring) lives in
 * `ScreenshotObjectSnapping.kt`, ported from `snap/snap_candidates.rs`, `snap/snap_guides.rs`
 * and `snap/snap_scoring.rs` — see `docs/SnowShot-T22-绑定与吸附-2026-11-05.md`.
 */

internal object ScreenshotSnapping {

    enum class Mode { NONE, OBJECT, GRID }

    /** Which mode the settings ask for; grid wins over object snapping. */
    fun resolvePersistent(gridEnabled: Boolean, objectEnabled: Boolean): Mode = when {
        gridEnabled -> Mode.GRID
        objectEnabled -> Mode.OBJECT
        else -> Mode.NONE
    }

    /**
     * Effective mode for one gesture: Ctrl inverts the picture — with snapping configured it
     * turns snapping off, and with none configured it turns object snapping on.
     */
    fun resolveEffective(gridEnabled: Boolean, objectEnabled: Boolean, ctrlPressed: Boolean): Mode =
        if (ctrlPressed) {
            if (gridEnabled || objectEnabled) Mode.NONE else Mode.OBJECT
        } else {
            resolvePersistent(gridEnabled, objectEnabled)
        }

    data class DrawRect(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
        val width: Double get() = maxX - minX
        val height: Double get() = maxY - minY
        val centerX: Double get() = (minX + maxX) / 2.0
        val centerY: Double get() = (minY + maxY) / 2.0
        fun translate(dx: Double, dy: Double) = DrawRect(minX + dx, minY + dy, maxX + dx, maxY + dy)
        companion object {
            fun fromCenter(centerX: Double, centerY: Double, width: Double, height: Double) =
                DrawRect(centerX - width / 2.0, centerY - height / 2.0, centerX + width / 2.0, centerY + height / 2.0)
        }
    }

    /** `GridSnapService`: rounds to the nearest grid line, and leaves anything odd untouched. */
    object Grid {

        fun snapValue(value: Double, gridSize: Double): Double {
            if (!value.isFinite() || !gridSize.isFinite() || gridSize <= 0.0) return value
            val snapped = round(value / gridSize) * gridSize
            return if (snapped.isFinite()) snapped else value
        }

        fun snapPoint(x: Double, y: Double, gridSize: Double): DoubleArray =
            doubleArrayOf(snapValue(x, gridSize), snapValue(y, gridSize))

        /** Snap only the edges the caller asked for; the rest of the rectangle stays put. */
        fun snapRect(
            rect: DrawRect,
            gridSize: Double,
            snapMinX: Boolean = false,
            snapMaxX: Boolean = false,
            snapMinY: Boolean = false,
            snapMaxY: Boolean = false
        ): DrawRect {
            if (!snapMinX && !snapMaxX && !snapMinY && !snapMaxY) return rect
            return DrawRect(
                if (snapMinX) snapValue(rect.minX, gridSize) else rect.minX,
                if (snapMinY) snapValue(rect.minY, gridSize) else rect.minY,
                if (snapMaxX) snapValue(rect.maxX, gridSize) else rect.maxX,
                if (snapMaxY) snapValue(rect.maxY, gridSize) else rect.maxY
            )
        }

        /** `snap_rect_to_grid_min_corner`: a move keeps the size and snaps the top-left corner. */
        fun snapRectToMinCorner(rect: DrawRect, gridSize: Double): DrawRect =
            snapRect(rect, gridSize, snapMinX = true, snapMinY = true)
    }

    /**
     * Grid delta for a move: how far the dragged rectangle has to shift so its top-left corner
     * lands on the grid.
     */
    fun moveDeltaForGrid(target: DrawRect, gridSize: Double): DoubleArray {
        val snapped = Grid.snapRectToMinCorner(target, gridSize)
        return doubleArrayOf(snapped.minX - target.minX, snapped.minY - target.minY)
    }
}
