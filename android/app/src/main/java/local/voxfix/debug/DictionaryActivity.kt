package local.voxfix.debug

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import local.voxfix.debug.ui.Palette
import local.voxfix.debug.ui.VoxFixTheme
import local.voxfix.debug.ui.VoxFixUi
import java.io.File
import java.nio.charset.CodingErrorAction

/**
 * The desktop personal-dictionary module on the phone: pick a word, list the ways it gets
 * misheard, one per line. Same file, same wording, same rules as `VoxFix Desktop`.
 *
 * Desktop lays this out as two panes on a 926×680 window. A phone is ~393 dp wide, so the
 * two panes become list → detail; every label, count, guard and status line is kept as is.
 */
class DictionaryActivity : Activity() {
    private companion object {
        const val REQUEST_EXPORT = 11
        const val REQUEST_IMPORT = 12

        /** 词典是纯文本，2MiB 足够几千条；超过基本是选错了文件。 */
        const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
    }

    private lateinit var palette: Palette

    private lateinit var file: File
    private var dictionary = VoiceDictionary()
    private var loadedText: String? = null
    private var loadFailed = false
    private var dirty = false
    private var current = -1
    private var query = ""
    private var deleted: Pair<VoiceDictionary.Entry, Int>? = null
    private var deletedUntil = 0L
    private var detail: View? = null

    private lateinit var content: LinearLayout
    private lateinit var counts: TextView
    private lateinit var search: EditText
    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var saveButton: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val expireUndo = Runnable {
        deleted = null
        content.findViewWithTag<View>("dictionary-undo")?.let { content.removeView(it) }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        palette = VoxFixTheme.palette(this)
        file = VoiceDictionaryStore.file(this)
        counts = VoxFixUi.note(this, palette, "")
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = VoxFixUi.note(this, palette, "").apply { maxLines = 2 }
        search = VoxFixUi.field(this, palette, "搜索词语或错法").apply {
            setSingleLine()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { query = text.toString().trim(); renderList() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val root = VoxFixUi.screenBody(
            this, palette, "语音纠错词典",
            onBack = { if (detail != null) showList() else close() },
            action = "保存",
            onAction = { if (dirty) save() }
        ) { body ->
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(16), dp(10))
            }
            column.addView(counts)
            column.addView(search, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8); bottomMargin = dp(8) })
            val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
            column.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            column.addView(status)
            body.addView(column, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        setContentView(root)
        saveButton = VoxFixUi.actionView(root) ?: TextView(this)

        load()
        // Only reachable from our own code (exported=false): open a word directly for screenshots.
        intent?.getIntExtra("index", -1)?.takeIf { it in dictionary.entries.indices }?.let { showDetail(it) }
    }

    private fun close() {
        if (dirty) {
            AlertDialog.Builder(this).setTitle("还有未保存的修改，保存吗？")
                .setNegativeButton("放弃") { _, _ -> finish() }
                .setPositiveButton("保存") { _, _ -> if (save()) finish() }
                .setNeutralButton("继续编辑", null).show()
            return
        }
        finish()
    }

    @Deprecated("Back press asks the same question as the desktop close button")
    override fun onBackPressed() {
        if (detail != null) { showList(); return }
        close()
    }

    private fun load() {
        loadedText = if (file.isFile) runCatching { file.readText(Charsets.UTF_8) }.getOrNull() else null
        loadFailed = loadedText == null && file.exists()
        dictionary = if (loadedText == null) VoiceDictionary() else runCatching {
            VoiceDictionary.parse(loadedText!!)
        }.getOrElse { loadFailed = true; VoiceDictionary() }
        dirty = false
        showList()
        status.text = if (loadFailed) "词典读取失败，请先修复文件" else if (loadFailed.not() && loadedText == null) "还没有词典文件，添加第一个词语即可创建" else "词典已载入 · 保存后下一句生效"
    }

    private fun showList() {
        detail = null
        current = -1
        content.removeAllViews()
        content.addView(listBox)
        search.visibility = View.VISIBLE
        renderList()
        updateCounts()
        saveButton.visibility = View.VISIBLE
        refreshSave()
        renderUndo()
    }

    private fun refreshSave() {
        saveButton.isEnabled = dirty
        saveButton.alpha = if (dirty) 1f else 0.4f
    }

