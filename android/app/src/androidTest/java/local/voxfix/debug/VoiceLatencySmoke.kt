package local.voxfix.debug

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Paced, fixed upstream audio; no microphone or input connection. */
internal object VoiceLatencySmoke {
    fun run(context: Context): String {
        val bytes = context.cacheDir.resolve("voice-audit.f32").readBytes()
        check(bytes.isNotEmpty() && bytes.size % 4 == 0 && bytes.size <= 16000 * 4 * 30)
        val fixture = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(fixture)
        check(fixture.size >= 73600 && fixture.all { it.isFinite() && it in -1f..1f })
        // Run the same harness against both the installed baseline and the new signature.
        val method = SpeechEngine::class.java.declaredMethods.first {
            it.name.startsWith("transcribe") && !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount in 3..4
        }.apply { isAccessible = true }
        val rows = JSONArray()
        for ((scenario, samples, chunkSamples) in listOf(Triple("prefix", fixture.copyOfRange(0, 64000), 1600),
                Triple("earlySpeech", fixture.copyOfRange(9600, 73600), 1600), Triple("full", fixture, 1600),
                Triple("earlySpeech20ms", fixture.copyOfRange(9600, 73600), 320))) {
            var engine: SpeechEngine? = null
            try {
                repeat(2) { pass ->
                    val started = SystemClock.elapsedRealtime()
                    var offset = 0
                    var firstTextMs = -1L
                    var updates = 0
                    var releasedAt = 0L
                    PcmCapture(SpeechEngine.CAPTURE_BUFFER_CHUNKS, {
                        if (offset == samples.size) {
                            releasedAt = SystemClock.elapsedRealtime()
                            null
                        } else {
                            val end = minOf(offset + chunkSamples, samples.size)
                            val wait = started + end * 1000L / SpeechEngine.SAMPLE_RATE - SystemClock.elapsedRealtime()
                            if (wait > 0) SystemClock.sleep(wait)
                            samples.copyOfRange(offset, end).also { offset = end }
                        }
                    }, { true }, {}).use { capture ->
                        val loadAt = SystemClock.elapsedRealtime()
                        if (engine == null) engine = SpeechEngine(context.assets)
                        val loadMs = SystemClock.elapsedRealtime() - loadAt
                        val next: () -> FloatArray? = { capture.next() }
                        val onText: (String) -> Unit = {
                            if (firstTextMs < 0) firstTextMs = SystemClock.elapsedRealtime() - started
                            updates++
                        }
                        val cancelled: () -> Boolean = { false }
                        val recording: () -> Boolean = { releasedAt == 0L }
                        val text = (if (method.parameterCount == 3) method.invoke(engine, next, onText, cancelled)
                            else method.invoke(engine, next, onText, cancelled, recording)) as String
                        check(text.isNotBlank() && firstTextMs >= 0 && releasedAt > 0)
                        rows.put(JSONObject().put("scenario", scenario).put("cold", pass == 0).put("loadMs", loadMs)
                            .put("firstTextMs", firstTextMs).put("releaseToDoneMs", SystemClock.elapsedRealtime() - releasedAt)
                            .put("updates", updates).put("characters", text.length))
                    }
                }
            } finally { engine?.release() }
        }
        return JSONObject().put("runs", rows).toString()
    }
}
