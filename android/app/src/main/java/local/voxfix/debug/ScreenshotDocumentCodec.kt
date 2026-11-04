package local.voxfix.debug

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON for [ScreenshotDocument]. Only fields the editor can rebuild are written: no bitmaps, no
 * views, no callbacks. Anything outside the whitelist is dropped instead of round-tripped.
 */
internal object ScreenshotDocumentCodec {
    const val VERSION = 1
    const val MAX_OBJECTS = 4000
    const val MAX_POINTS = 400_000
    const val MAX_CHARS = 400_000
    const val MAX_BLOCKS = 2000
    const val MAX_SIDE = 20_000

    class FormatException(message: String) : Exception(message)

    fun encode(document: ScreenshotDocument): String {
        val objects = JSONArray()
        document.objects.forEach { stroke ->
            val style = JSONObject()
                .put("color", stroke.style.color).put("width", stroke.style.width)
                .put("opacity", stroke.style.opacity.toDouble()).put("area", stroke.style.area.name)
                .put("filter", stroke.style.filter.name).put("strength", stroke.style.strength)
                .put("fontSize", stroke.style.fontSize).put("bold", stroke.style.bold)
                .put("align", stroke.style.align).put("serial", stroke.style.serialShape.name)
                .put("family", stroke.style.fontFamily).put("verticalAlign", stroke.style.verticalAlign)
                .put("outlineColor", stroke.style.outlineColor).put("outlineWidth", stroke.style.outlineWidth)
                .put("fillColor", stroke.style.fillColor).put("hasFill", stroke.style.hasFill)
                .put("cornerRadius", stroke.style.cornerRadius)
            val points = JSONArray()
            stroke.points.forEach { points.put(it.x.toDouble()).put(it.y.toDouble()) }
            objects.put(JSONObject()
                .put("id", stroke.id).put("tool", stroke.tool.name).put("color", stroke.color)
                .put("width", stroke.width.toDouble()).put("style", style)
                .put("text", stroke.text).put("number", stroke.number)
                .put("textWidth", stroke.textWidth.toDouble()).put("fontScale", stroke.fontScale.toDouble())
                .put("textHeight", stroke.textHeight.toDouble()).put("points", points))
        }
        val root = JSONObject()
            .put("version", VERSION).put("canvasWidth", document.canvasWidth)
            .put("canvasHeight", document.canvasHeight)
            .put("viewport", JSONArray().put(document.viewport.left).put(document.viewport.top)
                .put(document.viewport.right).put(document.viewport.bottom))
            .put("objects", objects)
            .put("drawingUnit",document.drawingUnit.toDouble()).put("fontUnit",document.fontUnit.toDouble())
        document.text?.let { text ->
            root.put("text", JSONObject()
                .put("mode", text.mode.name).put("translated", text.translated)
                .put("blocks", blocks(text.blocks)).put("translation", blocks(text.translation)))
        }
        if(document.regions.isNotEmpty()) root.put("regions",JSONArray().apply {
            document.regions.forEach { put(JSONArray().put(it.left).put(it.top).put(it.right).put(it.bottom)) }
        })
        return root.toString()
    }

    private fun blocks(value: List<ScreenshotTextBlock>) = JSONArray().apply {
        value.forEach { block ->
            val row=JSONArray().put(block.left).put(block.top).put(block.right).put(block.bottom).put(block.text)
            // A moved line keeps the rectangle it came from so the old pixels can be covered again.
            val origin=block.origin
            row.put(origin?.left ?: -1).put(origin?.top ?: -1).put(origin?.right ?: -1).put(origin?.bottom ?: -1)
            put(row)
        }
    }

