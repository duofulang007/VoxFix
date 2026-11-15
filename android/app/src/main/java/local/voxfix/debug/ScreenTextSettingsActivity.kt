package local.voxfix.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import local.voxfix.debug.ui.Palette
import local.voxfix.debug.ui.VoxFixTheme
import local.voxfix.debug.ui.VoxFixUi

/**
 * 「截图与取字」设置：只放用户需要的东西——权限、工具栏布局、离线翻译。
 *
 * 弧线开关在屏幕页（同一偏好的唯一入口）；对象/网格吸附与无线调试属高级能力，
 * 已移到开发者工具。这里不再有 900 字开发说明与参数滑杆。
 */
class ScreenTextSettingsActivity : Activity() {
    private lateinit var palette: Palette
    private lateinit var translateStatus: TextView
    private lateinit var downloadRow: LinearLayout
    private lateinit var deleteRow: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        palette = VoxFixTheme.palette(this)
        TranslateRuntime.init(this)
        translateStatus = VoxFixUi.text(this, "", 13f, palette.textSecondary)
        downloadRow = LinearLayout(this)
        deleteRow = LinearLayout(this)

        setContentView(VoxFixUi.screen(this, palette, "截图与取字", onBack = { finish() }) { col ->
            val perm = VoxFixUi.card(this, palette)
            perm.addView(VoxFixUi.head(this, palette, "权限"))
            val state = a11yState()
            perm.addView(VoxFixUi.row(
                this, palette, "屏幕取字权限",
                desc = "取字、截图与录屏依赖系统无障碍",
                iconRes = R.drawable.ic_vx_lock,
                trailing = VoxFixUi.pill(this, palette, state.first, toneFor(state.second)),
                chevron = true,
                divider = false
            ) { ScreenTextHealth.openSystemSettings(this) })
            col.addView(perm)

            val shot = VoxFixUi.card(this, palette)
            shot.addView(VoxFixUi.head(this, palette, "截图"))
            shot.addView(VoxFixUi.row(
                this, palette, "工具栏布局",
                desc = "拖动编排工具，分绘制与截图两栏",
                iconRes = R.drawable.ic_vx_crop,
                chevron = true,
                divider = false
            ) { startActivity(Intent(this, ScreenshotToolbarSettingsActivity::class.java)) })
            col.addView(shot)

            val translate = VoxFixUi.card(this, palette)
            translate.addView(VoxFixUi.head(this, palette, "离线翻译", "腾讯 Hy-MT2 · 本机运行"))
            translateStatus.setPadding(dp(18), 0, dp(18), dp(6))
            translate.addView(translateStatus)

            downloadRow = VoxFixUi.row(
                this, palette, "下载翻译模型",
                desc = "约 462MB · 首次需要联网",
                value = "下载",
                valueColor = palette.primary,
                iconRes = R.drawable.ic_vx_download,
                chevron = true
            ) {
                if (!Modules.isOn(this, Module.TRANSLATE)) {
                    android.widget.Toast.makeText(this, "先在功能管理里开启「离线翻译」", android.widget.Toast.LENGTH_SHORT).show()
                    return@row
                }
                TranslateDownloader.start(this) { renderTranslate(it) }
            }
            translate.addView(downloadRow)

            deleteRow = VoxFixUi.row(
                this, palette, "删除翻译模型",
                desc = "释放约 462MB，随时可重新下载",
                value = "删除",
                valueColor = palette.danger,
                iconRes = R.drawable.ic_vx_trash,
                iconTone = VoxFixUi.Tone.DANGER,
                divider = false
            ) {
                if (TranslateRuntime.isBusy()) {
                    android.widget.Toast.makeText(this, "翻译进行中，结束后可删除", android.widget.Toast.LENGTH_SHORT).show()
                    return@row
                }
                TranslateDownloader.cancel()
                TranslateRuntime.releaseNow()
                TranslateModelStore(this).delete()
                renderTranslate(TranslateDownloader.State.Idle)
            }
            translate.addView(deleteRow)
            translate.addView(VoxFixUi.note(
                this, palette,
                "下载完成后翻译完全离线；框选后点「翻译」会先识别再翻译。"
            ).apply { setPadding(dp(18), dp(2), dp(18), dp(12)) })
            col.addView(translate)

            col.addView(VoxFixUi.hintRow(
                this, palette, R.drawable.ic_vx_info,
                "截图结果先进剪贴板，不写入相册；需要留档时在图片页另存。"
            ))
        })
    }

    private fun a11yState(): Pair<String, Int> = when {
        ScreenTextHealth.bound(this) -> "已连接" to palette.success
        ScreenTextHealth.listed(this) -> "已启用未连接" to palette.warning
        else -> "未授权" to palette.warning
    }

    /** 状态色收敛成胶囊语义色，页面不再直接拿 success/warning 画文字。 */
    private fun toneFor(color: Int): VoxFixUi.Tone = when (color) {
        palette.success -> VoxFixUi.Tone.SUCCESS
        palette.warning -> VoxFixUi.Tone.WARNING
        palette.danger -> VoxFixUi.Tone.DANGER
        else -> VoxFixUi.Tone.NEUTRAL
    }

    private fun dp(value: Int) = VoxFixTheme.dp(this, value)

    override fun onResume() {
        super.onResume()
        TranslateRuntime.init(this)
        TranslateDownloader.observe { renderTranslate(it) }
        renderTranslate(TranslateDownloader.State.Idle)
    }

    override fun onPause() {
        TranslateDownloader.observe(null)
        super.onPause()
    }

    private fun renderTranslate(state: TranslateDownloader.State) {
        runOnUiThread {
            val ready = TranslateModelStore(this).ready()
            downloadRow.visibility = View.VISIBLE
            deleteRow.visibility = if (ready) View.VISIBLE else View.GONE
            when (state) {
                is TranslateDownloader.State.Running -> {
                    val percent = if (state.total > 0) state.downloaded * 100 / state.total else 0
                    translateStatus.text = if (state.verifying) "正在校验模型文件…"
                    else "下载中 $percent%（${state.downloaded / (1024 * 1024)}/${state.total / (1024 * 1024)}MB）"
                    downloadRow.visibility = View.GONE
                    deleteRow.visibility = View.GONE
                }
                is TranslateDownloader.State.Failed -> {
                    translateStatus.text = state.message
                }
                TranslateDownloader.State.Ready -> {
                    translateStatus.text = "已就绪（461.9MB），可离线翻译"
                    downloadRow.visibility = View.GONE
                }
                TranslateDownloader.State.Idle -> {
                    translateStatus.text = if (ready) "已就绪（461.9MB），可离线翻译" else "未下载（约 462MB）"
                    downloadRow.visibility = if (ready) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onDestroy() {
        TranslateDownloader.observe(null)
        super.onDestroy()
    }
}
