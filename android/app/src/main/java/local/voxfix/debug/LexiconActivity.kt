package local.voxfix.debug

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import java.util.concurrent.Executors

/** System document picker keeps personal vocabulary out of shared app folders. */
class LexiconActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private var format = "json"
    private var running = false
    private val buttons = mutableListOf<Button>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        format = state?.getString("format") ?: "json"
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(32 + bars.left, 32 + bars.top, 32 + bars.right, 32 + bars.bottom); insets
        }
        layout.addView(TextView(this).apply { text = "个人词库与迁移"; textSize = 24f })
        layout.addView(TextView(this).apply {
            text = "选词习惯默认保存在本机，长期保留。完整备份用于 VoxFix 换机恢复；通用文本包含词语、空格分隔拼音和词频，其他输入法可能需要转换格式。仅有简拼的记录保留在完整备份中。导入会合并，不清空已有词库。"; textSize = 16f
        })
        status = TextView(this).apply { textSize = 16f }; layout.addView(status)
        fun button(label: String, action: () -> Unit) {
            Button(this).apply { text = label; layout.addView(this); buttons.add(this); setOnClickListener { if (!running) action() } }
        }
        button("添加个人词语") { addVoiceWord() }
        button("管理语音词典") { manageVoiceWords() }
        button("导出完整备份") { export("json") }
        button("导出通用词库") { export("tsv") }
        button("合并导入词库") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "text/tab-separated-values", "application/octet-stream")), 2)
        }
        button("返回") { finish() }
        setContentView(ScrollView(this).apply { addView(layout) })
        work { store -> "已保存 ${store.count()} 条拼音词条、${store.voiceWords().size} 条语音词语。语音改字逐步学习，手动添加立即优先。备份不包含录音、剪贴板或正文。" }
    }
    private fun export(value: String) {
        format = value
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (value == "json") "application/json" else "text/tab-separated-values")
            .putExtra(Intent.EXTRA_TITLE, "voxfix-personal-lexicon-${System.currentTimeMillis()}.$value"), 1)
    }
    private fun work(action: (PersonalLexicon) -> String) {
        running = true; buttons.forEach { it.isEnabled = false }
        worker.execute {
            val message = try { PersonalLexicon(this).use(action) } catch (error: Exception) { "操作未完成：${error.message ?: "读写失败"}。已有词库保留。" }
            runOnUiThread { if (!isDestroyed) { running = false; buttons.forEach { it.isEnabled = true }; status.text = message } }
        }
    }
    @Deprecated("Uses the platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) { status.text = "已取消，词库未改动。"; return }
        val uri = data?.data ?: return
        val selectedFormat = format
        work { store ->
            if (requestCode == 1) {
                check(store.migrationComplete()) { "请先打开一次 VoxFix 键盘，完成已有词库迁移" }
                val text = if (selectedFormat == "json") store.exportBackup() else store.exportTsv()
                require(text.toByteArray(Charsets.UTF_8).size <= PersonalLexicon.MAX_BYTES) { "词库导出超过32MiB，需要分批迁移" }
                contentResolver.openOutputStream(uri, "wt").use { output -> checkNotNull(output) { "无法打开导出文件" }; output.write(text.toByteArray(Charsets.UTF_8)) }
                if (selectedFormat == "json") "完整个人词库备份已保存。" else "通用词库已保存；无法确认完整拼音的记录仅包含在完整备份中。"
            } else if (requestCode == 2) {
                val bytes = contentResolver.openInputStream(uri).use { input ->
                    checkNotNull(input) { "无法读取词库文件" }
                    val buffer = ByteArray(8192); val output = java.io.ByteArrayOutputStream()
                    while (true) { val size = input.read(buffer); if (size < 0) break; require(output.size() + size <= PersonalLexicon.MAX_BYTES) { "词库文件超过32MiB，请分批导入" }; output.write(buffer, 0, size) }
                    output.toByteArray()
                }
                require(bytes.size <= PersonalLexicon.MAX_BYTES) { "词库文件超过32MiB，请分批导入" }
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                store.importBackup(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString())
                "词库已合并，现有 ${store.count()} 条。重复导入不累加词频；返回键盘即可使用。"
            } else error("未知文件操作")
        }
    }
    override fun onSaveInstanceState(state: Bundle) { state.putString("format", format); super.onSaveInstanceState(state) }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    private fun addVoiceWord(word: String = "", code: String = "") {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 8) }
        val target = EditText(this).apply { hint = "正确词语，如：张玥、VoxFix"; setSingleLine(); setText(word); fields.addView(this) }
        if (word.isNotEmpty()) target.isEnabled = false
        val reading = EditText(this).apply { hint = "完整拼音（可选），如：zhang yue"; setSingleLine(); setText(code); fields.addView(this) }
        fields.addView(TextView(this).apply { text = "词语用于语音优先识别；填写准确拼音后，也能用于拼音候选。不会把同音词全部强制替换。" })
        val dialog = AlertDialog.Builder(this).setTitle("添加个人词语").setView(fields).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = target.text.toString().trim(); val pinyin = reading.text.toString().trim()
                try {
                    VoiceLexicon.validateWord(value)
                    if (pinyin.isNotEmpty()) PersonalLexicon.validate(org.json.JSONArray().put(org.json.JSONObject().put("key", "p:" + pinyin.replace(" ", "")).put("word", value).put("code", pinyin).put("hits", 1)))
                    work { store -> store.addVoiceWord(value, pinyin); "已保存，下一次语音优先考虑此词。" }
                    dialog.dismiss()
                } catch (error: Exception) { target.error = error.message ?: "词语或拼音无效" }
            }
        }
        dialog.show()
    }
    private fun manageVoiceWords() {
        worker.execute {
            val words = try { PersonalLexicon(this).use { it.voiceWords() } } catch (_: Exception) { runOnUiThread { status.text = "词典读取失败，已有数据保留。" }; return@execute }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (words.isEmpty()) { status.text = "还没有语音词语，可以手动添加；听写后用拼音改正同音错字，也会逐步学习。"; return@runOnUiThread }
                AlertDialog.Builder(this).setTitle("语音词典").setItems(words.map { "${it.word} · ${if (it.manual) "手动优先" else "纠正${it.hits}次"}" }.toTypedArray()) { _, i ->
                    val row = words[i]
                    AlertDialog.Builder(this).setTitle(row.word).setItems(arrayOf("编辑／设为手动优先", "忘记此词的语音偏好")) { _, action ->
                        if (action == 0) addVoiceWord(row.word, row.code)
                        else AlertDialog.Builder(this).setTitle("忘记语音偏好？").setMessage("移除“${row.word}”的语音词条和相关纠正记忆，保留已有拼音选词记录。")
                            .setNegativeButton("取消", null).setPositiveButton("忘记") { _, _ -> work { store -> store.forgetVoiceWord(row.word); "已忘记此词的语音偏好。" } }.show()
                    }.setNegativeButton("返回", null).show()
                }.setNegativeButton("关闭", null).show()
            }
        }
    }
}
