package local.voxfix.debug

import android.content.Context
import android.util.Log
import java.io.File

/** Loads the pushed model and translates one sentence on the real device. */
object TranslateSmoke {
    fun run(context: Context, threads: Int = 4, chars: Int = 0, mode: String = ""): String {
        require(threads in 1..8 && chars in 0..600)
        val model = File(context.filesDir, "translate/hy-mt2-1.8b-1.25bit.gguf")
        if (!model.isFile || model.length() != TranslateModelStore.BYTES) {
            return "TRANSLATE_SMOKE_SKIP no-model"
        }
        TranslateModelPatcher.ensure(context, model)
        val loadStart = System.nanoTime()
        val pinCores = !mode.contains("unpinned")
        val quantizedKv = !mode.contains("f16")
        val handle = LlamaBridge.load(model.absolutePath, threads, 2048, pinCores, quantizedKv)
        check(handle != 0L) { "native load failed" }
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000
        val chinese = mode.contains("zh")
        val source = if (chinese)
            "会议将于上午9:30在1204号会议室开始。请各部门携带本月财务报告，核对预算和实际支出的差异，并在周五之前提交书面说明。未经负责人确认，不得修改付款金额或收款账户。".repeat(8).take(chars)
        else if (chars == 0) "The meeting starts at 9:30 AM in Room 1204." else
            "The meeting starts at 9:30 AM in Room 1204. Please bring the monthly report and confirm the budget before Friday. ".repeat(6).take(chars)
        val prompt = TranslateRequest.buildPrompt(
            if (chinese) TranslatePair(TranslateLanguage.ZH, TranslateLanguage.EN) else TranslatePair.DEFAULT,
            source
        )
        val genStart = System.nanoTime()
        var updates=0
        var firstUpdateMs=-1L
        var lastUpdate=""
        var genMs: Long
        val output = try {
            LlamaBridge.translate(handle, prompt, if (mode.contains("prefill")) 1 else if (chars == 0) 96 else 1024, 60_000) { bytes ->
                val text=TranslateRequest.decodePartial(bytes)
                if(text.isNotBlank()) {
                    if(firstUpdateMs<0) firstUpdateMs=(System.nanoTime()-genStart)/1_000_000
                    updates++; lastUpdate=text
                }
            }.also {
                genMs = (System.nanoTime() - genStart) / 1_000_000
            }
        } finally {
            LlamaBridge.free(handle)
        }
        Log.i("VoxFix", "TRANSLATE_SMOKE load_ms=$loadMs gen_ms=$genMs chars=${output.length}")
        Log.i("VoxFix", "TRANSLATE_SMOKE_TEXT $output")
        check(output.isNotBlank() && (mode.contains("prefill") || output.contains("1204"))) { "output=$output" }
        check(lastUpdate==output) { "stream differs from final result" }
        if(!mode.contains("prefill")) check(updates>1 && firstUpdateMs<genMs) { "stream did not update before completion" }
        return "TRANSLATE_SMOKE_PASS mode=$mode threads=$threads input_chars=${source.length} load_ms=$loadMs gen_ms=$genMs updates=$updates first_ms=$firstUpdateMs out=$output"
    }
}
