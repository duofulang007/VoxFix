package local.voxfix.debug

/**
 * T22 snap wiring, ported from Snow Shot 1.1.8 at `afdc340b14e7f92a294a95c374d13ada0d8c7a82`
 * (Apache-2.0):
 * - `SnowSnapConfig` / `SnowGridConfig` defaults from
 *   `snow-draw-engine-core/src/types.rs` (`SnapConfig`, `GridConfig`).
 * - Drag application semantics from
 *   `snow-draw-engine-editor/src/snapping.rs` (`resolve_move_snap`, `resolve_resize_snap`,
 *   `object_snap_plan`, `visible_reference_rects`) and
 *   `edit_workflow/selection_edit.rs` (`resize_snap_anchors_for_sign` usage).
 *
 * Phone-side differences (documented, upstream has no direct counterpart):
 * - No Ctrl key on touch, so the effective mode is always the persistent one
 *   (`resolveEffective(..., ctrlPressed=false)`); the engine also inverts with Ctrl.
 * - The phone editor has no viewport zoom, so the snap distance is not divided by camera zoom.
 * - Toggle UI is not ported yet; both switches default to off like upstream, so behaviour is
 *   unchanged until the settings surface is ported.
 */
internal class ScreenshotSnapConfig(
    val snapEnabled: Boolean = DEFAULT_SNAP_ENABLED,
    val snapDistance: Double = DEFAULT_SNAP_DISTANCE,
    val enablePointSnaps: Boolean = DEFAULT_ENABLE_POINT_SNAPS,
    val enableGapSnaps: Boolean = DEFAULT_ENABLE_GAP_SNAPS,
    val showGuides: Boolean = DEFAULT_SHOW_GUIDES,
    val gridEnabled: Boolean = DEFAULT_GRID_ENABLED,
    val gridSize: Double = DEFAULT_GRID_SIZE
) {
    companion object {
        /** `SnapConfig::DEFAULT_ENABLED` */
        const val DEFAULT_SNAP_ENABLED = false
        /** `SnapConfig::DEFAULT_DISTANCE` */
        const val DEFAULT_SNAP_DISTANCE = 8.0
        /** `SnapConfig::DEFAULT_ENABLE_POINT_SNAPS` */
        const val DEFAULT_ENABLE_POINT_SNAPS = true
        /** `SnapConfig::DEFAULT_ENABLE_GAP_SNAPS` */
        const val DEFAULT_ENABLE_GAP_SNAPS = true
        /** `SnapConfig::DEFAULT_SHOW_GUIDES` */
        const val DEFAULT_SHOW_GUIDES = true
        /** `GridConfig::DEFAULT_ENABLED` */
        const val DEFAULT_GRID_ENABLED = false
        /** `GridConfig::DEFAULT_SIZE` */
        const val DEFAULT_GRID_SIZE = 20.0

        fun fromPreferences(prefs: android.content.SharedPreferences): ScreenshotSnapConfig =
            ScreenshotSnapConfig(
                snapEnabled = prefs.getBoolean("snapEnabled", DEFAULT_SNAP_ENABLED),
                snapDistance = Double.fromBits(
                    prefs.getLong("snapDistance", DEFAULT_SNAP_DISTANCE.toRawBits())),
                enablePointSnaps = prefs.getBoolean("enablePointSnaps", DEFAULT_ENABLE_POINT_SNAPS),
                enableGapSnaps = prefs.getBoolean("enableGapSnaps", DEFAULT_ENABLE_GAP_SNAPS),
                showGuides = prefs.getBoolean("showGuides", DEFAULT_SHOW_GUIDES),
                gridEnabled = prefs.getBoolean("gridEnabled", DEFAULT_GRID_ENABLED),
                gridSize = Double.fromBits(
                    prefs.getLong("gridSize", DEFAULT_GRID_SIZE.toRawBits()))
            )
    }
}

/**
 * Pure application of the snap services to a selection drag, mirroring
 * `Editor::resolve_move_snap` / `resolve_resize_snap`. The reference set excludes the
 * dragged elements and filter overlays (`visible_reference_rects`).
 */
internal object ScreenshotSnapApplication {

    data class MoveResult(val dx: Double, val dy: Double, val guides: List<ScreenshotObjectSnapping.SnapGuide>)

