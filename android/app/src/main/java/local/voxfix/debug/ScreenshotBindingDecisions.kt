package local.voxfix.debug

/**
 * Endpoint binding decisions for T22, ported from Snow Shot 1.1.8
 * `arrow/binding_core_decisions.rs` at `afdc340b14e7f92a294a95c374d13ada0d8c7a82`
 * (Apache-2.0).
 *
 * The rules are Excalidraw's: an endpoint either stays unchanged, drops its binding, or
 * binds with one of two strategies (Inside pins a ratio inside the element, Orbit keeps a
 * projected outline focus). Which one applies depends on where the pointer is, whether the
 * opposite end already binds the same element, and on the caller's bind mode / Alt gate.
 */

internal object ScreenshotBindingDecisions {

    enum class Disposition { UNCHANGED, CLEAR, BIND }

    /** One endpoint's verdict; [mode]/[bindableId]/[focusPoint] are null unless [Disposition.BIND]. */
    data class Decision(
        val disposition: Disposition,
        val mode: ScreenshotBindMode? = null,
        val bindableId: Long? = null,
        val focusPoint: DoubleArray? = null
    )

    data class DecisionPair(val start: Decision, val end: Decision)

    /** Caller context (original `ComputeEndpointDragContext`). */
    data class Context(
        val zoom: Double = 1.0,
        val isBindingEnabled: Boolean = true,
        val bindMode: ScreenshotBindMode = ScreenshotBindMode.ORBIT
    )

    /** Per-drag modifiers (original `ComputeEndpointDragOptions`). */
    data class Options(
        val complexBindings: Boolean? = null,
        val angleLocked: Boolean? = null,
        val altKey: Boolean? = null,
        val finalize: Boolean? = null,
        val oppositeOrbitFocusPoint: DoubleArray? = null
    )

    data class DragInput(
        val arrow: ScreenshotBindingArrow,
        val pointer: DoubleArray,
        val bindables: List<ScreenshotBindingGeometry.Bindable>,
        val draggedPoints: List<Pair<Int, ScreenshotDrawingPoint>>,
        val context: Context = Context(),
        val options: Options = Options()
    )

    private val UNCHANGED = Decision(Disposition.UNCHANGED)
    private val CLEAR = Decision(Disposition.CLEAR)

    private fun bind(mode: ScreenshotBindMode, bindable: ScreenshotBindingGeometry.Bindable, focus: DoubleArray) =
        Decision(Disposition.BIND, mode, bindable.id, focus)

    /** Normalised dragged indices, ignoring anything past the end of the point list. */
    fun normalizeDraggedPoints(arrow: ScreenshotBindingArrow, dragged: List<Pair<Int, ScreenshotDrawingPoint>>) =
        dragged.filter { it.first in arrow.points.indices }.toMap()

    private fun pickDragEdge(startDragged: Boolean, decision: Decision, other: Decision) =
        if (startDragged) DecisionPair(decision, other) else DecisionPair(other, decision)

    /** `to_binding`: the strategy resolved into a persisted binding. */
    fun toBinding(
        arrow: ScreenshotBindingArrow,
        edge: ScreenshotEndpointEdge,
        element: ScreenshotBindingGeometry.Bindable,
        mode: ScreenshotBindMode,
        focusPoint: DoubleArray,
        midpointSnappingEnabled: Boolean
    ): ScreenshotFixedPointBinding {
        val fixed = if (arrow.elbowed) {
            ScreenshotBindingProjection.calculateFixedPointForElbowBinding(arrow, element, edge, midpointSnappingEnabled)
        } else {
            ScreenshotBindingProjection.calculateFixedPointForBinding(element, focusPoint)
        }
        return ScreenshotFixedPointBinding(
            elementId = element.id,
            mode = if (arrow.elbowed) ScreenshotBindMode.ORBIT else mode,
            fixedX = fixed[0],
            fixedY = fixed[1]
        )
    }

    private fun strategyForElbowEndpoint(input: DragInput): DecisionPair {
        val dragged = normalizeDraggedPoints(input.arrow, input.draggedPoints)
        val draggedIndex = dragged.keys.minOrNull()
            ?: return DecisionPair(UNCHANGED, UNCHANGED)
        val hit = ScreenshotBindingGeometry.hoveredBindable(
            input.pointer, input.bindables, ScreenshotBindingGeometry.maxBindingDistance(input.context.zoom)
        )
        val focusPoint = input.arrow.pointAtGlobal(draggedIndex)
        val decision = if (hit != null) bind(ScreenshotBindMode.ORBIT, hit, focusPoint) else CLEAR
        return if (draggedIndex == 0) DecisionPair(decision, UNCHANGED) else DecisionPair(UNCHANGED, decision)
    }

