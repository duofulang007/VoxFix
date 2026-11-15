package local.voxfix.debug

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import local.voxfix.debug.ui.Palette
import local.voxfix.debug.ui.VoxFixTheme
import local.voxfix.debug.ui.VoxFixUi
import local.voxfix.debug.ui.VxSwitch
import java.util.concurrent.Executors

/**
 * 开发者工具：只有「关于 → 当前版本」连点 7 次开启后才在设置页出现。
 *
 * 用户层不需要、但排查故障必需的能力都在这里：弧线诊断与恢复、无线调试授权、
 * 截图吸附、语音自检（手动录音/电平/识别结果）、模块重置与构建信息。
 */
class DevToolsActivity : Activity() {
    private lateinit var palette: Palette
    private lateinit var voiceStatus: TextView
    private lateinit var voiceLevel: TextView
    private lateinit var voiceResult: TextView
    private lateinit var startButton: TextView
    private lateinit var stopButton: TextView
    private lateinit var diagValue: TextView

    private val worker = Executors.newSingleThreadExecutor()
    private var engine: DesktopSpeechEngine? = null
    private val releaseVoice = Runnable { worker.execute { if (!active) { engine?.close(); engine = null } } }
    @Volatile private var microphoneCapture: PcmCapture? = null
    private var original = ""
    @Volatile private var active = false
    @Volatile private var recordingRequested = false
    @Volatile private var visible = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        palette = VoxFixTheme.palette(this)
        voiceStatus = VoxFixUi.note(this, palette, "语音模型按需加载")
        voiceLevel = VoxFixUi.note(this, palette, "未录音")
        voiceResult = VoxFixUi.text(this, state?.getString("transcript") ?: "", 15f, palette.textPrimary)
            .apply { setTextIsSelectable(true) }

