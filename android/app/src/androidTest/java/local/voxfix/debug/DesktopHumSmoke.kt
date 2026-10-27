package local.voxfix.debug

import android.app.Instrumentation
import kotlin.math.PI
import kotlin.math.sin
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthetic fixtures only; no microphone capture or user dictionary. */
internal object DesktopHumSmoke {
    fun run(instrumentation: Instrumentation): String {
        var cases = 0
        val failures = mutableListOf<String>()
        DesktopSpeechEngine(instrumentation.targetContext.assets).use { engine ->
            val bytes = instrumentation.context.assets.open("pause-hum-32000-1.pcm").use { it.readBytes() }
            val pcm = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
            var cursor = 0
            val historical = engine.transcribe({
                if (cursor == pcm.size) null else {
                    val end = minOf(cursor + 320, pcm.size)
                    FloatArray(end - cursor) { pcm[cursor + it] / 32768f }.also { cursor = end }
                }
            }, { if (it.isNotEmpty()) failures.add("historical hum callback: $it") })
            if (historical.isNotEmpty()) failures.add("historical hum: $historical")
            cases++
            for (hz in listOf(40, 50, 60, 80, 100, 120, 160, 200, 240)) for (size in listOf(6400, 48000, 192000))
                for (level in listOf(.001, .03, .3)) {
                val audio = FloatArray(size) { i ->
                    val phase = 2 * PI * hz * i / 16000
                    (level * (sin(phase) + .3 * sin(phase * 2) + .15 * sin(phase * 3) + .07 * sin(phase * 4)) *
                        (.6 + .4 * sin(2 * PI * 3 * i / 16000))).toFloat()
                }
                var offset = 0
                val callbacks = mutableListOf<String>()
                val text = engine.transcribe({
                    if (offset == size) null else {
                        val end = minOf(offset + 320, size)
                        audio.copyOfRange(offset, end).also { offset = end }
                    }
                }, { if (it.isNotEmpty()) callbacks.add(it) },
                    onSettled = { value, _ -> if (value.isNotEmpty()) callbacks.add(value) },
                    onDraft = { value, _ -> if (value.isNotEmpty()) callbacks.add(value) })
                if (text.isNotEmpty() || callbacks.isNotEmpty()) failures.add("${hz}Hz/${size}/$level: $text $callbacks")
                cases++
            }
        }
        check(failures.isEmpty()) { "Hum produced words: $failures" }
        return "DESKTOP_HUM_PASS cases=$cases no-drafts-or-finals"
    }
}
