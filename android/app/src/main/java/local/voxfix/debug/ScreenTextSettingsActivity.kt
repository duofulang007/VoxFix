package local.voxfix.debug

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.view.WindowInsets

class ScreenTextSettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var wirelessStatus: TextView
    private lateinit var translateStatus: TextView
    private lateinit var translateDownload: Button
    private lateinit var translateDelete: Button
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.setBackgroundColor(0xFFFFFFFF.toInt())
        TranslateRuntime.init(this)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); insets
            }
        }
        layout.addView(TextView(this).apply { text = "截图工具"; textSize = 24f })
        layout.addView(Switch(this).apply {
            text = "显示贴边截图弧线"; textSize = 18f; minHeight = pad * 2
            isChecked = enabled(this@ScreenTextSettingsActivity)
            setOnCheckedChangeListener { _, checked -> preferences(this@ScreenTextSettingsActivity).edit().putBoolean("enabled", checked).apply() }
        })
        layout.addView(Button(this).apply {
            text = "截图工具栏布局"
            setOnClickListener { startActivity(Intent(this@ScreenTextSettingsActivity,ScreenshotToolbarSettingsActivity::class.java)) }
        })
        layout.addView(ScreenshotSnapSettings(this))
        layout.addView(TextView(this).apply {
            text = "从屏幕边缘的小弧线向内滑，打开截图；长按弧线后可拖动换边和调整高度。\n\n截图后先画框，可拖动框内移动、拖四边或角点调整。可直接保存图片、识别文字或翻译，识别后可复制文字；不会松手就处理。\n\n保存的截图在输入法剪贴板的「图片」页管理，支持预览、插入聊天、复制、裁剪另存和批量删除。不自动写入系统相册。直接插入需要聊天应用支持接收图片。\n\n只在主动截图时获取画面，未保存的画面退出或闲置90秒后释放；闲置或旋转时保留已识别文字和完成的译文，锁屏或主动退出时清理。安全页面无法截图。\n\n首次使用需手动启用「VoxFix 截图工具」无障碍服务，它独立于键盘运行。"
            textSize = 16f; setPadding(0, pad, 0, pad)
        })
        layout.addView(TextView(this).apply { text = "无线调试（弧柄）"; textSize = 20f; setPadding(0, pad, 0, 0) })
        wirelessStatus = TextView(this).apply { textSize = 16f; setPadding(0, dp(6), 0, dp(6)) }
        layout.addView(wirelessStatus)
        layout.addView(Button(this).apply {
            text = "复制一次性授权命令"
            setOnClickListener {
                val command = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("adb", command))
                android.widget.Toast.makeText(this@ScreenTextSettingsActivity, "已复制，电脑上执行一次即可", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
        layout.addView(TextView(this).apply {
            text = "弧柄展开后第二枚按钮就是开关。面板下方常驻一行连接状态：「电脑已连 · USB/Wi-Fi」表示这条通道现在就能用，剪贴板同步也走它；15 秒没有电脑心跳会变成「电脑未连」。\n\n点开关会先体检：没连 Wi-Fi 会自动拉起网络面板；不在同一 Wi-Fi、电脑端没运行都会直接说清楚，不会闷头写入。开启后电脑首次需要配对一次，端口每次随机，电脑侧用 adb mdns services 查。"
            textSize = 14f; setPadding(0, 0, 0, pad)
        })
        layout.addView(TextView(this).apply { text = "离线翻译"; textSize = 20f; setPadding(0, pad, 0, 0) })
        translateStatus = TextView(this).apply { textSize = 16f; setPadding(0, dp(6), 0, dp(6)) }
        layout.addView(translateStatus)
        translateDownload = Button(this).apply {
            text = "下载翻译模型（约 462MB）"
            setOnClickListener { TranslateDownloader.start(this@ScreenTextSettingsActivity) { renderTranslate(it) } }
        }
        layout.addView(translateDownload)
        translateDelete = Button(this).apply {
            text = "删除翻译模型"
            setOnClickListener {
                if (TranslateRuntime.isBusy()) {
                    android.widget.Toast.makeText(this@ScreenTextSettingsActivity, "翻译进行中，结束后可删除", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                TranslateDownloader.cancel()
                TranslateRuntime.releaseNow()
                TranslateModelStore(this@ScreenTextSettingsActivity).delete()
                renderTranslate(TranslateDownloader.State.Idle)
            }
        }
        layout.addView(translateDelete)
        layout.addView(TextView(this).apply {
            text = "腾讯 Hy-MT2-1.8B（1.25bit 量化，Apache-2.0）。权重只保存在本机应用目录，不随安装包分发；下载需要联网，下载完成后翻译完全离线。框选后可直接点「翻译」，内部先识别再翻译。语言切换是单行的「自动检测 ⇄ 简体中文」：点任一侧原地展开语言选项，中间箭头可交换方向。保存导出当前显示的原图、识别结果或完整译文。"
            textSize = 14f; setPadding(0, 0, 0, pad)
        })
        status = TextView(this).apply { textSize = 16f }; layout.addView(status)
        layout.addView(Button(this).apply {
            text = "打开无障碍设置"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })
        layout.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
        setContentView(android.widget.ScrollView(this).apply { addView(layout) })
    }
    override fun onResume() {
        super.onResume()
        TranslateRuntime.init(this)
        TranslateDownloader.observe { renderTranslate(it) }
        status.text = if (connected(this)) "无障碍服务已启用" else "尚未启用无障碍服务"
        wirelessStatus.text = when {
            !AdbWireless.granted(this) -> "未授权：需要在电脑上执行一次 pm grant（按钮可复制命令）"
            !AdbWireless.wifiConnected(this) -> "已授权；当前没有连 Wi-Fi，开启会被系统直接回滚"
            AdbWireless.enabled(contentResolver) -> "已授权 · 无线调试已开启"
            else -> "已授权 · 无线调试未开启"
        }
    }
    override fun onPause() {
        TranslateDownloader.observe(null)
        super.onPause()
    }
    private fun renderTranslate(state: TranslateDownloader.State) {
        runOnUiThread {
            val ready = TranslateModelStore(this).ready()
            when (state) {
                is TranslateDownloader.State.Running -> {
                    val percent = if (state.total > 0) state.downloaded * 100 / state.total else 0
                    translateStatus.text = if (state.verifying) "正在校验模型文件…"
                    else "下载中 $percent%（${state.downloaded / (1024 * 1024)}/${state.total / (1024 * 1024)}MB）"
                    translateDownload.visibility = android.view.View.GONE
                    translateDelete.visibility = android.view.View.GONE
                }
                is TranslateDownloader.State.Failed -> {
                    translateStatus.text = state.message
                    translateDownload.visibility = android.view.View.VISIBLE
                    translateDownload.text = "重试下载"
                    translateDelete.visibility = if (ready) android.view.View.VISIBLE else android.view.View.GONE
                }
                TranslateDownloader.State.Ready -> {
                    translateStatus.text = "已就绪（461.9MB），可离线翻译"
                    translateDownload.visibility = android.view.View.GONE
                    translateDelete.visibility = android.view.View.VISIBLE
                }
                TranslateDownloader.State.Idle -> {
                    translateStatus.text = if (ready) "已就绪（461.9MB），可离线翻译" else "未下载（约 462MB）"
                    translateDownload.visibility = if (ready) android.view.View.GONE else android.view.View.VISIBLE
                    translateDownload.text = "下载翻译模型（约 462MB）"
                    translateDelete.visibility = if (ready) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        TranslateDownloader.observe(null)
        super.onDestroy()
    }
    companion object {
        fun preferences(context: Context) = context.getSharedPreferences("screen-text", Context.MODE_PRIVATE)
        fun enabled(context: Context) = preferences(context).getBoolean("enabled", false)
        fun connected(context: Context): Boolean {
            val component = ComponentName(context, ScreenTextService::class.java)
            return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
        }
        fun toggle(context: Context) {
            val next = !enabled(context)
            preferences(context).edit().putBoolean("enabled", next).apply()
            if (next && !connected(context)) context.startActivity(Intent(context, ScreenTextSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
