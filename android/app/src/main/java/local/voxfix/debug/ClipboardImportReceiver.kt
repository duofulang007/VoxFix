package local.voxfix.debug

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 电脑 → 手机剪贴板反向导入（debug 包专用，2026-11-08）。
 *
 * 电脑端守护脚本（tools/copyq_phone_sync.py 的 push 线程）先把内容以 base64 经
 * `adb exec-out run-as … cat > files/clipboard-import/<name>` 落到私有目录，
 * 再发 `am broadcast` 通知本接收器处理：
 *
 *  - ACTION_IMPORT_TEXT：读文本写入系统剪贴板，ClipData 描述标签固定为 [PC_CLIP_LABEL]，
 *    [VoxFixImeService.updateClipboard] 靠这个标签把「来自电脑」传给前端（voxfixClipboard
 *    的 fromPc 参数），前端据此给文本条目画「电脑」来源标签。键盘收起时写入的文本也会在
 *    下次键盘弹出时由 onStartInputView → updateClipboard 记入历史。
 *  - ACTION_IMPORT_IMAGE：读 PNG 解码为 Bitmap 后交 [ScreenshotStore.save] 入「图片」页。
 *    硬性防范：解码失败（内容不是图片，例如误传文本）一律丢弃并记日志，绝不写入图片页。
 *
 * 安全说明：exported=true 是 adb shell 广播所需（shell uid 无法持有自定义签名权限），
 * 本应用为个人 debug 包（local.voxfix.debug），风险面与既有调试能力一致。
 */
class ClipboardImportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 电脑端伴生工具每次连着就推一次自己的 IPv4 列表，供弧柄判断"是否同一 Wi-Fi"。
        // 纯内存/SharedPreferences 写入，不需要线程池。
        if (intent.action == ACTION_PC_INFO) {
            val addrs = intent.getStringExtra("addrs").orEmpty()
            AdbWireless.rememberPc(context, addrs)
            android.util.Log.i("VoxFix", "PC_INFO addrs=$addrs")
            return
        }
        // 心跳：电脑端每 5 秒推一次"通道还活着 + 走的是 USB 还是 Wi-Fi"。
        // 手机只存时间戳，15 秒没心跳就当断开 —— 拔线/换网/电脑休眠都会让心跳自己停。
        if (intent.action == ACTION_PC_STATE) {
            ScreenTextSettingsActivity.preferences(context).edit()
                .putLong("pc.beat", android.os.SystemClock.elapsedRealtime())
                .putString("pc.transport", intent.getStringExtra("transport").orEmpty())
                .putString("pc.clip", intent.getStringExtra("clip").orEmpty())
                .apply()
            return
        }
        val pending = goAsync()
        worker.execute {
            try {
                when (intent.action) {
                    ACTION_TEXT -> importText(context, intent)
                    ACTION_IMAGE -> importImage(context, intent)
                }
            } catch (error: Exception) {
                android.util.Log.w("VoxFix", "CLIPBOARD_IMPORT_FAILED", error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun importText(context: Context, intent: Intent) {
        val file = importFile(context, intent) ?: return
        try {
            val text = file.readText(Charsets.UTF_8)
            require(text.isNotEmpty()) { "导入文本为空" }
            val clipped = if (text.length > TEXT_LIMIT) text.take(TEXT_LIMIT) else text
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(PC_CLIP_LABEL, clipped))
            android.util.Log.i("VoxFix", "CLIPBOARD_IMPORT_TEXT chars=${clipped.length}${if (clipped.length < text.length) " (截断自 ${text.length})" else ""}")
        } finally {
            file.delete()
        }
    }

    private fun importImage(context: Context, intent: Intent) {
        val file = importFile(context, intent) ?: return
        try {
            // 防范措施的第一道闸：先把文件读进来按 PNG 解码，解不出位图就当垃圾删掉。
            // 文本内容永远走不到 ScreenshotStore，图片页不会出现文本。
            require(file.length() in 1..MAX_IMAGE_BYTES) { "导入图片大小越界（${file.length()} 字节）" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "内容不是可识别的图片" }
            val bitmap = BitmapFactory.decodeFile(file.path)
            require(bitmap != null && bitmap.width > 0 && bitmap.height > 0) { "图片解码失败" }
            try {
                val entry = ScreenshotStore(context).save(bitmap, SOURCE_PC)
                android.util.Log.i("VoxFix", "CLIPBOARD_IMPORT_IMAGE id=${entry.id} ${entry.width}x${entry.height}")
            } finally {
                bitmap.recycle()
            }
        } finally {
            file.delete()
        }
    }

    /** 只接受导入目录内的简单文件名，拒绝任何路径穿越。 */
    private fun importFile(context: Context, intent: Intent): File? {
        val name = intent.getStringExtra("name") ?: return null
        require(name.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "非法导入文件名" }
        val directory = File(context.filesDir, IMPORT_DIR)
        val file = File(directory, name)
        require(file.canonicalFile.parentFile == directory.canonicalFile) { "导入路径越界" }
        if (!file.isFile) {
            android.util.Log.w("VoxFix", "CLIPBOARD_IMPORT_MISSING $name")
            return null
        }
        return file
    }

    companion object {
        const val ACTION_TEXT = "local.voxfix.debug.IMPORT_TEXT"
        const val ACTION_IMAGE = "local.voxfix.debug.IMPORT_IMAGE"
        const val ACTION_PC_INFO = "local.voxfix.debug.PC_INFO"
        const val ACTION_PC_STATE = "local.voxfix.debug.PC_STATE"
        /** 写入系统剪贴板时的描述标签，前端「电脑」标签的判据。 */
        const val PC_CLIP_LABEL = "VoxFix · 电脑"
        /** ScreenshotStore 里电脑来源图片的 source 标记。 */
        const val SOURCE_PC = "pc"
        const val IMPORT_DIR = "clipboard-import"
        private const val TEXT_LIMIT = 10000   // 与键盘剪贴板历史的单条上限一致
        private const val MAX_IMAGE_BYTES = 16L * 1024 * 1024  // 与 ScreenshotStore 单图上限一致
        private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "clipboard-import").apply { isDaemon = true }
        }
    }
}
