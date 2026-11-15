package local.voxfix.debug

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import local.voxfix.debug.ui.Palette
import local.voxfix.debug.ui.VoxFixTheme
import local.voxfix.debug.ui.VoxFixUi
import local.voxfix.debug.ui.VxSwitch

/**
 * 「电脑同步」页：手机 ⇄ 电脑剪贴板的可见状态与开关。
 *
 * 这条链路本来就有（`ClipboardImportReceiver` + `tools/copyq_phone_sync.py`），但手机侧一直
 * 没有入口：能不能收、为什么没收到、走的是 USB 还是 Wi-Fi，用户只能从弧柄展开时那行小字猜。
 * 2026-11-14 精简删掉的状态行是死 UI（接收器从不读），所以这次补页面时同时给接收器加了
 * 真正生效的开关（[PcSyncPrefs]），避免再做一次「看得见、点不动」。
 *
 * 数据来源只有心跳：电脑端每 5 秒广播一次 `PC_STATE`，15 秒没有就显示未连接。
 */
class PcSyncActivity : Activity() {
    private lateinit var palette: Palette
    private lateinit var statusPill: TextView
    private lateinit var transportValue: TextView
    private lateinit var addressValue: TextView
    private lateinit var clipboardValue: TextView
    private lateinit var beatValue: TextView
    private lateinit var imageRow: LinearLayout
    private lateinit var imageSwitch: VxSwitch
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        palette = VoxFixTheme.palette(this)
        statusPill = VoxFixUi.pill(this, palette, "未连接")
        transportValue = valueText()
        addressValue = valueText()
        clipboardValue = valueText()
        beatValue = valueText()

        setContentView(VoxFixUi.screen(this, palette, "电脑同步", onBack = { finish() }) { col ->
            val status = VoxFixUi.card(this, palette)
            status.addView(VoxFixUi.head(this, palette, "连接状态", "电脑端每 5 秒心跳"))
            status.addView(VoxFixUi.row(
                this, palette, "电脑端",
                desc = "拔线、换网或电脑休眠都会自动断开",
                trailing = statusPill
            ))
            status.addView(valueRow("连接方式", "USB 或 Wi-Fi，由电脑端上报", transportValue))
            status.addView(valueRow("电脑地址", "电脑端每次连接都会上报", addressValue))
            status.addView(valueRow("剪贴板监听", "CopyQ 标签监听是否就绪", clipboardValue))
            status.addView(valueRow("最近心跳", "超过 15 秒即视为断开", beatValue, divider = false))
            col.addView(status)

            col.addView(VoxFixUi.section(this, palette, "手机侧控制"))
            val controls = VoxFixUi.card(this, palette)
            controls.addView(VoxFixUi.switchRow(
                this, palette, "接收电脑剪贴板",
                desc = "文本进入系统剪贴板与键盘历史，标记「电脑」来源",
                checked = PcSyncPrefs.enabled(this),
                iconRes = R.drawable.ic_vx_clip
            ) { on ->
                PcSyncPrefs.setEnabled(this, on)
                updateImageAvailability()
                renderStatus()
            })
            imageRow = VoxFixUi.switchRow(
                this, palette, "接收电脑图片",
                desc = "电脑推送的图片进入剪贴板「图片」页",
                checked = PcSyncPrefs.images(this),
                enabled = PcSyncPrefs.enabled(this),
                iconRes = R.drawable.ic_vx_image,
                divider = false
            ) { on -> PcSyncPrefs.setImages(this, on) }
            imageSwitch = imageRow.tag as VxSwitch
            controls.addView(imageRow)
            col.addView(controls)

            col.addView(VoxFixUi.section(this, palette, "电脑端"))
            val help = VoxFixUi.card(this, palette)
            help.addView(VoxFixUi.head(this, palette, "运行伴生工具", "tools/copyq_phone_sync.bat"))
            help.addView(VoxFixUi.note(
                this, palette,
                "电脑端运行 tools/copyq_phone_sync.bat，走 USB 调试或无线调试均可。\n" +
                    "只有你在 CopyQ 里手动加到「手机文本」「手机图片」标签的条目才会推到手机；" +
                    "电脑上随手复制的内容不会进来。\n" +
                    "手机复制到电脑由 scrcpy 负责，这里的开关只影响电脑 → 手机。"
            ).apply { setPadding(dp(18), 0, dp(18), dp(14)) })
            col.addView(help)

            col.addView(VoxFixUi.hintRow(
                this, palette, R.drawable.ic_vx_plug,
                "开关立即生效：关闭后电脑仍在推送的内容会被直接丢弃，不再写入剪贴板或图片页。"
            ))
        })

        updateImageAvailability()
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun valueText(): TextView =
        VoxFixUi.text(this, "—", 13.5f, palette.textSecondary)

    private fun valueRow(
        title: String,
        desc: String,
        value: TextView,
        divider: Boolean = true
    ): LinearLayout = VoxFixUi.row(
        this, palette, title,
        desc = desc,
        trailing = value,
        divider = divider
    )

    /** 总开关关闭时图片开关跟着变灰：避免出现「图片开着但总开关拦掉」的误导状态。 */
    private fun updateImageAvailability() {
        val master = PcSyncPrefs.enabled(this)
        imageSwitch.isEnabled = master
        imageSwitch.alpha = if (master) 1f else 0.45f
        imageRow.alpha = if (master) 1f else 0.5f
    }

    private fun renderStatus() {
        val status = PcSyncPrefs.snapshot(this)
        val receiving = PcSyncPrefs.enabled(this)
        statusPill.text = when {
            !receiving -> "已关闭接收"
            status.connected -> "已连接"
            else -> "未连接"
        }
        val background = when {
            !receiving -> palette.warningSoft
            status.connected -> palette.successSoft
            else -> palette.surfaceSunken
        }
        statusPill.setTextColor(when {
            !receiving -> palette.warning
            status.connected -> palette.success
            else -> palette.textSecondary
        })
        statusPill.background = VoxFixTheme.round(background, dp(20).toFloat())
        transportValue.text = status.transportLabel
        addressValue.text = status.addresses.ifBlank { "未上报" }
        clipboardValue.text = when {
            !status.connected -> "—"
            status.clipboardReady -> "正常"
            else -> "未就绪"
        }
        beatValue.text = status.ageLabel
    }

    private fun dp(value: Int) = VoxFixTheme.dp(this, value)

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