    /** `get_endpoint_binding_decisions`: the full branch order matters, keep it verbatim. */
    fun decide(input: DragInput): DecisionPair {
        val arrow = input.arrow
        val bindables = input.bindables
        val context = input.context
        val options = input.options
        val complexBindings = options.complexBindings ?: false
        val dragged = normalizeDraggedPoints(arrow, input.draggedPoints)
        val startIndex = 0
        val endIndex = arrow.points.size - 1
        val startDragged = dragged.containsKey(startIndex)
        val endDragged = dragged.containsKey(endIndex)
        val byId = bindables.associateBy { it.id }

        if (!startDragged && !endDragged) return DecisionPair(UNCHANGED, UNCHANGED)
        // Dragging both ends at once means the arrow is being moved, not attached.
        if (startDragged && endDragged) return DecisionPair(CLEAR, CLEAR)
        if (!context.isBindingEnabled) return pickDragEdge(startDragged, CLEAR, UNCHANGED)
        if (arrow.elbowed) return strategyForElbowEndpoint(input)

        val draggedIndex = if (startDragged) startIndex else endIndex
        val draggedPoint = dragged[draggedIndex] ?: return DecisionPair(UNCHANGED, UNCHANGED)
        val globalPoint = doubleArrayOf(
            arrow.originX + draggedPoint.x.toDouble(),
            arrow.originY + draggedPoint.y.toDouble()
        )
        // While angle-locked the dragged point slides along the locked ray, so hit-test the
        // raw pointer instead (same as the original scene pointer).
        val angleLocked = options.angleLocked ?: false
        val hitTarget = if (angleLocked) input.pointer else globalPoint
        val maxDistance = ScreenshotBindingGeometry.maxBindingDistance(context.zoom)
        val hit = ScreenshotBindingGeometry.hoveredBindable(hitTarget, bindables, maxDistance)
        val otherBinding = if (startDragged) arrow.endBinding else arrow.startBinding
        val overlapping = ScreenshotBindingGeometry.bindablesOverPoint(globalPoint, bindables, maxDistance)
        val otherBindable = otherBinding?.let { byId[it.elementId] }
        val isOverlappingOther = otherBindable != null && overlapping.any { it.id == otherBindable.id }
        val isNested = hit != null && otherBindable != null && otherBindable.id != hit.id &&
            ScreenshotBindingGeometry.isInsideOther(otherBindable, hit)
        val bindModeForcesInside = context.bindMode == ScreenshotBindMode.INSIDE ||
            context.bindMode == ScreenshotBindMode.SKIP
        val altForcesInside = options.altKey ?: false
        val pointInHit = hit != null && ScreenshotBindingGeometry.isPointInBindable(hitTarget, hit)
        val otherFocusPoint = if (otherBinding != null && otherBindable != null) {
            ScreenshotBindingGeometry.fixedPointToGlobal(otherBinding.fixedX, otherBinding.fixedY, otherBindable)
        } else {
            null
        }
        val otherFocusPointIsInElement = if (otherBindable != null && otherFocusPoint != null) {
            ScreenshotBindingGeometry.isPointInBindable(otherFocusPoint, otherBindable) ||
                ScreenshotBindingGeometry.distanceToOutline(otherFocusPoint, otherBindable) <= 1e-4
        } else {
            false
        }
        val pointIsCloseToOtherElement = otherBindable != null && (
            ScreenshotBindingGeometry.isPointInBindable(globalPoint, otherBindable) ||
                ScreenshotBindingGeometry.distanceToOutline(globalPoint, otherBindable) <= maxDistance
            )
        // An opposite inside binding is never overridden.
        val otherNeverOverride = otherBinding?.mode == ScreenshotBindMode.INSIDE
        val oppositeIndex = if (startDragged) endIndex else startIndex
        val oppositePoint = arrow.pointAtGlobal(oppositeIndex)
        val midpointSnappingEnabled = !angleLocked

        val angleLockedOther = if (otherBindable != null && !otherNeverOverride && angleLocked) {
            val edge = if (startDragged) ScreenshotEndpointEdge.END else ScreenshotEndpointEdge.START
            val projected = ScreenshotBindingProjection.projectFixedPointOntoDiagonal(
                arrow, oppositePoint, otherBindable, edge, bindables, context.zoom,
                // The opposite end keeps midpoint snapping while angle-locked.
                true
            ) ?: oppositePoint
            bind(ScreenshotBindMode.ORBIT, otherBindable, projected)
        } else {
            UNCHANGED
        }

        val otherDecision = if (otherBindable != null) {
            val orbitFocus = options.oppositeOrbitFocusPoint
            if (orbitFocus != null) {
                if (!otherNeverOverride && !otherFocusPointIsInElement && !pointIsCloseToOtherElement) {
                    bind(ScreenshotBindMode.ORBIT, otherBindable, orbitFocus)
                } else {
                    angleLockedOther
                }
            } else {
                angleLockedOther
            }
        } else {
            UNCHANGED
        }

        if (bindModeForcesInside) {
            // An opaque overlapping element wins the forced-inside target, otherwise the hit.
            val bindTarget = if (hit != null && isOverlappingOther) {
                otherBindable?.takeIf { ScreenshotBindingGeometry.isBackgroundOpaque(it) } ?: hit
            } else {
                hit
            }
            val forced = if (bindTarget != null) {
                bind(ScreenshotBindMode.INSIDE, bindTarget, globalPoint)
            } else {
                CLEAR
            }
            val shouldBreakOppositeBinding = (options.finalize ?: false) &&
                hit != null && otherBinding != null &&
                otherBinding.elementId == hit.id && arrow.points.size == 2
            val other = if (shouldBreakOppositeBinding) CLEAR else UNCHANGED
            return pickDragEdge(startDragged, forced, other)
        }

        if (altForcesInside) {
            val forced = if (hit != null) bind(ScreenshotBindMode.INSIDE, hit, globalPoint) else CLEAR
            return pickDragEdge(startDragged, forced, UNCHANGED)
        }

        if (otherBinding != null && hit != null && otherBinding.elementId == hit.id) {
            if (!complexBindings) {
                val current = bind(ScreenshotBindMode.INSIDE, hit, globalPoint)
                val opposite = bind(ScreenshotBindMode.INSIDE, hit, oppositePoint)
                return pickDragEdge(startDragged, current, opposite)
            }
            if (otherBinding.mode == ScreenshotBindMode.ORBIT) {
                val edge = if (startDragged) ScreenshotEndpointEdge.START else ScreenshotEndpointEdge.END
                val projectedFocus = ScreenshotBindingProjection.projectFixedPointOntoDiagonal(
                    arrow, globalPoint, hit, edge, bindables, context.zoom, midpointSnappingEnabled
                ) ?: globalPoint
                val current = bind(ScreenshotBindMode.ORBIT, hit, projectedFocus)
                val other = if (options.finalize ?: false) {
                    if (arrow.points.size > 2) UNCHANGED else CLEAR
                } else {
                    UNCHANGED
                }
                return pickDragEdge(startDragged, current, other)
            }
            return pickDragEdge(startDragged, bind(ScreenshotBindMode.INSIDE, hit, globalPoint), UNCHANGED)
        }

        if (hit == null) return pickDragEdge(startDragged, CLEAR, otherDecision)

        if (otherBindable != null && isOverlappingOther && ScreenshotBindingGeometry.isBackgroundOpaque(otherBindable)) {
            return pickDragEdge(startDragged, bind(ScreenshotBindMode.INSIDE, otherBindable, globalPoint), UNCHANGED)
        }

        // Normal case: inside when the pointer is within the hit element, orbit otherwise.
        val mode = when {
            complexBindings -> ScreenshotBindMode.ORBIT
            pointInHit && !isNested -> ScreenshotBindMode.INSIDE
            else -> ScreenshotBindMode.ORBIT
        }
        val projectedFocus = if (mode == ScreenshotBindMode.ORBIT) {
            ScreenshotBindingProjection.projectFixedPointOntoDiagonal(
                arrow, globalPoint, hit,
                if (startDragged) ScreenshotEndpointEdge.START else ScreenshotEndpointEdge.END,
                bindables, context.zoom, midpointSnappingEnabled
            ) ?: globalPoint
        } else {
            globalPoint
        }
        return pickDragEdge(startDragged, bind(mode, hit, projectedFocus), otherDecision)
    }
}
