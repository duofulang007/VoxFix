package local.voxfix.debug

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Compares the same weights before/after portable graph optimization, without recording. */
internal object VoiceModelLoadSmoke {
    fun run(context: Context): String {
        val probe = SpeechEngine(context.assets)
        val config = try {
            (probe.javaClass.getDeclaredField("online").apply { isAccessible = true }.get(probe) as OnlineRecognizer).config
        } finally { probe.release() }
        val bytes = context.cacheDir.resolve("voice-audit.f32").readBytes()
        check(bytes.isNotEmpty() && bytes.size % 4 == 0 && bytes.size <= 16000 * 4 * 30)
        val samples = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
        check(samples.all { it.isFinite() && it in -1f..1f })
        val folder = context.cacheDir.resolve("voice-reference")
        check(listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt").all { folder.resolve(it).isFile })
        val original = config.copy(modelConfig = config.modelConfig.copy(
            transducer = OnlineTransducerModelConfig(folder.resolve("encoder.int8.onnx").path,
                folder.resolve("decoder.onnx").path, folder.resolve("joiner.int8.onnx").path),
            tokens = folder.resolve("tokens.txt").path))
        val rows = JSONArray()
        var reference: String? = null
        for (useOptimized in listOf(false, true, true, false)) {
            val started = SystemClock.elapsedRealtime()
            val recognizer = if (useOptimized) OnlineRecognizer(context.assets, config) else OnlineRecognizer(config = original)
            val loadMs = SystemClock.elapsedRealtime() - started
            try {
                val stream = recognizer.createStream()
                try {
                    for (offset in samples.indices step 320) {
                        stream.acceptWaveform(samples.copyOfRange(offset, minOf(offset + 320, samples.size)), 16000)
                        while (recognizer.isReady(stream)) recognizer.decode(stream)
                    }
                    stream.acceptWaveform(FloatArray(8000), 16000)
                    stream.inputFinished()
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    val text = recognizer.getResult(stream).text
                    check(text.isNotBlank())
                    if (reference == null) reference = text else check(reference == text) { "Graph optimization changed fixed-audio recognition" }
                    rows.put(JSONObject().put("optimized", useOptimized).put("loadMs", loadMs).put("characters", text.length))
                } finally { stream.release() }
            } finally { recognizer.release(); NativeMemory.purge() }
        }
        return JSONObject().put("runs", rows).put("sameText", true).toString()
    }
}
