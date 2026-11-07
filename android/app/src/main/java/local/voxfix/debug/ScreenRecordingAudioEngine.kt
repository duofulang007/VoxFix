package local.voxfix.debug

import android.media.AudioRecord

/**
 * 把 1–2 路 `AudioRecord` 按 10 ms 槽对齐，交给上层编码。
 *
 * 上游对应 `LiveAudioMixer`（`direct.rs`）：每个音源线程把自己的 PCM 写进以「帧号 / 槽长」
 * 为键的槽里，混音线程按槽号取数据；某一路在抖动窗口内没到就按静音补齐，不会卡住另一路。
 * 暂停时两路都只读不记（也不推进帧计数），恢复后从同一个槽号继续，时间轴因此是连续的。
 *
 * 这里只做**对齐与切片**；混音与分轨的取舍由 [ScreenRecordingAudio.mixSlot] 与调用方决定。
 */
internal class ScreenRecordingAudioEngine(
    private val sources: List<Source>,
    private val sink: Sink
) {
    enum class Kind(val trackKey: String) {
        SYSTEM(ScreenRecordingAudio.TRACK_SYSTEM),
        MICROPHONE(ScreenRecordingAudio.TRACK_MICROPHONE)
    }

    /** [channels] 是设备实际给到的声道数：麦克风常为 1，播放内录一般为 2。 */
    internal class Source(val kind: Kind, val record: AudioRecord, val channels: Int)

    fun interface Sink {
        /** 每个 10 ms 槽回调一次；缺数据的音源不出现在 [samples] 里。 */
        fun onSlot(slotIndex: Long, samples: Map<Kind, ShortArray>)
    }

    private val lock = Object()
    /** 每个音源待消费的槽：槽号 → 双声道样本。 */
    private val pending = HashMap<Kind, HashMap<Long, ShortArray>>()
    /** 每个音源已写入的帧数（只在非暂停时推进）。 */
    private val frames = HashMap<Kind, Long>()
    private val readers = ArrayList<Thread>()
    /** 读线程已退出的音源：不再等它的数据，按静音处理，避免拖慢另一路。 */
    private val dead = HashSet<Kind>()
    private var mixer: Thread? = null
    private var nextSlot = 0L
    private var running = false

    @Volatile private var paused = false

    fun start() {
        running = true
        for (source in sources) {
            pending[source.kind] = HashMap()
            frames[source.kind] = 0L
            readers += Thread({ readLoop(source) }, "voxfix-audio-${source.kind.trackKey}").apply {
                isDaemon = true
                start()
            }
        }
        mixer = Thread({ mixLoop() }, "voxfix-audio-mixer").apply { isDaemon = true; start() }
    }

    fun setPaused(value: Boolean) {
        paused = value
        synchronized(lock) { lock.notifyAll() }
    }

    /** 停止采集并补齐最后一槽，然后等线程退出。 */
    fun stop() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        for (reader in readers) runCatching { reader.join(1200) }
        runCatching { mixer?.join(1200) }
        readers.clear()
        mixer = null
        synchronized(lock) {
            pending.clear()
            frames.clear()
            dead.clear()
            nextSlot = 0
        }
    }

    private fun readLoop(source: Source) {
        // One slot per read has to be sized for the source's own channel count: a mono record fills
        // the byte buffer with twice the frames of a stereo one, which used to overflow the slice.
        val pcm = ByteArray(ScreenRecordingAudio.SLOT_FRAMES * source.channels * ScreenRecordingAudio.BYTES_PER_SAMPLE)
        val samples = ShortArray(ScreenRecordingAudio.SLOT_SAMPLES)
        try {
            while (running) {
                val read = source.record.read(pcm, 0, pcm.size)
                if (read <= 0) continue
                // Paused: keep draining the record so the HAL buffer does not overflow, but drop the
                // audio. Frame counters stay put, so the slot timeline resumes where it stopped.
                if (paused) continue
                val framesRead = read / (source.channels * ScreenRecordingAudio.BYTES_PER_SAMPLE)
                if (framesRead <= 0) continue
                decode(pcm, read, source.channels, samples, framesRead)
                store(source.kind, samples, framesRead)
            }
        } catch (error: Exception) {
            // A dead source must not stall the mixer: without this the missing frames kept every slot
            // waiting out the whole jitter window, which slowed both tracks to ~1/10 real time.
            android.util.Log.w("VoxFix", "SCREEN_RECORD_AUDIO_READ_FAILED ${source.kind}", error)
            synchronized(lock) { dead += source.kind; lock.notifyAll() }
        }
    }

    /** little-endian i16 → 双声道样本；单声道时复制到左右两声道。 */
    private fun decode(pcm: ByteArray, length: Int, channels: Int, out: ShortArray, frames: Int) {
        if (channels >= 2) {
            val count = minOf(out.size, length / ScreenRecordingAudio.BYTES_PER_SAMPLE)
            for (index in 0 until count) {
                val low = pcm[index * 2].toInt() and 0xFF
                val high = pcm[index * 2 + 1].toInt()
                out[index] = ((high shl 8) or low).toShort()
            }
            return
        }
        val mono = ShortArray(frames)
        for (index in 0 until frames) {
            val low = pcm[index * 2].toInt() and 0xFF
            val high = pcm[index * 2 + 1].toInt()
            mono[index] = ((high shl 8) or low).toShort()
        }
        val stereo = ScreenRecordingAudio.upmixMonoToStereo(mono, frames)
        System.arraycopy(stereo, 0, out, 0, minOf(out.size, stereo.size))
    }

    private fun store(kind: Kind, samples: ShortArray, framesRead: Int) {
        synchronized(lock) {
            val base = frames[kind] ?: 0L
            val slots = pending[kind] ?: return
            var offset = 0
            var frame = base
            while (offset < framesRead) {
                val slotIndex = ScreenRecordingAudio.slotOf(frame)
                val frameInSlot = (frame % ScreenRecordingAudio.SLOT_FRAMES).toInt()
                val copy = minOf(framesRead - offset, ScreenRecordingAudio.SLOT_FRAMES - frameInSlot)
                if ((offset + copy) * ScreenRecordingAudio.CHANNELS > samples.size) break
                // Keep at most the jitter window of slots: anything older is already emitted or lost.
                if (slotIndex > nextSlot - 1 && slotIndex <= nextSlot + ScreenRecordingAudio.JITTER_SLOTS) {
                    val slot = slots.getOrPut(slotIndex) { ShortArray(ScreenRecordingAudio.SLOT_SAMPLES) }
                    System.arraycopy(samples, offset * ScreenRecordingAudio.CHANNELS, slot,
                        frameInSlot * ScreenRecordingAudio.CHANNELS, copy * ScreenRecordingAudio.CHANNELS)
                }
                offset += copy
                frame += copy
            }
            frames[kind] = base + framesRead
            lock.notifyAll()
        }
    }

    private fun mixLoop() {
        while (running) {
            if (paused) {
                synchronized(lock) { lock.wait(ScreenRecordingAudio.SLOT_MS.toLong()) }
                continue
            }
            val slotIndex = synchronized(lock) { nextSlot }
            if (!awaitSlot(slotIndex)) break
            val samples = HashMap<Kind, ShortArray>()
            synchronized(lock) {
                for (source in sources) {
                    pending[source.kind]?.remove(slotIndex)?.let { samples[source.kind] = it }
                }
                nextSlot = slotIndex + 1
                prune(slotIndex)
            }
            sink.onSlot(slotIndex, samples)
        }
        flushTail()
    }

    /** 等到每个音源都写完这一槽，或抖动窗口用尽（缺数据就当静音）。 */
    private fun awaitSlot(slotIndex: Long): Boolean {
        val deadline = System.nanoTime() + ScreenRecordingAudio.JITTER_MS * 1_000_000L
        val target = (slotIndex + 1) * ScreenRecordingAudio.SLOT_FRAMES
        synchronized(lock) {
            while (running) {
                if (sources.all { it.kind in dead || (frames[it.kind] ?: 0L) >= target }) return true
                val remaining = (deadline - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) return true
                runCatching { lock.wait(remaining.coerceAtMost(ScreenRecordingAudio.SLOT_MS.toLong())) }
            }
            return false
        }
    }

    private fun prune(slotIndex: Long) {
        val oldest = slotIndex - ScreenRecordingAudio.JITTER_SLOTS
        for (slots in pending.values) slots.keys.removeAll { it < oldest }
    }

    /** 收尾：把已写入但还没发出的槽送出去，避免最后 10 ms 被吞掉。 */
    private fun flushTail() {
        val remaining = synchronized(lock) {
            val last = pending.values.flatMap { it.keys }.maxOrNull() ?: return
            (nextSlot..last).map { slot ->
                slot to sources.mapNotNull { source -> pending[source.kind]?.remove(slot)?.let { source.kind to it } }.toMap()
            }
        }
        for ((slot, samples) in remaining) {
            if (samples.isNotEmpty()) sink.onSlot(slot, samples)
        }
    }
}
