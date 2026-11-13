package local.voxfix.debug

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 截图工具（无障碍服务 + 圆弧把手）的健康状态与一键恢复。
 *
 * 装机、跑 instrumentation、系统回收都会杀掉服务进程；HyperOS 把被杀的
 * accessibility service 记成 `Crashed` 后**不会自动重绑**，用户看到的就是
 * 「圆弧入口不见了」。这里提供三件事：
 * 1. 区分「从没启用」「被系统停掉（Crashed）」「服务正常但圆弧开关关着」；
 * 2. 用输入法已持有的 WRITE_SECURE_SETTINGS 做一次「摘出再放回」的重绑；
 * 3. 把结论以 JSON 交给键盘前端，让用户点一下就能恢复。
 */
internal object ScreenTextHealth {
    enum class Outcome { OK, DENIED, DISABLED, REVERTED, FAILED }

    private const val CACHE_MS = 2_000L
    private const val DETACH_WAIT_MS = 600L
    private const val POLL_STEP_MS = 250L
    private const val POLL_TRIES = 12          // ≈3s，系统重绑通常 1s 内完成

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screen-text-health").apply { isDaemon = true }
    }
    @Volatile private var cachedAt = 0L
    @Volatile private var cached = ""

    private fun component(context: Context) = ComponentName(context, ScreenTextService::class.java)

    /** Secure 列表里有没有本服务（用户是否启用过）。 */
    fun listed(context: Context): Boolean {
        val wanted = component(context)
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')
            ?.any { ComponentName.unflattenFromString(it.trim()) == wanted } == true
    }

    /**
     * 系统是否真的把服务绑上了。Crashed 的服务不会出现在这个列表里，
     * 因此它能区分「列表里有」和「真的在跑」。
     */
    fun bound(context: Context): Boolean {
        val wanted = component(context)
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
            val service = info.resolveInfo?.serviceInfo ?: return@any false
            val name = service.name.let { if (it.startsWith(".")) service.packageName + it else it }
            service.packageName == wanted.packageName && name == wanted.className
        }
    }

    /** 无障碍与输入法同进程，桥接对象在就说明服务实例还活着。 */
    fun live(): Boolean = ScreenshotEditorHost.available()

    fun arcEnabled(context: Context): Boolean = ScreenTextSettingsActivity.enabled(context)

    /** OK / CRASHED / DISABLED / ARC_OFF，附带三个原始判据，前端据此给一句话。 */
    fun status(context: Context): String {
        val now = System.currentTimeMillis()
        if (now - cachedAt < CACHE_MS) return cached
        val listed = listed(context)
        val bound = bound(context)
        val live = live()
        val arc = arcEnabled(context)
        val code = when {
            !listed -> "DISABLED"
            !bound && !live -> "CRASHED"
            !arc -> "ARC_OFF"
            else -> "OK"
        }
        val json = JSONObject().put("code", code).put("listed", listed).put("bound", bound)
            .put("live", live).put("arc", arc).toString()
        cached = json
        cachedAt = now
        return json
    }

    fun invalidate() { cachedAt = 0L; cached = "" }

    /** 打开系统无障碍设置页；用户手动启用是唯一不依赖 WRITE_SECURE_SETTINGS 的路。 */
    fun openSystemSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { android.util.Log.w("VoxFix", "SCREEN_TEXT_SETTINGS_FAILED", it) }
    }

    /**
     * 「摘出再放回」重绑。只解决 Crashed；从没启用过的（listed=false）直接返回 DISABLED，
     * 由调用方引导去系统设置，避免把用户手动关掉的开关又打开。
     */
    fun repair(context: Context, done: (Outcome) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        worker.execute {
            val outcome = runCatching {
                if (!AdbWireless.granted(app)) return@runCatching Outcome.DENIED
                val wanted = component(app)
                val resolver = app.contentResolver
                val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    .orEmpty()
                val parts = current.split(':').map { it.trim() }.filter { it.isNotEmpty() }
                val hasSelf = parts.any { ComponentName.unflattenFromString(it) == wanted }
                if (!hasSelf) return@runCatching Outcome.DISABLED
                val without = parts.filterNot { ComponentName.unflattenFromString(it) == wanted }
                // 先摘出：系统会解绑并清掉 Crashed 记录，再放回才会重新 bind。
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    without.joinToString(":"))
                Thread.sleep(DETACH_WAIT_MS)
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    parts.joinToString(":"))
                // 全局无障碍总开关被关掉时，只写服务列表不会 bind。
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                var ok = false
                for (attempt in 0 until POLL_TRIES) {
                    Thread.sleep(POLL_STEP_MS)
                    if (bound(app) || live()) { ok = true; break }
                }
                if (ok) Outcome.OK else Outcome.REVERTED
            }.getOrElse {
                if (it is SecurityException) Outcome.DENIED else Outcome.FAILED
            }
            invalidate()
            main.post { done(outcome) }
        }
    }
}
