package local.voxfix.debug

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import local.voxfix.debug.ui.Palette
import local.voxfix.debug.ui.VoxFixTheme
import local.voxfix.debug.ui.VoxFixUi
import java.io.File
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * 本地备份页：备份存在 `Download/VoxFix/`，卸载 App 不会删。
 *
 * 与「拼音词库 / 语音纠错词典」里的导出区别：那两个是用户主动选路径的一次性文件，
 * 这里是固定位置、自动轮换（保留最近 2 份）的兜底副本，重装后进来就能恢复。
 */
class BackupActivity : Activity() {
    private companion object {
        /**
         * 卸载重装后系统会把 MediaStore 条目的 owner 置空，本应用就再也查不到自己当年写的
         * 那几份备份（文件还在下载目录里，只是无权按媒体库索引进来了）。所以恢复必须留一条
         * 走系统文件选择器的路：用户亲手选中 zip，授权即成立。
         */
        const val REQUEST_PICK = 13
    }

    private val RESTART_LOG = "VoxFixBackup"

    private val worker = Executors.newSingleThreadExecutor()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var palette: Palette
    private lateinit var status: TextView
    private lateinit var listBox: LinearLayout
    private var busy = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        palette = VoxFixTheme.palette(this)
        status = VoxFixUi.note(this, palette, "")

