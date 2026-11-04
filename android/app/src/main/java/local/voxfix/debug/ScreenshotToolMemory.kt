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
            base.copy(color=json.optInt("color",base.color),width=int("width",base.width,1,64),opacity=opacity,
                area=area ?: ScreenshotAreaMode.values().firstOrNull { it.name==json.optString("area") } ?: base.area,
                filter=ScreenshotFilter.values().firstOrNull { it.name==json.optString("filter") } ?: base.filter,
                strength=int("strength",base.strength,4,64),fontSize=int("fontSize",base.fontSize,8,96),
                bold=json.optBoolean("bold",base.bold),align=int("align",base.align,0,2),
                serialShape=ScreenshotSerialShape.values().firstOrNull { it.name==json.optString("serialShape") } ?: base.serialShape,
                fontFamily=json.optString("fontFamily").takeIf { it in setOf("sans-serif","serif","monospace") } ?: base.fontFamily,
                verticalAlign=int("verticalAlign",base.verticalAlign,0,2),outlineColor=json.optInt("outlineColor",base.outlineColor),
                outlineWidth=int("outlineWidth",base.outlineWidth,0,12),fillColor=json.optInt("fillColor",base.fillColor),
                hasFill=json.optBoolean("hasFill",base.hasFill),cornerRadius=int("cornerRadius",base.cornerRadius,0,32))
        }.getOrNull()
    }
    fun save(tool: ScreenshotDrawingTool,style: ScreenshotDrawingStyle) {
        if(tool in setOf(ScreenshotDrawingTool.SELECT,ScreenshotDrawingTool.ERASER)) return
        val json=JSONObject().put("color",style.color).put("width",style.width).put("opacity",style.opacity.toDouble())
            .put("area",style.area.name).put("filter",style.filter.name).put("strength",style.strength)
            .put("fontSize",style.fontSize).put("bold",style.bold).put("align",style.align).put("serialShape",style.serialShape.name)
            .put("fontFamily",style.fontFamily).put("verticalAlign",style.verticalAlign).put("outlineColor",style.outlineColor)
            .put("outlineWidth",style.outlineWidth).put("fillColor",style.fillColor).put("hasFill",style.hasFill).put("cornerRadius",style.cornerRadius).toString()
        val edit=preferences.edit().putString(key(tool,null),json)
        if(tool in setOf(ScreenshotDrawingTool.FILTER,ScreenshotDrawingTool.HIGHLIGHT)) edit.putString(key(tool,style.area),json)
        edit.apply()
    }
    var startNumber: Int
        get()=(preferences.all["start-number"] as? Int ?: 1).coerceIn(1,9999)
        set(value) { preferences.edit().putInt("start-number",value.coerceIn(1,9999)).apply() }
    private fun key(tool: ScreenshotDrawingTool,area: ScreenshotAreaMode?)="style.${tool.name}.${area?.name ?: "last"}"
}
