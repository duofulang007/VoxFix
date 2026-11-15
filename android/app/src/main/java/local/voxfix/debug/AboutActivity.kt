package local.voxfix.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import local.voxfix.debug.ui.Palette
import local.voxfix.debug.ui.VoxFixTheme
import local.voxfix.debug.ui.VoxFixUi
import local.voxfix.debug.update.UpdateChecker

/**
 * 「关于」页：项目简介、版本、更新日志、开源许可入口。
 *
 * 开发者模式开关藏在这里：连点「当前版本」7 次。默认关闭，
 * 普通用户不会看到「试用与自检 / 开发者工具」。
 */
class AboutActivity : Activity() {

    /**
     * 本地更新日志：每轮发版手工补一行，首行是最新版。
     * 不依赖远程日志，避免首启联网。
     */
    private val changelog = listOf(
        "0.5.4" to "稳定性：从桌面或通知反复进入不再叠出多个首页；系统内存紧张时主动释放语音模型，输入法不再被系统清掉。",
        "0.5.3" to "备份补全：文本剪贴板、常用语、键盘外观与常用设置一并备份；「从文件恢复」直接落在备份目录。",
        "0.5.2" to "本地备份补「从文件恢复」：卸载重装后系统会收回旧文件所有权，手动选中 zip 即可恢复。",
        "0.5.1" to "本地备份：词库、语音词典与剪贴板图片存到下载目录，卸载不丢；检查更新的失败提示改为可读文案。",
        "0.5.0" to "二级页统一外壳；弧线一键恢复；开发者工具与用户界面分离。",
        "0.4.0" to "App 外壳四栏改版；画布改为照片查看器式缩放；图片编辑原地升级为工程；应用内检查更新与开源许可。",
        "0.3.0-r5" to "桌面 R5 语音管线接入；截图工程化与剪贴板图片编辑重做。"
    )

    private lateinit var palette: Palette

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        palette = VoxFixTheme.palette(this)

        setContentView(VoxFixUi.screen(this, palette, "关于", onBack = { finish() }) { col ->
            // 品牌头：标记 + 名称 + 一句话定位，替代「关于页里再写一段自我介绍」。
            val brand = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = VoxFixTheme.round(palette.surface, dp(18).toFloat())
                elevation = dp(1).toFloat()
                setPadding(dp(18), dp(16), dp(18), dp(16))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }
            val mark = android.widget.FrameLayout(this).apply {
                background = VoxFixTheme.round(palette.primarySoft, dp(18).toFloat())
                addView(
                    VoxFixUi.icon(this@AboutActivity, R.drawable.ic_vx_mark, 28, palette.primary),
                    android.widget.FrameLayout.LayoutParams(dp(28), dp(28), android.view.Gravity.CENTER)
                )
            }
            brand.addView(mark, LinearLayout.LayoutParams(dp(56), dp(56)))
            val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            words.addView(VoxFixUi.text(this, "VoxFix 输入法", 17f, palette.textPrimary, medium = true))
            words.addView(VoxFixUi.text(this, "26 键拼音 · 本机语音 · 屏幕取字", 12.5f, palette.textTertiary).apply {
                setPadding(0, dp(4), 0, 0)
            })
            brand.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(14) })
            col.addView(brand)

            val version = VoxFixUi.card(this, palette)
            version.addView(VoxFixUi.head(this, palette, "版本"))
            version.addView(VoxFixUi.row(this, palette, "当前版本",
                value = versionLabel(),
                iconRes = R.drawable.ic_vx_info) { onVersionTapped() })
            version.addView(VoxFixUi.row(this, palette, "检查更新",
                desc = "平时只在有新版本时于首页横幅提示",
                iconRes = R.drawable.ic_vx_download,
                chevron = true,
                divider = DevMode.enabled(this)) {
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
            if (DevMode.enabled(this)) {
                version.addView(VoxFixUi.row(
                    this, palette, "开发者模式",
                    desc = "已开启 · 设置页可见「开发者工具」",
                    iconRes = R.drawable.ic_vx_plug,
                    value = "关闭",
                    valueColor = palette.primary,
                    divider = false
                ) {
                    DevMode.disable(this)
                    recreate()
                })
            }
            col.addView(version)

            col.addView(VoxFixUi.section(this, palette, "更新日志"))
            val log = VoxFixUi.card(this, palette)
            changelog.forEachIndexed { index, entry ->
                log.addView(VoxFixUi.row(
                    this, palette, entry.first,
                    desc = entry.second,
                    divider = index != 0
                ))
            }
            col.addView(log)

            val licenses = VoxFixUi.card(this, palette)
            licenses.addView(VoxFixUi.row(this, palette, "开源许可",
                desc = "SenseVoice · sherpa-onnx · librime · Snow Shot 派生组件等",
                iconRes = R.drawable.ic_vx_book,
                chevron = true, divider = false) {
                startActivity(Intent(this, LicenseActivity::class.java))
            })
            col.addView(licenses)

            col.addView(VoxFixUi.hintRow(
                this, palette, R.drawable.ic_vx_info,
                "问题反馈与版本发布说明见仓库 docs/ 目录。"
            ))
        })
    }

    /**
     * 连点 7 次开启开发者模式。前 4 次不给任何反馈，避免普通用户误触发时看到调试字样；
     * 剩余 1–2 次才提示，第 7 次明确告知已开启。
     */
    private fun onVersionTapped() {
        val remaining = DevMode.tap(this) ?: return
        when (remaining) {
            0 -> {
                Toast.makeText(this, "开发者模式已开启", Toast.LENGTH_LONG).show()
                recreate()
            }
            1, 2 -> Toast.makeText(this, "再点 $remaining 次开启开发者模式", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = VoxFixTheme.dp(this, value)

    private fun versionLabel(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("")
}
