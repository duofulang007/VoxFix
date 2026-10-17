package local.voxfix.debug

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 固定音频标点回归。
 *
 * 语料来源：`.android/punct-audit-20261017/fixtures`（Huihui 合成的中文分句/疑问/数字
 * 与仓库既有中英混合样本），全部按 20 ms 真实节拍走 [SpeechEngine.transcribe] 完整流水线，
 * 断言的是真实模型输出里的标点，不向辅助函数喂人工写好的标点文本。
 *
 * 覆盖四条路径：二遍有机会完成（带尾静音）、尾段直接松手（无尾静音）、
 * 冷启动（全新引擎首个请求）、取消（立即取消必须为空）。
 */
internal object VoicePunctuationSmoke {

    fun run(context: Context): String {
        val folder = context.cacheDir.resolve("punct-audit-20261017")
        check(folder.isDirectory) { "缺少语料目录 $folder" }
        val cases = JSONArray(folder.resolve("cases.json").readText())
        check(cases.length() in 1..40) { "语料条数异常 ${cases.length()}" }

        val rows = JSONArray()
        val engine = SpeechEngine(context.assets)
        val pssBefore = android.os.Debug.getPss()
        try {
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val id = case.getString("id")
                check(id.matches(Regex("[a-z0-9-]+"))) { "非法 id $id" }
                val samples = read(folder.resolve("$id.f32"))
                val expected = case.getString("expect")
                val least = case.optInt("count", 1)
                fun satisfied(text: String) = text.count { it == expected[0] } >= least
                // 带 800 ms 尾静音：够 VAD 切段并让二遍跑完；无尾静音：模拟说完立刻松手。
                val paced = transcribeFixed(engine, samples, trailingSilenceMs = 800)
                val abrupt = transcribeFixed(engine, samples, trailingSilenceMs = 0)
                rows.put(JSONObject().put("id", id).put("expect", expected).put("least", least)
                    .put("paced", paced.text).put("abrupt", abrupt.text)
                    .put("pacedOk", satisfied(paced.text))
                    .put("abruptOk", satisfied(abrupt.text))
                    // 收尾耗时＝总耗时减音频时长，即松手后到出结果这一段。
                    .put("pacedTailMs", paced.totalMs - paced.audioMs)
                    .put("abruptTailMs", abrupt.totalMs - abrupt.audioMs)
                    .put("pacedAudioMs", paced.audioMs))
                folder.resolve("partial.json").writeText(rows.toString(2))
            }

            // 冷启动：全新引擎直接给固定音频，标点模型与二遍都还没预热。
            val cold = SpeechEngine(context.assets)
            val coldText = try {
                transcribeFixed(cold, read(folder.resolve("${cases.getJSONObject(0).getString("id")}.f32")), 800).text
            } finally { cold.release() }
            rows.put(JSONObject().put("id", "cold").put("text", coldText)
                .put("hasMark", coldText.any { it in "，。？" }))

            // 取消：立即取消必须返回空串，不能漏出半截文字或标点。
            var cancelled = false
            val cancelText = engine.transcribe({ cancelled = true; FloatArray(3200) }, {}, { cancelled })
            rows.put(JSONObject().put("id", "cancel").put("text", cancelText).put("empty", cancelText.isEmpty()))
            rows.put(JSONObject().put("id", "memory").put("pssBeforeKb", pssBefore)
                .put("pssLoadedKb", android.os.Debug.getPss()))
        } finally { engine.release() }
        val pssReleased = android.os.Debug.getPss()

        val output = JSONObject().put("syntheticAudio", true).put("runs", rows)
            .put("pssReleasedKb", pssReleased).toString(2)
        folder.resolve("device.json").writeText(output)
        return "PUNCT_SMOKE_DONE cases=${cases.length()} file=cache/punct-audit-20261017/device.json"
    }

    /** 一次固定音频识别：文本 + 总耗时 + 音频时长（用于算出松手后的收尾耗时）。 */
    private class Fixed(val text: String, val totalMs: Long, val audioMs: Long)

    /** 按 20 ms 真实节拍喂音频；尾静音长度决定松手时二遍有没有机会完成。 */
    private fun transcribeFixed(engine: SpeechEngine, samples: FloatArray, trailingSilenceMs: Int): Fixed {
        val audio = if (trailingSilenceMs > 0) samples + FloatArray(16000 * trailingSilenceMs / 1000) else samples
        var offset = 0
        val started = SystemClock.elapsedRealtime()
        val text = engine.transcribe({
            if (offset >= audio.size) null else {
                val end = minOf(offset + SpeechEngine.AUDIO_CHUNK_SAMPLES, audio.size)
                val wait = started + end * 1000L / 16000 - SystemClock.elapsedRealtime()
                if (wait > 0) SystemClock.sleep(wait)
                audio.copyOfRange(offset, end).also { offset = end }
            }
        }, {}, { false }, { offset < audio.size })
        return Fixed(text, SystemClock.elapsedRealtime() - started, audio.size / 16L)
    }

    private fun read(path: File): FloatArray {
        val bytes = path.readBytes()
        check(bytes.isNotEmpty() && bytes.size % 4 == 0 && bytes.size <= 16000 * 4 * 30) { "音频异常 $path" }
        val samples = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
        check(samples.all { it.isFinite() && it in -1f..1f }) { "采样越界 $path" }
        return samples
    }
}