        setContentView(VoxFixUi.screen(this, palette, "开发者工具", onBack = { finish() }) { col ->
            col.addView(arcCard())
            col.addView(snapCard())
            col.addView(voiceCard())
            col.addView(maintenanceCard())
            col.addView(VoxFixUi.note(this, palette, "这些内容默认对普通用户隐藏；关闭入口在「关于 → 开发者模式」。"))
        })
    }

    // ───────────────────────── 弧线 ─────────────────────────

    private fun arcCard(): LinearLayout {
        val card = VoxFixUi.card(this, palette)
        card.addView(VoxFixUi.head(this, palette, "贴边弧线", "诊断与恢复"))
        diagValue = VoxFixUi.text(this, "", 14f, palette.textSecondary)
        card.addView(VoxFixUi.row(
            this, palette, "诊断",
            desc = "无障碍 · 窗口 · 占用",
            trailing = diagValue,
            divider = true
        ))
        refreshDiagnostics()
        card.addView(VoxFixUi.row(
            this, palette, "恢复贴边弧线",
            desc = "写回开启、重置异常位置并重建窗口",
            value = "执行", valueColor = palette.primary, chevron = true
        ) {
            ArcRecovery.reset(this) { result -> runOnUiThread { afterArcReset(result) } }
        })
        card.addView(VoxFixUi.row(
            this, palette, "无线调试",
            desc = "弧柄展开后的第二枚按钮",
            value = wirelessLabel()
        ))
        card.addView(VoxFixUi.row(
            this, palette, "复制一次性授权命令",
            desc = "电脑上执行一次即可获得 WRITE_SECURE_SETTINGS",
            value = "复制", valueColor = palette.primary
        ) {
            val command = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
            getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("adb", command))
            android.widget.Toast.makeText(this, "已复制", android.widget.Toast.LENGTH_SHORT).show()
        })
        card.addView(VoxFixUi.row(
            this, palette, "打开无障碍设置",
            desc = "深链到本服务的条目", chevron = true, divider = false
        ) { ScreenTextHealth.openSystemSettings(this) })
        return card
    }

    private fun refreshDiagnostics() {
        if (!::diagValue.isInitialized) return
        val bound = ScreenTextHealth.bound(this)
        val listed = ScreenTextHealth.listed(this)
        val visible = ScreenTextService.windowVisible()
        val instance = ScreenTextService.alive()
        val text = when {
            bound && visible -> "正常"
            bound && instance -> "窗口未挂上"
            bound -> "实例不在"
            listed -> "已启用未连接"
            else -> "未授权"
        }
        diagValue.text = text
        diagValue.setTextColor(if (bound && visible) palette.success else palette.warning)
    }

    private fun wirelessLabel(): String = when {
        !AdbWireless.granted(this) -> "未授权"
        !AdbWireless.wifiConnected(this) -> "已授权 · 未连 Wi-Fi"
        AdbWireless.enabled(contentResolver) -> "已开启"
        else -> "已关闭"
    }

    private fun afterArcReset(result: ArcRecovery.Result) {
        val message = when (result) {
            ArcRecovery.Result.OK -> "弧线已恢复"
            ArcRecovery.Result.BUSY -> "正在截图或录屏，结束后再试"
            ArcRecovery.Result.DISABLED -> "无障碍没有启用，先去系统设置打开"
            ArcRecovery.Result.DENIED -> "缺少 WRITE_SECURE_SETTINGS，先复制命令到电脑执行"
            ArcRecovery.Result.REVERTED -> "系统回滚了重绑，去设置里关闭再打开"
            ArcRecovery.Result.FAILED -> "重绑失败，稍后再试"
            ArcRecovery.Result.WINDOW_MISSING -> "服务已连上，窗口仍未出现，再执行一次"
        }
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        recreate()
    }

    // ───────────────────────── 截图吸附 ─────────────────────────

    private fun snapCard(): LinearLayout {
        val card = VoxFixUi.card(this, palette)
        card.addView(VoxFixUi.head(this, palette, "截图高级", "手机直接拖框，通常不需要吸附"))
        val prefs = ScreenshotSnapConfig.preferences(this)
        val config = ScreenshotSnapConfig.fromPreferences(prefs)
        var syncing = false
        lateinit var objectRow: LinearLayout
        lateinit var gridRow: LinearLayout

        fun applyMode(grid: Boolean, objects: Boolean) {
            if (syncing) return
            syncing = true
            val (snap, gridOn) = ScreenshotSnapConfig.applyMode(grid, objects)
            prefs.edit().putBoolean("snapEnabled", snap).putBoolean("gridEnabled", gridOn).apply()
            (objectRow.tag as? VxSwitch)?.isChecked = snap
            (gridRow.tag as? VxSwitch)?.isChecked = gridOn
            syncing = false
        }

        objectRow = VoxFixUi.switchRow(
            this, palette, "对象吸附",
            desc = "对齐其它对象的边与中心",
            checked = config.snapEnabled
        ) { applyMode(false, it) }
        gridRow = VoxFixUi.switchRow(
            this, palette, "网格吸附",
            desc = "对齐网格，间距固定 20px",
            checked = config.gridEnabled,
            divider = false
        ) { applyMode(it, false) }
        card.addView(objectRow)
        card.addView(gridRow)
        // 旧版本可能同时存了两个 true，进页面时归一到互斥状态。
        applyMode(config.gridEnabled, config.snapEnabled)
        return card
    }

    // ───────────────────────── 语音自检 ─────────────────────────

    private fun voiceCard(): LinearLayout {
        val card = VoxFixUi.card(this, palette)
        card.addView(VoxFixUi.head(this, palette, "语音自检", "手动录音，不需要键盘"))

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            card.addView(VoxFixUi.row(
                this, palette, "麦克风权限",
                value = "未授权", valueColor = palette.warning, chevron = true
            ) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) })
        }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = VoxFixUi.textButton(this, palette, "开始录音") { requestRecording() }
        stopButton = VoxFixUi.textButton(this, palette, "停止并补全标点", palette.textSecondary) { stopRecording() }
        stopButton.isEnabled = false
        actions.addView(startButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(stopButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(actions)
        card.addView(voiceStatus)
        card.addView(voiceLevel)
        card.addView(voiceResult, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(96)
        ).apply { topMargin = dp(6) })
        return card
    }

    private fun requestRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else beginRecording()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == 1) {
            voiceStatus.text = if (grants.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                "麦克风权限已允许" else "麦克风权限被拒绝"
            recreate()
        }
    }

    private fun beginRecording() {
        if (active) return
        startButton.removeCallbacks(releaseVoice)
        active = true; recordingRequested = true
        startButton.isEnabled = false; stopButton.isEnabled = true
        voiceStatus.text = "正在开启麦克风"; voiceResult.text = ""
        worker.execute {
            try {
                if (!visible) return@execute
                Log.i("VoxFix", "CAPTURE_REQUESTED")
                val text = DesktopSpeechEngine.record(
                    { engine ?: DesktopSpeechEngine(assets).also { engine = it } },
                    { value -> ui { voiceResult.text = value } },
                    { rms -> ui { voiceLevel.text = "麦克风音量：$rms / 100" } },
                    { recordingRequested && visible }, { !visible },
                    { ui { if (recordingRequested && visible) voiceStatus.text = "实时识别 · 点击停止结束" } },
                    { capture -> microphoneCapture = capture; if (!recordingRequested || !visible) capture?.stop() })
                Log.i("VoxFix", "CAPTURE_FINISHED characters=${text.length}")
                original = text
                if (visible) ui {
                    voiceResult.text = text
                    voiceStatus.text = if (text.isEmpty()) "录音结束，未识别到文字" else "已保留识别原文"
                }
            } catch (error: Throwable) {
                if (error is DesktopVoiceFailure) ui { voiceResult.text = error.retained }
                report("录音或识别失败，已收到文字保留", error)
            } finally {
                active = false; recordingRequested = false
                ui {
                    startButton.isEnabled = true; stopButton.isEnabled = false; voiceLevel.text = "未录音"
                    if (visible) startButton.postDelayed(releaseVoice, 30_000)
                }
            }
        }
    }

    private fun stopRecording() {
        recordingRequested = false
        microphoneCapture?.stop()
        if (active) { stopButton.isEnabled = false; voiceStatus.text = "正在结束录音…" }
    }

    private fun report(message: String, error: Throwable) {
        Log.e("VoxFix", message, error)
        ui {
            voiceStatus.text = "$message：${error.message ?: error.javaClass.simpleName}"
            voiceLevel.text = "未录音"
        }
    }

    private fun ui(action: () -> Unit) = runOnUiThread { if (!isDestroyed) action() }

    // ───────────────────────── 维护 ─────────────────────────

    private fun maintenanceCard(): LinearLayout {
        val card = VoxFixUi.card(this, palette)
        card.addView(VoxFixUi.head(this, palette, "维护"))
        card.addView(VoxFixUi.row(
            this, palette, "恢复默认功能开关",
            desc = "只重置模块开关，不删除词库、词频与剪贴板",
            value = "重置", valueColor = palette.danger
        ) {
            AlertDialog.Builder(this)
                .setTitle("恢复默认功能开关")
                .setMessage("语音与屏幕相关模块恢复默认；个人数据、词库与剪贴板不受影响。")
                .setPositiveButton("恢复") { _, _ ->
                    Modules.reset(this)
                    android.widget.Toast.makeText(this, "已恢复默认", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        })
        card.addView(VoxFixUi.row(
            this, palette, "版本",
            value = runCatching {
                val info = packageManager.getPackageInfo(packageName, 0)
                "${info.versionName} (${info.longVersionCode})"
            }.getOrDefault("")
        ))
        card.addView(VoxFixUi.row(this, palette, "包名", value = packageName, divider = false))
        return card
    }

    // ───────────────────────── 生命周期 ─────────────────────────

    override fun onStart() { super.onStart(); visible = true; refreshDiagnostics() }

    override fun onStop() {
        visible = false; stopRecording()
        startButton.removeCallbacks(releaseVoice)
        worker.execute { engine?.close(); engine = null }
        super.onStop()
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putString("transcript", if (active && original.isNotEmpty()) original else voiceResult.text.toString())
        super.onSaveInstanceState(state)
    }

    override fun onDestroy() {
        startButton.removeCallbacks(releaseVoice)
        worker.execute { engine?.close(); engine = null }
        worker.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int) = VoxFixTheme.dp(this, value)
}