        setContentView(VoxFixUi.screen(this, palette, "本地备份", onBack = { finish() }) { col ->
            val intro = VoxFixUi.card(this, palette)
            intro.addView(VoxFixUi.head(this, palette, "卸载也不会丢", "存到 Download/VoxFix"))
            intro.addView(VoxFixUi.note(
                this, palette,
                "备份放在手机的下载目录，卸载 VoxFix 不会删掉它；只有你在这里或文件管理器里删除才会消失。" +
                    "包含拼音词库、语音词典、剪贴板图片与截图工程（恢复后仍可继续编辑）；不含录音和正文。" +
                    "离线翻译模型不备份，需要时在「离线翻译」里重新下载。"
            ).apply { setPadding(dp(18), 0, dp(18), dp(12)) })
            col.addView(intro)

            val actions = VoxFixUi.card(this, palette)
            actions.addView(VoxFixUi.row(
                this, palette, "立即备份",
                desc = "词库、词典与剪贴板图片打包到下载目录",
                iconRes = R.drawable.ic_vx_download, chevron = true
            ) { backup() })
            actions.addView(VoxFixUi.row(
                this, palette, "从文件恢复",
                desc = "卸载重装后列表可能为空，用这里手动选 Download/VoxFix 里的 zip",
                iconRes = R.drawable.ic_vx_upload, chevron = true, divider = false
            ) { pick() })
            col.addView(actions)

            col.addView(VoxFixUi.section(this, palette, "已有备份"))
            listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(listBox)
            col.addView(status)
        })
        refresh()
    }

    private fun refresh() {
        val archives = runCatching { LocalBackup.list(this) }.getOrDefault(emptyList())
        listBox.removeAllViews()
        if (archives.isEmpty()) {
            listBox.addView(VoxFixUi.note(
                this, palette,
                "没有可列出的备份。如果你刚卸载重装过，系统会收回旧文件的所有权（文件本身还在下载目录里），" +
                    "请用上面的「从文件恢复」手动选择 Download/VoxFix/ 里的 zip。"
            ))
            return
        }
        val card = VoxFixUi.card(this, palette)
        archives.forEachIndexed { index, archive ->
            card.addView(VoxFixUi.row(
                this, palette, archive.label,
                desc = "${archive.bytes / 1024} KB · Download/${LocalBackup.FOLDER}",
                chevron = true, divider = index != 0
            ) { open(archive) })
        }
        listBox.addView(card)
    }

    private fun open(archive: LocalBackup.Archive) {
        AlertDialog.Builder(this)
            .setTitle(archive.label)
            .setMessage("恢复是与现有内容合并：本机多的词保留，备份里的词补进来，不会清空。")
            .setNegativeButton("取消", null)
            .setNeutralButton("删除") { _, _ -> delete(archive) }
            .setPositiveButton("恢复") { _, _ -> restore(archive) }
            .show()
    }

    private fun backup() = work {
        val archive = LocalBackup.create(this, versionName())
        "已备份 ${archive.bytes / 1024} KB：Download/${LocalBackup.FOLDER}/${archive.name}"
    }

    private fun restore(archive: LocalBackup.Archive) = restore(archive.uri, archive.label)

    /**
     * 两条恢复入口（列表 / 文件选择器）共用同一收尾。
     *
     * 卸载重装后系统收回了旧备份的 MediaStore 所有权，列表必然为空，用户只能走文件选择器；
     * 而 `prefs-restore/` 只有进程冷启动时才会落位。此前只有列表路径排了重启，文件选择器
     * 恢复完就停在运行中的旧进程里——词库与剪贴板回来了，截图工具栏顺序、工具样式却一直是默认值。
     */
    private fun restore(uri: android.net.Uri, label: String) = work {
        val message = "已恢复 $label — " + LocalBackup.restore(this, uri)
        Log.w(RESTART_LOG, "restore 完成：prefs-restore 文件数=${File(filesDir, "prefs-restore").listFiles()?.size}")
        // 正常恢复已在主线程即时写回偏好，不需要冷启动；这里只兜底旧版本遗留的 prefs-restore。
        if (LocalBackup.needsRestart(this)) scheduleRestart()
        message
    }

    /**
     * 恢复摘要让用户看 1.5 秒后自动重启；下载目录里的备份与安装包不受进程退出影响。
     * 放 worker 队列而不是主线程 handler：exitProcess 的时机不依赖主线程是否空闲。
     */
    private fun scheduleRestart() = worker.execute {
        Log.w(RESTART_LOG, "scheduleRestart：1.5 秒后退出进程，prefs-restore 将在下次启动落位")
        try { Thread.sleep(1500) } catch (_: InterruptedException) {}
        restartNow()
    }

    /**
     * 自动重启：退出进程让 prefs-restore 落位。默认输入法与无障碍服务由系统按原配置
     * 重新拉起；这里再用 WRITE_SECURE_SETTINGS（无线调试功能已授予）原样重写一遍
     * enabled_accessibility_services，触发无障碍服务立即重新绑定——值原样保留，
     * 不影响 GKD 等其他无障碍应用。
     */
    private fun restartNow() {
        Log.w(RESTART_LOG, "restartNow：触发无障碍重绑定并退出进程")
        runCatching {
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (!current.isNullOrBlank() && current.contains(packageName)) {
                Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, current)
            }
        }
        exitProcess(0)
    }

    private fun delete(archive: LocalBackup.Archive) = work {
        check(LocalBackup.delete(this, archive.uri)) { "系统未允许删除这份文件" }
        "已删除 ${archive.label}。回到键盘即可使用恢复后的内容。"
    }

    /** 走系统文件选择器：这是卸载重装后唯一 100% 能读到旧备份的路径。 */
    private fun pick() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
        // 直接落在 Download/VoxFix：省掉用户在文件选择器里点「菜单 → 下载内容 → VoxFix」三层。
        // 目录不存在时系统自行回退，不抛错，所以这里吞掉异常即可。
        runCatching {
            intent.putExtra(
                // 等价于 DocumentsContract.EXTRA_INITIAL_URI；用字面量避免不同 compileSdk 下的解析差异。
                "android.provider.extra.INITIAL_URI",
                android.provider.DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:${android.os.Environment.DIRECTORY_DOWNLOADS}/${LocalBackup.FOLDER}"
                )
            )
        }
        startActivityForResult(intent, REQUEST_PICK)
    }

    @Deprecated("Uses the platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK) return
        if (resultCode != RESULT_OK) { status.text = "已取消，内容未改动。"; return }
        val uri = data?.data ?: return
        restore(uri, "所选备份")
    }

    private fun work(action: () -> String) {
        if (busy) return
        busy = true
        status.text = "处理中…"
        worker.execute {
            val message = try {
                action()
            } catch (error: Exception) {
                "未完成：${error.message ?: "读写失败"}。现有内容未改动。"
            }
            runOnUiThread {
                if (!isDestroyed) {
                    busy = false
                    status.text = message
                    refresh()
                }
            }
        }
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun dp(value: Int) = VoxFixTheme.dp(this, value)

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
