package local.voxfix.debug

import android.content.Context

/**
 * 把备份里解析好的偏好整份写回运行中的 SharedPreferences。
 *
 * 只由 [LocalBackup] 的恢复流程调用。**commit 必须在主线程**：`screen-text` 的监听器
 * （[ScreenTextService.onSharedPreferenceChanged]）会操作弧线 View，在其他线程回调会直接抛
 * "Only the original thread that created a view hierarchy can touch its views"。
 *
 * 用 clear + put + 一次 commit 复现「整份文件替换」的语义：备份里没有的键会被清掉，
 * 与旧版复制 XML 的行为一致。
 */
object PrefsStore {
    fun replaceAll(context: Context, entries: Map<String, Map<String, Any>>): Int {
        var count = 0
        for ((name, values) in entries) {
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for ((key, value) in values) when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
            if (editor.commit()) count++
        }
        return count
    }
}
