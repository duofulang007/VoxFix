package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-14 收敛后的模块表：6 开关两组（输入 / 屏幕），
 * 词典/表情/剪贴板/电脑同步/独立截图开关已删、功能本体常开。
 */
class ModuleStructureTest {

    @Test
    fun `exactly six switches in two groups`() {
        val modules = Module.values().toList()
        assertEquals(listOf("voice"), modules.filter { it.group == ModuleGroup.INPUT }.map { it.key })
        assertEquals(
            listOf("screentext", "scroll", "watermark", "record", "translate"),
            modules.filter { it.group == ModuleGroup.SCREEN }.map { it.key }
        )
        assertEquals(6, modules.size)
    }

    @Test
    fun `screenshot children all hang under screentext`() {
        for (key in listOf("scroll", "watermark", "record", "translate")) {
            val module = Module.values().first { it.key == key }
            assertEquals(Module.SCREENTEXT, module.parent)
            assertEquals(1, module.depth)
        }
        assertNull(Module.SCREENTEXT.parent)
        assertNull(Module.VOICE.parent)
    }

    @Test
    fun `defaults match shipped behaviour`() {
        val expected = mapOf(
            "voice" to true,
            "screentext" to true,
            "scroll" to true,
            "watermark" to true,
            "record" to true,
            "translate" to false
        )
        for (module in Module.values()) {
            assertEquals(expected[module.key], module.defaultOn)
        }
    }

    @Test
    fun `keys stay stable for the keyboard snapshot`() {
        // Modules.json() 的键即前端过滤映射的键；改名会静默破坏 preview 的 moduleParents。
        assertEquals(
            setOf("voice", "screentext", "scroll", "watermark", "record", "translate"),
            Module.values().map { it.key }.toSet()
        )
        // 旧键已删，防止写回。
        val keys = Module.values().map { it.key }.toSet()
        assertTrue(keys.none { it in setOf("dictionary", "emoji", "clipboard", "pcsync", "screenshot") })
    }
}
