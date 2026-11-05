package local.voxfix.debug

import kotlin.math.roundToInt

/** Explicit property bits mirror the original style patches; equal-to-primary clicks still apply. */
enum class ScreenshotStyleProperty(val read: (ScreenshotDrawingStyle)->Any,
    val patch: (ScreenshotDrawingStyle,ScreenshotDrawingStyle)->ScreenshotDrawingStyle) {
    COLOR({it.color},{s,v->s.copy(color=v.color)}),
    WIDTH({it.width},{s,v->s.copy(width=v.width)}),
    OPACITY({it.opacity},{s,v->s.copy(opacity=v.opacity)}),
    AREA({it.area},{s,v->s.copy(area=v.area)}),
    FILTER({it.filter},{s,v->s.copy(filter=v.filter)}),
    STRENGTH({it.strength},{s,v->s.copy(strength=v.strength)}),
    FONT_SIZE({it.fontSize},{s,v->s.copy(fontSize=v.fontSize)}),
    BOLD({it.bold},{s,v->s.copy(bold=v.bold)}),
    ALIGN({it.align},{s,v->s.copy(align=v.align)}),
    SERIAL_SHAPE({it.serialShape},{s,v->s.copy(serialShape=v.serialShape)}),
    FONT_FAMILY({it.fontFamily},{s,v->s.copy(fontFamily=v.fontFamily)}),
    VERTICAL_ALIGN({it.verticalAlign},{s,v->s.copy(verticalAlign=v.verticalAlign)}),
    OUTLINE_COLOR({it.outlineColor},{s,v->s.copy(outlineColor=v.outlineColor)}),
    OUTLINE_WIDTH({it.outlineWidth},{s,v->s.copy(outlineWidth=v.outlineWidth)}),
    FILL_COLOR({it.fillColor},{s,v->s.copy(fillColor=v.fillColor)}),
    HAS_FILL({it.hasFill},{s,v->s.copy(hasFill=v.hasFill)}),
    CORNER_RADIUS({it.cornerRadius},{s,v->s.copy(cornerRadius=v.cornerRadius)}),
    STROKE_PATTERN({it.strokePattern},{s,v->s.copy(strokePattern=v.strokePattern)}),
    FILL_PATTERN({it.fillPattern},{s,v->s.copy(fillPattern=v.fillPattern)}),
    SHAPE({it.shape},{s,v->s.copy(shape=v.shape)}),
    CURVED({it.curved},{s,v->s.copy(curved=v.curved)}),
    ARROW_TYPE({it.arrowType},{s,v->s.copy(arrowType=v.arrowType)}),
    ARROW_RATIO({it.arrowRatio},{s,v->s.copy(arrowRatio=v.arrowRatio)}),
    ARROW_SHAFT_TYPE({it.arrowShaftType},{s,v->s.copy(arrowShaftType=v.arrowShaftType)}),
    START_HEAD({it.startHead},{s,v->s.copy(startHead=v.startHead)}),
    END_HEAD({it.endHead},{s,v->s.copy(endHead=v.endHead)}),
    SERIAL_GEOMETRY_VERSION({it.serialGeometryVersion},{s,v->s.copy(serialGeometryVersion=v.serialGeometryVersion)})
}

