package local.voxfix.debug

import android.app.Instrumentation
import android.os.Debug
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Full-page translation on the real device. The corpus is built from complete sentences only, so a
 * failure can never be blamed on a half-sentence produced by `take(600)`, and the tail code makes
 * the last generated sentence checkable.
 */
internal object TranslateFullPageSmoke {
    private const val TAIL_ZH = "最后核验码：73519。"
    private const val TAIL_EN = "Final verification code: 73519."

    private val ZH = listOf(
        "会议将于上午9:30在1204号会议室开始。",
        "请各部门携带本月财务报告，核对预算和实际支出的差异。",
        "未经负责人确认，不得修改付款金额或收款账户。",
        "第一季度的差旅费用比去年同期下降12.5%。",
        "系统每天凌晨两点自动备份日志，保留九十天。",
        "如果发票信息有误，请在三个工作日内提交更正申请。",
        "客户反馈称移动端的加载时间仍然偏长。",
        "技术团队计划在下个月上线新的缓存层。",
        "培训定于周四下午两点，地点改为西楼三层。",
        "请在备注栏写明合同编号和项目名称。"
    )
    private val EN = listOf(
        "The meeting starts at 9:30 AM in Room 1204. ",
        "Every department must bring this month's financial report and explain the gap between budget and actual spending. ",
        "Nobody may change the payment amount or the receiving account without the owner's approval. ",
        "Travel costs in the first quarter fell by 12.5 percent year on year. ",
        "The system backs up its logs at two in the morning and keeps them for ninety days. ",
        "If the invoice details are wrong, file a correction within three working days. ",
        "Customers report that loading on mobile is still slow. ",
        "The engineering team plans to ship a new cache layer next month. ",
        "Training is on Thursday afternoon, now in the third floor west room. ",
        "Write the contract number and the project name in the notes field. "
    )

    /** Only whole sentences are concatenated, then the coded tail is appended last. */
    fun corpus(chinese: Boolean, budget: Int = 600): String {
        val tail = if (chinese) TAIL_ZH else TAIL_EN
        val sentences = if (chinese) ZH else EN
        val body = StringBuilder()
        var index = 0
        while (true) {
            val next = sentences[index % sentences.size]
            if (body.length + next.length + tail.length > budget) break
            body.append(next)
            index++
        }
        return body.toString() + tail
    }

    fun run(test: Instrumentation, chinese: Boolean): String {
        val source = corpus(chinese)
        val done = CountDownLatch(1)
        val started = System.nanoTime()
        var firstMs = -1L
        var result = ""
        var error = ""
        val before = Debug.getPss()
        test.runOnMainSync {
            TranslateRuntime.init(test.targetContext)
            TranslateRuntime.translate(source,
                if (chinese) TranslatePair(TranslateLanguage.ZH, TranslateLanguage.EN) else TranslatePair.DEFAULT,
                { result = it; done.countDown() }, { error = it; done.countDown() },
                { if (firstMs < 0) firstMs = (System.nanoTime() - started) / 1_000_000 })
        }
        check(done.await(10, TimeUnit.MINUTES)) { "runtime callback timeout" }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val loaded = Debug.getPss()
        TranslateRuntime.releaseForVoice()
        check(!TranslateRuntime.occupiesMemory()) { "translator did not release" }
        System.gc()
        val record = JSONObject().put("source", source).put("result", result).put("error", error)
            .put("elapsedMs", elapsed).put("firstMs", firstMs).put("sourceChars", source.length)
            .put("outChars", result.length).put("pssBeforeKb", before).put("pssLoadedKb", loaded)
            .put("pssReleasedKb", Debug.getPss())
        File(test.targetContext.cacheDir, "translate-full-${if (chinese) "zh" else "en"}.json")
            .writeText(record.toString(2))
        check(error.isBlank()) { error }
        check(result.contains("73519")) { "translation dropped the tail code: $result" }
        if (chinese) check(result.contains("1204") && result.contains("9:30")) { "dropped embedded numbers: $result" }
        // The last sentence must be complete: a truncated tail is not "finished".
        check(result.trimEnd().lastOrNull() in listOf('。', '.', '！', '!', '？', '?')) {
            "translation ends mid-sentence: $result"
        }
        return "TRANSLATE_FULL_PAGE_PASS in=${source.length} chinese=$chinese ms=$elapsed " +
            "firstMs=$firstMs out=${result.length} pss=$before/$loaded/${Debug.getPss()}"
    }
}
