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
                .put("filter", stroke.style.filter.name).put("filterStrength", stroke.style.strength.toDouble())
                .put("fontSize", stroke.style.fontSize.toDouble()).put("bold", stroke.style.bold)
                .put("align", stroke.style.align).put("serial", stroke.style.serialShape.name)
                .put("family", stroke.style.fontFamily).put("verticalAlign", stroke.style.verticalAlign)
                .put("outlineColor", stroke.style.outlineColor).put("outlineWidth", stroke.style.outlineWidth)
                .put("fillColor", stroke.style.fillColor).put("hasFill", stroke.style.hasFill)
                .put("cornerRadius", stroke.style.cornerRadius)
                .also { style -> stroke.style.cornerRadii?.let { radii ->
                    style.put("cornerRadii", JSONArray().apply { radii.forEach { r -> put(r.toDouble()) } }) } }
                .put("strokePattern", stroke.style.strokePattern.name).put("fillPattern", stroke.style.fillPattern.name)
                .put("shape", stroke.style.shape.name).put("curved", stroke.style.curved)
                .put("arrowType", stroke.style.arrowType.name).put("arrowRatio", stroke.style.arrowRatio.toDouble())
                .put("arrowShaftType", stroke.style.arrowShaftType.name)
                .put("startHead", stroke.style.startHead.name).put("endHead", stroke.style.endHead.name)
                .put("serialGeometryVersion",stroke.style.serialGeometryVersion)
            val points = JSONArray()
            stroke.points.forEach { points.put(it.x.toDouble()).put(it.y.toDouble()) }
            val objectRow = JSONObject()
                .put("id", stroke.id).put("tool", stroke.tool.name).put("color", stroke.color)
                .put("width", stroke.width.toDouble()).put("style", style)
                .put("text", stroke.text).put("number", stroke.number)
                .put("textWidth", stroke.textWidth.toDouble()).put("fontScale", stroke.fontScale.toDouble())
                .put("autoResize", stroke.autoResize)
                .put("textHeight", stroke.textHeight.toDouble()).put("points", points)
                // Written only for pen strokes that use the smoothed geometry; a missing key means the
                // legacy polyline, so older readers and older documents stay valid.
                .put("smooth", stroke.smooth).put("closedPath", stroke.closedPath).put("boundTextId",stroke.boundTextId)
                .put("rotation",stroke.rotation.toDouble())
            if (stroke.fixedElbowSegments.isNotEmpty()) {
                objectRow.put("fixedElbowSegments", JSONArray(stroke.fixedElbowSegments.sorted()))
            }
            // Binding keys are omitted for free endpoints, so a pre-T22 project re-encodes byte for
            // byte and an older reader simply sees no binding.
            stroke.startBinding?.let { objectRow.put("startBinding", bindingRow(it)) }
            stroke.endBinding?.let { objectRow.put("endBinding", bindingRow(it)) }
            stroke.autoFilterCategory?.let { objectRow.put("autoFilterCategory", it) }
            objects.put(objectRow)
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
        document.regionOperands?.let { root.put("regionOperands", JSONArray(it)) }
        document.watermark?.let { mark ->
            val row = JSONObject()
                .put("text", mark.text).put("fontSize", mark.fontSize.toDouble())
                .put("fontFamily", mark.fontFamily).put("color", mark.color)
                .put("angle", mark.angle.toDouble()).put("gap", mark.gap.toDouble())
                .put("opacity", mark.opacity.toDouble())
            if (mark.template.isNotEmpty()) row.put("template", mark.template).put("appliedAt", mark.appliedAt)
            root.put("watermark", row)
        }
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
            if(row.has("boundTextId") && row.opt("boundTextId") !is Int && row.opt("boundTextId") !is Long)
                throw FormatException("工程序号文字关联编号无效")
            if(row.has("rotation") && row.opt("rotation") !is Number) throw FormatException("工程对象旋转角无效")
            val rotation=row.optDouble("rotation",0.0).toFloat()
            if(!rotation.isFinite()) throw FormatException("工程对象旋转角无效")
            val fontScale=row.optDouble("fontScale",1.0).toFloat()
            if(!fontScale.isFinite() || fontScale<=0f) throw FormatException("工程字体缩放无效")
            val style = style(row.optJSONObject("style"), tool, fontScale)
            val fixedSegments = linkedSetOf<Int>()
            if (row.has("fixedElbowSegments")) {
                val fixed = row.optJSONArray("fixedElbowSegments")
                    ?: throw FormatException("工程折线固定段无效")
                if (tool != ScreenshotDrawingTool.ARROW || style.arrowType != ScreenshotArrowType.ELBOW ||
                    fixed.length() >= list.size) throw FormatException("工程折线固定段无效")
                for (i in 0 until fixed.length()) {
                    val segment = fixed.opt(i) as? Int ?: throw FormatException("工程折线固定段无效")
                    if (segment !in 1 until list.size || !fixedSegments.add(segment))
                        throw FormatException("工程折线固定段无效")
                }
            }
            val autoFilterCategory = if (row.has("autoFilterCategory")) {
                val category = row.opt("autoFilterCategory") as? Int
                if (category == null || category !in ScreenshotAutoFilter.labels.indices ||
                    tool != ScreenshotDrawingTool.FILTER || style.area != ScreenshotAreaMode.RECTANGLE ||
                    list.size != 2 || list[0].x >= list[1].x || list[0].y >= list[1].y) {
                    throw FormatException("工程自动筛选区域无效")
                }
                category
            } else null
            objects.add(ScreenshotDrawingStroke(tool, list, row.optInt("color", 0), row.optDouble("width", 2.0).toFloat(),
                style, text = row.optString("text", ""), number = row.optLong("number", 1L),
                textWidth = row.optDouble("textWidth", 0.0).toFloat(), fontScale = fontScale,
                textHeight = row.optDouble("textHeight", 0.0).toFloat(), autoResize = tool == ScreenshotDrawingTool.TEXT && row.optBoolean("autoResize", false),
                id = id,
                smooth = row.optBoolean("smooth", false), closedPath = row.optBoolean("closedPath", false),
                boundTextId = row.optLong("boundTextId",0L), rotation=ScreenshotObjectTransform.normalize(rotation),
                fixedElbowSegments = fixedSegments,
                startBinding = readBinding(row, "startBinding"), endBinding = readBinding(row, "endBinding"),
                autoFilterCategory = autoFilterCategory))
        }
        val byId=objects.associateBy { it.id }
        if(objects.any { it.boundTextId<0 || (it.boundTextId!=0L &&
                (it.tool!=ScreenshotDrawingTool.SERIAL || byId[it.boundTextId]?.tool!=ScreenshotDrawingTool.TEXT)) })
            throw FormatException("工程序号文字关联无效")
        // A stale pin (target deleted or turned into an arrow) is dropped instead of failing the
        // whole project: a screenshot with one broken binding must still open.
        for (index in objects.indices) {
            val stroke = objects[index]
            if (stroke.startBinding == null && stroke.endBinding == null) continue
            fun bound(binding: ScreenshotFixedPointBinding?): ScreenshotFixedPointBinding? {
                val target = binding ?: return null
                if (stroke.tool != ScreenshotDrawingTool.ARROW) return null
                if (target.elementId == stroke.id) return null
                val element = byId[target.elementId] ?: return null
                if (element.tool == ScreenshotDrawingTool.ARROW) return null
                return target
            }
            val start = bound(stroke.startBinding)
            val end = bound(stroke.endBinding)
            if (start != stroke.startBinding || end != stroke.endBinding) {
                objects[index] = stroke.copy(startBinding = start, endBinding = end)
            }
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
        val regionOperands = root.optJSONArray("regionOperands")?.let { operands ->
            if (operands.length() > MAX_OBJECTS) throw FormatException("工程选区过多")
            ScreenshotRegion.fromJson(operands)?.toJson()?.toString()
                ?: throw FormatException("工程选区无效")
        }
        fun unit(key: String): Float {
            val value=root.optDouble(key,0.0).toFloat()
            if(!value.isFinite() || value<0 || value>10000) throw FormatException("工程绘制比例无效")
            return value
        }
        val watermark = root.optJSONObject("watermark")?.let { mark ->
            val text = mark.optString("text", "")
            if (text.length > 200) throw FormatException("水印文字过长")
            val template = mark.optString("template", "")
            if (template.length > 200) throw FormatException("水印模板过长")
            ScreenshotWatermark(text, mark.optDouble("fontSize", 16.0).toFloat(),
                mark.optString("fontFamily", "sans-serif"), mark.optInt("color", 0xFF000000.toInt()),
                mark.optDouble("angle", 30.0).toFloat(), mark.optDouble("gap", 56.0).toFloat(),
                mark.optDouble("opacity", .16).toFloat(), template, mark.optLong("appliedAt", 0L)).normalized()
        }
        return ScreenshotDocument(width, height, ScreenshotDocRect(left, top, right, bottom), objects, text,regions,regionOperands,unit("drawingUnit"),unit("fontUnit"),watermark)
    }

    private fun bindingRow(binding: ScreenshotFixedPointBinding) = JSONArray()
        .put(binding.elementId).put(binding.mode.name).put(binding.fixedX).put(binding.fixedY)

    /** A malformed binding is a corrupt project, not something to silently drop. */
    private fun readBinding(row: JSONObject, key: String): ScreenshotFixedPointBinding? {
        if (!row.has(key)) return null
        val values = row.optJSONArray(key) ?: throw FormatException("工程箭头绑定无效")
        if (values.length() != 4) throw FormatException("工程箭头绑定无效")
        val elementId = values.optLong(0, -1L)
        val fixedX = values.optDouble(2, Double.NaN)
        val fixedY = values.optDouble(3, Double.NaN)
        return ScreenshotBindingDocument.parseBinding(elementId, values.optString(1, ""), fixedX, fixedY)
            ?: throw FormatException("工程箭头绑定无效")
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

    private fun style(row: JSONObject?, tool: ScreenshotDrawingTool,fontScale: Float): ScreenshotDrawingStyle {
        if (row == null) return ScreenshotDrawingStyle()
        fun name(key: String, fallback: String) = row.optString(key, fallback)
        val rawSize=row.optDouble("fontSize",18.0).toFloat()
        // 新键 `filterStrength` 是上游 0..1 强度；旧工程写的是 4..64 的像素块边长（`strength`），
        // 读到旧键时按原语义线性换算一次，写回永远用新键。
        val strength=if(row.has("filterStrength"))
            row.optDouble("filterStrength",0.5).takeIf { it.isFinite() }?.coerceIn(0.0,1.0)?.toFloat() ?: 0.5f
        else row.optInt("strength",-1).takeIf { it>=0 }?.let { ((it-4)/60f).coerceIn(0f,1f) } ?: 0.5f
        // A size edit divides by the object's scale to leave its outline/background untouched.
        val effectiveSize=rawSize*fontScale
        val fontSize=if(rawSize.isFinite() && rawSize>0f && effectiveSize.isFinite() && effectiveSize>=ScreenshotFontSize.MIN) rawSize
            else ScreenshotFontSize.clamp(tool,rawSize)
        return ScreenshotDrawingStyle(
            color = row.optInt("color", 0xFFF5222D.toInt()),
            width = row.optInt("width", 2),
            opacity = row.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
            area = runCatching { ScreenshotAreaMode.valueOf(name("area", "RECTANGLE")) }.getOrDefault(ScreenshotAreaMode.RECTANGLE),
            filter = runCatching { ScreenshotFilter.valueOf(name("filter", "MOSAIC")) }.getOrDefault(ScreenshotFilter.MOSAIC),
            strength = strength,
            // Doubles since the original model stores a double; older documents wrote integers and
            // still decode, because a JSON number reads the same either way.
            fontSize = fontSize,
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
            cornerRadius = row.optInt("cornerRadius", 0).coerceIn(0, 83),
            cornerRadii = row.optJSONArray("cornerRadii")?.let { radii ->
                require(radii.length() == 4) { "cornerRadii 需要 4 个值" }
                List(4) { corner ->
                    val value = radii.optDouble(corner, Double.NaN)
                    require(value.isFinite() && value >= 0.0) { "cornerRadii[$corner] 非法" }
                    value.toFloat()
                }
            },
            strokePattern = runCatching { ScreenshotStrokePattern.valueOf(name("strokePattern", "SOLID")) }.getOrDefault(ScreenshotStrokePattern.SOLID),
            fillPattern = runCatching { ScreenshotFillPattern.valueOf(name("fillPattern", "SOLID")) }.getOrDefault(ScreenshotFillPattern.SOLID),
            shape = runCatching { ScreenshotRectangleShape.valueOf(name("shape", "RECTANGLE")) }.getOrDefault(ScreenshotRectangleShape.RECTANGLE),
            curved = row.optBoolean("curved", false),
            arrowType = runCatching { ScreenshotArrowType.valueOf(name("arrowType", "CURVE")) }
                .getOrDefault(ScreenshotArrowType.CURVE),
            // A missing key means a document written before the shaft options existed: the original
            // starts every arrow on Curve at ratio 1, so older projects keep that shape.
            arrowRatio = row.optDouble("arrowRatio", 1.0).toFloat().coerceIn(1f, 3f),
            arrowShaftType = runCatching { ScreenshotArrowShaftType.valueOf(name("arrowShaftType", "PLAIN")) }
                .getOrDefault(ScreenshotArrowShaftType.PLAIN),
            startHead = runCatching { ScreenshotArrowhead.valueOf(name("startHead", "NONE")) }.getOrDefault(ScreenshotArrowhead.NONE),
            endHead = runCatching { ScreenshotArrowhead.valueOf(name("endHead", "ARROW")) }.getOrDefault(ScreenshotArrowhead.ARROW),
            serialGeometryVersion = row.optInt("serialGeometryVersion",0).coerceIn(0,1)
        )
    }
}