    private fun renderList() {
        listBox.removeAllViews()
        val needle = query.lowercase()
        val shown = dictionary.entries.withIndex().filter { (_, entry) ->
            needle.isEmpty() || entry.word.lowercase().contains(needle) || entry.variants.any { it.lowercase().contains(needle) }
        }
        if (dictionary.entries.isEmpty()) {
            listBox.addView(hint("还没有词语。添加一个词，再写下它常被识别成什么。"))
        } else if (shown.isEmpty()) {
            listBox.addView(hint("没有匹配「$query」的词语"))
        }
        val card = VoxFixUi.card(this, palette)
        shown.forEachIndexed { position, (index, entry) ->
            card.addView(row(entry, index, divider = position != 0))
        }
        card.addView(addRow())
        listBox.addView(card)
        // 只在完整列表下给备份入口：搜索态插进来会打断「找词」的动线。
        if (query.isEmpty()) listBox.addView(backupCard())
    }

    /** 语音词典的换机通道：与桌面同格式，导出即可在桌面版继续编辑。 */
    private fun backupCard(): View {
        val card = VoxFixUi.card(this, palette)
        card.addView(VoxFixUi.head(this, palette, "备份与恢复", "只含词语与错法"))
        card.addView(VoxFixUi.row(
            this, palette, "导出词典",
            desc = "与桌面版同一份 JSON，换机或重装后导入",
            iconRes = R.drawable.ic_vx_download, chevron = true
        ) { exportDictionary() })
        card.addView(VoxFixUi.row(
            this, palette, "导入词典",
            desc = "与现有词典合并，不清空、不覆盖已有错法",
            iconRes = R.drawable.ic_vx_upload, chevron = true, divider = false
        ) { importDictionary() })
        return card
    }

