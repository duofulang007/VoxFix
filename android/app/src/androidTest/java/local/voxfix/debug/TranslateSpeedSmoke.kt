package local.voxfix.debug

import android.app.Instrumentation
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Measures where the wall clock goes before any timeout is touched: page prefill, first output
 * token, then decode throughput. Rounds are interleaved across configs and the minimum per config
 * is reported, because a single sequential pass measures heat soak more than it measures settings
 * (same config measured 25 and 13.6 tok/s back to back in the first attempt).
 */
internal object TranslateSpeedSmoke {
    private const val TIMEOUT_MS = 180_000L
    private const val ROUNDS = 3

    private data class Config(val threads: Int, val batch: Int, val quantizedKv: Boolean, val flashAttn: Int) {
        val label: String
            get() = "t${threads}b${batch}${if (quantizedKv) "q" else "f"}fa$flashAttn"
    }

    private val configs = listOf(
        Config(4, 256, true, -1),
        Config(4, 512, true, -1),
        Config(4, 256, false, -1)
    )

    fun run(test: Instrumentation): String {
        val context = test.targetContext
        val model = File(context.filesDir, "translate/${TranslateModelStore.FILE}")
        check(model.isFile && model.length() == TranslateModelStore.BYTES) { "translation model missing" }
        TranslateModelPatcher.ensure(context, model)
        val pair = TranslatePair(TranslateLanguage.ZH, TranslateLanguage.EN)
        val page = TranslateFullPageSmoke.corpus(chinese = true)
        val sample = TranslateFullPageSmoke.corpus(chinese = true, budget = 240)
        val pagePrompt = TranslateRequest.buildPrompt(pair, page)
        val samplePrompt = TranslateRequest.buildPrompt(pair, sample)
        val best = mutableMapOf<String, JSONObject>()

        for (round in 0 until ROUNDS) {
            for (config in configs) {
                val handle = LlamaBridge.load(model.absolutePath, config.threads, 2048, true,
                    config.quantizedKv, config.batch, config.flashAttn)
                // Forcing flash attention off made this model fail to load on the K60; record that
                // instead of aborting the whole sweep on the first unsupported setting.
                if (handle == 0L) {
                    best.putIfAbsent(config.label, JSONObject().put("round", round).put("prefillMs", -1)
                        .put("ms", -1).put("firstMs", -1).put("outChars", 0).put("error", "LOAD_FAILED")
                        .put("streamMatches", false))
                    continue
                }
                try {
                    val prefillStart = System.nanoTime()
                    runCatching { LlamaBridge.translate(handle, pagePrompt, 1, TIMEOUT_MS) }
                    val prefillMs = (System.nanoTime() - prefillStart) / 1_000_000

                    var firstMs = -1L
                    var last = ""
                    var error = ""
                    val start = System.nanoTime()
                    val output = try {
                        LlamaBridge.translate(handle, samplePrompt, 1024, TIMEOUT_MS) { bytes ->
                            val text = TranslateRequest.decodePartial(bytes)
                            if (text.isNotBlank()) {
                                if (firstMs < 0) firstMs = (System.nanoTime() - start) / 1_000_000
                                last = text
                            }
                        }
                    } catch (failure: Exception) {
                        error = failure.message ?: failure.toString()
                        ""
                    }
                    val elapsed = (System.nanoTime() - start) / 1_000_000
                    Log.i("VoxFix", "TRANSLATE_SPEED round=$round cfg=${config.label} prefill_ms=$prefillMs " +
                        "ms=$elapsed firstMs=$firstMs out=${output.length} error=$error")
                    val record = JSONObject().put("round", round).put("prefillMs", prefillMs)
                        .put("ms", elapsed).put("firstMs", firstMs).put("outChars", output.length)
                        .put("error", error).put("streamMatches", last == output)
                    val previous = best[config.label]
                    // Keep the fastest run per config: thermal throttling can only add time.
                    if (previous == null || elapsed < previous.getLong("ms")) best[config.label] = record
                } finally {
                    LlamaBridge.free(handle)
                }
            }
        }
        val records = JSONArray()
        val summary = StringBuilder()
        for (config in configs) {
            val record = best[config.label] ?: continue
            records.put(record.put("config", config.label))
            summary.append(config.label).append("=pre").append(record.getLong("prefillMs")).append("ms/")
                .append(record.getLong("ms")).append("ms/").append(record.getLong("firstMs")).append("ms/")
                .append(record.getInt("outChars")).append("ch ")
        }
        File(context.cacheDir, "translate-speed.json").writeText(
            JSONObject().put("pageChars", page.length).put("sampleChars", sample.length)
                .put("rounds", ROUNDS).put("configs", records).toString(2))
        check(records.length() == configs.size) { "incomplete speed table" }
        return "TRANSLATE_SPEED_PASS page=${page.length} $summary".trim()
    }
}
