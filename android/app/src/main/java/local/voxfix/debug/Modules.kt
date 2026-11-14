package local.voxfix.debug

import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * 功能模块清单与开关状态。单一真相源：SharedPreferences("voxfix-modules")。
 *
 * 规则（见 docs/App外壳设计-2026-11-13.md §4，2026-11-14 收敛）：
 *  - 三大能力：输入法（键盘本体，常开无开关）、截图（screentext 全家）、语音（voice）。
 *  - 词典 / 表情 / 剪贴板 / 电脑同步不再设开关：功能本体常开，入口不再被隐藏
 *    （旧开关只控制菜单项显隐，功能本体从不读取，属于无效控制）。
 *  - 关闭 = 隐藏全部入口 + 不初始化运行时；数据保留，随时可开回。
 *  - 父项关闭时子项一律视为关闭（isOn 会一路检查祖先）。
 *  - 键盘本身不是模块，不设开关。
 */
enum class Module(
    val key: String,
    val defaultOn: Boolean,
    val label: String,
    val summary: String,
    val group: ModuleGroup
) {
    VOICE("voice", true, "语音输入", "本地离线识别，长按空格说话", ModuleGroup.INPUT),

    SCREENTEXT("screentext", true, "屏幕取字", "贴边弧线滑入，取字与截图总开关", ModuleGroup.SCREEN),
    SCROLL("scroll", true, "长截屏", "滚动拼接长页面", ModuleGroup.SCREEN),
    WATERMARK("watermark", true, "水印", "文字与图片水印", ModuleGroup.SCREEN),
    RECORD("record", true, "录屏", "选区录制，含音轨", ModuleGroup.SCREEN),
    TRANSLATE("translate", false, "离线翻译", "需下载 462MB 模型", ModuleGroup.SCREEN);

    /** 祖先模块。用 when 而非构造参数，避免枚举项互相引用的初始化顺序问题。 */
    val parent: Module?
        get() = when (this) {
            SCROLL, WATERMARK, RECORD, TRANSLATE -> SCREENTEXT
            else -> null
        }

    val depth: Int
        get() = if (parent == null) 0 else 1 + (parent?.depth ?: 0)

    val iconRes: Int
        get() = when (this) {
            VOICE -> R.drawable.ic_vx_mic
            SCREENTEXT -> R.drawable.ic_vx_scan
            SCROLL -> R.drawable.ic_vx_scroll
            WATERMARK -> R.drawable.ic_vx_water
            RECORD -> R.drawable.ic_vx_record
            TRANSLATE -> R.drawable.ic_vx_translate
        }

    companion object {
        /** 展示顺序：输入 → 屏幕，同级按声明顺序。 */
        fun ordered(): List<Module> = Module.values().toList()
    }
}

enum class ModuleGroup(val label: String) {
    INPUT("输入"),
    SCREEN("屏幕")
}

object Modules {
    private const val PREFS = "voxfix-modules"
    private const val PREFIX = "m."
    private const val SCHEMA = "schema"

    /**
     * 开关变化广播。无障碍服务常驻，靠它即时收起或恢复贴边弧线；
     * 页面回到前台时也会重新读取，广播不是唯一路径。
     */
    const val ACTION_CHANGED = "local.voxfix.debug.MODULE_CHANGED"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 只看自身开关，不含祖先。 */
    fun isSelfOn(context: Context, module: Module): Boolean =
        prefs(context).getBoolean(PREFIX + module.key, module.defaultOn)

    /** 自身与全部祖先都打开才算可用。 */
    fun isOn(context: Context, module: Module): Boolean {
        var cursor: Module? = module
        while (cursor != null) {
            if (!isSelfOn(context, cursor)) return false
            cursor = cursor.parent
        }
        return true
    }

    fun children(module: Module): List<Module> = values().filter { it.parent == module }

    fun set(context: Context, module: Module, on: Boolean) {
        val editor = prefs(context).edit()
        editor.putBoolean(PREFIX + module.key, on)
        if (!on) children(module).forEach { editor.putBoolean(PREFIX + it.key, false) }
        editor.apply()
        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
    }

    fun reset(context: Context) {
        val editor = prefs(context).edit().clear().putInt(SCHEMA, 1)
        values().forEach { editor.putBoolean(PREFIX + it.key, it.defaultOn) }
        editor.apply()
        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
    }

    fun countOn(context: Context): Int = values().count { isOn(context, it) }

    /** 键盘前端用的快照：键为模块 key，值为「自身开关」是否打开（不含祖先）。 */
    fun json(context: Context): String {
        val json = JSONObject()
        values().forEach { json.put(it.key, prefs(context).getBoolean(PREFIX + it.key, it.defaultOn)) }
        return json.toString()
    }

    private fun values(): List<Module> = Module.values().toList()
}
