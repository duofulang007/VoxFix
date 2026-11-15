package local.voxfix.debug

import android.content.Context
import android.content.SharedPreferences

/**
 * 「screen-text」偏好文件的唯一入口。
 *
 * 之前这个文件由 ScreenTextSettingsActivity 的 companion 暴露，导致无障碍服务、广播接收器、
 * 工具栏与字段配色都要 import 一个 Activity 才能读设置——层与层直接串在一起。偏好归属
 * 收到这里以后，Activity 只负责界面，非 UI 代码不再依赖页面类。
 *
 * 迁移期 [file] 仍开放给尚未拆出类型化访问器的模块（工具栏布局、翻译方向、长截图间隔等）；
 * 新增键请在本类补语义化方法，不要再把 key 散落到调用点。
 */
object ScreenTextPrefs {
    const val FILE = "screen-text"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_RIGHT = "right"
    private const val KEY_Y = "y"
    private const val KEY_TONE = "handle_tone"

    /** 弧线默认位置：右侧、屏幕高度 40% 处。 */
    const val DEFAULT_Y = 0.4f

    fun file(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 贴边弧线总开关。默认 true：无障碍授权后弧线直接出现。
     * 旧默认 false 会让重装/清数据后「必须再找一次开关」，是用户反复报「弧线不见了」的确定性原因。
     */
    fun arcEnabled(context: Context): Boolean =
        file(context).getBoolean(KEY_ENABLED, true)

    fun setArcEnabled(context: Context, on: Boolean) {
        file(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** @return 写入后的值，方便调用方决定是否需要跳设置页。 */
    fun toggleArc(context: Context): Boolean {
        val next = !arcEnabled(context)
        setArcEnabled(context, next)
        return next
    }

    fun handleRight(context: Context): Boolean = file(context).getBoolean(KEY_RIGHT, true)

    fun handleY(context: Context): Float = file(context).getFloat(KEY_Y, DEFAULT_Y)

    fun setHandlePosition(context: Context, right: Boolean, y: Float) {
        file(context).edit().putBoolean(KEY_RIGHT, right).putFloat(KEY_Y, y).apply()
    }

    /** 位置被写坏时的兜底；正常高度不覆盖。 */
    fun resetHandleGeometry(context: Context) {
        file(context).edit().putBoolean(KEY_RIGHT, true).putFloat(KEY_Y, DEFAULT_Y).apply()
    }

    fun clearTone(context: Context) {
        file(context).edit().remove(KEY_TONE).apply()
    }

    /** 弧度位置越界或 NaN 时返回默认值；供弧线展示与「恢复弧线」共用。 */
    fun sanitizedY(raw: Float): Float =
        if (raw.isNaN() || raw.isInfinite() || raw !in 0f..1f) DEFAULT_Y else raw
}
