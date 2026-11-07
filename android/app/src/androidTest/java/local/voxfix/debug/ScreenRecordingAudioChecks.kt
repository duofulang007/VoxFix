package local.voxfix.debug

import android.app.Instrumentation
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Device checks for the recording audio path (T28 混音/分轨).
 *
 * Upstream stores the enabled sources either as one `mixed` track or as `system` + `microphone`
 * tracks (`RecordingAudioMode::tracks`). MediaMuxer can hold several AAC tracks, but whether this
 * ROM's encoder + muxer combination accepts two is exactly what has to be proven on the device.
 *
 * The check synthesises two tones, mixes them with the same [ScreenRecordingAudio.mixSlot] the
 * recorder uses, encodes both layouts with the production constants (48 kHz / stereo / 160 kbps),
 * then re-reads each file: track count, mime, duration, and the decoded RMS of every track.
 */
internal object ScreenRecordingAudioChecks {
    private class Track(val key: String, val pcm: ShortArray)

    private class Encoded(val format: MediaFormat, val samples: List<Pair<Long, ByteArray>>)

    fun run(test: Instrumentation): String {
        val dir = test.targetContext.cacheDir
        val left = tone(440.0, 0.4)
        val right = tone(660.0, 0.4)

        // Mixed layout: upstream sums the two sources sample by sample before encoding.
        val mixed = ShortArray(left.size)
        var offset = 0
        while (offset < left.size) {
            val count = minOf(ScreenRecordingAudio.SLOT_SAMPLES, left.size - offset)
            val slot = ScreenRecordingAudio.mixSlot(
                left.copyOfRange(offset, offset + count),
                right.copyOfRange(offset, offset + count),
                count)
            System.arraycopy(slot, 0, mixed, offset, count)
            offset += count
        }

        val mixedFile = File(dir, "recording-audio-mixed.mp4").apply { delete() }
        val separateFile = File(dir, "recording-audio-separate.mp4").apply { delete() }
        val mixedRms = try {
            write(listOf(Track(ScreenRecordingAudio.TRACK_MIXED, mixed)), mixedFile)
            val tracks = describe(mixedFile)
            check(tracks.size == 1) { "混音应只有一条音轨，实际 ${tracks.size}" }
            check(tracks[0].startsWith("audio/")) { "混音轨不是音频：${tracks[0]}" }
            val duration = durationMs(mixedFile)
            check(duration in 200..1_200) { "混音时长异常：${duration}ms" }
            decodeRms(mixedFile, 0)
        } finally {
            mixedFile.delete()
        }

        val separateRms = try {
            write(listOf(
                Track(ScreenRecordingAudio.TRACK_SYSTEM, left),
                Track(ScreenRecordingAudio.TRACK_MICROPHONE, right)
            ), separateFile)
            val tracks = describe(separateFile)
            check(tracks.size == 2) { "分轨应写两条音轨，实际 ${tracks.size}" }
            check(tracks.all { it.startsWith("audio/") }) { "分轨轨类型异常：$tracks" }
            val duration = durationMs(separateFile)
            check(duration in 200..1_200) { "分轨时长异常：${duration}ms" }
            listOf(decodeRms(separateFile, 0), decodeRms(separateFile, 1))
        } finally {
            separateFile.delete()
        }

        // Two different tones must stay two distinct non-silent tracks, not one content copied twice.
        check(separateRms.all { it > 500 }) { "分轨中有空轨：$separateRms" }
        check(abs(separateRms[0] - separateRms[1]) > 1) { "两条分轨内容相同：$separateRms" }
        check(mixedRms > 500) { "混音轨是静音：$mixedRms" }

        return "SCREENSHOT_RECORDING_AUDIO_PASS:mixed=${mixedRms}:separate=${separateRms.joinToString("+")}" +
            ":rate=${ScreenRecordingAudio.SAMPLE_RATE}:channels=${ScreenRecordingAudio.CHANNELS}" +
            ":bitrate=${ScreenRecordingAudio.BITRATE}"
    }

    private fun tone(frequency: Double, seconds: Double): ShortArray {
        val frames = (ScreenRecordingAudio.SAMPLE_RATE * seconds).toInt()
        val pcm = ShortArray(frames * ScreenRecordingAudio.CHANNELS)
        for (frame in 0 until frames) {
            val value = (sin(2 * PI * frequency * frame / ScreenRecordingAudio.SAMPLE_RATE) * 12_000).toInt().toShort()
            pcm[frame * ScreenRecordingAudio.CHANNELS] = value
            pcm[frame * ScreenRecordingAudio.CHANNELS + 1] = value
        }
        return pcm
    }

    /**
     * Encodes every track first (collecting its output format), then muxes: `addTrack` is only legal
     * before `start()`, so buffering the encoded samples is the only way to build a multi-track file
     * without relying on all encoders reaching INFO_OUTPUT_FORMAT_CHANGED in lockstep.
     */
    private fun write(tracks: List<Track>, target: File) {
        val encoded = tracks.map { encodeTrack(it) }
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val ids = encoded.map { muxer.addTrack(it.format) }
            muxer.start()
            val info = MediaCodec.BufferInfo()
            encoded.forEachIndexed { index, track ->
                for ((ptsUs, bytes) in track.samples) {
                    info.set(0, bytes.size, ptsUs, 0)
                    muxer.writeSampleData(ids[index], ByteBuffer.wrap(bytes), info)
                }
            }
        } finally {
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun encodeTrack(track: Track): Encoded {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
            ScreenRecordingAudio.SAMPLE_RATE, ScreenRecordingAudio.CHANNELS)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, ScreenRecordingAudio.BITRATE)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, ScreenRecordingAudio.SLOT_BYTES)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val samples = ArrayList<Pair<Long, ByteArray>>()
        var output: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var fed = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        if (fed >= track.pcm.size) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val count = minOf(ScreenRecordingAudio.SLOT_SAMPLES, track.pcm.size - fed)
                            val buffer = codec.getInputBuffer(index)!!
                            buffer.clear()
                            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            for (sample in 0 until count) buffer.putShort(track.pcm[fed + sample])
                            val ptsUs = fed / ScreenRecordingAudio.CHANNELS * 1_000_000L / ScreenRecordingAudio.SAMPLE_RATE
                            codec.queueInputBuffer(index, 0, count * ScreenRecordingAudio.BYTES_PER_SAMPLE, ptsUs, 0)
                            fed += count
                        }
                    }
                }
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        output = codec.outputFormat
                        continue
                    }
                    if (index < 0) continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(index)!!
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes, 0, info.size)
                        samples += info.presentationTimeUs to bytes
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        return Encoded(output ?: error("编码器没有输出轨道格式"), samples)
    }

    private fun describe(file: File): List<String> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return (0 until extractor.trackCount).map {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: "?"
            }
        } finally {
            extractor.release()
        }
    }

    private fun durationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1
        } finally {
            retriever.release()
        }
    }

    /** Decodes one track to PCM and returns its RMS, so "wrote a track" also means "audio is in it". */
    private fun decodeRms(file: File, trackIndex: Int): Int {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var sum = 0.0
            var count = 0L
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    out == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    out >= 0 -> {
                        val buffer = codec.getOutputBuffer(out)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = buffer.asShortBuffer()
                            while (shorts.hasRemaining()) {
                                val value = shorts.get().toInt()
                                sum += (value * value).toDouble()
                                count++
                            }
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return if (count == 0L) 0 else sqrt(sum / count).toInt()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
