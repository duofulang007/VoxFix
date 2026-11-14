package local.voxfix.debug

/**
 * 画布视口：图片坐标 ↔ 视图坐标的唯一换算来源。
 *
 * 为什么单独抽出来：改造前 zoom 与 translation 分散在 `ScreenshotCaptureOverlay` 里，
 * 缩放锚点用的是**内容局部坐标**（`ScaleGestureDetector.focusX` 在白被 View 变换后是局部坐标），
 * 而 `translationX` 是**屏幕像素**，两套坐标系混用导致捏合时焦点漂移（"重影"、画面跑偏）。
 *
 * 这里的口径只有一套：`offsetX/offsetY` 与 [zoomBy] 的 `focusX/focusY` **都是视图坐标**，
 * 于是 `screen = offset + image * scale` 一条式子成立，焦点补偿也不需要再乘 zoom。
 *
 * 纯 float 数学、不依赖 `android.graphics`，因此可以被 JVM 单测直接覆盖
 * （见 `ScreenshotViewportTest`）；与 `ScreenshotCanvasFit`/`ScreenshotCanvasZoom` 同属
 * 「公式不动 UI」的可测层。
 *
 * 尺寸语义：这里的 [scale] 是**绝对比例**（图片像素 → 视图像素），不是改造前那个
 * 「整幅 = 1」的 zoom 倍率。两者换算为 `scale = zoom * fit.containScale`。
 */
class ScreenshotViewport(val imageWidth: Int, val imageHeight: Int) {

    var viewWidth: Float = 0f
        private set
    var viewHeight: Float = 0f
        private set

    /** 图片像素 → 视图像素。 */
    var scale: Float = 1f
        private set
    /** 图片原点 (0,0) 落在视图坐标系的位置（指 [contentLeft]/[contentTop] 那一点，不是整图原点）。 */
    var offsetX: Float = 0f
        private set
    var offsetY: Float = 0f
        private set

    /**
     * 画布可见范围（图片坐标）。
     *
     * 截图工程里底图是整屏截图的原始像素，但用户当时只框了一块；编辑器必须**只显示那一块**，
     * 否则他会看到一张陌生的全屏截图，完全找不到自己截的内容。默认等于整图。
     */
    var contentLeft: Float = 0f
        private set
    var contentTop: Float = 0f
        private set
    var contentRight: Float = imageWidth.toFloat()
        private set
    var contentBottom: Float = imageHeight.toFloat()
        private set
    val contentWidth: Float get() = contentRight - contentLeft
    val contentHeight: Float get() = contentBottom - contentTop

    /** 设定画布显示范围。越界会被夹进图片，退化到小于 1px 时按整图处理。 */
    fun setContentRect(left: Float, top: Float, right: Float, bottom: Float) {
        val iw = imageWidth.toFloat()
        val ih = imageHeight.toFloat()
        val l = left.coerceIn(0f, iw)
        val t = top.coerceIn(0f, ih)
        val r = right.coerceIn(0f, iw)
        val b = bottom.coerceIn(0f, ih)
        if (r - l < 1f || b - t < 1f) {
            contentLeft = 0f; contentTop = 0f; contentRight = iw; contentBottom = ih
            return
        }
        contentLeft = l; contentTop = t; contentRight = r; contentBottom = b
    }

    val hasSize: Boolean get() = viewWidth > 0f && viewHeight > 0f && imageWidth > 0 && imageHeight > 0

    /** 整幅（contain）对应的比例，也是缩放的下限。 */
    fun minScale(fit: ScreenshotCanvasFit.Fit?): Float =
        fit?.containScale?.takeIf { it > 0f } ?: 1f

    /**
     * 缩放上限 = 整幅比例 × 倍率上限。
     * 倍率沿用 [ScreenshotCanvasZoom.maxZoom]（满宽 ×1.2、整体夹在 [4,12]），
     * 这样"长图也能放到 1:1 以上"的既有语义不变。
     */
    fun maxScale(fit: ScreenshotCanvasFit.Fit?): Float {
        val min = minScale(fit)
        return (min * ScreenshotCanvasZoom.maxZoom(fit)).coerceAtLeast(min)
    }

    /** 首次布局或窗口尺寸变化：整幅（= 画布范围）居中。 */
    fun fit(w: Float, h: Float, fit: ScreenshotCanvasFit.Fit?) {
        viewWidth = w.coerceAtLeast(0f)
        viewHeight = h.coerceAtLeast(0f)
        val min = minScale(fit)
        previousMinScale = min
        scale = min
        offsetX = (viewWidth - contentWidth * min) / 2f
        offsetY = (viewHeight - contentHeight * min) / 2f
        bound()
    }

    /**
     * 窗口尺寸变化（旋转、分屏、键盘弹出）：保留当前相对倍率与视图中心对应的图片位置，
     * 而不是把用户重新丢回整幅。
     */
    fun resize(w: Float, h: Float, fit: ScreenshotCanvasFit.Fit?) {
        val nw = w.coerceAtLeast(0f)
        val nh = h.coerceAtLeast(0f)
        if (!hasSize || nw <= 0f || nh <= 0f) { fit(nw, nh, fit); return }
        // 视图中心现在指向哪张图片坐标（用旧的尺寸与比例算）
        val centerImageX = toImageX(viewWidth / 2f)
        val centerImageY = toImageY(viewHeight / 2f)
        val oldMin = previousMinScale.takeIf { it > 0f } ?: scale
        val ratio = scale / oldMin
        viewWidth = nw
        viewHeight = nh
        val min = minScale(fit)
        previousMinScale = min
        scale = clampScale(min * ratio, fit)
        offsetX = viewWidth / 2f - centerImageX * scale
        offsetY = viewHeight / 2f - centerImageY * scale
        bound()
    }

