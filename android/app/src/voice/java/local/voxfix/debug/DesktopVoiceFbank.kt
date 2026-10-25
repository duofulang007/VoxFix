package local.voxfix.debug

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln

/**
 * The feature front end the pinned R5 graph expects, reproduced bit-for-bit enough to
 * batch-match sherpa-onnx: 80-bin hamming fbank (25/10 ms, snip_edges, dither 0),
 * LFR(7,6) centred on `i * 6` with edge clamping, then `(x + neg_mean) * inv_stddev`.
 *
 * Guessing any of this silently keeps the decode plausible but wrong, so the numbers
 * here are pinned by a golden test against kaldi_native_fbank output
 * (see DesktopHotwordTest).
 */
internal object DesktopVoiceFbank {
    const val RATE = 16000
    const val FRAME_LENGTH = 400
    const val FRAME_SHIFT = 160
    const val PADDED = 512
    const val NUM_BINS = 80
    const val LOW_FREQ = 20.0
    const val HIGH_FREQ = RATE / 2.0
    const val PREEMPHASIS = 0.97
    private const val FLOOR = 1.1920929e-7f  // float32 epsilon, kaldi's log floor

    private val window = DoubleArray(FRAME_LENGTH) { 0.54 - 0.46 * cos(2.0 * PI * it / (FRAME_LENGTH - 1)) }

    /** Per mel bin: the FFT bins it covers and their triangular weights. */
    private val banks: Array<Pair<IntArray, DoubleArray>> = run {
        val low = melScale(LOW_FREQ)
        val high = melScale(HIGH_FREQ)
        val delta = (high - low) / (NUM_BINS + 1)
        val width = RATE.toDouble() / PADDED
        Array(NUM_BINS) { bin ->
            val left = low + bin * delta
            val center = left + delta
            val right = center + delta
            val indexes = ArrayList<Int>(16)
            val weights = ArrayList<Double>(16)
            for (i in 0 until PADDED / 2) {
                val mel = melScale(width * i)
                if (mel > left && mel < right) {
                    indexes.add(i)
                    weights.add(if (mel <= center) (mel - left) / delta else (right - mel) / delta)
                }
            }
            indexes.toIntArray() to weights.toDoubleArray()
        }
    }

    private fun melScale(frequency: Double) = 1127.0 * ln(1.0 + frequency / 700.0)

    fun frameCount(samples: Int): Int = if (samples < FRAME_LENGTH) 0 else 1 + (samples - FRAME_LENGTH) / FRAME_SHIFT

    /**
     * Log mel frames. Samples are in [-1, 1]; the R5 graph declares `normalize_samples=0`,
     * which in kaldi terms means the waveform is scaled to the int16 range first.
     */
    fun frames(samples: FloatArray, scale: Double = 32768.0): Array<DoubleArray> {
        val total = frameCount(samples.size)
        val out = Array(total) { DoubleArray(NUM_BINS) }
        val re = DoubleArray(PADDED)
        val im = DoubleArray(PADDED)
        val frame = DoubleArray(FRAME_LENGTH)
        for (index in 0 until total) {
            val start = index * FRAME_SHIFT
            var mean = 0.0
            for (i in 0 until FRAME_LENGTH) { frame[i] = samples[start + i] * scale; mean += frame[i] }
            mean /= FRAME_LENGTH
            for (i in 0 until FRAME_LENGTH) frame[i] -= mean
            for (i in FRAME_LENGTH - 1 downTo 1) frame[i] -= PREEMPHASIS * frame[i - 1]
            frame[0] -= PREEMPHASIS * frame[0]
            for (i in 0 until FRAME_LENGTH) frame[i] *= window[i]
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            System.arraycopy(frame, 0, re, 0, FRAME_LENGTH)
            fft(re, im)
            val power = DoubleArray(PADDED / 2) { re[it] * re[it] + im[it] * im[it] }
            for (bin in 0 until NUM_BINS) {
                val (indexes, weights) = banks[bin]
                var energy = 0.0
                for (i in indexes.indices) energy += power[indexes[i]] * weights[i]
                out[index][bin] = ln(if (energy > FLOOR) energy else FLOOR.toDouble())
            }
        }
        return out
    }

    /** LFR(7,6) with clamping, then CMVN; `count = 1 + (T - 1) / shift`. */
    fun stack(frames: Array<DoubleArray>, windowSize: Int, shift: Int, mean: FloatArray, std: FloatArray): Array<FloatArray> {
        if (frames.isEmpty()) return emptyArray()
        val count = 1 + (frames.size - 1) / shift
        val left = (windowSize - 1) / 2
        return Array(count) { i ->
            val centre = i * shift
            FloatArray(NUM_BINS * windowSize) { k ->
                val source = (centre - left + k / NUM_BINS).coerceIn(0, frames.size - 1)
                ((frames[source][k % NUM_BINS] + mean[k]) * std[k]).toFloat()
            }
        }
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wr = cos(angle)
            val wi = kotlin.math.sin(angle)
            var start = 0
            while (start < n) {
                var curR = 1.0
                var curI = 0.0
                for (k in 0 until length / 2) {
                    val a = start + k
                    val b = a + length / 2
                    val vr = re[b] * curR - im[b] * curI
                    val vi = re[b] * curI + im[b] * curR
                    re[b] = re[a] - vr
                    im[b] = im[a] - vi
                    re[a] += vr
                    im[a] += vi
                    val nextR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nextR
                }
                start += length
            }
            length = length shl 1
        }
    }
}
