package local.voxfix.debug

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class ScreenshotDrawTemplate(
    val document: ScreenshotDocument,
    val selectedIds: Set<Long>,
    val centerX: Float,
    val centerY: Float
)

/** Object-only templates. The payload embeds the existing Android screenshot project format. */
internal class ScreenshotDrawTemplateStore(context: Context) {
    private val prefs = context.getSharedPreferences("screenshot-draw-templates", Context.MODE_PRIVATE)
    private fun entries() = JSONObject(prefs.getString("templates", "{}") ?: "{}")

    fun names(): List<String> = entries().keys().asSequence().toList()

    fun save(name: String, template: ScreenshotDrawTemplate) {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 80) { "请输入 1–80 个字符的模板名" }
        val entries = entries()
        require(entries.keys().asSequence().none { it.equals(clean, ignoreCase = true) }) { "已有同名模板" }
        val payload = JSONObject().put("schemaVersion", 1)
            .put("document", ScreenshotDocumentCodec.encode(template.document))
            .put("selectedIds", JSONArray(template.selectedIds))
            .put("x", template.centerX.toDouble()).put("y", template.centerY.toDouble())
        require(payload.toString().toByteArray().size <= 16 * 1024 * 1024) { "模板超过 16 MiB" }
        prefs.edit().putString("templates", entries.put(clean, payload).toString()).apply()
    }

    fun load(name: String): ScreenshotDrawTemplate {
        val row = entries().getJSONObject(name)
        require(row.getInt("schemaVersion") == 1)
        val document = ScreenshotDocumentCodec.decode(row.getString("document"))
        val selected = row.getJSONArray("selectedIds").let { ids -> (0 until ids.length()).mapTo(linkedSetOf()) { ids.getLong(it) } }
        require(selected.isNotEmpty() && document.objects.map { it.id }.containsAll(selected))
        val x = row.getDouble("x").toFloat()
        val y = row.getDouble("y").toFloat()
        require(x.isFinite() && y.isFinite())
        return ScreenshotDrawTemplate(document, selected, x, y)
    }

    fun delete(name: String) {
        val entries = entries()
        if (entries.remove(name) != null) prefs.edit().putString("templates", entries.toString()).apply()
    }
}
