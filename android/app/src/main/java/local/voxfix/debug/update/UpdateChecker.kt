package local.voxfix.debug.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import local.voxfix.debug.AdbWireless
import local.voxfix.debug.DevMode
import local.voxfix.debug.DownloadEngine
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内检查更新：清单多源回退 → 只比 versionCode → 首页横幅 → 用户点击后才下载
 * （Range 续传 + SHA-256 强制校验）→ 只读 URI 调系统安装器。
 *
 * 状态机：HIDDEN → AVAILABLE → DOWNLOADING → READY；FAILED 可重试。
 * 自动检查失败静默（不打扰），手动「检查更新」必须给反馈。
 *
 * 清单源：主源 GitHub Releases（owner/repo 确定后填 RELEASE_MANIFEST，见发版 checklist）；
 * 镜像位发布前填入并实测——「支持镜像字段」不等于「国内可更新」。联调走
 * `adb reverse tcp:18080 tcp:18080` + `http://127.0.0.1:18080/manifest.json`，
 * 仅 debug 构建（FLAG_DEBUGGABLE）默认启用 loopback HTTP，正式源仍强制 HTTPS。
 */
object UpdateChecker {
    enum class Phase { HIDDEN, AVAILABLE, DOWNLOADING, READY, FAILED }

    data class Status(
        val phase: Phase,
        val manifest: UpdateCore.Manifest? = null,
        val downloaded: Long = 0L,
        val total: Long = 0L,
        val message: String = ""
    )

    private const val PREFS = "voxfix-update"
    private const val KEY_DISMISSED = "dismissedVersionCode"
    private const val KEY_LAST_CHECK = "lastCheckAt"
    private const val KEY_MANIFEST_URLS = "manifestUrls"

