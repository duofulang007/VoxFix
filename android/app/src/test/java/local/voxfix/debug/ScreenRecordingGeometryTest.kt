package local.voxfix.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRecordingGeometryTest {
    @Test fun clarityBoundsMatchUpstream() {
        assertEquals(3840 to 2160, ScreenRecordingGeometry.maximumSize(ScreenRecordingOptions.Clarity.UHD_4K))
        assertEquals(2560 to 1440, ScreenRecordingGeometry.maximumSize(ScreenRecordingOptions.Clarity.QHD_2K))
        assertEquals(1920 to 1080, ScreenRecordingGeometry.maximumSize(ScreenRecordingOptions.Clarity.FULL_HD))
        assertEquals(1280 to 720, ScreenRecordingGeometry.maximumSize(ScreenRecordingOptions.Clarity.HD_720))
        assertEquals(854 to 480, ScreenRecordingGeometry.maximumSize(ScreenRecordingOptions.Clarity.SD_480))
    }

    @Test fun landscapeBoundsTransposeForPortraitCapture() {
        val landscape = 1920 to 1080
        assertEquals(1080 to 1920, ScreenRecordingGeometry.orientedMaximumSize(landscape, 917, 1364))
        assertEquals(1920 to 1080, ScreenRecordingGeometry.orientedMaximumSize(landscape, 1364, 917))
        assertEquals(1080 to 1920, ScreenRecordingGeometry.orientedMaximumSize(1080 to 1920, 917, 1364))
        assertEquals(1920 to 1080, ScreenRecordingGeometry.orientedMaximumSize(1080 to 1920, 1364, 917))
    }

    @Test fun outputSizeNeverUpscalesAndKeepsEvenDimensions() {
        // A 1080p portrait phone region already fits: only the odd width is even-aligned.
        assertEquals(916 to 1364, ScreenRecordingGeometry.outputSize(917, 1364, ScreenRecordingOptions.Clarity.FULL_HD))
        // A tall region is bounded by the portrait height.
        assertEquals(720 to 1080, ScreenRecordingGeometry.outputSize(2000, 3000, ScreenRecordingOptions.Clarity.HD_720))
        // A wide region is bounded by the landscape height once transposed.
        assertEquals(2880 to 2160, ScreenRecordingGeometry.outputSize(4000, 3000, ScreenRecordingOptions.Clarity.UHD_4K))
        // Tiny regions keep a usable floor.
        assertEquals(16 to 16, ScreenRecordingGeometry.outputSize(10, 10, ScreenRecordingOptions.Clarity.SD_480))
    }

    @Test fun cropFractionMapsSelectionIntoFrame() {
        val fraction = ScreenRecordingGeometry.cropFraction(100, 200, 500, 800, 1080, 2400)
        assertEquals(100f / 1080, fraction[0], 1e-6f)
        assertEquals(200f / 2400, fraction[1], 1e-6f)
        assertEquals(400f / 1080, fraction[2], 1e-6f)
        assertEquals(600f / 2400, fraction[3], 1e-6f)
    }

    @Test fun cropFractionClampsToFrame() {
        val fraction = ScreenRecordingGeometry.cropFraction(-10, -10, 100, 100, 1080, 2400)
        assertEquals(0f, fraction[0], 1e-6f)
        assertEquals(0f, fraction[1], 1e-6f)
        assertEquals(100f / 1080, fraction[2], 1e-6f)
        assertEquals(100f / 2400, fraction[3], 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyCropIsRejected() {
        ScreenRecordingGeometry.cropFraction(2000, 3000, 2100, 3100, 1080, 2400)
    }

    @Test fun pausedTimeIsSubtractedFromTheTimeline() {
        assertEquals(4000L, ScreenRecordingGeometry.pausedOffsetMicros(8_000, 5_000, 1_000))
        // A stale pause start never moves time backwards.
        assertEquals(1_000L, ScreenRecordingGeometry.pausedOffsetMicros(4_000, 5_000, 1_000))
        assertEquals(500_000L, ScreenRecordingGeometry.presentationTimeUs(2_000_000_000, 1_000_000_000, 500_000_000))
        assertEquals(0L, ScreenRecordingGeometry.presentationTimeUs(1_000_000_000, 2_000_000_000, 0))
    }

    @Test fun durationUsesHoursMinutesSeconds() {
        assertEquals("00:00:00", ScreenRecordingGeometry.formatDuration(0))
        assertEquals("00:00:09", ScreenRecordingGeometry.formatDuration(9_600))
        assertEquals("00:01:05", ScreenRecordingGeometry.formatDuration(65_000))
        assertEquals("01:00:00", ScreenRecordingGeometry.formatDuration(3_600_000))
        assertEquals("00:00:00", ScreenRecordingGeometry.formatDuration(-50))
    }

    @Test fun fileNameFollowsTheScreenshotNamingStyle() {
        val name = ScreenRecordingGeometry.fileName(1_760_000_000_000)
        assertTrue(name, Regex("""VoxFix 录屏 \d{4}-\d{2}-\d{2} \d{2}-\d{2}-\d{2}\.mp4""").matches(name))
    }

    @Test fun bitrateScalesWithPixelsFrameRateAndQuality() {
        val standard = ScreenRecordingGeometry.bitrate(1920, 1080, 30, 80)
        assertTrue(standard in 4_200_000..4_500_000)
        assertTrue(ScreenRecordingGeometry.bitrate(1920, 1080, 30, 100) > standard)
        assertTrue(ScreenRecordingGeometry.bitrate(1920, 1080, 30, 40) < standard)
        assertTrue(ScreenRecordingGeometry.bitrate(1920, 1080, 60, 80) > standard)
        assertTrue(ScreenRecordingGeometry.bitrate(3840, 2160, 60, 100) <= 40_000_000)
        assertTrue(ScreenRecordingGeometry.bitrate(16, 16, 5, 0) >= 1_000_000)
    }

    @Test fun onlyMp4CarriesAudio() {
        val silenceMic = ScreenRecordingOptions(format = ScreenRecordingOptions.Format.GIF, microphone = true)
        assertEquals(false, silenceMic.audioEnabled)
        val talk = ScreenRecordingOptions(format = ScreenRecordingOptions.Format.MP4, microphone = true, systemAudio = false)
        assertEquals(true, talk.audioEnabled)
        val none = ScreenRecordingOptions(format = ScreenRecordingOptions.Format.MP4, microphone = false, systemAudio = false)
        assertEquals(false, none.audioEnabled)
    }
}
