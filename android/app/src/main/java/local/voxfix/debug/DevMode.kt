package local.voxfix.debug

import android.content.Context

/**
 * 开发者模式开关。默认关闭，普通用户路径上不出现「试用与自检 / 开发者工具」。
 *
 * 同一个安装包（applicationId 恒为 local.voxfix.debug）无法用 BuildConfig.DEBUG 区分
 * 「我开发时」与「给别人用时」，所以用显式开关：关于页「当前版本」连点 7 次开启，
 * 开启后关于页常驻一行「关闭开发者模式」。
 */
object DevMode {
    private const val PREFS = "voxfix-app"
    private const val KEY_ENABLED = "dev.enabled"
    private const val KEY_TAPS = "dev.taps"
    private const val KEY_TAP_AT = "dev.tapAt"

    const val REQUIRED_TAPS = 7
    const val WINDOW_MS = 1500L

    data class Decision(val taps: Int, val enabled: Boolean, val remaining: Int)

    /** 纯函数：连点计数与超时清零，便于 JVM 单测。 */
    fun decision(taps: Int, lastAtMs: Long, nowMs: Long, alreadyEnabled: Boolean): Decision {
        if (alreadyEnabled) return Decision(0, true, 0)
        val continued = lastAtMs > 0 && nowMs - lastAtMs in 0..WINDOW_MS
        val next = if (continued) taps + 1 else 1
        if (next >= REQUIRED_TAPS) return Decision(0, true, 0)
        return Decision(next, false, REQUIRED_TAPS - next)
    }

    fun enabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    /**
     * 记录一次点击。
     * @return null 表示已开启且无需再计数；否则返回还差几次（0 表示本次刚好开启）。
     */
    fun tap(context: Context): Int? {
        if (enabled(context)) return null
        val p = prefs(context)
        val result = decision(
            p.getInt(KEY_TAPS, 0),
            p.getLong(KEY_TAP_AT, 0),
            System.currentTimeMillis(),
            alreadyEnabled = false
        )
        p.edit().putInt(KEY_TAPS, result.taps)
            .putLong(KEY_TAP_AT, if (result.enabled) 0 else System.currentTimeMillis())
            .putBoolean(KEY_ENABLED, result.enabled)
            .apply()
        return result.remaining
    }

    fun disable(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false)
            .putInt(KEY_TAPS, 0).putLong(KEY_TAP_AT, 0).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
