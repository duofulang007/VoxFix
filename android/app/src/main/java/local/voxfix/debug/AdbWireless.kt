package local.voxfix.debug

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 无线调试（Android 11+）的系统开关。
 *
 * 系统没有公开 API，唯一入口是 Settings.Global.adb_wifi_enabled；写入需要 WRITE_SECURE_SETTINGS
 * （signature|privileged|development，只能用 `adb shell pm grant` 一次性授予，卸载即失效）。
 * 真机实测（K60 / Android 15 / HyperOS V816）：Wi-Fi 未连接时 putInt 返回成功，但 AdbDebuggingManager
 * 立刻把值改回 0 并打 "Not connected to any wireless network" —— 所以每次写入都必须回读确认，不能乐观置位。
 *
 * 端口读不到（service.adb.tcp.port 与 adb_wifi_port 都为空），连接侧由 PC 用 `adb mdns services` 发现。
 */
internal object AdbWireless {
    const val KEY = "adb_wifi_enabled"
    private const val WAIT_STEP_MS = 200L
    private const val WAIT_TRIES = 6
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "adb-wireless") }

    /** 开启前的体检结果；DIFFERENT_NETWORK / PC_OFFLINE 用来把"连不上"拆成可行动的原因。 */
    enum class Outcome { OK, NO_WIFI, NO_PC_INFO, DIFFERENT_NETWORK, PC_OFFLINE, DENIED, REVERTED }

    /** 电脑端伴生工具的探测端口（tools/voxfix_phone_companion.py 里的 PingServer）。 */
    const val PROBE_PORT = 18768
    private const val PROBE_TIMEOUT_MS = 700
    private const val KEY_PC_ADDRS = "pc.addrs"


    fun enabled(resolver: ContentResolver): Boolean =
        runCatching { Settings.Global.getInt(resolver, KEY, 0) == 1 }.getOrDefault(false)

    fun granted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun wifiConnected(context: Context): Boolean = localSubnet(context) != null

    /** 当前 Wi-Fi 的 IPv4 网段；没连 Wi-Fi 或拿不到地址时返回 null。 */
    fun localSubnet(context: Context): Subnet? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching null
        val network = manager.activeNetwork ?: return@runCatching null
        val capabilities = manager.getNetworkCapabilities(network) ?: return@runCatching null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@runCatching null
        val link = manager.getLinkProperties(network) ?: return@runCatching null
        val address = link.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return@runCatching null
        Subnet.parse(address.address.hostAddress ?: return@runCatching null, address.prefixLength)
    }.getOrNull()

    data class Subnet(val value: Long, val prefix: Int) {
        fun contains(ip: String): Boolean {
            val target = parseAddress(ip) ?: return false
            if (prefix <= 0) return true
            val mask = if (prefix >= 32) 0xFFFFFFFFL else ((1L shl prefix) - 1) shl (32 - prefix)
            return (target and mask) == (value and mask)
        }
        companion object {
            fun parse(ip: String, prefix: Int): Subnet? { val v = parseAddress(ip) ?: return null; return Subnet(v, prefix) }
        }
    }

    private fun parseAddress(ip: String): Long? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    /** 电脑端每次连着就会推一次自己的 IPv4 列表（逗号分隔），可能同时有以太网和 Wi-Fi。 */
    fun rememberPc(context: Context, addresses: String) {
        ScreenTextSettingsActivity.preferences(context).edit().putString(KEY_PC_ADDRS, addresses.trim()).apply()
    }

    fun pcAddresses(context: Context): List<String> =
        ScreenTextSettingsActivity.preferences(context).getString(KEY_PC_ADDRS, "")
            .orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** 电脑端伴生工具是否在这条链路上；只回一行 VOXFIX。阻塞调用，必须放后台线程。 */
    private fun probe(address: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, PROBE_PORT), PROBE_TIMEOUT_MS)
            socket.soTimeout = PROBE_TIMEOUT_MS
            socket.getInputStream().bufferedReader().readLine()?.trim() == "VOXFIX"
        }
    }.getOrDefault(false)

    /** 体检：先看手机自己在不在 Wi-Fi，再看和电脑是不是同一网段，最后才探测电脑端进程。 */
    fun precheck(context: Context): Outcome {
        val subnet = localSubnet(context) ?: return Outcome.NO_WIFI
        val addresses = pcAddresses(context)
        if (addresses.isEmpty()) return Outcome.NO_PC_INFO
        val sameSubnet = addresses.filter { subnet.contains(it) }
        if (sameSubnet.isEmpty()) return Outcome.DIFFERENT_NETWORK
        if (sameSubnet.none { probe(it) }) return Outcome.PC_OFFLINE
        return Outcome.OK
    }

    /** 后台写 + 回读确认，结果回主线程。 */
    fun set(context: Context, on: Boolean, done: (Outcome) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        worker.execute {
            val outcome = runCatching {
                if (!granted(app)) return@runCatching Outcome.DENIED
                if (on) {
                    val ready = precheck(app)
                    if (ready != Outcome.OK) return@runCatching ready
                }
                val resolver = app.contentResolver
                if (!Settings.Global.putInt(resolver, KEY, if (on) 1 else 0)) return@runCatching Outcome.DENIED
                for (attempt in 0 until WAIT_TRIES) {
                    Thread.sleep(WAIT_STEP_MS)
                    if (enabled(resolver) == on) return@runCatching Outcome.OK
                }
                if (on && localSubnet(app) == null) Outcome.NO_WIFI else Outcome.REVERTED
            }.getOrElse { if (it is SecurityException) Outcome.DENIED else Outcome.REVERTED }
            main.post { done(outcome) }
        }
    }

    /** 系统会自己关掉无线调试（断 Wi-Fi、重启、开发者选项关闭），调用方据此保持图标不说谎。 */
    fun observe(context: Context, onChanged: () -> Unit): ContentObserver {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChanged()
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(KEY), false, observer)
        return observer
    }

    fun stop(context: Context, observer: ContentObserver) {
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
    }
}
