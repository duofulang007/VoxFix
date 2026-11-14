package local.voxfix.debug

/**
 * 弹层落点（纯函数，JVM 可测）。
 *
 * 刻意不接收 `android.graphics.Rect` / 返回 `Point`：单元测试里的 android 类是抛
 * `Stub!` 的空壳，用了就只能跑真机。参数一律用 dp/px 的 Int。
 *
 * 原版 Popover 挂在触发器下方、到屏幕边缘上翻；改造前字体面板用的是 `Gravity.CENTER`
 * （与触发器无关，正好落在屏幕中央／输入法区域），是「弹出来却点不到」的原因之一。
 */
internal object ScreenshotPopupPlacement {
    data class Result(val x: Int,val y: Int)

    fun place(anchorLeft: Int,anchorTop: Int,anchorRight: Int,anchorBottom: Int,
              width: Int,height: Int,screenWidth: Int,screenHeight: Int,gap: Int,margin: Int = 8): Result {
        val centerX=(anchorLeft+anchorRight)/2
        val below=anchorBottom+gap
        val above=anchorTop-gap-height
        val y=when {
            below+height<=screenHeight-margin -> below
            above>=margin -> above
            else -> margin
        }
        val x=(centerX-width/2).coerceIn(margin,(screenWidth-width-margin).coerceAtLeast(margin))
        return Result(
            x,
            y.coerceIn(margin,(screenHeight-height-margin).coerceAtLeast(margin)))
    }
}
