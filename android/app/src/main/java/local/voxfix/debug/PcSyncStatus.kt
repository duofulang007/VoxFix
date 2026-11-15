package local.voxfix.debug

/**
 * 电脑端心跳的判定结果（纯数据，JVM 单测可直接覆盖）。
 *
 * 电脑端伴生工具每 5 秒发一次 `PC_STATE`；15 秒没心跳就当断开——拔线、换网、电脑休眠都会让
 * 心跳自己停，不需要手机去猜。这里只做「时间戳 → 可显示状态」的换算，不碰 Android API。
 */
data class PcSyncStatus(
    val connected: Boolean,
    val transport: String,
    val clipboardReady: Boolean,
    val addresses: String,
    /** 距最近一次心跳的毫秒数；从未收到心跳时为 -1。 */
    val ageMs: Long
) {
    val transportLabel: String
        get() = when (transport) {
            "usb" -> "USB"
            "wifi" -> "Wi-Fi"
            else -> "—"
        }

    /** 「3 秒前 / 42 秒前 / 从未连接」——状态页直接用。 */
    val ageLabel: String
        get() = when {
            ageMs < 0 -> "从未连接"
            ageMs < 1_000 -> "刚刚"
            ageMs < 60_000 -> "${ageMs / 1_000} 秒前"
            else -> "${ageMs / 60_000} 分钟前"
        }

    companion object {
        const val TIMEOUT_MS = 15_000L

        fun of(now: Long, beat: Long, transport: String, clip: String, addresses: String): PcSyncStatus {
            val age = if (beat <= 0L) -1L else (now - beat).coerceAtLeast(0L)
            return PcSyncStatus(
                connected = age in 0L..TIMEOUT_MS,
                transport = transport.trim().lowercase(),
                clipboardReady = clip.trim() == "1",
                addresses = addresses.trim(),
                ageMs = age
            )
        }
    }
}
