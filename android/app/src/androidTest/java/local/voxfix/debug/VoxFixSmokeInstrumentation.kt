package local.voxfix.debug

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

/**
 * Device entry points that are still maintained: the desktop R5 engine, the hotword
 * rescue, the dictionary page data, memes, pinyin and the lexicon. Everything that
 * only exercised the removed double-pass engine (and its per-mode flags) is gone.
 */
class VoxFixSmokeInstrumentation : Instrumentation() {
    private var pinyin = false
    private var lexicon = false
    private var modelCompare = ""
    private var evalMode = ""
    private var evalOffset = 0
    private var evalLimit = 0

    override fun onCreate(arguments: Bundle?) {
        pinyin = arguments?.getString("pinyin") == "true"
        lexicon = arguments?.getString("lexicon") == "true"
        modelCompare = arguments?.getString("modelCompare") ?: ""
        evalMode = arguments?.getString("evalMode") ?: ""
        evalOffset = arguments?.getString("evalOffset")?.toIntOrNull() ?: 0
        evalLimit = arguments?.getString("evalLimit")?.toIntOrNull() ?: 0
        super.onCreate(arguments); start()
    }

    override fun onStart() {
        val output = Bundle()
        try {
            if (modelCompare == "ascend-eval") {
                output.putString("result", DesktopCorpusEval.run(targetContext, evalMode, evalOffset, evalLimit))
                finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "desktop-delivery") {
                output.putString("result", DesktopVoiceDeliverySmoke.run(this)); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare.startsWith("desktop-live-")) {
                output.putString("result", DesktopVoiceLiveSmoke.run(targetContext, modelCompare.removePrefix("desktop-live-")))
                finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "hotword-migrate") {
                output.putString("result", DesktopHotwordSmoke.migrate(targetContext)); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "hotword") {
                output.putString("result", DesktopHotwordSmoke.run(targetContext, context.assets)); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "meme-search") {
                output.putString("result", MemeSearchSmoke.run(targetContext)); finish(Activity.RESULT_OK, output); return
            }
            if (lexicon) {
                output.putString("result", PersonalLexiconSmoke.run(targetContext)); finish(Activity.RESULT_OK, output); return
            }
            if (pinyin) {
                output.putString("result", PinyinSmoke.run(targetContext)); finish(Activity.RESULT_OK, output); return
            }
            check(targetContext.assets.list("streaming").orEmpty().isEmpty()) { "Unused streaming assets still packaged" }
            repeat(2) {
                val engine = DesktopSpeechEngine(targetContext.assets)
                check(engine.transcribe({ error("cancelled supplier") }, {}, { true }).isEmpty())
                engine.close(); engine.close()
            }
            output.putString("result", "ARM64_DESKTOP_R5_LOAD_PASS")
            finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            output.putString("result", if (pinyin) "ARM64_PINYIN_SMOKE_FAIL" else "ARM64_ASR_LOAD_FAIL")
            output.putString("error", error.toString())
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
