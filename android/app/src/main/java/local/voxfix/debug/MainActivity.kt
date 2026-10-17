package local.voxfix.debug

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var engine: SpeechEngine? = null
    @Volatile private var microphoneCapture: PcmCapture? = null
    private var original = ""
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var level: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
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
        Button(this).apply { text = "系统选择输入法"; layout.addView(this); setOnClickListener { getSystemService(InputMethodManager::class.java).showInputMethodPicker() } }
        Button(this).apply { text = "个人词库与迁移"; layout.addView(this); setOnClickListener { startActivity(Intent(this@MainActivity, LexiconActivity::class.java)) } }
        Button(this).apply { text = "允许麦克风"; layout.addView(this); setOnClickListener { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) } }
        EditText(this).apply { hint = "在这里试用 VoxFix 输入法"; contentDescription = "VoxFix输入法试用框"; layout.addView(this) }
        label("中文语音输入 · 本地离线\n停止后自动补全标点。", 16f)
        status = label("正在加载离线模型…", 18f)
        level = label("未录音", 16f)
        start = Button(this).apply { text = "开始录音"; layout.addView(this); setOnClickListener { requestRecording() } }
        stop = Button(this).apply { text = "停止并补全标点"; isEnabled = false; layout.addView(this); setOnClickListener { stopRecording() } }
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
        status.text = "正在开启麦克风"; result.text = ""
        worker.execute {
            try {
                if (!visible) return@execute
                Log.i("VoxFix", "CAPTURE_REQUESTED")
                val text = SpeechEngine.record(
                    { engine ?: SpeechEngine(assets).also { engine = it } },
                    { value -> ui { result.text = value } },
                    { rms ->
                        ui { level.text = "麦克风音量：$rms / 100" }
                    }, { recordingRequested && visible }, { !visible },
                    { ui { if (recordingRequested && visible) status.text = "实时识别 · 点击停止结束" } },
                    { capture -> microphoneCapture = capture; if (!recordingRequested || !visible) capture?.stop() })
                Log.i("VoxFix", "CAPTURE_FINISHED characters=${text.length}")
                original = text
                if (visible) ui {
                    result.text = text
                    status.text = if (text.isEmpty()) "录音结束，未识别到文字" else "已保留识别原文"
                }
            } catch (error: Throwable) { report("录音或识别失败，已收到文字保留", error) }
            finally {
                active = false; recordingRequested = false
                ui { start.isEnabled = true; stop.isEnabled = false; level.text = "未录音" }
            }
        }
    }
    private fun stopRecording() {
        recordingRequested = false
        microphoneCapture?.stop()
        if (active) { stop.isEnabled = false; status.text = "正在结束录音…" }
    }
    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() {
        visible = false; stopRecording()
        worker.execute { engine?.release(); engine = null }
        super.onStop()
    }
    override fun onSaveInstanceState(state: Bundle) { state.putString("transcript", if (active && original.isNotEmpty()) original else result.text.toString()); super.onSaveInstanceState(state) }
    override fun onDestroy() {
        worker.execute { engine?.release(); engine = null }
        worker.shutdown()
        super.onDestroy()
    }
}
