package local.voxfix.debug

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRecordingAudioTest {
    /** Upstream `RecordingAudioMode::tracks`: 混音一轨；分轨按启用音源各一轨，系统在前。 */
    @Test fun trackKeysFollowUpstreamAudioMode() {
        assertEquals(listOf("mixed"), ScreenRecordingAudio.trackKeys(system = true, microphone = false, separate = false))
        assertEquals(listOf("mixed"), ScreenRecordingAudio.trackKeys(system = false, microphone = true, separate = false))
        assertEquals(listOf("mixed"), ScreenRecordingAudio.trackKeys(system = true, microphone = true, separate = false))
        assertEquals(emptyList<String>(), ScreenRecordingAudio.trackKeys(system = false, microphone = false, separate = false))

        assertEquals(listOf("system"), ScreenRecordingAudio.trackKeys(system = true, microphone = false, separate = true))
        assertEquals(listOf("microphone"), ScreenRecordingAudio.trackKeys(system = false, microphone = true, separate = true))
        assertEquals(listOf("system", "microphone"),
            ScreenRecordingAudio.trackKeys(system = true, microphone = true, separate = true))
        assertEquals(emptyList<String>(), ScreenRecordingAudio.trackKeys(system = false, microphone = false, separate = true))
    }

    @Test fun slotGeometryMatchesUpstreamPackets() {
        // 48 kHz × 10 ms = 480 帧；双声道 = 960 样本 = 1920 字节。
        assertEquals(48_000, ScreenRecordingAudio.SAMPLE_RATE)
        assertEquals(2, ScreenRecordingAudio.CHANNELS)
        assertEquals(480, ScreenRecordingAudio.SLOT_FRAMES)
        assertEquals(960, ScreenRecordingAudio.SLOT_SAMPLES)
        assertEquals(1_920, ScreenRecordingAudio.SLOT_BYTES)
        assertEquals(10, ScreenRecordingAudio.JITTER_SLOTS)
        assertEquals(160_000, ScreenRecordingAudio.BITRATE)
        // 槽号按帧号整除。
        assertEquals(0L, ScreenRecordingAudio.slotOf(479))
        assertEquals(1L, ScreenRecordingAudio.slotOf(480))
        assertEquals(2L, ScreenRecordingAudio.slotOf(1_000))
    }

    /** `mix_audio_slot`: 逐样本相加并夹在 i16 区间，不做平均。 */
    @Test fun mixAddsSamplesAndClampsToInt16() {
        val system = shortArrayOf(30_000, -30_000, 0, 1_000)
        val microphone = shortArrayOf(30_000, -30_000, 0, -2_000)
        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 0, -1_000),
            ScreenRecordingAudio.mixSlot(system, microphone, 4))
    }

    @Test fun mixTreatsMissingSourceAsSilence() {
        val system = shortArrayOf(1_000, 2_000, 3_000)
        assertEquals(system.toList(), ScreenRecordingAudio.mixSlot(system, null, 3).toList())
        assertEquals(system.toList(), ScreenRecordingAudio.mixSlot(null, system, 3).toList())
        assertArrayEquals(ShortArray(3), ScreenRecordingAudio.mixSlot(null, null, 3))
    }

    /** 短于槽长的输入按静音补齐，不会把上一槽的尾巴带进来。 */
    @Test fun mixZeroPadsShortSlots() {
        val mixed = ScreenRecordingAudio.mixSlot(shortArrayOf(500), shortArrayOf(-200), 4)
        assertEquals(listOf<Short>(300, 0, 0, 0), mixed.toList())
        assertEquals(4, mixed.size)
    }

    @Test fun monoUpmixDuplicatesIntoBothChannels() {
        val stereo = ScreenRecordingAudio.upmixMonoToStereo(shortArrayOf(7, -8), 3)
        assertEquals(listOf<Short>(7, 7, -8, -8, 0, 0), stereo.toList())
    }

    @Test fun optionsReportTrackCountAndSummary() {
        val mixed = ScreenRecordingOptions(microphone = true, systemAudio = true)
        assertEquals(1, mixed.audioTrackCount)
        assertEquals("双源", mixed.audioSummary)

        val separate = mixed.copy(separateAudioTracks = true)
        assertEquals(2, separate.audioTrackCount)

        val systemOnly = ScreenRecordingOptions(microphone = false, systemAudio = true, separateAudioTracks = true)
        assertEquals(1, systemOnly.audioTrackCount)
        assertEquals("系统声音", systemOnly.audioSummary)

        val silent = ScreenRecordingOptions(microphone = false, systemAudio = false)
        assertEquals(0, silent.audioTrackCount)
        assertEquals("静音", silent.audioSummary)

        // 动画格式不含音轨（上游 `audioSupported = format == mp4`）。
        val animated = separate.copy(format = ScreenRecordingOptions.Format.GIF)
        assertEquals(0, animated.audioTrackCount)
        assertTrue(!animated.audioEnabled)
    }
}
