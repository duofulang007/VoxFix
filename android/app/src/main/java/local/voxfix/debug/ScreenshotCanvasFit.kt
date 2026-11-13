package local.voxfix.debug

/**
 * 画布基准与「满宽」档位：`containScale` 是整幅比例，`widthScale` 是按宽度铺满的比例。
 * 画布把它们换算成缩放倍数（整幅=1，满宽=widthScale/containScale），手势在这之间连续变化。
 *
 * 前端 `preview/image-fit.mjs` 必须给出同一组结果——截图时预览与编辑时的显示规则要一致。
 * 两边各有一份单测跑同一张矩阵，改公式必须两边一起改。
 */
object ScreenshotCanvasFit {
    /** [canFitWidth] 为真才存在「满宽」档位：铺满宽度要明显大于整幅，否则双击没有意义。 */
    data class Fit(val containScale: Float, val widthScale: Float, val canFitWidth: Boolean)

    fun of(imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): Fit {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return Fit(1f, 1f, false)
        }
        val contain = minOf(1f, viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        val width = minOf(1f, viewWidth.toFloat() / imageWidth)
        return Fit(contain, width, width > contain * 1.05f)
    }
}