    private var previousMinScale = 1f

    fun setViewSize(w: Float, h: Float) {
        viewWidth = w.coerceAtLeast(0f)
        viewHeight = h.coerceAtLeast(0f)
    }

    /** 绝对写入（仅供双击动画等"每帧给目标值"的写入者使用）。@return 是否发生变化。 */
    fun set(newScale: Float, newOffsetX: Float, newOffsetY: Float, fit: ScreenshotCanvasFit.Fit?): Boolean {
        val beforeScale = scale
        val beforeX = offsetX
        val beforeY = offsetY
        scale = clampScale(newScale, fit)
        offsetX = newOffsetX
        offsetY = newOffsetY
        bound()
        return scale != beforeScale || offsetX != beforeX || offsetY != beforeY
    }

    fun clampScale(target: Float, fit: ScreenshotCanvasFit.Fit?): Float {
        val min = minScale(fit)
        val max = maxScale(fit)
        return target.coerceIn(min, max)
    }

    /**
     * 以视图坐标 [focusX],[focusY] 为锚点按 [factor] 缩放。
     *
     * 推导：缩放前该点下的图片像素 `q = (focus - offset) / scale`；
     * 要保持它在屏幕上不动，缩放到 `scale'` 后需 `offset' = focus - q * scale'`，
     * 代入 `ratio = scale'/scale` 得 `offset' = focus - (focus - offset) * ratio`。
     * 这里 focus 与 offset 同属视图坐标，所以**不需要**任何额外的 zoom 乘数。
     *
     * @return 视口是否真的发生了变化。调用方据此决定要不要重绘——缩放已到上下限时
     * 继续捏合不应该触发任何绘制。
     */
    fun zoomBy(factor: Float, focusX: Float, focusY: Float, fit: ScreenshotCanvasFit.Fit?): Boolean {
        if (factor <= 0f || factor.isNaN() || factor.isInfinite()) return false
        val target = clampScale(scale * factor, fit)
        if (target == scale) return false
        val ratio = target / scale
        offsetX = focusX - (focusX - offsetX) * ratio
        offsetY = focusY - (focusY - offsetY) * ratio
        scale = target
        bound()
        return true
    }

    /** 以视图坐标锚点直接缩放到 [targetScale]（双击动画用）。@return 是否发生变化。 */
    fun zoomTo(targetScale: Float, focusX: Float, focusY: Float, fit: ScreenshotCanvasFit.Fit?): Boolean {
        val target = clampScale(targetScale, fit)
        if (target == scale) return false
        val ratio = target / scale
        offsetX = focusX - (focusX - offsetX) * ratio
        offsetY = focusY - (focusY - offsetY) * ratio
        scale = target
        bound()
        return true
    }

    /**
     * 平移，增量单位是视图像素。
     *
     * @return 是否真的移动了。已经贴边时继续拖不应触发重绘——长图整幅显示时高度正好等于
     * 视口，任何纵向拖动都会被边界吃掉，那时逐帧重画底图纯属浪费。
     */
    fun panBy(dx: Float, dy: Float): Boolean {
        val beforeX = offsetX
        val beforeY = offsetY
        offsetX += dx
        offsetY += dy
        bound()
        return offsetX != beforeX || offsetY != beforeY
    }

    /**
     * 边界夹取，区间是连续的：图片边缘的视图位置被限制在
     * 「min(0, 视图-内容) … max(0, 视图-内容)」。
     *
     * 不能写成"内容小于视图就强制居中、否则贴边"的二值分支——那会在
     * 「内容恰好占满视图」的临界点上前后来回跳，那正是缩放抽搐的来源。
     */
    fun bound() {
        if (!hasSize) return
        val w = contentWidth * scale
        val h = contentHeight * scale
        val loX = minOf(0f, viewWidth - w)
        val hiX = maxOf(0f, viewWidth - w)
        val loY = minOf(0f, viewHeight - h)
        val hiY = maxOf(0f, viewHeight - h)
        offsetX = offsetX.coerceIn(loX, hiX)
        offsetY = offsetY.coerceIn(loY, hiY)
    }

    fun toScreenX(imageX: Float): Float = offsetX + (imageX - contentLeft) * scale
    fun toScreenY(imageY: Float): Float = offsetY + (imageY - contentTop) * scale
    fun toImageX(screenX: Float): Float =
        if (scale > 0f) contentLeft + (screenX - offsetX) / scale else contentLeft
    fun toImageY(screenY: Float): Float =
        if (scale > 0f) contentTop + (screenY - offsetY) / scale else contentTop

    /** 绘制用平移量：`canvas.translate(panX,panY); canvas.scale(scale)` 之后可直接用图片坐标绘制。 */
    val panX: Float get() = offsetX - contentLeft * scale
    val panY: Float get() = offsetY - contentTop * scale

    /** 屏幕 dp → 图片像素。所有"屏幕上固定大小"的手柄、命中半径都走这里。 */
    fun dpToImage(dp: Float, density: Float): Float = if (scale > 0f) dp * density / scale else dp * density

    /** 当前可见的图片区域，夹在画布范围里（drawBitmap 的 srcRect 不能越界）。 */
    val imageLeft: Float get() = toImageX(0f).coerceIn(contentLeft, contentRight)
    val imageTop: Float get() = toImageY(0f).coerceIn(contentTop, contentBottom)
    val imageRight: Float get() = toImageX(viewWidth).coerceIn(contentLeft, contentRight)
    val imageBottom: Float get() = toImageY(viewHeight).coerceIn(contentTop, contentBottom)

    /** 画布在视图里的显示尺寸（可能超出视图）。 */
    val displayWidth: Float get() = contentWidth * scale
    val displayHeight: Float get() = contentHeight * scale
}
