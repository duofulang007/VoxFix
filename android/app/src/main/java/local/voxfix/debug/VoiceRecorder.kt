package local.voxfix.debug

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** Shared Android capture: keep the microphone draining while models load or decode. */
internal object VoiceRecorder {
    /**
     * [isMuted] 为真时把样本换成静音再交给识别：麦克风继续读（不阻塞、不重开会话），
     * 但打字期间说的话不会进 VAD、不会成段上屏。用户选完字立刻恢复。
     */
    fun record(onLevel: (Int) -> Unit, shouldContinue: () -> Boolean, isCancelled: () -> Boolean,
               onReady: () -> Unit, onCapture: (PcmCapture?) -> Unit, maxSamples: Int,
               isMuted: () -> Boolean = { false }, transcribe: (() -> FloatArray?) -> String): String {
        val started = System.nanoTime()
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "设备不支持16kHz单声道录音：$minimum" }
        val microphone = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 12800))
        var capture: PcmCapture? = null
        val stopFrame = AtomicLong(Long.MAX_VALUE)
        try {
            check(microphone.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            if (!shouldContinue() || isCancelled()) return ""
            microphone.startRecording()
            check(microphone.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风未进入录音状态" }
            Log.i("VoxFix", "ASR_MIC_STARTED elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            val pcm = ShortArray(320)
            var total = 0
            capture = PcmCapture(300, read@{
                val received = microphone.read(pcm, 0, minOf(pcm.size, maxSamples - total))
                check(received > 0) { "麦克风采样失败：$received" }
                val count = minOf(received.toLong(), (stopFrame.get() - total).coerceAtLeast(0)).toInt()
                if (count == 0) return@read null
                total += count
                if (total == count) {
                    Log.i("VoxFix", "ASR_MIC_READY elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
                    onReady()
                }
                val muted = isMuted()
                val samples = FloatArray(count) { if (muted) 0f else pcm[it] / 32768f }
                if (muted) onLevel(0) else {
                    val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
                    onLevel((rms * 100).toInt().coerceIn(0, 100))
                }
                samples
            }, { shouldContinue() && !isCancelled() && total < maxSamples }, {
                if (microphone.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val stoppedAt = System.nanoTime()
                    val timestamp = AudioTimestamp()
                    if (microphone.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS)
                        stopFrame.set(timestamp.framePosition + (stoppedAt - timestamp.nanoTime).coerceAtLeast(0) * 16000 / 1_000_000_000)
                    microphone.stop()
                }
            })
            onCapture(capture)
            if (isCancelled()) return ""
            return transcribe { capture.next() }
        } finally {
            onCapture(null)
            try { capture?.close() } finally { microphone.release() }
        }
    }
}