    fun moveSnap(
        config: ScreenshotSnapConfig?,
        ctrlPressed: Boolean,
        originalBounds: ScreenshotSnapping.DrawRect,
        baseDx: Double,
        baseDy: Double,
        referenceRects: List<ScreenshotSnapping.DrawRect>
    ): MoveResult {
        if (config == null) return MoveResult(baseDx, baseDy, emptyList())
        val mode = ScreenshotSnapping.resolveEffective(config.gridEnabled, config.snapEnabled, ctrlPressed)
        val target = originalBounds.translate(baseDx, baseDy)
        return when (mode) {
            ScreenshotSnapping.Mode.GRID -> {
                val snapped = ScreenshotSnapping.Grid.snapRectToMinCorner(target, config.gridSize)
                MoveResult(
                    baseDx + snapped.minX - target.minX,
                    baseDy + snapped.minY - target.minY,
                    emptyList()
                )
            }
            ScreenshotSnapping.Mode.OBJECT -> {
                if (!config.enablePointSnaps && !config.enableGapSnaps) {
                    return MoveResult(baseDx, baseDy, emptyList())
                }
                val result = ScreenshotObjectSnapping.snapMove(
                    target, referenceRects, config.snapDistance,
                    config.enablePointSnaps, config.enableGapSnaps
                )
                MoveResult(
                    baseDx + result.dx, baseDy + result.dy,
                    if (config.showGuides) result.guides else emptyList()
                )
            }
            ScreenshotSnapping.Mode.NONE -> MoveResult(baseDx, baseDy, emptyList())
        }
    }

    /**
     * Resize snap for one already-computed unsnapped frame. Mirrors the upstream two-step:
     * the dragged-edge sign picks which edges may move and which anchors snap, the
     * correction is returned so the caller can re-drive the resize from the corrected
     * handle point. Grid mode is skipped while aspect lock (shift) is held, as upstream.
     */
    data class ResizeResult(val dx: Double, val dy: Double, val guides: List<ScreenshotObjectSnapping.SnapGuide>)

    fun resizeSnap(
        config: ScreenshotSnapConfig?,
        ctrlPressed: Boolean,
        aspectLocked: Boolean,
        unsnappedFrame: ScreenshotSnapping.DrawRect,
        draggedXSign: Double,
        draggedYSign: Double,
        referenceRects: List<ScreenshotSnapping.DrawRect>
    ): ResizeResult {
        if (config == null) return ResizeResult(0.0, 0.0, emptyList())
        val mode = ScreenshotSnapping.resolveEffective(config.gridEnabled, config.snapEnabled, ctrlPressed)
        val snapMinX = draggedXSign < -1e-9
        val snapMaxX = draggedXSign > 1e-9
        val snapMinY = draggedYSign < -1e-9
        val snapMaxY = draggedYSign > 1e-9
        return when {
            mode == ScreenshotSnapping.Mode.GRID && !aspectLocked -> {
                val snapped = ScreenshotSnapping.Grid.snapRect(
                    unsnappedFrame, config.gridSize, snapMinX, snapMaxX, snapMinY, snapMaxY)
                ResizeResult(edgeDelta(snapped.minX, unsnappedFrame.minX, snapMinX,
                    snapped.maxX, unsnappedFrame.maxX, snapMaxX),
                    edgeDelta(snapped.minY, unsnappedFrame.minY, snapMinY,
                        snapped.maxY, unsnappedFrame.maxY, snapMaxY),
                    emptyList())
            }
            mode == ScreenshotSnapping.Mode.OBJECT -> {
                if (!config.enablePointSnaps) return ResizeResult(0.0, 0.0, emptyList())
                val anchorsX = anchorsForSign(draggedXSign)
                val anchorsY = anchorsForSign(draggedYSign)
                if (anchorsX.isEmpty() && anchorsY.isEmpty()) return ResizeResult(0.0, 0.0, emptyList())
                val result = ScreenshotObjectSnapping.snapResize(
                    unsnappedFrame, referenceRects, config.snapDistance,
                    anchorsX, anchorsY, config.enablePointSnaps
                )
                ResizeResult(
                    edgeDelta(
                        unsnappedFrame.minX + result.dx, unsnappedFrame.minX, snapMinX,
                        unsnappedFrame.maxX + result.dx, unsnappedFrame.maxX, snapMaxX),
                    edgeDelta(
                        unsnappedFrame.minY + result.dy, unsnappedFrame.minY, snapMinY,
                        unsnappedFrame.maxY + result.dy, unsnappedFrame.maxY, snapMaxY),
                    if (config.showGuides) result.guides else emptyList()
                )
            }
            else -> ResizeResult(0.0, 0.0, emptyList())
        }
    }

    /** Per-axis correction: only the dragged edge moves. */
    private fun edgeDelta(
        snappedMin: Double, unsnappedMin: Double, useMin: Boolean,
        snappedMax: Double, unsnappedMax: Double, useMax: Boolean
    ): Double = when {
        useMin -> snappedMin - unsnappedMin
        useMax -> snappedMax - unsnappedMax
        else -> 0.0
    }

    /** `resize_snap_anchors_for_sign` from `edit_workflow/geometry/shapes.rs`. */
    internal fun anchorsForSign(sign: Double): List<ScreenshotObjectSnapping.Anchor> = when {
        sign < 0.0 -> listOf(ScreenshotObjectSnapping.Anchor.START)
        sign > 0.0 -> listOf(ScreenshotObjectSnapping.Anchor.END)
        else -> emptyList()
    }
}
