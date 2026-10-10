package local.voxfix.debug

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import java.io.File
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var engine: SpeechEngine? = null
    private var polisher: NativePolisher? = null
    private var original = ""
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var level: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var undo: Button
    @Volatile private var active = false
    @Volatile private var recordingRequested = false
    @Volatile private var visible = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(32 + bars.left, 32 + bars.top, 32 + bars.right, 32 + bars.bottom); insets
        }
        fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; layout.addView(this) }
        label("VoxFix 真机验证", 26f)
        Button(this).apply { text = "启用 VoxFix 输入法"; layout.addView(this); setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) } }
        Button(this).apply { text = "选择输入法"; layout.addView(this); setOnClickListener { getSystemService(InputMethodManager::class.java).showInputMethodPicker() } }
        Button(this).apply { text = "允许麦克风"; layout.addView(this); setOnClickListener { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) } }
        EditText(this).apply { hint = "在这里试用 VoxFix 输入法"; contentDescription = "VoxFix输入法试用框"; layout.addView(this) }
        label("中文语音输入 · 本地离线\n停止后自动整理，保留你的原意与语气。", 16f)
        status = label("正在加载离线模型…", 18f)
        level = label("未录音", 16f)
        start = Button(this).apply { text = "开始录音"; layout.addView(this); setOnClickListener { requestRecording() } }
        stop = Button(this).apply { text = "停止并补全标点"; isEnabled = false; layout.addView(this); setOnClickListener { stopRecording() } }
        undo = Button(this).apply {
            text = "撤销整理"; visibility = View.GONE; layout.addView(this)
            setOnClickListener { result.text = original; visibility = View.GONE; status.text = "已恢复识别原文" }
        }
        result = TextView(this).apply {
            text = state?.getString("transcript") ?: ""; textSize = 22f; setTextIsSelectable(true)
        }
        layout.addView(ScrollView(this).apply { addView(result) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        status.text = "可启用输入法 · 语音模型按需加载"
    }

    private fun ui(action: () -> Unit) = runOnUiThread { if (!isDestroyed) action() }
    private fun report(message: String, error: Throwable) {
        Log.e("VoxFix", message, error)
        ui { status.text = "$message：${error.message ?: error.javaClass.simpleName}"; level.text = "未录音" }
    }
    private fun requestRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else beginRecording()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == 1) status.text = if (grants.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            "麦克风权限已允许，请点击开始录音" else "麦克风权限被拒绝，请重新点击开始录音授权"
    }
    private fun beginRecording() {
        if (active) return
        active = true; recordingRequested = true; start.isEnabled = false; stop.isEnabled = true
        polisher?.prepare(); undo.visibility = View.GONE; original = ""
        status.text = "正在录音 · 停止后统一补标点"; result.text = ""
        worker.execute {
            try {
                if (engine == null) engine = SpeechEngine(assets)
                if (!visible) return@execute
                if (polisher == null) {
                    try { polisher = NativePolisher(File(filesDir, "polish/Qwen3.5-2B-Q4_K_M.gguf"), TextNormalizer(assets, File(filesDir, "wetext-0.1.8"))) }
                    catch (error: Throwable) { Log.e("VoxFix", "POLISH_RUNTIME_UNAVAILABLE", error) }
                }
                polisher?.prepare()
                Log.i("VoxFix", "CAPTURE_REQUESTED")
                val text = checkNotNull(engine).record(
                    { value -> ui { result.text = value } },
                    { rms ->
                        if (!visible) engine?.stop()
                        ui { level.text = "麦克风音量：$rms / 100" }
                    }, { recordingRequested && visible })
                Log.i("VoxFix", "CAPTURE_FINISHED characters=${text.length}")
                original = text
                if (text.isNotEmpty() && visible) {
                    ui { status.text = "正在整理…"; stop.isEnabled = false; level.text = "未录音" }
                    try {
                        val started = System.nanoTime()
                        val polished = checkNotNull(polisher).polish(text)
                        Log.i("VoxFix", "POLISH_FINISHED seconds=${(System.nanoTime() - started) / 1e9} changed=${polished != text}")
                        ui { result.text = polished; undo.visibility = if (polished != text) View.VISIBLE else View.GONE; status.text = if (polished != text) "已完成" else "已保留识别结果" }
                    } catch (error: Throwable) {
                        Log.e("VoxFix", "POLISH_FAILED_OR_CANCELLED", error)
                        ui { result.text = text; status.text = "整理暂未完成，已保留识别原文" }
                    }
                } else ui { status.text = if (text.isEmpty()) "录音结束，未识别到文字" else "已保留识别原文" }
            } catch (error: Throwable) { report("录音或识别失败，已收到文字保留", error) }
            finally {
                active = false; recordingRequested = false
                ui { start.isEnabled = true; stop.isEnabled = false; level.text = "未录音" }
            }
        }
    }
    private fun stopRecording() {
        recordingRequested = false
        if (active) { engine?.stop(); stop.isEnabled = false; status.text = "正在结束录音并补全标点…" }
    }
    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() {
        visible = false; stopRecording(); polisher?.cancel()
        worker.execute { engine?.release(); engine = null; polisher?.release(); polisher = null }
        super.onStop()
    }
    override fun onSaveInstanceState(state: Bundle) { state.putString("transcript", if (active && original.isNotEmpty()) original else result.text.toString()); super.onSaveInstanceState(state) }
    override fun onDestroy() {
        engine?.stop()
        polisher?.cancel()
        worker.execute { engine?.release(); engine = null; polisher?.release(); polisher = null }
        worker.shutdown()
        super.onDestroy()
    }
}