    /** 正式源：GitHub Releases 的 latest asset（owner/repo 取自 remote，2026-11-14 已定）。
     *  Release 未发布前此地址 404——手动检查提示失败属预期，发布首个 Release 后即生效。 */
    private const val RELEASE_MANIFEST =
        "https://github.com/duofulang007/VoxFix/releases/latest/download/manifest.json"
    /** debug 联调源：PC 静态服务 + adb reverse；经 debug 网络配置放行 loopback HTTP。 */
    private const val LOOPBACK_MANIFEST = "http://127.0.0.1:18080/manifest.json"
    private const val CHECK_THROTTLE_MS = 24L * 60 * 60 * 1000

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "voxfix-update") }
    private val cancelled = AtomicBoolean(false)
    /** 一次只跑一个检查任务：连点直接忽略，避免把单线程队列堆满、十几秒后才回结果。 */
    private val checking = AtomicBoolean(false)
    @Volatile private var status = Status(Phase.HIDDEN)
    private var listener: ((Status) -> Unit)? = null

    private const val TAG = "VoxFixUpdate"

    fun observe(l: ((Status) -> Unit)?) {
        listener = l
        l?.let { main.post { it(status) } }
    }

    fun current(): Status = status

    // ───── 持久化与派生 ─────

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastCheckAt(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK, 0L)

    /** 自动检查节流：距上次成功检查 ≥24h 才在 onResume 里再查（About 页手动检查不受限）。 */
    fun shouldAutoCheck(context: Context): Boolean =
        System.currentTimeMillis() - lastCheckAt(context) > CHECK_THROTTLE_MS

    fun dismiss(context: Context) {
        val code = status.manifest?.versionCode ?: 0
        prefs(context).edit().putInt(KEY_DISMISSED, code).apply()
        publish(Status(Phase.HIDDEN))
    }

    private fun apkFile(context: Context, m: UpdateCore.Manifest): File =
        File(File(context.cacheDir, "updates"), "voxfix-${m.versionName}-${m.versionCode}.apk")

    /** 冷启动/回前台推导：下载完 → READY；有新版且未忽略 → AVAILABLE；否则 HIDDEN。下载中不重置。 */
    fun refresh(context: Context): Status {
        if (status.phase == Phase.DOWNLOADING) return status
        val m = status.manifest
        val next = when {
            m != null && apkFile(context, m).isFile -> Status(Phase.READY, m)
            m != null && UpdateCore.isNewer(m.versionCode, currentVersionCode(context))
                && prefs(context).getInt(KEY_DISMISSED, 0) != m.versionCode -> Status(Phase.AVAILABLE, m)
            else -> Status(Phase.HIDDEN)
        }
        publish(next)
        return next
    }

    private fun currentVersionCode(context: Context): Int = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)

    // ───── 检查 ─────

    /**
     * manual=true 时失败也回调（UI 给反馈）；false 时失败静默回落 HIDDEN。回调总在主线程。
     *
     * 多源全失败时给用户的是人话（「更新源上还没有发布版本」/「连不上更新服务器」），
     * 每个源的原始异常只进 logcat —— 把 socket 原文糊到 toast 上是之前最受诟病的一处。
     */
    fun check(context: Context, manual: Boolean, done: ((Status) -> Unit)? = null) {
        val app = context.applicationContext
        if (!checking.compareAndSet(false, true)) return
        cancelled.set(false)
        val urls = manifestUrls(app)
        worker.execute {
            val failures = ArrayList<String>()
            var manifest: UpdateCore.Manifest? = null
            try {
                if (urls.isEmpty()) error("未配置更新源")
                for (url in urls) {
                    try {
                        // debug 构建放行局域网 http 联调源；正式源仍强制 https。
                        manifest = UpdateCore.parse(fetchText(url), isDebugBuild(app))
                            ?: throw IllegalStateException("清单格式无效")
                        break
                    } catch (error: Exception) {
                        failures += "${sourceLabel(url)}：${error.message ?: error.javaClass.simpleName}"
                        if (cancelled.get()) throw error
                    }
                }
            } catch (error: Exception) {
                failures += error.message ?: "检查中断"
            } finally {
                checking.set(false)
            }

            val found = manifest
            val next = when {
                found == null -> {
                    Log.w(TAG, "检查更新失败：${failures.joinToString("；")}")
                    if (manual) Status(Phase.FAILED, message = summarize(failures)) else Status(Phase.HIDDEN)
                }
                !UpdateCore.isNewer(found.versionCode, currentVersionCode(app)) -> {
                    prefs(app).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                    Status(Phase.HIDDEN, found)
                }
                else -> {
                    prefs(app).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                    Status(Phase.AVAILABLE, found)
                }
            }
            main.post { publish(next); done?.invoke(next) }
        }
    }

    /** 把多源失败折成一句人话；细节留给 logcat 的 VoxFixUpdate。 */
    private fun summarize(failures: List<String>): String = when {
        failures.isEmpty() -> "检查更新失败"
        failures.any { it.contains("404") } -> "更新源上还没有发布版本"
        else -> "连不上更新服务器，请检查网络"
    }

    /** 源的人话标签：不把 IP、端口和 socket 原文暴露到用户可读的提示里。 */
    private fun sourceLabel(url: String): String = when {
        url.contains("github.com", ignoreCase = true) -> "GitHub"
        url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost") -> "本机联调"
        else -> "局域网联调"
    }

    private fun manifestUrls(context: Context): List<String> {
        val configured = prefs(context).getString(KEY_MANIFEST_URLS, null)
            ?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (configured != null) return configured
        return buildList {
            if (RELEASE_MANIFEST.isNotEmpty()) add(RELEASE_MANIFEST)
            // 回环与局域网联调源只在开发者模式开启时参与：普通使用时它们必然连不上，
            // 白等 6 秒连接超时，还会把 socket 原文带进检查结果（真机实测过）。
            if (isDebugBuild(context) && DevMode.enabled(context)) {
                add(LOOPBACK_MANIFEST)
                AdbWireless.pcAddresses(context).forEach { add("http://$it:18080/manifest.json") }
            }
        }
    }

    private fun isDebugBuild(context: Context): Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun fetchText(url: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "VoxFix-update")
            }
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            return conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn?.disconnect()
        }
    }

    // ───── 下载 ─────

    /** AVAILABLE → DOWNLOADING → READY/FAILED；仅用户点击触发，不自动下载。 */
    fun startDownload(context: Context) {
        val app = context.applicationContext
        val m = status.manifest ?: return
        if (status.phase == Phase.DOWNLOADING || status.phase == Phase.READY) return
        val target = apkFile(app, m)
        if (target.isFile) {
            publish(Status(Phase.READY, m))
            return
        }
        val dir = target.parentFile!!
        if (!dir.isDirectory) dir.mkdirs()
        val need = if (m.apkSizeBytes > 0) m.apkSizeBytes + 128L * 1024 * 1024 else 0L
        if (need > 0 && dir.usableSpace < need) {
            publish(Status(Phase.FAILED, m, message = "可用空间不足，需要约 ${m.apkSizeBytes / (1024 * 1024)}MB"))
            return
        }
        cancelled.set(false)
        publish(Status(Phase.DOWNLOADING, m, downloaded = 0, total = m.apkSizeBytes))
        val partial = File(dir, "${target.name}.part")
        worker.execute {
            try {
                DownloadEngine.download(
                    sources = m.apkUrls,
                    partial = partial,
                    target = target,
                    expectedBytes = m.apkSizeBytes,
                    expectedSha256 = m.apkSha256,
                    allowInsecureHttp = isDebugBuild(app),
                    cancelled = { cancelled.get() },
                    onProgress = { progress ->
                        when (progress) {
                            is DownloadEngine.Progress.Downloading ->
                                publish(Status(Phase.DOWNLOADING, m, progress.downloaded, progress.total))
                            DownloadEngine.Progress.Verifying ->
                                publish(Status(Phase.DOWNLOADING, m, m.apkSizeBytes, m.apkSizeBytes))
                        }
                    }
                )
                publish(Status(Phase.READY, m))
            } catch (error: Exception) {
                publish(
                    if (cancelled.get()) Status(Phase.AVAILABLE, m)
                    else Status(Phase.FAILED, m, message = error.message ?: "下载失败，请重试")
                )
            }
        }
    }

    fun cancelDownload() {
        cancelled.set(true)
        DownloadEngine.disconnectCurrent()
    }

    // ───── 安装 ─────

    /** 已授权安装 → 拉系统安装器；否则跳本 App 的「安装未知应用」开关页，返回后横幅仍在 READY。 */
    fun install(context: Context) {
        val app = context.applicationContext
        val m = status.manifest ?: return
        val file = apkFile(app, m)
        if (!file.isFile) return
        if (app.packageManager.canRequestPackageInstalls()) {
            val uri = Uri.parse("content://${app.packageName}.updateapk/updates/${file.name}")
            app.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun publish(next: Status) {
        status = next
        listener?.let { main.post { it(next) } }
    }
}
