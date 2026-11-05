package local.voxfix.debug

import android.content.Context
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Screen recording preferences, mirroring Snow Shot's `RecordingSettings`.
 *
 * The upstream option set is larger (encoder/preset/mouse-keyboard effects). Only the values the
 * phone build actually honours are stored here; the rest stay documented as pending in
 * `docs/SnowShot-T28-录屏-实现与验收-2026-11-06.md`.
 */
internal data class ScreenRecordingOptions(
    val format: Format = Format.MP4,
    val clarity: Clarity = Clarity.FULL_HD,
    val frameRate: Int = 30,
    val quality: Int = 80,
    val microphone: Boolean = false,
    val systemAudio: Boolean = true,
    val startDelaySeconds: Int = 0
) {
    enum class Format(val extension: String, val hasAudio: Boolean) {
        MP4("mp4", true), GIF("gif", false), APNG("apng", false), WEBP("webp", false)
    }

    enum class Clarity(val maxWidth: Int, val maxHeight: Int) {
        UHD_4K(3840, 2160), QHD_2K(2560, 1440), FULL_HD(1920, 1080), HD_720(1280, 720), SD_480(854, 480)
    }

    val audioEnabled: Boolean get() = format.hasAudio && (microphone || systemAudio)
}

internal object ScreenRecordingGeometry {
    /** Upstream `screenRecordingMaximumSizeForClarity`: landscape bounds per clarity. */
    fun maximumSize(clarity: ScreenRecordingOptions.Clarity): Pair<Int, Int> =
        clarity.maxWidth to clarity.maxHeight

    /** Upstream `screenRecordingOrientedMaximumSize`: transpose when capture and bounds disagree. */
    fun orientedMaximumSize(maximum: Pair<Int, Int>, captureWidth: Int, captureHeight: Int): Pair<Int, Int> {
        val maximumLandscape = maximum.first > maximum.second
        val maximumPortrait = maximum.second > maximum.first
        val captureLandscape = captureWidth > captureHeight
        val capturePortrait = captureHeight > captureWidth
        return if ((maximumLandscape && capturePortrait) || (maximumPortrait && captureLandscape))
            maximum.second to maximum.first else maximum
    }

    /** Output size for a capture region, scaled down to the clarity bound and even-aligned. */
    fun outputSize(width: Int, height: Int, clarity: ScreenRecordingOptions.Clarity): Pair<Int, Int> {
        require(width > 0 && height > 0) { "录制区域为空" }
        val maximum = orientedMaximumSize(maximumSize(clarity), width, height)
        val scale = min(1f, min(maximum.first.toFloat() / width, maximum.second.toFloat() / height))
        return even((width * scale).roundToInt()) to even((height * scale).roundToInt())
    }

    /** Normalised crop inside the captured frame, as `(u, v, width, height)`. */
    fun cropFraction(left: Int, top: Int, right: Int, bottom: Int, screenWidth: Int, screenHeight: Int): FloatArray {
        require(screenWidth > 0 && screenHeight > 0) { "屏幕尺寸无效" }
        val l = left.coerceIn(0, screenWidth)
        val t = top.coerceIn(0, screenHeight)
        val r = right.coerceIn(0, screenWidth)
        val b = bottom.coerceIn(0, screenHeight)
        require(r > l && b > t) { "录制区域不在屏幕内" }
        return floatArrayOf(
            l.toFloat() / screenWidth,
            t.toFloat() / screenHeight,
            (r - l).toFloat() / screenWidth,
            (b - t).toFloat() / screenHeight
        )
    }

    /** Converts a paused interval into the offset subtracted from every later timestamp. */
    fun pausedOffsetMicros(nowNs: Long, pauseStartNs: Long, accumulatedNs: Long): Long =
        accumulatedNs + (nowNs - pauseStartNs).coerceAtLeast(0)

    fun presentationTimeUs(nowNs: Long, startNs: Long, pausedNs: Long): Long =
        ((nowNs - startNs - pausedNs).coerceAtLeast(0)) / 1_000

    fun formatDuration(milliseconds: Long): String {
        val total = (milliseconds / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60)
    }

    fun fileName(timestamp: Long): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.CHINA)
            .format(java.util.Date(timestamp))
        return "VoxFix 录屏 $stamp.mp4"
    }

    /** Video bitrate in bits per second for the chosen size, rate and 0–100 quality. */
    fun bitrate(width: Int, height: Int, frameRate: Int, quality: Int): Int {
        val rate = frameRate.coerceIn(5, 60)
        val q = quality.coerceIn(0, 100)
        val raw = width.toLong() * height * rate * 0.07 * (q / 80.0)
        return raw.toLong().coerceIn(1_000_000L, 40_000_000L).toInt()
    }

    private fun even(value: Int): Int = (value / 2 * 2).coerceAtLeast(16)
}

internal class ScreenRecordingPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("voxfix-recording", Context.MODE_PRIVATE)

    fun load(): ScreenRecordingOptions {
        val defaults = ScreenRecordingOptions()
        return ScreenRecordingOptions(
            format = runCatching { ScreenRecordingOptions.Format.valueOf(prefs.getString("format", null) ?: defaults.format.name) }
                .getOrDefault(defaults.format),
            clarity = runCatching { ScreenRecordingOptions.Clarity.valueOf(prefs.getString("clarity", null) ?: defaults.clarity.name) }
                .getOrDefault(defaults.clarity),
            frameRate = prefs.getInt("frameRate", defaults.frameRate).coerceIn(5, 60),
            quality = prefs.getInt("quality", defaults.quality).coerceIn(0, 100),
            microphone = prefs.getBoolean("microphone", defaults.microphone),
            systemAudio = prefs.getBoolean("systemAudio", defaults.systemAudio),
            startDelaySeconds = prefs.getInt("startDelay", defaults.startDelaySeconds).coerceIn(0, 30)
        )
    }

    fun save(options: ScreenRecordingOptions) {
        prefs.edit()
            .putString("format", options.format.name)
            .putString("clarity", options.clarity.name)
            .putInt("frameRate", options.frameRate)
            .putInt("quality", options.quality)
            .putBoolean("microphone", options.microphone)
            .putBoolean("systemAudio", options.systemAudio)
            .putInt("startDelay", options.startDelaySeconds)
            .apply()
    }
}
