package local.voxfix.debug

import android.app.Instrumentation
import android.os.Debug
import android.os.Handler
import android.os.SystemClock
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real models and the IME's actual idle callback; no microphone, editor or touch events. */
internal object VoiceMemorySmoke {
    fun run(instrumentation: Instrumentation): String {
        check(NativeMemory.purge()) { "Native allocator purge is unsupported" }
        lateinit var service: VoxFixImeService
        instrumentation.runOnMainSync { service = VoxFixImeService() }
        fun field(name: String) = service.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun invoke(name: String) = service.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
        val worker = field("worker").get(service) as ExecutorService
        val handler = field("main").get(service) as Handler
        val release = field("releaseVoice").get(service) as Runnable
        val report = JSONObject().put("pssBeforeKb", Debug.getPss())
        try {
            check(field("engine").get(service) == null)
            val started = SystemClock.elapsedRealtime()
            val engine = SpeechEngine(instrumentation.targetContext.assets)
            report.put("captureReadyMs", SystemClock.elapsedRealtime() - started)
            field("engine").set(service, engine)
            engine.secondPass()
            report.put("allModelsReadyMs", SystemClock.elapsedRealtime() - started)
                .put("pssLoadedKb", Debug.getPss())
            val bytes = instrumentation.targetContext.cacheDir.resolve("voice-audit.f32").readBytes()
            check(bytes.isNotEmpty() && bytes.size % 4 == 0 && bytes.size <= 16000 * 4 * 30)
            val samples = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
            check(samples.all { it.isFinite() && it in -1f..1f })
            var offset = 0
            val text = engine.transcribe({
                if (offset == samples.size) null else {
                    val end = minOf(offset + SpeechEngine.AUDIO_CHUNK_SAMPLES, samples.size)
                    samples.copyOfRange(offset, end).also { offset = end }
                }
            }, {})
            check(text.isNotBlank())
            report.put("characters", text.length).put("pssAfterInferenceKb", Debug.getPss())
            field("shown").setBoolean(service, true)
            field("busy").setBoolean(service, true)
            instrumentation.runOnMainSync { release.run() }
            worker.submit {}.get(10, TimeUnit.SECONDS)
            check(field("engine").get(service) === engine) { "Released an active recognizer" }
            instrumentation.runOnMainSync { invoke("scheduleVoiceRelease") }
            check(!handler.hasCallbacks(release)) { "Scheduled release during recognition" }
            field("busy").setBoolean(service, false)
            val idleAt = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync { invoke("scheduleVoiceRelease") }
            check(handler.hasCallbacks(release))
            SystemClock.sleep(1000)
            check(field("engine").get(service) === engine) { "Warm reuse window was lost" }
            while (field("engine").get(service) != null && SystemClock.elapsedRealtime() - idleAt < 35_000) {
                SystemClock.sleep(200)
            }
            check(field("engine").get(service) == null) { "Models remained resident while keyboard shown" }
            check(Debug.getPss() < report.getInt("pssAfterInferenceKb") * 0.7) { "Native memory remained high after release" }
            report.put("idleReleaseMs", SystemClock.elapsedRealtime() - idleAt)
                .put("pssReleasedKb", Debug.getPss())
            SystemClock.sleep(5000)
            report.put("pssReleasedAfter5sKb", Debug.getPss())
            // A new cold load can be cancelled before the asynchronous second pass is ready.
            repeat(2) {
                val reloaded = SpeechEngine(instrumentation.targetContext.assets)
                reloaded.release()
                reloaded.release()
            }
            report.put("pssAfterReloadReleaseKb", Debug.getPss()).put("status", "VOICE_IDLE_RELEASE_PASS")
            return report.toString()
        } finally {
            instrumentation.runOnMainSync { handler.removeCallbacks(release) }
            worker.submit { (field("engine").get(service) as SpeechEngine?)?.release() }.get(15, TimeUnit.SECONDS)
            worker.shutdown()
            (field("pinyinLoader").get(service) as ExecutorService).shutdown()
        }
    }
}
