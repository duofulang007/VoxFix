package local.voxfix.debug

/**
 * Drag-time binding glue for T22: turns one endpoint drag into a binding decision and the
 * endpoint position that decision implies.
 *
 * Everything here works in **world** coordinates. The selection layer converts to and from a
 * stroke's local space (which also carries rotation) around this call, so the binding ratios
 * stay element-relative and the arrow can be rotated afterwards without breaking the pin.
 */

internal object ScreenshotBindingDrag {

    data class Result(
        val points: List<ScreenshotDrawingPoint>,
        val startBinding: ScreenshotFixedPointBinding?,
        val endBinding: ScreenshotFixedPointBinding?
    )

    /**
     * @param worldPoints the arrow's current points in world space, tip already moved.
     * @param handle index that is being dragged; middle vertices never bind.
     * @return null when nothing applies, so the caller keeps its plain path.
     */
    fun dragEndpoint(
        stroke: ScreenshotDrawingStroke,
        worldPoints: List<ScreenshotDrawingPoint>,
        handle: Int,
        pointer: ScreenshotDrawingPoint,
        bindables: List<ScreenshotBindingGeometry.Bindable>
    ): Result? {
        if (stroke.tool != ScreenshotDrawingTool.ARROW) return null
        if (worldPoints.size < 2) return null
        if (handle != 0 && handle != worldPoints.lastIndex) return null

        val arrow = ScreenshotBindingArrow(
            points = worldPoints,
            elbowed = stroke.style.arrowType == ScreenshotArrowType.ELBOW,
            startBinding = stroke.startBinding,
            endBinding = stroke.endBinding,
            hasStartArrowhead = stroke.style.startHead != ScreenshotArrowhead.NONE,
            hasEndArrowhead = stroke.style.endHead != ScreenshotArrowhead.NONE
        )
        val pair = ScreenshotBindingDecisions.decide(
            ScreenshotBindingDecisions.DragInput(
                arrow = arrow,
                pointer = doubleArrayOf(pointer.x.toDouble(), pointer.y.toDouble()),
                bindables = bindables,
                draggedPoints = listOf(handle to worldPoints[handle])
            )
        )
        val byId = bindables.associateBy { it.id }
        val bound = ScreenshotBindingDocument.applyBindings(
            stroke, pair, byId, midpointSnappingEnabled = true
        )

        val points = worldPoints.toMutableList()
        val resolveArrow = arrow.copy(startBinding = bound.startBinding, endBinding = bound.endBinding)
        bound.endBinding?.let { binding ->
            byId[binding.elementId]?.let { element ->
                ScreenshotBindingProjection.updateBoundPoint(
                    resolveArrow, ScreenshotEndpointEdge.END, binding, element, byId, dragging = true
                )?.let { points[points.lastIndex] = ScreenshotDrawingPoint(it[0].toFloat(), it[1].toFloat()) }
            }
        }
        bound.startBinding?.let { binding ->
            byId[binding.elementId]?.let { element ->
                ScreenshotBindingProjection.updateBoundPoint(
                    resolveArrow, ScreenshotEndpointEdge.START, binding, element, byId, dragging = true
                )?.let { points[0] = ScreenshotDrawingPoint(it[0].toFloat(), it[1].toFloat()) }
            }
        }

        return Result(points, bound.startBinding, bound.endBinding)
    }

    /**
     * Re-resolves both pinned ends without making a new decision. Used after the commit-time
     * axis snap, which could otherwise push a pinned tip off its element.
     */
    fun resolve(
        stroke: ScreenshotDrawingStroke,
        worldPoints: List<ScreenshotDrawingPoint>,
        bindables: List<ScreenshotBindingGeometry.Bindable>
    ): Result? {
        if (stroke.startBinding == null && stroke.endBinding == null) return null
        if (worldPoints.size < 2) return null
        val byId = bindables.associateBy { it.id }
        val arrow = ScreenshotBindingArrow(
            points = worldPoints,
            elbowed = stroke.style.arrowType == ScreenshotArrowType.ELBOW,
            startBinding = stroke.startBinding,
            endBinding = stroke.endBinding,
            hasStartArrowhead = stroke.style.startHead != ScreenshotArrowhead.NONE,
            hasEndArrowhead = stroke.style.endHead != ScreenshotArrowhead.NONE
        )
        val points = worldPoints.toMutableList()
        stroke.endBinding?.let { binding ->
            byId[binding.elementId]?.let { element ->
                ScreenshotBindingProjection.updateBoundPoint(
                    arrow, ScreenshotEndpointEdge.END, binding, element, byId, dragging = false
                )?.let { points[points.lastIndex] = ScreenshotDrawingPoint(it[0].toFloat(), it[1].toFloat()) }
            }
        }
        stroke.startBinding?.let { binding ->
            byId[binding.elementId]?.let { element ->
                ScreenshotBindingProjection.updateBoundPoint(
                    arrow, ScreenshotEndpointEdge.START, binding, element, byId, dragging = false
                )?.let { points[0] = ScreenshotDrawingPoint(it[0].toFloat(), it[1].toFloat()) }
            }
        }
        return Result(points, stroke.startBinding, stroke.endBinding)
    }
}
