package local.voxfix.debug

import android.content.Context
import org.json.JSONObject

/** Only tool choices and creation defaults survive sessions; document history stays in its session. */
class ScreenshotToolMemory(context: Context) {
    private val preferences=context.getSharedPreferences("screenshot-tool-memory",Context.MODE_PRIVATE)
    private fun raw(key: String)=runCatching { preferences.getString(key,null) }.getOrNull()
    private fun recent()=raw("recent").orEmpty().take(2048).split(',').mapNotNull { name ->
        ScreenshotAction.values().firstOrNull { it.name==name }
    }.distinct()
    fun entry(group: List<ScreenshotAction>): ScreenshotAction?=recent().firstOrNull { it in group }
    fun remember(action: ScreenshotAction) {
        if(action !in ScreenshotDrawingTools.actions.values && action !in setOf(ScreenshotAction.RECOGNIZE,ScreenshotAction.TRANSLATE,ScreenshotAction.BARCODE)) return
        preferences.edit().putString("recent",(listOf(action)+(recent()-action)).joinToString(",") { it.name }).apply()
    }
    fun style(tool: ScreenshotDrawingTool,area: ScreenshotAreaMode?=null): ScreenshotDrawingStyle? {
        val raw=raw(key(tool,area)) ?: return null
        if(raw.length>4096) return null
        return runCatching {
            val json=JSONObject(raw); val base=defaultScreenshotDrawingStyle(tool)
            fun int(name: String,default: Int,min: Int,max: Int)=json.optInt(name,default).coerceIn(min,max)
            val opacity=json.optDouble("opacity",base.opacity.toDouble()).takeIf { it.isFinite() }?.coerceIn(0.0,1.0)?.toFloat() ?: base.opacity
            val fontSize=json.optDouble("fontSize",base.fontSize.toDouble()).toFloat()
            // `filterStrength` 是上游 0..1 强度；旧版本存的是 4..64 的像素块边长（`strength`），
            // 读到旧键时按原来的线性语义换算一次，之后只写新键。
            val strength=if(json.has("filterStrength"))
                json.optDouble("filterStrength",base.strength.toDouble()).takeIf { it.isFinite() }?.coerceIn(0.0,1.0)?.toFloat() ?: base.strength
            else json.optInt("strength",-1).takeIf { it>=0 }?.let { ((it-4)/60f).coerceIn(0f,1f) } ?: base.strength
            base.copy(color=json.optInt("color",base.color),width=int("width",base.width,1,64),opacity=opacity,
                area=area ?: ScreenshotAreaMode.values().firstOrNull { it.name==json.optString("area") } ?: base.area,
                filter=ScreenshotFilter.values().firstOrNull { it.name==json.optString("filter") } ?: base.filter,
                strength=strength,fontSize=ScreenshotFontSize.clamp(tool,fontSize,base.fontSize),
                bold=json.optBoolean("bold",base.bold),align=int("align",base.align,0,2),
                serialShape=ScreenshotSerialShape.values().firstOrNull { it.name==json.optString("serialShape") } ?: base.serialShape,
                fontFamily=ScreenshotFontFamilies.resolve(json.optString("fontFamily")) ?: base.fontFamily,
                verticalAlign=int("verticalAlign",base.verticalAlign,0,2),outlineColor=json.optInt("outlineColor",base.outlineColor),
                outlineWidth=int("outlineWidth",base.outlineWidth,0,12),fillColor=json.optInt("fillColor",base.fillColor),
                hasFill=json.optBoolean("hasFill",base.hasFill),cornerRadius=int("cornerRadius",base.cornerRadius,0,83),
                strokePattern=ScreenshotStrokePattern.values().firstOrNull { it.name==json.optString("strokePattern") } ?: base.strokePattern,
                fillPattern=ScreenshotFillPattern.values().firstOrNull { it.name==json.optString("fillPattern") } ?: base.fillPattern,
                shape=ScreenshotRectangleShape.values().firstOrNull { it.name==json.optString("shape") } ?: base.shape,
                curved=if(json.has("curved")) json.optBoolean("curved") else base.curved,
                arrowType=ScreenshotArrowType.values().firstOrNull { it.name==json.optString("arrowType") } ?: base.arrowType,
                arrowRatio=json.optDouble("arrowRatio",base.arrowRatio.toDouble()).toFloat().takeIf { it.isFinite() }?.coerceIn(1f,3f) ?: base.arrowRatio,
                arrowShaftType=ScreenshotArrowShaftType.values().firstOrNull { it.name==json.optString("arrowShaftType") } ?: base.arrowShaftType,
                startHead=ScreenshotArrowhead.values().firstOrNull { it.name==json.optString("startHead") } ?: base.startHead,
                endHead=ScreenshotArrowhead.values().firstOrNull { it.name==json.optString("endHead") } ?: base.endHead,
                serialGeometryVersion=int("serialGeometryVersion",0,0,1))
        }.getOrNull()
    }
    fun save(tool: ScreenshotDrawingTool,style: ScreenshotDrawingStyle) {
        if(tool in setOf(ScreenshotDrawingTool.SELECT,ScreenshotDrawingTool.ERASER)) return
        val json=JSONObject().put("color",style.color).put("width",style.width).put("opacity",style.opacity.toDouble())
            .put("area",style.area.name).put("filter",style.filter.name).put("filterStrength",style.strength.toDouble())
            .put("fontSize",style.fontSize.toDouble()).put("bold",style.bold).put("align",style.align).put("serialShape",style.serialShape.name)
            .put("serialGeometryVersion",style.serialGeometryVersion)
            .put("fontFamily",style.fontFamily).put("verticalAlign",style.verticalAlign).put("outlineColor",style.outlineColor)
            .put("outlineWidth",style.outlineWidth).put("fillColor",style.fillColor).put("hasFill",style.hasFill).put("cornerRadius",style.cornerRadius)
            .put("strokePattern",style.strokePattern.name).put("fillPattern",style.fillPattern.name).put("shape",style.shape.name).put("curved",style.curved)
            .put("arrowType",style.arrowType.name).put("arrowRatio",style.arrowRatio.toDouble()).put("arrowShaftType",style.arrowShaftType.name)
            .put("startHead",style.startHead.name).put("endHead",style.endHead.name).toString()
        val edit=preferences.edit().putString(key(tool,null),json)
        if(tool in setOf(ScreenshotDrawingTool.FILTER,ScreenshotDrawingTool.HIGHLIGHT)) edit.putString(key(tool,style.area),json)
        edit.apply()
    }
    var startNumber: Long
        get()=when(val value=preferences.all["start-number"]) {
            is Long -> value.coerceAtLeast(0)
            is Int -> value.toLong().coerceAtLeast(0)
            else -> 1L
        }
        set(value) { preferences.edit().putLong("start-number",value.coerceAtLeast(0)).apply() }
    private fun key(tool: ScreenshotDrawingTool,area: ScreenshotAreaMode?)="style.${tool.name}.${area?.name ?: "last"}"
}
