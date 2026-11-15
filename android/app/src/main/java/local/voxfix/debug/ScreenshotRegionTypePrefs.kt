package local.voxfix.debug

import android.content.Context

/** 截图区域形状的持久化（矩形 / 折线 / 曲线 / 手绘），与截图浮层 UI 解耦。 */
internal object ScreenshotRegionTypePrefs {
    private const val FILE = "screenshot-region-type"
    private const val KEY = "screenshot_selection/region_type"

    fun shape(context: Context): ScreenshotRegionShape = ScreenshotRegionShape.fromId(
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
    )

    fun save(context: Context, shape: ScreenshotRegionShape) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, shape.id).apply()
    }
}
