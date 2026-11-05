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
            if (modelCompare == "screenshot-scroll") {
                output.putString("result", ScreenshotScrollChecks.run(this)); finish(Activity.RESULT_OK,output); return
            }
            if (modelCompare == "screenshot-smart-tools") {
                output.putString("result", ScreenshotSmartToolsChecks.run(this)); finish(Activity.RESULT_OK,output); return
            }
            if (modelCompare == "screenshot-linear-handles") {
                output.putString("result", ScreenshotLinearHandlesChecks.run(this)); finish(Activity.RESULT_OK,output); return
            }
            if (modelCompare == "screenshot-selection-resize") {
                output.putString("result", ScreenshotSelectionResizeChecks.run(this)); finish(Activity.RESULT_OK,output); return
            }
            if (modelCompare == "screenshot-selection-style") {
                output.putString("result", ScreenshotSelectionStyleChecks.run(this)); finish(Activity.RESULT_OK,output); return
            }
            if (modelCompare == "screenshot-pen-feedback") {
                output.putString("result", ScreenshotPenFeedbackChecks.run(this)); finish(Activity.RESULT_OK,output); return
            }
            if (modelCompare == "screenshot-color-picker") {
                output.putString("result", ScreenshotColorPickerSmoke.run(this)); finish(Activity.RESULT_OK,output); return
            }
            if (modelCompare == "screenshot-parity-foundation") {
                output.putString("result", ScreenshotParityFoundationSmoke.run(this)); finish(Activity.RESULT_OK,output); return
            }
            if (modelCompare == "screenshot-filter") {
                output.putString("result", ScreenshotFilterStabilitySmoke.run(this)); finish(Activity.RESULT_OK,output); return
            }
            if (modelCompare == "screenshot-history") {
                output.putString("result", ScreenshotHistorySmoke.run(this)); finish(Activity.RESULT_OK,output); return
            }
            if (modelCompare == "screenshot-memory") {
                output.putString("result", ScreenshotToolMemorySmoke.run(this)); finish(Activity.RESULT_OK,output); return
            }
            if (modelCompare in setOf("screenshot-memory-write","screenshot-memory-read")) {
                output.putString("result", ScreenshotToolMemorySmoke.persistence(this,modelCompare.endsWith("write"))); finish(Activity.RESULT_OK,output); return
            }
            if (modelCompare == "translate-full") {
                output.putString("result", TranslateFullPageSmoke.run(this,evalMode=="zh")); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "translate-speed") {
                output.putString("result", TranslateSpeedSmoke.run(this)); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "production-ocr") {
                output.putString("result", ScreenOcrProductionSmoke.run(this)); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "production-ocr-layouts") {
                output.putString("result", ScreenOcrProductionSmoke.layouts(this)); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "translate-download") {
                output.putString("result", TranslateDownloadSmoke.run(this,evalMode=="interrupt")); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "screen-text") {
                output.putString("result", ScreenTextSmoke.run(this)); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "translate") {
                output.putString("result", TranslateSmoke.run(targetContext,
                    if (evalMode.startsWith("benchmark")) evalOffset else 4,
                    if (evalMode.startsWith("benchmark")) evalLimit else 0, evalMode))
                finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "screenshot-refinement") {
                output.putString("result", ScreenshotRefinementSmoke.run(this)); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "screenshot-selected") {
                output.putString("result", ScreenshotSelectedToolsSmoke.run(this)); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "screenshot-editing") {
                output.putString("result", ScreenshotObjectEditingSmoke.run(this)); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "screenshot-document") {
                output.putString("result", ScreenshotDocumentSmoke.run(this)); finish(Activity.RESULT_OK, output); return
            }
            // The full toolbar suite also covers the settings editor, whose drag injection is a
            // separate work item; these overlay suites are run on their own entry.
            if (modelCompare == "screenshot-overlay-suites") {
                val preferences = targetContext.getSharedPreferences("screen-text", android.content.Context.MODE_PRIVATE)
                val original=preferences.all
                try {
                preferences.edit().remove("translate.source").remove("translate.target")
                    .remove(ScreenshotToolbarEditor.KEY)
                    .remove(ScreenshotToolbarSection.DRAWING.key).remove(ScreenshotToolbarSection.ACTIONS.key).commit()
                preferences.edit().putString(ScreenshotToolbarEditor.KEY,
                    ScreenshotToolbarLayout(ScreenshotAction.values().map { listOf(it) }).encode()).commit()
                ScreenshotDrawingSmoke.run(this)
                ScreenshotEditingSmoke.run(this)
                ScreenshotRetentionSmoke.run(this)
                ScreenshotTranslationFeedbackSmoke.run(this)
                preferences.edit().remove(ScreenshotToolbarEditor.KEY).commit()
                } finally {
                    val restore=preferences.edit().clear()
                    original.forEach { (key,value) -> when(value) {
                        is String -> restore.putString(key,value)
                        is Boolean -> restore.putBoolean(key,value)
                        is Int -> restore.putInt(key,value)
                        is Long -> restore.putLong(key,value)
                        is Float -> restore.putFloat(key,value)
                        is Set<*> -> restore.putStringSet(key,value.filterIsInstance<String>().toSet())
                    } }
                    restore.commit()
                }
                output.putString("result", "SCREENSHOT_OVERLAY_SUITES_PASS: drawing,editing,retention,translation-feedback")
                finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "screenshot-project") {
                output.putString("result", ScreenshotProjectSmoke.run(this)); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "screenshot-toolbar") {
                output.putString("result", ScreenshotToolbarSmoke.run(this)); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "desktop-parity") {
                output.putString("result", VoiceTextParitySmoke.run(this)); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "desktop-continuous") {
                output.putString("result", VoiceTextParitySmoke.continuous(this)); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "ascend-eval") {
                output.putString("result", DesktopCorpusEval.run(targetContext, evalMode, evalOffset, evalLimit))
                finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "desktop-delivery") {
                output.putString("result", DesktopVoiceDeliverySmoke.run(this)); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "desktop-short") {
                output.putString("result", DesktopVoiceDeliverySmoke.shortSpeech(this)); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "desktop-hum") {
                output.putString("result", DesktopHumSmoke.run(this)); finish(Activity.RESULT_OK, output); return
            }
            if (modelCompare == "desktop-pause") {
                output.putString("result", DesktopVoiceDeliverySmoke.pauseNoise(this)); finish(Activity.RESULT_OK, output); return
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
            output.putString("result", when {
                modelCompare == "translate" -> "TRANSLATE_SMOKE_FAIL"
                // A translation failure is not an ASR load failure; the label used to be wrong.
                modelCompare == "translate-full" -> "TRANSLATE_FULL_PAGE_FAIL"
                modelCompare == "translate-speed" -> "TRANSLATE_SPEED_FAIL"
                modelCompare == "translate-download" -> "TRANSLATE_DOWNLOAD_FAIL"
                modelCompare == "production-ocr-layouts" -> "PRODUCTION_OCR_LAYOUT_FAIL"
                modelCompare == "screenshot-toolbar" -> "SCREENSHOT_TOOLBAR_FAIL"
                modelCompare == "screenshot-selection-style" -> "SCREENSHOT_SELECTION_STYLE_FAIL"
                modelCompare == "screenshot-selection-resize" -> "SCREENSHOT_SELECTION_RESIZE_FAIL"
                modelCompare == "screenshot-linear-handles" -> "SCREENSHOT_LINEAR_HANDLES_FAIL"
                modelCompare == "screenshot-color-picker" -> "SCREENSHOT_COLOR_PICKER_FAIL"
                modelCompare == "screenshot-parity-foundation" -> "SCREENSHOT_PARITY_FOUNDATION_FAIL"
                modelCompare == "screenshot-selected" -> "SCREENSHOT_SELECTED_TOOLS_FAIL"
                modelCompare == "screenshot-editing" -> "SCREENSHOT_OBJECT_EDITING_FAIL"
                modelCompare == "screenshot-document" -> "SCREENSHOT_DOCUMENT_FAIL"
                modelCompare == "screenshot-project" -> "SCREENSHOT_PROJECT_FAIL"
                modelCompare == "screenshot-overlay-suites" -> "SCREENSHOT_OVERLAY_SUITES_FAIL"
                modelCompare == "screenshot-refinement" -> "SCREENSHOT_REFINEMENT_FAIL"
                modelCompare == "screenshot-history" -> "SCREENSHOT_HISTORY_FAIL"
                modelCompare == "screenshot-filter" -> "SCREENSHOT_FILTER_STABILITY_FAIL"
                modelCompare == "screenshot-smart-tools" -> "SCREENSHOT_SMART_TOOLS_FAIL"
                modelCompare == "screenshot-scroll" -> "SCREENSHOT_SCROLL_FAIL"
                modelCompare.startsWith("screenshot-memory") -> "SCREENSHOT_TOOL_MEMORY_FAIL"
                modelCompare.startsWith("screen-text") -> "SCREEN_TEXT_SMOKE_FAIL"
                pinyin -> "ARM64_PINYIN_SMOKE_FAIL"
                else -> "ARM64_ASR_LOAD_FAIL"
            })
            output.putString("error", error.toString())
            if(modelCompare.startsWith("screenshot-")) output.putString("trace",error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
