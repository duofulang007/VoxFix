package local.voxfix.debug

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import local.voxfix.debug.ui.Palette
import local.voxfix.debug.ui.VoxFixTheme
import local.voxfix.debug.ui.VoxFixUi

/**
 * 「试用键盘」：普通用户唯一的试打入口。
 *
 * 只有输入框与一句提示，不含录音、电平、识别结果等调试内容——那些在开发者工具里。
 * 真机脚本依赖这里的输入框（contentDescription）与 `voxfix.autofocus` extra。
 */
class MainActivity : Activity() {
    private lateinit var palette: Palette
    private lateinit var field: EditText

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        palette = VoxFixTheme.palette(this)
        field = VoxFixUi.field(this, palette, "在这里试用 VoxFix 输入法", multiline = true).apply {
            contentDescription = "VoxFix输入法试用框"
            minLines = 4
        }

        setContentView(VoxFixUi.screen(this, palette, "试用键盘", onBack = { finish() }) { col ->
            val card = VoxFixUi.card(this, palette)
            card.addView(VoxFixUi.head(this, palette, "试打", "键盘已就绪"))
            card.addView(field, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(148)
            ).apply {
                leftMargin = dp(16); rightMargin = dp(16)
            })
            card.addView(VoxFixUi.note(
                this, palette,
                "长按空格说话，松手定稿并自动补标点。识别与语音都在本机完成。"
            ).apply { setPadding(dp(16), dp(8), dp(16), dp(12)) })
            col.addView(card)

            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                val mic = VoxFixUi.card(this, palette)
                mic.addView(VoxFixUi.row(
                    this, palette, "允许麦克风",
                    desc = "语音输入首次使用需要授权",
                    value = "未授权",
                    valueColor = palette.warning,
                    chevron = true, divider = false
                ) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                })
                col.addView(mic)
            }
        })

        // 首页向导第 3 步带 voxfix.autofocus 进来时自动聚焦试用框，键盘直接就绪；
        // 真机脚本 `am start` 不带 extra，默认路径不变。
        if (intent.getBooleanExtra("voxfix.autofocus", false)) {
            field.requestFocus()
            field.post {
                getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == 1) recreate()
    }

    private fun dp(value: Int) = VoxFixTheme.dp(this, value)
}
