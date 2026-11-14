package local.voxfix.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import local.voxfix.debug.ui.Palette
import local.voxfix.debug.ui.VoxFixTheme
import local.voxfix.debug.ui.VoxFixUi
import local.voxfix.debug.update.UpdateChecker

/**
 * 「关于」页：项目简介、版本、更新日志、开源许可入口。
 * 检查更新入口由 update/UpdateChecker 在接入后挂到版本卡（见 docs/关于与应用内更新-2026-11-14.md）。
 */
class AboutActivity : Activity() {

    /**
     * 本地更新日志：每轮发版手工补一行，首行是最新版。
     * 不依赖远程日志，避免首启联网。
     */
    private val changelog = listOf(
        "0.3.0-r5" to "桌面 R5 语音管线接入；截图工程化与剪贴板图片编辑重做。"
    )

    private lateinit var palette: Palette

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        palette = VoxFixTheme.palette(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.bg)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        val scroll = ScrollView(this)
        val col = VoxFixUi.page(this, palette)

        val info = VoxFixUi.card(this, palette)
        info.addView(VoxFixUi.head(this, palette, "VoxFix 输入法"))
        info.addView(VoxFixUi.row(this, palette, "定位",
            desc = "自有安卓输入法：26 键拼音、本机离线语音、屏幕取字与截图标注。",
            divider = false))
        info.addView(VoxFixUi.row(this, palette, "隐私",
            desc = "识别、语音与翻译都在本机完成，不上传屏幕内容与录音。"))
        col.addView(info)

        val version = VoxFixUi.card(this, palette)
        version.addView(VoxFixUi.head(this, palette, "版本"))
        version.addView(VoxFixUi.row(this, palette, "当前版本", value = versionLabel(), divider = false))
        version.addView(VoxFixUi.row(this, palette, "检查更新",
            desc = "手动检查；平时只在有新版本时于首页横幅提示",
            chevron = true) {
            Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show()
            UpdateChecker.check(this, manual = true) { status ->
                val message = when (status.phase) {
                    UpdateChecker.Phase.AVAILABLE -> "发现新版本 ${status.manifest?.versionName}，回首页点横幅即可更新"
                    UpdateChecker.Phase.FAILED -> status.message
                    else -> "已是最新版本"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        })
        changelog.forEach { (ver, note) ->
            version.addView(VoxFixUi.row(this, palette, ver, desc = note))
        }
        col.addView(version)

        val licenses = VoxFixUi.card(this, palette)
        licenses.addView(VoxFixUi.head(this, palette, "开源许可"))
        licenses.addView(VoxFixUi.row(this, palette, "开源许可",
            desc = "SenseVoice · sherpa-onnx · librime · Snow Shot 派生组件等",
            chevron = true, divider = false) {
            startActivity(Intent(this, LicenseActivity::class.java))
        })
        col.addView(licenses)

        col.addView(VoxFixUi.note(this, palette,
            "问题反馈与版本发布说明见仓库 docs/ 目录。"))
        scroll.addView(col)
        root.addView(scroll)
        setContentView(root)
    }

    private fun versionLabel(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("")
}