    private fun exportDictionary() {
        if (dirty) { status.text = "请先保存当前修改，再导出"; return }
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "voxfix-voice-dictionary.json"),
            REQUEST_EXPORT
        )
    }

    private fun importDictionary() {
        if (dirty) { status.text = "请先保存当前修改，再导入"; return }
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream")),
            REQUEST_IMPORT
        )
    }

    @Deprecated("Uses the platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) { status.text = "已取消，词典未改动。"; return }
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_EXPORT -> runCatching {
                val bytes = dictionary.serialize().toByteArray(Charsets.UTF_8)
                contentResolver.openOutputStream(uri, "wt").use { output ->
                    checkNotNull(output) { "无法打开导出文件" }
                    output.write(bytes)
                }
                bytes.size
            }.onSuccess {
                status.text = "已导出 ${dictionary.entries.size} 个词语 · 桌面版可直接打开该文件"
            }.onFailure { status.text = "导出未完成：${it.message ?: "写入失败"}。本机词典未改动。" }
            REQUEST_IMPORT -> runCatching {
                val bytes = contentResolver.openInputStream(uri).use { input ->
                    checkNotNull(input) { "无法读取词典文件" }
                    val buffer = ByteArray(8192)
                    val output = java.io.ByteArrayOutputStream()
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        check(output.size() + size <= MAX_IMPORT_BYTES) { "词典文件超过 2MiB，请确认选对了文件" }
                        output.write(buffer, 0, size)
                    }
                    output.toByteArray()
                }
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                mergeDictionary(VoiceDictionary.parse(text))
            }.onSuccess { (added, merged) ->
                dirty = true
                renderList(); updateCounts(); refreshSave()
                status.text = "已合并 $added 个新词、$merged 条错法 · 点右上角保存后下一句生效"
            }.onFailure { status.text = "导入未完成：${it.message ?: "读取失败"}。本机词典未改动。" }
            else -> Unit
        }
    }

    /** 合并规则集中在 VoiceDictionary.mergeInto：本地备份恢复与手动导入必须一致。 */
    private fun mergeDictionary(incoming: VoiceDictionary): Pair<Int, Int> =
        VoiceDictionary.mergeInto(dictionary, incoming)

    private fun row(entry: VoiceDictionary.Entry, index: Int, divider: Boolean): LinearLayout =
        VoxFixUi.row(
            this, palette, entry.word,
            value = if (entry.variants.isEmpty()) "仅救援" else "${entry.variants.size} 种错法",
            chevron = true,
            divider = divider
        ) { showDetail(index) }

    private fun addRow(): LinearLayout = VoxFixUi.row(
        this, palette, "＋ 添加词语",
        desc = "正确词语，再写它常被识别成什么",
        chevron = true,
        divider = true
    ) { addWord() }

    private fun showDetail(index: Int) {
        if (index !in dictionary.entries.indices) return
        current = index
        val entry = dictionary.entries[index]
        content.removeAllViews()
        saveButton.visibility = View.VISIBLE
        search.visibility = View.GONE
        refreshSave()
        content.addView(VoxFixUi.head(this, palette, entry.word, scopeOf(entry)))

        val variants = VoxFixUi.field(this, palette, "每行一项，例如：col death", multiline = true).apply {
            minLines = 5
            setText(entry.variants.joinToString("\n"))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (current != index) return
                    entry.variants = VoiceDictionary.cleanVariants(text.toString(), paste = false).toMutableList()
                    dirty = true; saveButton.isEnabled = true
                    refreshSave()
                    status.text = "有未保存的修改"; updateCounts()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        content.addView(label("容易识别成", "每行一项 · 粘贴自动整理"))
        content.addView(variants, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        val previewInput = VoxFixUi.field(this, palette, "输入一句文字试试替换").apply { setSingleLine() }
        val previewOut = TextView(this).apply { textSize = 14f; setTextColor(palette.textPrimary); setPadding(0, dp(8), 0, 0) }
        val previewHits = TextView(this).apply { textSize = 11f; setTextColor(palette.textTertiary); setPadding(0, dp(4), 0, 0) }
        content.addView(label("文字预览", ""))
        val previewRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        previewRow.addView(previewInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        previewRow.addView(VoxFixUi.textButton(this, palette, "预览") {
            val text = previewInput.text.toString()
            if (text.isEmpty()) { previewHits.text = "先输入一句想检查的文字"; return@textButton }
            val result = runCatching { DesktopVoiceDictionary.parse(dictionary.serialize()) }.getOrElse { DesktopVoiceDictionary() }
            if (result.entries.isEmpty()) { previewHits.text = "未命中错法，文字保持原样"; previewOut.text = text; return@textButton }
            val replaced = result.correct(text, DesktopVoiceRules.chinese(text))
            previewOut.text = replaced
            val hits = mutableListOf<String>()
            for (rule in result.entries) for (variant in rule.from) {
                val pattern = Regex("(?i)" + if (variant.all { it.code < 128 }) "(?<![A-Za-z0-9_])${Regex.escape(variant)}(?![A-Za-z0-9_])" else Regex.escape(variant))
                if (pattern.containsMatchIn(text) && !hits.contains("$variant → ${rule.to}")) hits.add("$variant → ${rule.to}")
            }
            previewHits.text = if (hits.isEmpty()) "未命中错法，文字保持原样" else hits.joinToString("；")
        }.apply { setPadding(dp(12), dp(8), 0, dp(8)) })
        content.addView(previewRow)
        content.addView(previewOut)
        content.addView(previewHits)

        content.addView(VoxFixUi.textButton(this, palette, "删除这个词语", palette.danger) { confirmDelete(index) }.apply {
            setPadding(0, dp(18), 0, dp(6))
        })
        detail = content
    }

    private fun addWord() {
        val input = VoxFixUi.field(this, palette, "正确词语，如：Codex").apply { setSingleLine() }
        val dialog = AlertDialog.Builder(this).setTitle("添加词语").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("添加", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val word = input.text.toString().trim()
                if (word.isEmpty()) { input.error = "先填一个词"; return@setOnClickListener }
                val existing = dictionary.indexOf(word)
                if (existing >= 0) { dialog.dismiss(); showDetail(existing); Toast.makeText(this, "这个词已经在列表里", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                dictionary.entries.add(VoiceDictionary.Entry(word, mutableListOf()))
                deleted = null
                dirty = true
                dialog.dismiss()
                showDetail(dictionary.entries.lastIndex)
                status.text = "已添加 $word，记得保存"
            }
        }
        dialog.show()
    }

    private fun confirmDelete(index: Int) {
        val word = dictionary.entries[index].word
        AlertDialog.Builder(this).setTitle("删除这个词语？")
            .setMessage("将移除「$word」及其纠错写法。\n删除后 10 秒内可撤回，保存后生效。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val entry = dictionary.entries.removeAt(index)
                deleted = entry to index
                deletedUntil = android.os.SystemClock.uptimeMillis() + 10_000
                dirty = true
                showList()
                status.text = "已移除 $word，记得保存"
                handler.removeCallbacks(expireUndo)
                handler.postDelayed(expireUndo, 10_000L)
            }.show()
    }

    private fun renderUndo() {
        val pending = deleted ?: return
        val left = ((deletedUntil - android.os.SystemClock.uptimeMillis() + 999) / 1000).coerceAtLeast(0)
        if (left <= 0) { expireUndo.run(); return }
        content.findViewWithTag<TextView>("dictionary-undo-countdown")?.let {
            it.text = "撤回 · $left 秒"
            handler.removeCallbacks(undoTick)
            handler.postDelayed(undoTick, 1000)
            return
        }
        val bar = LinearLayout(this).apply {
            tag = "dictionary-undo"
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.surfaceSunken); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        bar.addView(TextView(this).apply { text = "已删除「${pending.first.word}」"; textSize = 12f; setTextColor(palette.textTertiary) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(VoxFixUi.textButton(this, palette, "撤回 · $left 秒", palette.textTertiary) { undoDelete() }
            .apply { tag = "dictionary-undo-countdown" })
        content.addView(bar, 0)
        handler.removeCallbacks(undoTick)
        handler.postDelayed(undoTick, 1000)
    }

    private val undoTick = Runnable { if (deleted != null && detail == null) renderUndo() }

    private fun undoDelete() {
        val pending = deleted ?: return
        if (android.os.SystemClock.uptimeMillis() >= deletedUntil) { deleted = null; showList(); return }
        if (dictionary.indexOf(pending.first.word) >= 0) { status.text = "已有同名词语，无法覆盖撤回"; return }
        val index = pending.second.coerceIn(0, dictionary.entries.size)
        dictionary.entries.add(index, pending.first)
        handler.removeCallbacks(expireUndo)
        deleted = null
        dirty = true
        showList()
        status.text = "已撤回删除，记得保存"
    }

    private fun save(): Boolean {
        dictionary.conflicts()?.let { status.text = it; return false }
        if (loadFailed) { status.text = "保存失败：原词典读取失败，已阻止覆盖"; return false }
        val existing = if (file.isFile) runCatching { file.readText(Charsets.UTF_8) }.getOrNull() else null
        if (existing != loadedText) { status.text = "保存失败：词典已被外部修改，请重新打开后再编辑"; return false }
        return try {
            val body = dictionary.serialize()
            VoiceDictionaryStore.write(file, dictionary)
            VoiceDictionaryStore.invalidate()
            loadedText = body
            dirty = false
            refreshSave()
            status.text = if (dictionary.entries.size > VoiceDictionary.HOTWORD_LIMIT)
                "词典已保存 · 末尾 ${dictionary.entries.size - VoiceDictionary.HOTWORD_LIMIT} 词仅用于错法纠正"
            else "词典已保存 · 下一句生效"
            updateCounts()
            true
        } catch (error: Exception) {
            status.text = "保存失败：${error.message ?: "无法写入"}"
            false
        }
    }

    private fun updateCounts() {
        val rules = dictionary.ruleCount()
        counts.text = "词语 ${dictionary.entries.size}  ·  纠错 $rules  ·  救援 ${dictionary.rescueCount()}/${VoiceDictionary.HOTWORD_LIMIT}" +
            (if (dictionary.entries.size > VoiceDictionary.HOTWORD_LIMIT)
                "\n末尾 ${dictionary.entries.size - VoiceDictionary.HOTWORD_LIMIT} 词不参与救援，错法纠正仍生效" else "")
    }

    private fun scopeOf(entry: VoiceDictionary.Entry): String = when {
        entry.variants.isEmpty() -> "未填错法 · 仅加入救援词表"
        entry.context == "zh" -> "旧规则仅中文/混说 · ${entry.variants.size} 种错法"
        else -> "中文、纯英文、混说 · ${entry.variants.size} 种错法"
    }

    private fun label(title: String, hint: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(6))
        addView(TextView(this@DictionaryActivity).apply {
            text = title; textSize = 13f; setTextColor(palette.textTertiary)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (hint.isNotEmpty()) addView(TextView(this@DictionaryActivity).apply {
            text = hint; textSize = 11f; setTextColor(palette.textDisabled)
        })
    }

    private fun hint(text: String) = VoxFixUi.note(this, palette, text).apply {
        setPadding(0, dp(12), 0, dp(12))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density)

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
