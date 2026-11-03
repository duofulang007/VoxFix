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
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
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
        layout.addView(TextView(this).apply {
            text = "从屏幕边缘的小弧线向内滑，打开截图；长按弧线后可拖动换边和调整高度。\n\n截图后先画框，可拖动框内移动、拖四边或角点调整。位置确认后点保存或复制，不会松手就处理。\n\n保存的截图在输入法剪贴板的「图片」页管理，支持预览、插入聊天、复制、裁剪另存和逐张删除。不自动写入系统相册。直接插入需要聊天应用支持接收图片。\n\n只在主动截图时获取画面，未保存的画面退出或闲置90秒后释放；锁屏或旋转结束当前截图。安全页面无法截图。\n\n首次使用需手动启用「VoxFix 截图工具」无障碍服务，它独立于键盘运行。"
            textSize = 16f; setPadding(0, pad, 0, pad)
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
        status.text = if (connected(this)) "无障碍服务已启用" else "尚未启用无障碍服务"
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
