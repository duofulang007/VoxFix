package local.voxfix.debug

import android.content.Context
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fixed synthetic speech only; never opens the microphone or writes to an input field. */
internal object VoiceEnglishSmoke {
    /** The original diagnostic subset also proves that more tail silence does not change the text. */
    private val LONG_SILENCE = setOf("mix-huihui-1", "mix-huihui-5", "mix-switch-4", "sentence-3", "word-6", "word-14")

    fun run(context: Context): String {
        val folder = context.cacheDir.resolve("english-audit-20261017")
        val cases = JSONArray(folder.resolve("cases.json").readText())
        check(cases.length() in 1..100)
        val engine = SpeechEngine(context.assets)
        val rows = JSONArray()
        try {
            val online = engine.javaClass.getDeclaredField("online").apply { isAccessible = true }.get(engine) as OnlineRecognizer
            val second = engine.secondPass()
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val id = case.getString("id")
                check(id.matches(Regex("[a-z0-9-]+")))
                val bytes = folder.resolve("$id.f32").readBytes()
                check(bytes.isNotEmpty() && bytes.size % 4 == 0 && bytes.size <= 16000 * 4 * 30)
                val samples = FloatArray(bytes.size / 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
                check(samples.all { it.isFinite() && it in -1f..1f })
                val stream = online.createStream()
                val raw = try {
                    for (offset in samples.indices step 320) {
                        stream.acceptWaveform(samples.copyOfRange(offset, minOf(offset + 320, samples.size)), 16000)
                        while (online.isReady(stream)) online.decode(stream)
                    }
                    stream.acceptWaveform(FloatArray(8000), 16000)
                    stream.inputFinished()
                    while (online.isReady(stream)) online.decode(stream)
                    online.getResult(stream).text
                } finally { stream.release() }
                val utterance = second.createStream()
                val candidate = try {
                    utterance.acceptWaveform(samples, 16000)
                    second.decode(utterance)
                    second.getResult(utterance).text
                } finally { utterance.release() }
                val clean = SpeechEngine.clean(raw)
                val refined = SpeechEngine.refine(clean, SpeechEngine.clean(candidate))
                val row = JSONObject().put("id", id).put("expected", case.getString("expected"))
                    .put("streaming", raw).put("sense_zh", candidate).put("refined", refined)
                    .put("formatted", SpeechEngine.punctuate(SpeechEngine.formatNumbers(refined)))
                // Every case also runs the real 20 ms paced pipeline, so a segmentation change is
                // measured against the same fixed audio instead of inferred from isolated calls.
                val pipeline = JSONArray()
                for (silence in if (id in LONG_SILENCE) listOf(0, 32000) else listOf(0)) {
                    val audio = samples + FloatArray(silence)
                    var offset = 0
                    val started = SystemClock.elapsedRealtime()
                    val text = engine.transcribe({
                        if (offset == audio.size) null else {
                            val end = minOf(offset + 320, audio.size)
                            val wait = started + end * 1000L / 16000 - SystemClock.elapsedRealtime()
                            if (wait > 0) SystemClock.sleep(wait)
                            audio.copyOfRange(offset, end).also { offset = end }
                        }
                    }, {}, { false }, { offset < audio.size })
                    pipeline.put(JSONObject().put("silenceMs", silence / 16).put("text", text)
                        .put("decodeMs", SystemClock.elapsedRealtime() - started))
                }
                row.put("pipeline", pipeline)
                rows.put(row)
                folder.resolve("partial.json").writeText(rows.toString(2))
            }
        } finally { engine.release() }
        val result = JSONObject().put("syntheticAudio", true).put("runs", rows).toString(2)
        folder.resolve("device.json").writeText(result)
        return "VOICE_ENGLISH_AUDIT_DONE cases=${rows.length()} file=cache/english-audit-20261017/device.json"
    }
}
