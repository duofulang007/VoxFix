package local.voxfix.debug

import android.content.Context
import android.os.SystemClock

/**
 * 手机 ⇄ 电脑剪贴板同步的手机侧设置与状态。
 *
 * 接收本体是 [ClipboardImportReceiver]（电脑端伴生工具 `tools/copyq_phone_sync.py`
 * 通过 `am broadcast` 推送）。2026-11-14 外壳精简时删掉过一行「电脑同步」状态，因为当时
 * 它只是展示文案、接收器从不读取任何开关；现在补的是真正会生效的控件：
 *
 *  - [enabled]：电脑 → 手机的总开关，关闭时文本与图片推送一律丢弃（文件也删掉，不占空间）；
 *  - [images]：只挡图片，文本照常接收；
 *  - 心跳与 `pc.addrs` 不受开关影响：关掉接收仍然能看到电脑在不在、走的 USB 还是 Wi-Fi。
 *
 * 默认全开，保持升级前「功能本体常开」的行为，不会因为新增页面而改变既有同步。
 */
object PcSyncPrefs {
    private const val KEY_ENABLED = "pc.sync"
    private const val KEY_IMAGES = "pc.images"
    private const val KEY_BEAT = "pc.beat"
    private const val KEY_TRANSPORT = "pc.transport"
    private const val KEY_CLIP = "pc.clip"
    private const val KEY_ADDRS = "pc.addrs"

    fun enabled(context: Context): Boolean =
        ScreenTextPrefs.file(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        ScreenTextPrefs.file(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun images(context: Context): Boolean =
        ScreenTextPrefs.file(context).getBoolean(KEY_IMAGES, true)

    fun setImages(context: Context, on: Boolean) {
        ScreenTextPrefs.file(context).edit().putBoolean(KEY_IMAGES, on).apply()
    }

    /** 电脑端最近一次心跳、连接方式、CopyQ 监听状态与地址。 */
    fun snapshot(context: Context, now: Long = SystemClock.elapsedRealtime()): PcSyncStatus {
        val prefs = ScreenTextPrefs.file(context)
        return PcSyncStatus.of(
            now = now,
            beat = prefs.getLong(KEY_BEAT, 0L),
            transport = prefs.getString(KEY_TRANSPORT, "").orEmpty(),
            clip = prefs.getString(KEY_CLIP, "").orEmpty(),
            addresses = prefs.getString(KEY_ADDRS, "").orEmpty()
        )
    }
}
