package local.voxfix.debug

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.io.File

class PolishSmokeInstrumentation : Instrumentation() {
    private var streaming = false
    private var ink = false
    override fun onCreate(arguments: Bundle?) { streaming = arguments?.getString("stream") == "true"; ink = arguments?.getString("ink") == "true"; super.onCreate(arguments); start() }
    override fun onStart() {
        val output = Bundle()
        try {
            if (ink) {
                val handwriting = HandwritingEngine(targetContext)
                try {
                    val candidates = handwriting.recognize("""{"w":1000,"h":1000,"strokes":[[[100,500],[900,500]]]}""")
                    check("一" in candidates && candidates.size == 9)
                    check(runCatching { handwriting.recognize("""{"w":10,"h":10,"strokes":[[[11,0]]]}""") }.isFailure)
                } finally { handwriting.release() }
                SpeechEngine(targetContext.assets).release()
                val normalizer = TextNormalizer(targetContext.assets, File(targetContext.filesDir, "wetext-0.1.8"))
                try { check(normalizer.format("十公里").replace(" ", "") == "10公里") }
                finally { normalizer.release() }
                output.putString("result", "ARM64_INK_ASR_LOAD_NORMALIZER_PASS")
                finish(Activity.RESULT_OK, output)
                return
            }
            val engine = NativePolisher(File(targetContext.filesDir, "polish/Qwen3.5-2B-Q4_K_M.gguf"), TextNormalizer(targetContext.assets, File(targetContext.filesDir, "wetext-0.1.8")))
            engine.prepare()
            if (streaming) {
                val started = System.nanoTime()
                var updates = 0
                var firstSeconds = 0.0
                val text = engine.polish("嗯我回家") { partial ->
                    check(partial.isNotEmpty() && !partial.contains('\uFFFD'))
                    if (updates++ == 0) firstSeconds = (System.nanoTime() - started) / 1e9
                }
                check(updates >= 2 && text.isNotEmpty()) { "Incremental callbacks missing" }
                output.putString("result", "ARM64_STREAM_CALLBACK_PASS")
                output.putString("updates", updates.toString())
                output.putString("firstSeconds", firstSeconds.toString())
                output.putString("seconds", ((System.nanoTime() - started) / 1e9).toString())
                engine.release()
                finish(Activity.RESULT_OK, output)
                return
            }
            val numericRaw = "重量是一百九十二克，不是二百零五克"
            val numericCandidate = "重量是192克，不是205克。"
            check(engine.guardText(numericRaw, numericCandidate) == numericCandidate)
            check(engine.guardText(numericRaw, "重量是190克，不是205克。") == numericRaw)
            check(engine.guardText("不要跑十公里", "不要跑10公里。") == "不要跑10公里。")
            check(engine.guardText("我回家记录一下", "我回家记录。") == "我回家记录。")
            for (phrase in listOf("另一双鞋", "我喜欢十三香", "我联系张三，王一")) check(engine.guardText(phrase, phrase) == phrase)
            output.putString("numericGuard", "NUMERIC_EQUIVALENCE_PASS")
            val raw = "呃，那个这双鞋重一百九十二克另一双重二百零五克我明天跑十公里不要跑十五公里然后然后回家记录一下"
            val started = System.nanoTime()
            val text = engine.polish(raw)
            output.putString("text", text)
            output.putString("seconds", ((System.nanoTime() - started) / 1e9).toString())
            check(text.contains("192") && text.contains("205") && text.contains("10") && text.contains("15") && text.contains("不要")) { "Expected Arabic quantities or negation missing" }
            check(!text.contains('<'))
            check(text != raw) { "Model produced no accepted change" }
            check(!text.contains("然后然后")) { "Repeated phrase was not removed" }
            check(!text.contains("呃") && !text.contains("另1双") && !text.contains("那个")) { "Natural classifier or filler cleanup failed" }
            output.putString("result", "ARM64_REAL_MODEL_PASS")
            finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            output.putString("result", "ARM64_REAL_MODEL_FAIL")
            output.putString("error", error.toString())
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
