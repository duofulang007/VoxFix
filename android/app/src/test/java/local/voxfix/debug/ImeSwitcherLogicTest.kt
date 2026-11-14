package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ImeSwitcher 纯逻辑回归：归一化、列表合并（绝不破坏讯飞等已有条目）、移除。
 * Android 壳（switchToSelf 等）走真机走查，不在此测。
 */
class ImeSwitcherLogicTest {

    @Test
    fun `normalizeId handles short and full class forms`() {
        assertEquals("pkg/.Cls", ImeSwitcher.normalizeId("pkg/.Cls"))
        assertEquals("pkg/.Cls", ImeSwitcher.normalizeId("pkg/pkg.Cls"))
        assertEquals("com.iflytek.inputmethod.miui/.FlyIME", ImeSwitcher.normalizeId("com.iflytek.inputmethod.miui/com.iflytek.inputmethod.miui.FlyIME"))
        assertEquals("nodot", ImeSwitcher.normalizeId("nodot"))
        assertEquals("pkg/other.Cls", ImeSwitcher.normalizeId("pkg/other.Cls"))
    }

    @Test
    fun `idMatches ignores short class spelling`() {
        assertTrue(ImeSwitcher.idMatches("local.voxfix.debug/.VoxFixImeService", "local.voxfix.debug/.VoxFixImeService"))
        assertTrue(ImeSwitcher.idMatches("local.voxfix.debug/local.voxfix.debug.VoxFixImeService", "local.voxfix.debug/.VoxFixImeService"))
        assertFalse(ImeSwitcher.idMatches("com.iflytek.inputmethod.miui/.FlyIME", "local.voxfix.debug/.VoxFixImeService"))
    }

    @Test
    fun `merge keeps existing entries untouched and appends self`() {
        val current = "com.iflytek.inputmethod.miui/.FlyIME:com.sohu.inputmethod.sogou.xiaomi/.SogouIME"
        val merged = ImeSwitcher.mergeEnabledImes(current, "local.voxfix.debug/.VoxFixImeService")
        assertEquals(
            "com.iflytek.inputmethod.miui/.FlyIME:com.sohu.inputmethod.sogou.xiaomi/.SogouIME:local.voxfix.debug/.VoxFixImeService",
            merged
        )
    }

    @Test
    fun `merge dedupes self and tolerates blank entries`() {
        // 自身已在列表（即便写成全类名）时原样返回——绝不重写已有条目的写法。
        val current = "com.iflytek.inputmethod.miui/.FlyIME::local.voxfix.debug/local.voxfix.debug.VoxFixImeService"
        assertEquals(
            "com.iflytek.inputmethod.miui/.FlyIME:local.voxfix.debug/local.voxfix.debug.VoxFixImeService",
            ImeSwitcher.mergeEnabledImes(current, "local.voxfix.debug/.VoxFixImeService")
        )
    }

    @Test
    fun `merge builds a fresh list from empty`() {
        assertEquals(
            "local.voxfix.debug/.VoxFixImeService",
            ImeSwitcher.mergeEnabledImes(null, "local.voxfix.debug/.VoxFixImeService")
        )
        assertEquals(
            "local.voxfix.debug/.VoxFixImeService",
            ImeSwitcher.mergeEnabledImes("", "local.voxfix.debug/.VoxFixImeService")
        )
    }

    @Test
    fun `remove keeps other entries in order`() {
        val current = "com.iflytek.inputmethod.miui/.FlyIME:local.voxfix.debug/.VoxFixImeService:com.sohu.inputmethod.sogou.xiaomi/.SogouIME"
        assertEquals(
            "com.iflytek.inputmethod.miui/.FlyIME:com.sohu.inputmethod.sogou.xiaomi/.SogouIME",
            ImeSwitcher.removeEnabledIme(current, "local.voxfix.debug/.VoxFixImeService")
        )
        assertEquals(current, ImeSwitcher.removeEnabledIme(current, "com.other/.Missing"))
    }
}
