package local.voxfix.debug

import android.content.Context

/** Exercises the production adoption gate with fixed text, never a user editor or lexicon. */
internal object VoiceAdoptionSafetySmoke {
    fun run(context: Context): String {
        EnglishVocabulary.load(context.assets)
        for ((raw, candidate) in listOf(
            "不要用EXA做表格" to "要用EXCEL做表格",
            "我没用EXA做表格" to "我用EXCEL做表格",
            "别用EXA做表格" to "用EXCEL做表格"
        )) {
            check(SpeechEngine.refine(raw, candidate) == raw) { "Term correction removed negation" }
            check(SpeechEngine.refine(raw, candidate, boundaryAgreement = true) == raw) { "Term agreement removed negation" }
        }
        check(SpeechEngine.refine("不要用EXA做表格", "不要用EXCEL做表格") == "不要用EXCEL做表格")
        check(SpeechEngine.refine("请把Excel发给我", "请把Word发给我") == "请把Excel发给我")
        check(SpeechEngine.refine("要取消订单", "不要取消订单", boundaryAgreement = true) == "不要取消订单")
        check(SpeechEngine.refine("要取消订单", "不要取消订单") == "要取消订单")
        for (number in listOf("一", "三", "3")) {
            val raw = "请导出${number}份EXA表格"
            check(SpeechEngine.refine(raw, "请导出EXCEL表格") == raw) { "Term correction removed quantity" }
        }
        check(SpeechEngine.refine("请导出三份EXA表格", "请导出三份EXCEL表格") == "请导出三份EXCEL表格")
        return "VOICE_ADOPTION_SAFETY_PASS cases=14; fixed text only"
    }
}
