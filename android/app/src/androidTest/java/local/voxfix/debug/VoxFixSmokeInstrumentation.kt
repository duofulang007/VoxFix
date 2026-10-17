package local.voxfix.debug

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Debug
import android.os.Process
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.getVadModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoxFixSmokeInstrumentation : Instrumentation() {
    private var pinyin = false
    private var benchmark = false
    private var quick = false
    private var memory = false
    private var latency = false
    private var modelLoad = false
    private var numberNormalization = false
    private var lexicon = false
    private var english = false
    override fun onCreate(arguments: Bundle?) {
        pinyin = arguments?.getString("pinyin") == "true"
        benchmark = arguments?.getString("benchmark") == "true"
        quick = arguments?.getString("quick") == "true"
        memory = arguments?.getString("memory") == "true"
        latency = arguments?.getString("latency") == "true"
        modelLoad = arguments?.getString("modelLoad") == "true"
        numberNormalization = arguments?.getString("numbers") == "true"
        lexicon = arguments?.getString("lexicon") == "true"
        english = arguments?.getString("english") == "true"
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        val output = Bundle()
        try {
            if (english) { output.putString("result", VoiceEnglishSmoke.run(targetContext)); finish(Activity.RESULT_OK, output); return }
            if (lexicon) { output.putString("result", PersonalLexiconSmoke.run(targetContext)); finish(Activity.RESULT_OK, output); return }
            if (numberNormalization) {
                output.putString("result", NumberNormalizationSmoke.run(targetContext))
                finish(Activity.RESULT_OK, output)
                return
            }
            if (modelLoad) {
                output.putString("result", VoiceModelLoadSmoke.run(targetContext))
                finish(Activity.RESULT_OK, output)
                return
            }
            if (latency) {
                output.putString("result", VoiceLatencySmoke.run(targetContext))
                finish(Activity.RESULT_OK, output)
                return
            }
            if (memory) {
                output.putString("result", VoiceMemorySmoke.run(this))
                finish(Activity.RESULT_OK, output)
                return
            }
            if (pinyin) {
                output.putString("result", PinyinSmoke.run(targetContext))
                finish(Activity.RESULT_OK, output)
                return
            }
            if (benchmark) {
                output.putString("result", benchmarkVoice())
                finish(Activity.RESULT_OK, output)
                return
            }
            val engine = SpeechEngine(targetContext.assets)
            try {
                engine.secondPass()
                check(SpeechEngine.refine("今天天气不错", "今天天气很好") == "今天天气不错")
                check(SpeechEngine.refine("我买了三斤苹果", "我买了3斤苹果") == "我买了3斤苹果")
                check(SpeechEngine.clean(" 你好 世界 ") == "你好世界")
                check(SpeechEngine.join("hello", "world") == "hello world")
                check(SpeechEngine.join("今天", "天气") == "今天天气")
            } finally { engine.release(); engine.release() }
            // Releasing while pass 2 is still loading must not leak it or race its destruction.
            repeat(2) { SpeechEngine(targetContext.assets).release() }
            output.putString("result", "ARM64_ASR_LOAD_PASS")
            finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            output.putString("result", if (pinyin) "ARM64_PINYIN_SMOKE_FAIL" else "ARM64_ASR_LOAD_FAIL")
            output.putString("error", error.toString())
            finish(Activity.RESULT_CANCELED, output)
        }
    }

    /** Fixed upstream audio only; measures inference, without opening the microphone or editor. */
    private fun benchmarkVoice(): String {
        val fixture = targetContext.cacheDir.resolve("voice-audit.f32")
        val bytes = fixture.readBytes()
        check(bytes.isNotEmpty() && bytes.size % 4 == 0 && bytes.size <= 16000 * 4 * 30)
        val samples = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
        check(samples.all { it.isFinite() && it in -1f..1f })
        val before = Debug.getPss()
        val started = System.nanoTime()
        val engine = SpeechEngine(targetContext.assets)
        val loadMs = (System.nanoTime() - started) / 1e6
        val loaded = Debug.getPss()
        val rows = JSONArray()
        val vadRows = JSONArray()
        val pipelineRows = JSONArray()
        try {
            val online = engine.javaClass.getDeclaredField("online").apply { isAccessible = true }.get(engine) as OnlineRecognizer
            val sense = if (quick) null else engine.secondPass()
            if (!quick) engine.release()
            var reference: String? = null
            var streamReference: String? = null
            // Alternate the order so warming or thermal drift does not always favor four threads.
            for ((threads, secondThreads, chunkSamples) in if (quick) emptyList() else listOf(
                Triple(4, 4, 3200), Triple(2, 4, 3200), Triple(2, 4, 1600),
                Triple(2, 2, 1600), Triple(2, 4, 3200), Triple(4, 4, 3200))) {
                val streaming = OnlineRecognizer(targetContext.assets, online.config.copy(
                    modelConfig = online.config.modelConfig.copy(numThreads = threads)))
                val secondConfig = checkNotNull(sense).config
                val second = try { OfflineRecognizer(targetContext.assets, secondConfig.copy(
                    modelConfig = secondConfig.modelConfig.copy(numThreads = secondThreads))) }
                catch (error: Throwable) { streaming.release(); throw error }
                try {
                    repeat(4) { pass ->
                        var elapsed = System.nanoTime()
                        var cpu = Process.getElapsedCpuTime()
                        val stream = streaming.createStream()
                        var firstTextAudioMs = -1.0
                        try {
                            for (offset in samples.indices step chunkSamples) {
                                stream.acceptWaveform(samples.copyOfRange(offset, minOf(offset + chunkSamples, samples.size)), 16000)
                                while (streaming.isReady(stream)) streaming.decode(stream)
                                if (firstTextAudioMs < 0 && streaming.getResult(stream).text.isNotBlank()) firstTextAudioMs = minOf(offset + chunkSamples, samples.size) / 16.0
                            }
                            stream.acceptWaveform(FloatArray(8000), 16000)
                            stream.inputFinished()
                            while (streaming.isReady(stream)) streaming.decode(stream)
                            val streamed = streaming.getResult(stream).text
                            check(streamed.isNotBlank())
                            if (streamReference == null) streamReference = streamed else check(streamReference == streamed) { "Streaming configuration changed recognition" }
                        } finally { stream.release() }
                        val onlineMs = (System.nanoTime() - elapsed) / 1e6
                        val onlineCpuMs = Process.getElapsedCpuTime() - cpu
                        elapsed = System.nanoTime(); cpu = Process.getElapsedCpuTime()
                        val utterance = second.createStream()
                        val text = try {
                            utterance.acceptWaveform(samples, 16000)
                            second.decode(utterance)
                            second.getResult(utterance).text
                        } finally { utterance.release() }
                        check(text.isNotBlank())
                        if (reference == null) reference = text else check(reference == text) { "Thread counts changed recognition" }
                        if (pass > 0) rows.put(JSONObject().put("threads", threads).put("secondThreads", secondThreads)
                            .put("chunkSamples", chunkSamples).put("firstTextAudioMs", firstTextAudioMs).put("streamingMs", onlineMs)
                            .put("streamingCpuMs", onlineCpuMs).put("secondMs", (System.nanoTime() - elapsed) / 1e6)
                            .put("secondCpuMs", Process.getElapsedCpuTime() - cpu))
                    }
                } finally { streaming.release(); second.release() }
            }
            for (threads in if (quick) emptyList() else listOf(4, 1, 1, 4)) {
                val config = checkNotNull(getVadModelConfig(0)); config.numThreads = threads
                val detector = Vad(targetContext.assets, config)
                try {
                    repeat(4) { pass ->
                        val elapsed = System.nanoTime(); val cpu = Process.getElapsedCpuTime()
                        var speechSamples = 0
                        for (offset in samples.indices step 3200) {
                            detector.acceptWaveform(samples.copyOfRange(offset, minOf(offset + 3200, samples.size)))
                            while (!detector.empty()) { speechSamples += detector.front().samples.size; detector.pop() }
                        }
                        detector.flush()
                        while (!detector.empty()) { speechSamples += detector.front().samples.size; detector.pop() }
                        if (pass > 0) vadRows.put(JSONObject().put("threads", threads).put("elapsedMs", (System.nanoTime() - elapsed) / 1e6)
                            .put("cpuMs", Process.getElapsedCpuTime() - cpu).put("speechSamples", speechSamples))
                        detector.reset()
                    }
                } finally { detector.release() }
            }
            val pipeline = if (quick) engine else SpeechEngine(targetContext.assets)
            try {
                var pipelineReference: String? = null
                repeat(3) {
                    var offset = 0; var last = ""; var updates = 0
                    val elapsed = System.nanoTime()
                    val text = pipeline.transcribe({
                        if (offset == samples.size) null else {
                            val end = minOf(offset + SpeechEngine.AUDIO_CHUNK_SAMPLES, samples.size)
                            samples.copyOfRange(offset, end).also { offset = end }
                        }
                    }, { value -> check(value != last); last = value; updates++ })
                    check(text.isNotBlank())
                    if (pipelineReference == null) pipelineReference = text
                    else check(SpeechEngine.refine(checkNotNull(pipelineReference), text) == text) { "Streaming content changed beyond allowed corrections" }
                    pipelineRows.put(JSONObject().put("elapsedMs", (System.nanoTime() - elapsed) / 1e6).put("updates", updates).put("characters", text.length))
                }
                var cancelled = false
                check(pipeline.transcribe({ cancelled = true; samples.copyOfRange(0, 3200) }, {}, { cancelled }).isEmpty())
                var silence = 0
                check(pipeline.transcribe({ if (silence++ < 15) FloatArray(3200) else null }, {}).isEmpty())
            } finally { pipeline.release() }
        } finally { engine.release(); fixture.delete() }
        return JSONObject().put("audioSeconds", samples.size / 16000.0).put("loadMs", loadMs)
            .put("pssBeforeKb", before).put("pssLoadedKb", loaded).put("pssReleasedKb", Debug.getPss())
            .put("numberExamples", JSONObject().put("十二万", JSONArray(SpeechEngine.numericTokens("十二万")))
                .put("二十万", JSONArray(SpeechEngine.numericTokens("二十万")))
                .put("一二三", JSONArray(SpeechEngine.numericTokens("一二三"))))
            .put("shortItn", SpeechEngine.refine("十点", "10点。"))
            .put("english", SpeechEngine.punctuate(SpeechEngine.clean("HELLO WORLD")))
            .put("pipeline", pipelineRows).put("vadRuns", vadRows).put("runs", rows).toString()
    }
}
