package local.voxfix.debug

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import java.util.concurrent.Executors

/**
 * 输入法启用 / 切换的唯一入口。
 *
 * 两条路径：
 *  - 系统路径（任何设备可用）：引导用户去「语言与输入法」勾选启用，再弹系统选择器选默认。
 *    HyperOS 实测（docs/输入法切换与安装权限-2026-11-14.md §1）：AOSP 启用页
 *    InputMethodAndSubtypeEnablerActivity 是空白壳，深链用 AvailableVirtualKeyboardActivity；
 *    MIUI「选择输入法」弹层正常列出 VoxFix。
 *  - 直写路径（需 WRITE_SECURE_SETTINGS，电脑上 pm grant 一次）：直接写
 *    Settings.Secure.ENABLED_INPUT_METHODS / DEFAULT_INPUT_METHOD 绕过系统弹层。
 *    写入后必须回读确认——与 AdbWireless 同一教训（HyperOS 可能立刻回滚）。
 */
object ImeSwitcher {

    /** 直写路径的执行结果；DENIED = 没有 WRITE_SECURE_SETTINGS。 */
    enum class Outcome { OK, DENIED, WRITE_FAILED, REVERTED }

    // ───────── 纯函数（JVM 可测）─────────

    /** 归一化 IME id：`pkg/pkg.Cls` → `pkg/.Cls`；`pkg/.Cls` 原样返回。 */
    fun normalizeId(id: String): String {
        val slash = id.indexOf('/')
        if (slash <= 0) return id
        val pkg = id.substring(0, slash)
        val cls = id.substring(slash + 1)
        if (cls.startsWith(".")) return id
        return if (cls.startsWith("$pkg.")) "$pkg/.${cls.substring(pkg.length + 1)}" else id
    }

    /** 设置串里的条目与目标 id 是否同一输入法（忽略短类名写法差异）。 */
    fun idMatches(settingValue: String, targetId: String): Boolean =
        normalizeId(settingValue) == normalizeId(targetId)

    /**
     * 把自身 IME 并进 enabled 列表：保序、去重、绝不改动已有条目（讯飞/搜狗等）。
     * current 为空时返回仅含自身。
     */
    fun mergeEnabledImes(current: String?, selfId: String): String {
        val entries = current.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.any { idMatches(it, selfId) }) return entries.joinToString(":")
        return (entries + selfId).joinToString(":")
    }

    /** 从 enabled 列表移除自身（其余条目原序保留）；自身不在时原样返回。 */
    fun removeEnabledIme(current: String?, selfId: String): String =
        current.orEmpty().split(':').map { it.trim() }
            .filter { it.isNotEmpty() && !idMatches(it, selfId) }
            .joinToString(":")

    // ───────── Android 壳 ─────────

    private const val WAIT_STEP_MS = 200L
    private const val WAIT_TRIES = 6
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ime-switcher") }
    // lazy：JVM 单测只碰纯函数，不能在 object 初始化时触碰 android.jar 的 Handler stub。
    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    fun selfId(context: Context): String = "${context.packageName}/.VoxFixImeService"

    fun enabled(context: Context): Boolean =
        runCatching {
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
        }.getOrDefault(false)

    fun granted(context: Context): Boolean = AdbWireless.granted(context)

    fun isDefault(context: Context): Boolean = runCatching {
        val current = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
        ).orEmpty()
        current.isNotEmpty() && idMatches(current, selfId(context))
    }.getOrDefault(false)

    /** 拉起系统「选择输入法」弹层（MIUI 定制对话框，实测正常）。 */
    fun pick(context: Context) {
        runCatching {
            context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
    }

    private fun waitFor(check: () -> Boolean): Boolean {
        repeat(WAIT_TRIES) {
            Thread.sleep(WAIT_STEP_MS)
            if (check()) return true
        }
        return check()
    }

    /** 同步版直写；阻塞调用，必须放后台线程。 */
    fun switchToSelfBlocking(context: Context): Outcome {
        val app = context.applicationContext
        if (!granted(app)) return Outcome.DENIED
        val resolver = app.contentResolver
        val self = selfId(app)
        return try {
            if (!enabled(app)) {
                val current = Settings.Secure.getString(
                    resolver, Settings.Secure.ENABLED_INPUT_METHODS
                ).orEmpty()
                val merged = mergeEnabledImes(current, self)
                if (!Settings.Secure.putString(resolver, Settings.Secure.ENABLED_INPUT_METHODS, merged)) {
                    return Outcome.WRITE_FAILED
                }
                if (!waitFor { enabled(app) }) return Outcome.REVERTED
            }
            if (isDefault(app)) return Outcome.OK
            if (!Settings.Secure.putString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD, self)) {
                return Outcome.WRITE_FAILED
            }
            if (!waitFor { isDefault(app) }) Outcome.REVERTED else Outcome.OK
        } catch (error: SecurityException) {
            Outcome.DENIED
        }
    }

    /** 异步直写，结果回主线程；UI 层拿 Outcome 决定提示与刷新。 */
    fun switchToSelf(context: Context, done: (Outcome) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val outcome = runCatching { switchToSelfBlocking(app) }
                .getOrElse { if (it is SecurityException) Outcome.DENIED else Outcome.REVERTED }
            main.post { done(outcome) }
        }
    }
}
