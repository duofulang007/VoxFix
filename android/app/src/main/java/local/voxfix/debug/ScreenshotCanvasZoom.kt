package local.voxfix.debug

/**
 * 画布缩放纯函数：clamp 区间、捏合目标、双击档位。
 *
 * 手势状态机（ScreenshotCaptureOverlay）的 zoom 语义全部从这里取值，
 * JVM 单测直接覆盖；与 ScreenshotCanvasFit 一样属于「公式不动 UI」的可测层。
 */
object ScreenshotCanvasZoom {
    /** zoom 的合法区间：1f（整幅）~ [max]。窗口尺寸变化后旧 zoom 可能越界，统一在此收口。 */
    fun clamp(zoom: Float, max: Float): Float = zoom.coerceIn(1f, max)

    /**
     * 缩放上限：没有档位信息（未布局）时 4f；有「满宽」信息时取满宽倍数的 1.2 倍，
     * 长图也能放到 1:1 以上；整体夹在 [4f, 12f]。
     */
    fun maxZoom(fit: ScreenshotCanvasFit.Fit?): Float {
        val fit = fit ?: return 4f
        val widthRatio = if (fit.containScale > 0f) fit.widthScale / fit.containScale else 1f
        return maxOf(4f, widthRatio * 1.2f).coerceIn(1f, 12f)
    }

    /** 「适应宽度」对应的缩放倍数（整幅为 1）；无档位信息时 1f（双击无意义，会早退）。 */
    fun widthZoom(fit: ScreenshotCanvasFit.Fit?): Float {
        val fit = fit ?: return 1f
        return if (fit.containScale > 0f) fit.widthScale / fit.containScale else 1f
    }

    /** 双指捏合目标：以焦点比例连续缩放，结果夹回合法区间（缩不回去的 bug 都出在这一步失守）。 */
    fun pinchTarget(zoom: Float, scaleFactor: Float, max: Float): Float =
        (zoom * scaleFactor).coerceIn(1f, max)

    /**
     * 双击目标档位：低于满宽 85% 视为「想去满宽」，否则回整幅；两者相同（普通截图）或
     * 与当前 zoom 差距过小（<2%）时返回 null——调用方直接早退，不做无意义的动画。
     */
    fun doubleTapTarget(zoom: Float, fit: ScreenshotCanvasFit.Fit?): Float? {
        val widthZoom = widthZoom(fit)
        val target = if (zoom < widthZoom * 0.85f) widthZoom.coerceAtMost(maxZoom(fit)) else 1f
        return if (Math.abs(target - zoom) < 0.02f) null else target
    }
}
