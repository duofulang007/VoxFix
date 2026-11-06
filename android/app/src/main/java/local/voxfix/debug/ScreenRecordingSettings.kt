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
    // Upstream defaults to 1080p, but phone screens are taller than 1920 (K60 is 1080x2400), which
    // would downscale every full-height capture by 20%. 2K keeps the native pixel grid there.
    val clarity: Clarity = Clarity.QHD_2K,
    val frameRate: Int = 30,
    val quality: Int = 80,
    val microphone: Boolean = false,
    val systemAudio: Boolean = true,
    val startDelaySeconds: Int = 0,
    // Upstream keeps separate export settings for animated images (`animated_image_clarity`,
    // `animated_image_frame_rate`, `loop_animated_images`): 720p, 10 fps and looping on by default.
    val animatedClarity: Clarity = Clarity.HD_720,
    val animatedFrameRate: Int = 10,
    val loopAnimated: Boolean = true
) {
    enum class Format(val extension: String, val hasAudio: Boolean, val label: String) {
        MP4("mp4", true, "MP4"), GIF("gif", false, "GIF"), APNG("apng", false, "APNG"), WEBP("webp", false, "WebP");

        val mime: String
            get() = when (this) {
                MP4 -> "video/mp4"
                GIF -> "image/gif"
                APNG -> "image/apng"
                WEBP -> "image/webp"
            }

        val animated: Boolean get() = this != MP4
    }

    enum class Clarity(val maxWidth: Int, val maxHeight: Int, val label: String) {
        UHD_4K(3840, 2160, "4K"), QHD_2K(2560, 1440, "2K"), FULL_HD(1920, 1080, "1080P"),
        HD_720(1280, 720, "720P"), SD_480(854, 480, "480P")
    }

    /** Upstream only exposes 1080p / 720p / 480p for animated images. */
    val clarityOptions: List<Clarity>
        get() = if (format.animated) AnimatedClarities else Clarity.values().toList()

    val frameRateOptions: List<Int>
        get() = if (format.animated) AnimatedFrameRates else VideoFrameRates

    val effectiveClarity: Clarity get() = if (format.animated) animatedClarity else clarity

    val effectiveFrameRate: Int get() = if (format.animated) animatedFrameRate else frameRate

    val audioEnabled: Boolean get() = format.hasAudio && (microphone || systemAudio)

    companion object {
        val AnimatedClarities = listOf(Clarity.FULL_HD, Clarity.HD_720, Clarity.SD_480)
        val AnimatedFrameRates = listOf(5, 10, 15, 24)
        val VideoFrameRates = listOf(15, 24, 30, 60)
    }
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

    /**
     * Normalised crop inside the captured frame, as `(u, v, width, height)`.
     *
     * [insetPx] shrinks the crop by a few pixels so the recording border and any one-texel edge
     * blending can never leak into the file.
     */
    fun cropFraction(left: Int, top: Int, right: Int, bottom: Int, screenWidth: Int, screenHeight: Int,
                     insetPx: Int = 0): FloatArray {
        require(screenWidth > 0 && screenHeight > 0) { "屏幕尺寸无效" }
        var l = left.coerceIn(0, screenWidth)
        var t = top.coerceIn(0, screenHeight)
        var r = right.coerceIn(0, screenWidth)
        var b = bottom.coerceIn(0, screenHeight)
        if (insetPx > 0 && r - l > insetPx * 2 + 8 && b - t > insetPx * 2 + 8) {
            l += insetPx; t += insetPx; r -= insetPx; b -= insetPx
        }
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

    /**
     * True when the screen rotated away from the orientation the frame was drawn in.
     *
     * A capture region is fixed in screen pixels, so a rotation invalidates it: upstream keeps the
     * recording area fixed and the projection cannot be re-cropped mid-session. Unknown rotations
     * (-1) never count as a change, so a ROM that refuses to report one cannot cancel a session.
     */
    fun rotationSuperseded(baseline: Int, current: Int): Boolean =
        baseline >= 0 && current >= 0 && current != baseline

    fun presentationTimeUs(nowNs: Long, startNs: Long, pausedNs: Long): Long =
        ((nowNs - startNs - pausedNs).coerceAtLeast(0)) / 1_000

    fun formatDuration(milliseconds: Long): String {
        val total = (milliseconds / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60)
    }

    fun fileName(timestamp: Long, extension: String = "mp4"): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.CHINA)
            .format(java.util.Date(timestamp))
        return "VoxFix 录屏 $stamp.$extension"
    }

    /**
     * Video bitrate for the chosen size, rate and 0–100 quality.
     *
     * Ported verbatim from Snow Shot's `video_quality.rs::smart_quality_bitrate_kbps`: a 1080p30
     * ladder of roughly 3.5 Mbps at quality 0 up to 18 Mbps at 100, scaled by pixel count (^0.92)
     * and frame rate (^0.85), with a floor so small regions do not starve.
     */
    fun bitrate(width: Int, height: Int, frameRate: Int, quality: Int): Int {
        val w = width.coerceAtLeast(1).toDouble()
        val h = height.coerceAtLeast(1).toDouble()
        val f = frameRate.coerceIn(1, 120).toDouble()
        val q = quality.coerceIn(0, 100) / 100.0
        val base = 3_500.0 + 14_500.0 * Math.pow(q, 1.35)
        val pixelScale = Math.pow(w * h / (1920.0 * 1080.0), 0.92)
        val fpsScale = Math.pow(f / 30.0, 0.85)
        val minimum = (w * h * f * 0.028 / 1000.0).coerceIn(600.0, 6_000.0)
        val kbps = maxOf(base * pixelScale * fpsScale, minimum).coerceIn(600.0, 240_000.0)
        return (kbps * 1000).toInt()
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
            startDelaySeconds = prefs.getInt("startDelay", defaults.startDelaySeconds).coerceIn(0, 30),
            animatedClarity = runCatching {
                ScreenRecordingOptions.Clarity.valueOf(prefs.getString("animatedClarity", null) ?: defaults.animatedClarity.name)
            }.getOrDefault(defaults.animatedClarity).takeIf { it in ScreenRecordingOptions.AnimatedClarities }
                ?: defaults.animatedClarity,
            animatedFrameRate = prefs.getInt("animatedFrameRate", defaults.animatedFrameRate)
                .coerceIn(1, 24),
            loopAnimated = prefs.getBoolean("loopAnimated", defaults.loopAnimated)
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
            .putString("animatedClarity", options.animatedClarity.name)
            .putInt("animatedFrameRate", options.animatedFrameRate)
            .putBoolean("loopAnimated", options.loopAnimated)
            .apply()
    }
}