/** Source priority and category scope: fixed upstream style.rs::style_toolbar_source. */
internal object ScreenshotSelectionStyle {
    private fun category(s: ScreenshotDrawingStroke)=s.tool to
        (if(s.tool==ScreenshotDrawingTool.HIGHLIGHT) s.style.area else ScreenshotAreaMode.RECTANGLE)
    fun indices(items: List<ScreenshotDrawingStroke>,selection: Set<Int>,primary: Int?): List<Int> {
        val selected=selection.filter { it in items.indices }
        val main=primary?.takeIf { it in selected }
        val filters=selected.filter { items[it].tool==ScreenshotDrawingTool.FILTER }
        if(filters.isNotEmpty()) {
            val first=main?.takeIf { it in filters } ?: filters.firstOrNull { items[it].style.area==ScreenshotAreaMode.PEN } ?: filters.first()
            return listOf(first)+filters.filter { it!=first }
        }
        val textTools=setOf(ScreenshotDrawingTool.TEXT,ScreenshotDrawingTool.SERIAL)
        val source=main?.takeIf { items[it].tool in textTools }
            ?: selected.firstOrNull { items[it].tool==ScreenshotDrawingTool.TEXT }
            ?: selected.firstOrNull { items[it].tool==ScreenshotDrawingTool.SERIAL }
            ?: listOf(ScreenshotDrawingTool.SPOTLIGHT,ScreenshotDrawingTool.RECTANGLE,ScreenshotDrawingTool.HIGHLIGHT,
                ScreenshotDrawingTool.LINE,ScreenshotDrawingTool.PEN,ScreenshotDrawingTool.ARROW).firstNotNullOfOrNull { tool ->
                selected.firstOrNull { items[it].tool==tool && (tool!=ScreenshotDrawingTool.HIGHLIGHT || items[it].style.area==ScreenshotAreaMode.RECTANGLE) }
                    ?: selected.firstOrNull { items[it].tool==tool }
            } ?: return emptyList()
        val matching=selected.filter { category(items[it])==category(items[source]) }
        return if(main in matching) listOf(main!!)+matching.filter { it!=main } else matching
    }
    fun inspected(s: ScreenshotDrawingStroke,pixelUnit: Float)=s.style.copy(color=s.color,
        width=(s.width/pixelUnit).roundToInt().coerceIn(1,64),
        fontSize=(s.style.fontSize*s.fontScale).takeIf { it.isFinite() && it>=ScreenshotFontSize.MIN } ?: ScreenshotFontSize.MIN)
    fun mixed(items: List<ScreenshotDrawingStroke>,indices: List<Int>,pixelUnit: Float): Set<ScreenshotStyleProperty> {
        val strokes=indices.map { items[it] }
        val first=strokes.firstOrNull() ?: return emptySet()
        fun value(s: ScreenshotDrawingStroke,p: ScreenshotStyleProperty): Any=when(p) {
            ScreenshotStyleProperty.WIDTH -> if(s.tool==ScreenshotDrawingTool.FILTER && s.style.area==ScreenshotAreaMode.RECTANGLE) 0f else s.width/pixelUnit
            ScreenshotStyleProperty.FONT_SIZE -> s.style.fontSize*s.fontScale
            ScreenshotStyleProperty.FILL_COLOR -> if(s.style.hasFill) s.style.fillColor else 0
            ScreenshotStyleProperty.COLOR -> s.color
            else -> p.read(s.style)
        }
        return ScreenshotStyleProperty.values().filterTo(mutableSetOf()) { p-> strokes.any { value(it,p)!=value(first,p) } }
    }
    fun patch(s: ScreenshotDrawingStroke,value: ScreenshotDrawingStyle,properties: Set<ScreenshotStyleProperty>,pixelUnit: Float): ScreenshotDrawingStroke {
        var next=s.style
        for(p in properties) {
            if(s.tool==ScreenshotDrawingTool.FILTER && s.style.area==ScreenshotAreaMode.RECTANGLE && p==ScreenshotStyleProperty.WIDTH) continue
            val serialShape=if(ScreenshotStyleProperty.SERIAL_SHAPE in properties) value.serialShape else s.style.serialShape
            if(s.tool==ScreenshotDrawingTool.SERIAL && serialShape==ScreenshotSerialShape.CIRCLE && p==ScreenshotStyleProperty.FONT_FAMILY) continue
            next=p.patch(next,value)
        }
        if(ScreenshotStyleProperty.FONT_SIZE in properties)
            next=next.copy(fontSize=ScreenshotFontSize.clamp(s.tool,value.fontSize)/s.fontScale)
        // arrow_with_style recomputes the path when crossing the Elbow type boundary.
        val points = if (s.tool == ScreenshotDrawingTool.ARROW && s.points.size >= 2 && s.style.arrowType != next.arrowType) {
            when {
                next.arrowType == ScreenshotArrowType.ELBOW -> ScreenshotElbowRouter.route(s.points.first(), s.points.last())
                s.style.arrowType == ScreenshotArrowType.ELBOW -> listOf(s.points.first(), s.points.last())
                else -> s.points
            }
        } else s.points
        return s.copy(style=next,
            points=points,
            fixedElbowSegments=if(next.arrowType==ScreenshotArrowType.ELBOW) s.fixedElbowSegments else emptySet(),
            color=if(ScreenshotStyleProperty.COLOR in properties) value.color else s.color,
            width=if(ScreenshotStyleProperty.WIDTH in properties && !(s.tool==ScreenshotDrawingTool.FILTER && s.style.area==ScreenshotAreaMode.RECTANGLE)) value.width*pixelUnit else s.width)
    }
}
