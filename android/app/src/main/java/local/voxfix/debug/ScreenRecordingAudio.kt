package local.voxfix.debug

/**
 * 录制音频的取样与混音规则，逐条对齐上游 `snow-recording-runtime/src/direct.rs`。
 *
 * - 取样：48 kHz、双声道、i16（`AUDIO_SAMPLE_RATE` / `AUDIO_CHANNELS`）。
 * - 分槽：每 10 ms 一个槽（`AUDIO_SLOT_MS`），最多容忍 100 ms 抖动（`AUDIO_JITTER_MS`）；
 *   某个音源在抖动窗口内没有数据时按静音补齐，另一路照常发声。
 * - 混音（`mix_audio_slot`）：逐样本**饱和相加**并夹在 i16 区间，不做平均也不做衰减。
 * - 分轨（`RecordingAudioMode::Separate`）：每个启用的音源各自一轨；混音模式只有一轨。
 *
 * 纯函数放在这里，JVM 可测；线程与编码在 `ScreenRecordingAudioEngine` / `ScreenRecorder`。
 */
internal object ScreenRecordingAudio {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val SLOT_MS = 10
    const val JITTER_MS = 100

    /** 上游 `tracks(..., 160)`：混音与分轨共用这个 AAC 码率。 */
    const val BITRATE = 160_000

    const val BYTES_PER_SAMPLE = 2

    /** 一个槽的帧数（48 kHz × 10 ms）。 */
    const val SLOT_FRAMES = SAMPLE_RATE * SLOT_MS / 1000

    /** 一个槽的每声道样本总数。 */
    const val SLOT_SAMPLES = SLOT_FRAMES * CHANNELS

    /** 一个槽的 PCM 字节数。 */
    const val SLOT_BYTES = SLOT_SAMPLES * BYTES_PER_SAMPLE

    /** 抖动窗口折成的槽数：超出这个范围就认为该音源缺数据。 */
    const val JITTER_SLOTS = JITTER_MS / SLOT_MS

    /** 输出轨的键，同时也是 `RecordingAudioMode` 的取值来源。 */
    const val TRACK_MIXED = "mixed"
    const val TRACK_SYSTEM = "system"
    const val TRACK_MICROPHONE = "microphone"

    /**
     * 上游 `RecordingAudioMode::tracks`：混音一轨；分轨按启用音源各一轨（系统在前、麦克风在后）。
     * 两路都没有时没有音轨。动画格式在上层就被排除（`audioSupported = format == mp4`）。
     */
    fun trackKeys(system: Boolean, microphone: Boolean, separate: Boolean): List<String> {
        if (!system && !microphone) return emptyList()
        if (!separate) return listOf(TRACK_MIXED)
        return buildList {
            if (system) add(TRACK_SYSTEM)
            if (microphone) add(TRACK_MICROPHONE)
        }
    }

    /**
     * 上游 `mix_audio_slot`：逐样本饱和相加，夹到 i16。缺一路就是另一路原样输出；
     * 两路都缺则是静音（调用方传 null 的情况由 [mixSlot] 归一化）。
     */
    fun mixSlot(system: ShortArray?, microphone: ShortArray?, samples: Int = SLOT_SAMPLES): ShortArray {
        val mixed = ShortArray(samples)
        for (source in listOfNotNull(system, microphone)) {
            val count = minOf(samples, source.size)
            for (index in 0 until count) {
                val sum = mixed[index].toInt() + source[index].toInt()
                mixed[index] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return mixed
    }

    /**
     * 麦克风在不支持立体声的设备上只能采到单声道，而上游的混音与编码都是双声道，
     * 这里把单声道样本复制到左右两声道，保持时间轴不变。
     */
    fun upmixMonoToStereo(mono: ShortArray, frames: Int): ShortArray {
        val stereo = ShortArray(frames * CHANNELS)
        for (frame in 0 until frames) {
            val sample = if (frame < mono.size) mono[frame] else 0
            stereo[frame * CHANNELS] = sample
            stereo[frame * CHANNELS + 1] = sample
        }
        return stereo
    }

    /** PCM 采样率换算成槽号；用于按时间轴对齐两路数据。 */
    fun slotOf(frameIndex: Long): Long = if (SLOT_FRAMES <= 0) 0 else frameIndex / SLOT_FRAMES
}
