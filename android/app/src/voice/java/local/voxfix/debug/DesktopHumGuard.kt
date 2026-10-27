package local.voxfix.debug

import kotlin.math.sqrt

/** Reject near-identical low-frequency cycles, rather than raising the gate for quiet speech. */
internal object DesktopHumGuard {
    fun isHum(audio: FloatArray): Boolean {
        if (audio.size < 3200) return false
        // Check the whole segment so an opening hum cannot discard speech later in the clip.
        val step = maxOf(4, audio.size / 4000)
        val mean = audio.sumOf { it.toDouble() } / audio.size
        for (lag in 64..400) {
            var cross = 0.0
            var leftEnergy = 0.0
            var rightEnergy = 0.0
            for (i in 0 until audio.size - lag step step) {
                val left = audio[i] - mean
                val right = audio[i + lag] - mean
                cross += left * right
                leftEnergy += left * left
                rightEnergy += right * right
            }
            if (leftEnergy > 1e-10 && rightEnergy > 1e-10 &&
                cross > .99 * sqrt(leftEnergy * rightEnergy)) return true
        }
        return false
    }
}
