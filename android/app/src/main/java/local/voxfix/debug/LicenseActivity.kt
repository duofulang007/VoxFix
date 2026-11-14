package local.voxfix.debug

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import local.voxfix.debug.ui.Palette
import local.voxfix.debug.ui.VoxFixTheme
import local.voxfix.debug.ui.VoxFixUi

/**
 * 开源许可阅读页：列表 → 全文。
 *
 * 资产路径来源（android/app/build.gradle.kts stageKeyboard / stagePinyin）：
 *  - stageKeyboard 目标根是 keyboardAssets/keyboard，许可随键盘资源打包为
 *    `keyboard/licenses/<name>/…`，rime-ice 词库许可在 `keyboard/vendor/rime-ice/`；
 *  - stagePinyin 是独立 assets srcDir，拼音许可在 `licenses/pinyin/`（无 keyboard/ 前缀）。
 * meme 目录只有 UPSTREAM.md；snow-region 目录内容不固定，读取时列出目录内全部文件。
 */
class LicenseActivity : Activity() {

    private data class Entry(val title: String, val dir: String, val note: String)

    private val entries = listOf(
        Entry("SenseVoice", "keyboard/licenses/sensevoice", "语音识别模型（FunASR / 阿里达摩院）"),
        Entry("sherpa-onnx", "keyboard/licenses/sherpa", "语音推理运行时（k2-fsa）"),
        Entry("标点恢复模型", "keyboard/licenses/punctuation", "CT-Transformer 定稿标点"),
        Entry("表情图源", "keyboard/licenses/meme", "表情搜索上游来源说明"),
        Entry("llama.cpp", "keyboard/licenses/llama", "离线翻译推理引擎"),
        Entry("Hy-MT2 翻译模型", "keyboard/licenses/hy-mt2", "腾讯混元翻译（Apache-2.0）"),
        Entry("Snow Region", "keyboard/licenses/snow-region", "长截屏拼接（Snow Shot 派生）"),
        Entry("librime 拼音", "licenses/pinyin", "拼音引擎与词库（rime）"),
        Entry("rime-ice 雾凇拼音", "keyboard/vendor/rime-ice", "拼音词库方案")
    )

    private lateinit var palette: Palette
    private var showingDetail: Entry? = null

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
        setContentView(root)
        showList(root)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 全文页返回列表，列表页才退出。
        if (showingDetail != null) {
            showingDetail = null
            showList(findViewById(android.R.id.content) as? LinearLayout ?: return)
        } else {
            super.onBackPressed()
        }
    }

    private fun showList(root: LinearLayout) {
        root.removeAllViews()
        val scroll = ScrollView(this)
        val col = VoxFixUi.page(this, palette)
        col.addView(VoxFixUi.head(this, palette, "开源许可", "点击条目查看全文"))
        val card = VoxFixUi.card(this, palette)
        entries.forEachIndexed { index, entry ->
            card.addView(VoxFixUi.row(this, palette, entry.title,
                desc = entry.note,
                chevron = true,
                divider = index != 0) { openDetail(root, entry) })
        }
        col.addView(card)
        col.addView(VoxFixUi.note(this, palette,
            "许可全文随 APK 资源分发；如条目显示缺失，请核对构建清单。"))
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun openDetail(root: LinearLayout, entry: Entry) {
        showingDetail = entry
        root.removeAllViews()
        val scroll = ScrollView(this)
        val col = VoxFixUi.page(this, palette)
        col.addView(VoxFixUi.head(this, palette, entry.title, entry.note))
        val files = listFiles(entry.dir)
        if (files.isEmpty()) {
            col.addView(VoxFixUi.note(this, palette, "许可文件缺失：${entry.dir}"))
        } else {
            for (path in files) {
                col.addView(VoxFixUi.text(this, path.substringAfterLast('/'), 13f, palette.textPrimary, medium = true)
                    .apply { setPadding(0, dp(10), 0, 0) })
                col.addView(TextView(this).apply {
                    text = runCatching { assets.open(path).readBytes().decodeToString() }
                        .getOrDefault("(读取失败)")
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(palette.textSecondary)
                    setPadding(0, dp(6), 0, dp(6))
                })
            }
        }
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun listFiles(path: String): List<String> = runCatching {
        assets.list(path)?.map { "$path/$it" }?.sorted() ?: emptyList()
    }.getOrDefault(emptyList())

    private fun dp(value: Int) = VoxFixTheme.dp(this, value)
}
