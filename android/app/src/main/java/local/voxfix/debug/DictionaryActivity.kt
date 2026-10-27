package local.voxfix.debug

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * The desktop personal-dictionary module on the phone: pick a word, list the ways it gets
 * misheard, one per line. Same file, same wording, same rules as `VoxFix Desktop`.
 *
 * Desktop lays this out as two panes on a 926×680 window. A phone is ~393 dp wide, so the
 * two panes become list → detail; every label, count, guard and status line is kept as is.
 */
class DictionaryActivity : Activity() {
    private val face = Color.rgb(251, 250, 248)
    private val ink = Color.rgb(20, 25, 31)
    private val dim = Color.rgb(102, 106, 113)
    private val field = Color.rgb(246, 245, 243)
    private val edge = Color.rgb(220, 219, 216)
    private val danger = Color.rgb(179, 38, 30)

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
    private lateinit var title: TextView
    private lateinit var counts: TextView
    private lateinit var search: EditText
    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var saveButton: TextView
    private lateinit var backButton: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val expireUndo = Runnable {
        deleted = null
        content.findViewWithTag<View>("dictionary-undo")?.let { content.removeView(it) }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        file = VoiceDictionaryStore.file(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(face)
            setPadding(dp(20), dp(14), dp(20), dp(10))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(dp(20) + bars.left, dp(14) + bars.top, dp(20) + bars.right, dp(10) + bars.bottom); insets
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        backButton = textButton("‹ 返回", dim) { close() }
        header.addView(backButton)
        title = TextView(this).apply {
            text = "个人词典"; textSize = 20f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), 0, 0, 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        saveButton = textButton("保存", Color.rgb(52, 130, 255)) { save() }
        header.addView(saveButton)
        root.addView(header)

        counts = TextView(this).apply { textSize = 11f; setTextColor(dim); setPadding(0, dp(6), 0, 0) }
        root.addView(counts)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        status = TextView(this).apply { textSize = 12f; setTextColor(dim); setPadding(0, dp(8), 0, 0); maxLines = 2 }
        root.addView(status)
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        window.statusBarColor = face
        window.navigationBarColor = face
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        search = EditText(this).apply {
            hint = "搜索词语或错法"; textSize = 14f; setSingleLine()
            setTextColor(ink); setHintTextColor(dim); setBackgroundColor(field)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { query = text.toString().trim(); renderList() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
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
        title.text = "个人词典"
        backButton.text = "‹ 返回"
        backButton.setOnClickListener { close() }
        content.removeAllViews()
        content.addView(search)
        content.addView(listBox)
        renderList()
        updateCounts()
        saveButton.visibility = View.VISIBLE
        renderUndo()
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
        for ((index, entry) in shown) listBox.addView(row(entry, index))
        listBox.addView(addRow(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    private fun row(entry: VoiceDictionary.Entry, index: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(13), dp(12), dp(13))
            background = rounded(Color.WHITE, 12f, edge)
            setOnClickListener { showDetail(index) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(6) }
        }
        row.addView(TextView(this).apply { text = entry.word; textSize = 15f; setTextColor(ink) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = if (entry.variants.isEmpty()) "仅救援" else "${entry.variants.size} 种错法"
            textSize = 11f; setTextColor(dim)
        })
        return row
    }

    private fun addRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        setPadding(dp(12), dp(13), dp(12), dp(13))
        background = rounded(field, 12f, edge)
        addView(TextView(this@DictionaryActivity).apply { text = "＋ 添加词语"; textSize = 14f; setTextColor(dim) })
        setOnClickListener { addWord() }
    }

    private fun showDetail(index: Int) {
        if (index !in dictionary.entries.indices) return
        current = index
        val entry = dictionary.entries[index]
        title.text = entry.word
        content.removeAllViews()
        saveButton.visibility = View.VISIBLE

        val scope = TextView(this).apply {
            text = scopeOf(entry); textSize = 11f; setTextColor(dim); setPadding(0, dp(4), 0, dp(10))
        }
        content.addView(scope)

        val variants = EditText(this).apply {
            hint = "每行一项，例如：col death"; textSize = 15f
            setTextColor(ink); setHintTextColor(dim); setBackgroundColor(field)
            gravity = Gravity.TOP; minLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setText(entry.variants.joinToString("\n"))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (current != index) return
                    entry.variants = VoiceDictionary.cleanVariants(text.toString(), paste = false).toMutableList()
                    dirty = true; saveButton.isEnabled = true
                    status.text = "有未保存的修改"; updateCounts()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        content.addView(label("容易识别成", "每行一项 · 粘贴自动整理"))
        content.addView(variants, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        val previewInput = EditText(this).apply {
            hint = "输入一句文字试试替换"; textSize = 14f; setSingleLine()
            setTextColor(ink); setHintTextColor(dim); setBackgroundColor(field)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val previewOut = TextView(this).apply { textSize = 14f; setTextColor(ink); setPadding(0, dp(8), 0, 0) }
        val previewHits = TextView(this).apply { textSize = 11f; setTextColor(dim); setPadding(0, dp(4), 0, 0) }
        content.addView(label("文字预览", ""))
        val previewRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        previewRow.addView(previewInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        previewRow.addView(textButton("预览", dim) {
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

        content.addView(textButton("删除这个词语", danger) { confirmDelete(index) }.apply {
            setPadding(0, dp(18), 0, 0)
        })
        detail = content
        backTo("‹ 词语列表") { showList() }
        saveButton.visibility = View.VISIBLE
    }

    private fun backTo(label: String, action: () -> Unit) {
        backButton.text = label
        backButton.setOnClickListener { action() }
    }

    private fun addWord() {
        val input = EditText(this).apply { hint = "正确词语，如：Codex"; textSize = 15f; setSingleLine() }
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
            setBackgroundColor(field); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        bar.addView(TextView(this).apply { text = "已删除「${pending.first.word}」"; textSize = 12f; setTextColor(dim) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(textButton("撤回 · $left 秒", dim) { undoDelete() }.apply { tag = "dictionary-undo-countdown" })
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

    private fun render() {
        if (detail != null && current >= 0) showDetail(current) else showList()
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
        addView(TextView(this@DictionaryActivity).apply { text = title; textSize = 13f; setTextColor(dim) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (hint.isNotEmpty()) addView(TextView(this@DictionaryActivity).apply { text = hint; textSize = 11f; setTextColor(dim) })
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(dim); setPadding(0, dp(12), 0, dp(12))
    }

    private fun textButton(text: String, color: Int, action: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(color); setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { action() }
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), stroke)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density)

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
