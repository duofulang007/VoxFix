package local.voxfix.debug

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * SharedPreferences XML 的只读解析。
 *
 * 备份恢复不再靠「写回 shared_prefs + 冷启动」，而是把 XML 里的键值在原进程里用
 * [android.content.SharedPreferences.Editor] 覆盖一遍——这样监听器当场生效，也不必杀进程
 * （HyperOS 会把被杀的无障碍服务记成 Crashed，用户就得重新授权「屏幕取字」）。
 *
 * 只解析 SharedPreferences 自己会写出的 6 种节点；未知节点直接忽略，坏文件由调用方跳过。
 * 解析器禁用 DOCTYPE 与外部实体：备份是用户选进来的文件，不能当可信 XML 处理。
 */
object PrefsXml {
    fun parse(file: File): Map<String, Any> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
        }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val root = factory.newDocumentBuilder().parse(file).documentElement ?: return emptyMap()
        val result = linkedMapOf<String, Any>()
        val nodes = root.childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index) as? Element ?: continue
            val key = node.getAttribute("name").takeIf { it.isNotEmpty() } ?: continue
            when (node.tagName) {
                "string" -> result[key] = node.textContent.orEmpty()
                // SharedPreferences 把标量写在 value 属性里，不是文本节点；兼容写法两者都取。
                "int" -> scalar(node)?.toIntOrNull()?.let { result[key] = it }
                "long" -> scalar(node)?.toLongOrNull()?.let { result[key] = it }
                "float" -> scalar(node)?.toFloatOrNull()?.let { result[key] = it }
                "boolean" -> scalar(node)?.toBooleanStrictOrNull()?.let { result[key] = it }
                "set" -> {
                    val values = linkedSetOf<String>()
                    val children = node.childNodes
                    for (childIndex in 0 until children.length) {
                        val child = children.item(childIndex) as? Element ?: continue
                        if (child.tagName == "string") values.add(child.textContent.orEmpty())
                    }
                    result[key] = values
                }
            }
        }
        return result
    }

    private fun scalar(node: Element): String? =
        node.getAttribute("value").takeIf { it.isNotEmpty() } ?: node.textContent?.trim()
}
