package local.voxfix.debug

/**
 * Document ↔ binding adapter: turns screenshot objects into bindables and writes endpoint
 * decisions back onto an arrow stroke.
 *
 * The conversion follows Snow Shot 1.1.8 `BindableElementState::arrow_bindable_state()`
 * (fixed commit `afdc340b`, Apache-2.0): **every bindable is a rectangle proxy** built from
 * the element's own rectangle — x/y are the centre minus half size, `angle` is the element
 * rotation, `stroke_width` its stroke, `z_index` its paint index, and `background_opaque` is
 * "the fill has non-zero alpha". The engine still supports ellipse/diamond bindables, but this
 * build never feeds them, so the phone side does not invent any.
 *
 * 待核对：上游由 `snow-draw-engine-model` 决定哪些元素进入 bindables 列表；该文件尚未取证，
 * 这里默认排除箭头/直线自身（连接器不互相绑定）。在拿到该规则前不宣称逐项一致。
 */

internal object ScreenshotBindingDocument {

    /**
     * @param boundsOf element bounds in the arrow's coordinate space; null skips the element.
     * @param paintIndexOf paint order used as `z_index`, later painted = on top.
     */
    fun bindables(
        strokes: List<ScreenshotDrawingStroke>,
        boundsOf: (ScreenshotDrawingStroke) -> ScreenshotBindingGeometry.Bounds?,
        paintIndexOf: (ScreenshotDrawingStroke) -> Int
    ): List<ScreenshotBindingGeometry.Bindable> = strokes.mapNotNull { stroke ->
        if (stroke.tool == ScreenshotDrawingTool.ARROW || stroke.tool == ScreenshotDrawingTool.LINE) return@mapNotNull null
        val bounds = boundsOf(stroke) ?: return@mapNotNull null
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        if (width <= 0f || height <= 0f) return@mapNotNull null
        ScreenshotBindingGeometry.Bindable(
            id = stroke.id,
            shape = ScreenshotBindingGeometry.Shape.RECTANGLE,
            x = bounds.left,
            y = bounds.top,
            width = width,
            height = height,
            angle = stroke.rotation,
            strokeWidth = stroke.width,
            zIndex = paintIndexOf(stroke).toDouble(),
            // rect.fill.a != 0 upstream: a shape without an opaque fill lets the arrow through
            backgroundOpaque = stroke.style.hasFill && (stroke.style.fillColor ushr 24) != 0,
            bindingEnabled = true,
            interiorHitEnabled = true,
            visibilityBounds = null
        )
    }

    /**
     * Validates one persisted binding. Kept free of JSON so it can be unit-tested on the JVM;
     * the codec only unpacks the four values and converts a null into `FormatException`.
     */
    fun parseBinding(elementId: Long, modeName: String?, fixedX: Double, fixedY: Double): ScreenshotFixedPointBinding? {
        val mode = runCatching { ScreenshotBindMode.valueOf(modeName ?: "") }.getOrNull() ?: return null
        if (elementId <= 0L || !fixedX.isFinite() || !fixedY.isFinite()) return null
        return ScreenshotFixedPointBinding(elementId, mode, fixedX, fixedY)
    }

    /** The arrow view the binding engine works on; points are used as-is (origin 0). */    fun arrowOf(stroke: ScreenshotDrawingStroke): ScreenshotBindingArrow = ScreenshotBindingArrow(
        points = stroke.points,
        elbowed = stroke.style.arrowType == ScreenshotArrowType.ELBOW,
        startBinding = stroke.startBinding,
        endBinding = stroke.endBinding,
        hasStartArrowhead = stroke.style.startHead != ScreenshotArrowhead.NONE,
        hasEndArrowhead = stroke.style.endHead != ScreenshotArrowhead.NONE
    )

    /**
     * Writes a decision pair onto the stroke: BIND stores the resolved ratio, CLEAR drops the
     * binding, UNCHANGED keeps whatever the stroke already had.
     */
    fun applyBindings(
        stroke: ScreenshotDrawingStroke,
        pair: ScreenshotBindingDecisions.DecisionPair,
        bindables: Map<Long, ScreenshotBindingGeometry.Bindable>,
        midpointSnappingEnabled: Boolean
    ): ScreenshotDrawingStroke {
        val arrow = arrowOf(stroke)
        return stroke.copy(
            startBinding = resolveBinding(stroke, arrow, pair.start, ScreenshotEndpointEdge.START, bindables, midpointSnappingEnabled),
            endBinding = resolveBinding(stroke, arrow, pair.end, ScreenshotEndpointEdge.END, bindables, midpointSnappingEnabled)
        )
    }

    private fun resolveBinding(
        stroke: ScreenshotDrawingStroke,
        arrow: ScreenshotBindingArrow,
        decision: ScreenshotBindingDecisions.Decision,
        edge: ScreenshotEndpointEdge,
        bindables: Map<Long, ScreenshotBindingGeometry.Bindable>,
        midpointSnappingEnabled: Boolean
    ): ScreenshotFixedPointBinding? = when (decision.disposition) {
        ScreenshotBindingDecisions.Disposition.UNCHANGED ->
            if (edge == ScreenshotEndpointEdge.START) stroke.startBinding else stroke.endBinding
        ScreenshotBindingDecisions.Disposition.CLEAR -> null
        ScreenshotBindingDecisions.Disposition.BIND -> {
            val id = decision.bindableId
            val mode = decision.mode
            val element = id?.let { bindables[it] }
            if (id == null || mode == null || element == null) {
                if (edge == ScreenshotEndpointEdge.START) stroke.startBinding else stroke.endBinding
            } else {
                ScreenshotBindingDecisions.toBinding(
                    arrow, edge, element, mode,
                    decision.focusPoint ?: arrow.pointAtGlobal(if (edge == ScreenshotEndpointEdge.START) 0 else -1),
                    midpointSnappingEnabled
                )
            }
        }
    }

    /**
     * Moves bound endpoints to the position their binding implies. Unbound ends are untouched,
     * which is why dragging a bound arrow by its body keeps both pins.
     */
    fun resolveEndpoints(
        stroke: ScreenshotDrawingStroke,
        bindables: Map<Long, ScreenshotBindingGeometry.Bindable>,
        dragging: Boolean = false
    ): ScreenshotDrawingStroke {
        if (stroke.startBinding == null && stroke.endBinding == null) return stroke
        val arrow = arrowOf(stroke)
        val points = arrow.points.toMutableList()

        stroke.endBinding?.let { binding ->
            val element = bindables[binding.elementId] ?: return@let
            ScreenshotBindingProjection.updateBoundPoint(
                arrow, ScreenshotEndpointEdge.END, binding, element, bindables, dragging
            )?.let { points[points.lastIndex] = ScreenshotDrawingPoint(it[0].toFloat(), it[1].toFloat()) }
        }
        stroke.startBinding?.let { binding ->
            val element = bindables[binding.elementId] ?: return@let
            ScreenshotBindingProjection.updateBoundPoint(
                arrow, ScreenshotEndpointEdge.START, binding, element, bindables, dragging
            )?.let { points[0] = ScreenshotDrawingPoint(it[0].toFloat(), it[1].toFloat()) }
        }
        return if (points == stroke.points) stroke else stroke.copy(points = points)
    }
}
