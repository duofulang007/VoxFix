package local.voxfix.debug

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import java.util.concurrent.Executors
import kotlin.math.pow

/**
 * 三档中性灰。取值见 docs/弧形入口状态色-2026-11-04.md，Android 为 #AARRGGBB。
 * 待机沉、激活亮/实，三档内部保持一致。
 */
internal enum class HandleTone(val idle: Int, val active: Int) {
    LIGHT(0x6b6b6b6b, 0xd16b6b6b.toInt()),                  // rgba(107,107,107,.42 / .82)
    MID(0xff979797.toInt(), 0xb3c7c7c7.toInt()),            // #ff979797 / #b3c7c7c7
    DARK(0x8cffffff.toInt(), 0xd1ffffff.toInt());           // rgba(255,255,255,.55 / .82)

    fun colorFor(pressed: Boolean) = if (pressed) active else idle
}

internal object HandleToneResolver {
    private const val KEY_TONE = "handle_tone"
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "handle-tone") }

    @Volatile
    private var cached: HandleTone? = null

    /** 主线程可随时调用：永不 IPC，只读内存或 prefs。 */
    fun peek(context: Context): HandleTone =
        cached ?: restore(context) ?: HandleTone.MID

    /**
     * 后台刷新。入口 show() 时调用一次即可。
     * 官方文档明确 getWallpaperColors 走 IPC 且不建议在 UI 线程调用。
     */
    fun refresh(context: Context, onReady: () -> Unit) {
        val app = context.applicationContext
        io.execute {
            val tone = runCatching { compute(app) }.getOrNull() ?: HandleTone.MID
            if (tone != cached) {
                cached = tone
                ScreenTextSettingsActivity.preferences(app).edit()
                    .putInt(KEY_TONE, tone.ordinal).apply()
                android.util.Log.i("VoxFix", "handle tone=$tone")
            }
            onReady()
        }
    }

    private fun restore(context: Context): HandleTone? {
        val prefs = ScreenTextSettingsActivity.preferences(context)
        val ordinal = prefs.getInt(KEY_TONE, -1)
        return HandleTone.entries.firstOrNull { it.ordinal == ordinal }?.also { cached = it }
    }

    private fun compute(context: Context): HandleTone {
        if (isNightMode(context)) return HandleTone.DARK
        // shortcut: 壁纸只能近似应用背景，需适配任意背景时另评双色描边。
        val colors = WallpaperManager.getInstance(context)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return HandleTone.MID
        if (colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0) return HandleTone.LIGHT
        return toneForLuminance(luminance(colors.primaryColor.toArgb()))
    }

    private fun isNightMode(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    /** 纯函数，可单测。 */
    internal fun toneForLuminance(l: Double): HandleTone = when {
        l > 0.55 -> HandleTone.LIGHT
        l > 0.12 -> HandleTone.MID
        else -> HandleTone.DARK
    }

    /** WCAG 相对亮度，白 = 1.0。不用 android.graphics.Color，否则 JVM 单测会 "not mocked"。 */
    internal fun luminance(argb: Int): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)
    }
}