    fun decode(json: String): ScreenshotDocument {
        val root = try { JSONObject(json) } catch (error: Exception) { throw FormatException("工程文件无法解析") }
        val version = root.optInt("version", -1)
        if (version != VERSION) throw FormatException("工程文件版本不受支持（$version）")
        val width = root.optInt("canvasWidth", 0)
        val height = root.optInt("canvasHeight", 0)
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) throw FormatException("工程画布尺寸无效")
        val viewport = root.optJSONArray("viewport") ?: throw FormatException("工程缺少视窗")
        if (viewport.length() != 4) throw FormatException("工程视窗无效")
        val left = viewport.getInt(0); val top = viewport.getInt(1)
        val right = viewport.getInt(2); val bottom = viewport.getInt(3)
        if (left < 0 || top < 0 || right > width || bottom > height || right - left < 1 || bottom - top < 1)
            throw FormatException("工程视窗超出画布")
        val rows = root.optJSONArray("objects") ?: JSONArray()
        if (rows.length() > MAX_OBJECTS) throw FormatException("工程对象过多")
        var points = 0
        val ids = HashSet<Long>()
        var maxId = 0L
        val objects = ArrayList<ScreenshotDrawingStroke>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val id = row.optLong("id", 0L)
            if (id <= 0L) throw FormatException("工程对象缺少编号")
            if (!ids.add(id)) throw FormatException("工程对象编号重复")
            maxId = maxOf(maxId, id)
            val tool = runCatching { ScreenshotDrawingTool.valueOf(row.getString("tool")) }
                .getOrElse { throw FormatException("工程对象类型无效") }
            val flat = row.optJSONArray("points") ?: JSONArray()
            if (flat.length() % 2 != 0) throw FormatException("工程对象坐标无效")
            points += flat.length() / 2
            if (points > MAX_POINTS) throw FormatException("工程坐标点过多")
            val list = ArrayList<ScreenshotDrawingPoint>(flat.length() / 2)
            for (i in 0 until flat.length() / 2) {
                val x = flat.getDouble(i * 2).toFloat()
                val y = flat.getDouble(i * 2 + 1).toFloat()
                if (!x.isFinite() || !y.isFinite()) throw FormatException("工程坐标超出范围")
                list.add(ScreenshotDrawingPoint(x, y))
            }
            if (list.isEmpty()) throw FormatException("工程对象缺少坐标")
            objects.add(ScreenshotDrawingStroke(tool, list, row.optInt("color", 0), row.optDouble("width", 2.0).toFloat(),
                style(row.optJSONObject("style")), row.optString("text", ""), row.optInt("number", 1),
                row.optDouble("textWidth", 0.0).toFloat(), row.optDouble("fontScale", 1.0).toFloat(),
                row.optDouble("textHeight", 0.0).toFloat(), id))
        }
        ScreenshotIds.above(maxId)
        val text = root.optJSONObject("text")?.let { value ->
            val mode = runCatching { ScreenshotTextMode.valueOf(value.optString("mode", "ORIGINAL")) }
                .getOrDefault(ScreenshotTextMode.ORIGINAL)
            val original = readBlocks(value.optJSONArray("blocks"))
            val translated = readBlocks(value.optJSONArray("translation"))
            ScreenshotDocumentText(original, translated, mode, value.optBoolean("translated", false))
        }
        val regions=root.optJSONArray("regions")?.let { parts ->
            if(parts.length()>MAX_OBJECTS) throw FormatException("工程选区过多")
            (0 until parts.length()).map { i ->
                val row=parts.getJSONArray(i)
                if(row.length()!=4) throw FormatException("工程选区无效")
                ScreenshotDocRect(row.getInt(0),row.getInt(1),row.getInt(2),row.getInt(3)).also {
                    if(it.left<0 || it.top<0 || it.right>width || it.bottom>height || it.width<1 || it.height<1)
                        throw FormatException("工程选区超出画布")
                }
            }
        }.orEmpty()
        fun unit(key: String): Float {
            val value=root.optDouble(key,0.0).toFloat()
            if(!value.isFinite() || value<0 || value>10000) throw FormatException("工程绘制比例无效")
            return value
        }
        return ScreenshotDocument(width, height, ScreenshotDocRect(left, top, right, bottom), objects, text,regions,unit("drawingUnit"),unit("fontUnit"))
    }

    private fun readBlocks(rows: JSONArray?): List<ScreenshotTextBlock> {
        if (rows == null) return emptyList()
        if (rows.length() > MAX_BLOCKS) throw FormatException("工程文字块过多")
        var chars = 0
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONArray(index)
            if (row.length() != 5 && row.length() != 9) throw FormatException("工程文字块无效")
            val origin=if (row.length()==9 && row.getInt(5)>=0) ScreenshotDocRect(row.getInt(5),row.getInt(6),row.getInt(7),row.getInt(8)) else null
            val block = ScreenshotTextBlock(row.getInt(0), row.getInt(1), row.getInt(2), row.getInt(3), row.getString(4), origin)
            chars += block.text.length
            if (chars > MAX_CHARS) throw FormatException("工程文字过多")
            if (block.right - block.left < 1 || block.bottom - block.top < 1) throw FormatException("工程文字块尺寸无效")
            block
        }
    }

    private fun style(row: JSONObject?): ScreenshotDrawingStyle {
        if (row == null) return ScreenshotDrawingStyle()
        fun name(key: String, fallback: String) = row.optString(key, fallback)
        return ScreenshotDrawingStyle(
            color = row.optInt("color", 0xFFFF4D4F.toInt()),
            width = row.optInt("width", 2),
            opacity = row.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
            area = runCatching { ScreenshotAreaMode.valueOf(name("area", "RECTANGLE")) }.getOrDefault(ScreenshotAreaMode.RECTANGLE),
            filter = runCatching { ScreenshotFilter.valueOf(name("filter", "MOSAIC")) }.getOrDefault(ScreenshotFilter.MOSAIC),
            strength = row.optInt("strength", 12).coerceIn(4, 64),
            fontSize = row.optInt("fontSize", 18).coerceIn(8, 96),
            bold = row.optBoolean("bold", false),
            align = row.optInt("align", 0).coerceIn(0, 2),
            serialShape = runCatching { ScreenshotSerialShape.valueOf(name("serial", "OUTLINED_CIRCLE")) }
                .getOrDefault(ScreenshotSerialShape.OUTLINED_CIRCLE),
            fontFamily = name("family", "sans-serif").take(40),
            verticalAlign = row.optInt("verticalAlign", 0).coerceIn(0, 2),
            outlineColor = row.optInt("outlineColor", 0xFF000000.toInt()),
            outlineWidth = row.optInt("outlineWidth", 0).coerceIn(0, 32),
            fillColor = row.optInt("fillColor", 0xFFFFFFFF.toInt()),
            hasFill = row.optBoolean("hasFill", false),
            cornerRadius = row.optInt("cornerRadius", 0).coerceIn(0, 64)
        )
    }
}
