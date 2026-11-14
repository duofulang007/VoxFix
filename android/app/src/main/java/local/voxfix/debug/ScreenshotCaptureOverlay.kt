package local.voxfix.debug

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Insets
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.view.GestureDetector
import android.view.VelocityTracker
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs
import kotlin.math.exp
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.Space
import android.widget.TextView

/** One save: what the user sees, plus the project that produced it. */
class ScreenshotSaveRequest(
    val region: Rect,
    val composite: Bitmap?,
    val document: ScreenshotDocument,
    /** Set when this replaces an existing project record; the record keeps its id and revisions. */
    val replaceId: String? = null,
    /** Revision the editor opened; a newer save must win instead of being overwritten. */
    val expectedRevision: Int? = null,
    /** Set when a plain image is being annotated: the new record borrows that record's pixels. */
    val copySourceFromId: String? = null
)

/** A record reopened for editing. [document] is a copy; leaving the editor must not touch it. */
class ScreenshotProject(val document: ScreenshotDocument, val replaceId: String?, val revision: Int?, val copySourceFromId: String?)

/**
 * Frozen image, adjustable selection and an unsaved text layer. Saving is one optional exit;
 * copy, in-place editing and original/result switching appear only after recognition.
 *
 * Palette metrics follow Snow Shot's toolbar main panel (32px buttons, 24px icons, 12/4px panel
 * margins, 8px item spacing, 1x16px separators with 12px sides, 8px radius, white surface).
 */
class ScreenshotCaptureOverlay(
    private val context: Context,
    private var image: Bitmap,
    private val save: (ScreenshotSaveRequest) -> Unit,
    private val recognize: (Rect) -> Unit,
    private val onText: (String) -> Unit,
    private val exit: () -> Unit,
    private val interaction: () -> Unit,
    private val translator: ScreenshotTranslator,
    private val toolMemory: ScreenshotToolMemory?=null,
    /** Reopening a saved record: the viewport is fixed and its marks are already loaded. */
    private val project: ScreenshotProject?=null
) {
    private companion object {
        // 几何与色值统一在 ScreenshotToolbarMetrics / ScreenshotUiTokens（原版常量 + 手机侧扩展）。
        const val BUTTON = ScreenshotToolbarMetrics.MAIN_CELL
        const val ICON = ScreenshotToolbarMetrics.MAIN_ICON
        const val PANEL_H = ScreenshotToolbarMetrics.PANEL_PAD_H
        const val PANEL_V = ScreenshotToolbarMetrics.PANEL_PAD_V
        const val ITEM_SPACING = ScreenshotToolbarMetrics.MAIN_GAP
        const val SEPARATOR_WIDTH = ScreenshotToolbarMetrics.SEPARATOR_WIDTH
        const val SEPARATOR_HEIGHT = ScreenshotToolbarMetrics.SEPARATOR_HEIGHT
        const val SEPARATOR_SIDE = ScreenshotToolbarMetrics.SEPARATOR_SIDE
        const val PANEL_RADIUS = ScreenshotToolbarMetrics.PANEL_RADIUS
        const val COLOR_TEXT = ScreenshotUiTokens.TEXT
        const val COLOR_TEXT_DISABLED = ScreenshotUiTokens.TEXT_DISABLED
        const val COLOR_DANGER = ScreenshotUiTokens.DANGER
        const val COLOR_PRIMARY = ScreenshotUiTokens.PRIMARY
        const val COLOR_SEPARATOR = ScreenshotUiTokens.SEPARATOR
        const val COLOR_PANEL = ScreenshotUiTokens.PANEL
    }
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    /**
     * 画布视口：图片坐标 ↔ 视图坐标的唯一换算来源，也是缩放/平移的唯一真源。
     *
     * 改造前靠 View 变换（`content.scaleX/scaleY`）放大，那是在**已经按布局尺寸栅格化过的
     * 纹理**上做二次放大：长图（916×10192 布局到 216 宽）放大 4 倍必然发虚、边缘出双线。
     * 现在 content 恒等于窗口尺寸，底图与矢量都按当前比例重绘，长图 1:1 也清晰；
     * 同时捏合锚点用的 focus 与 offset 统一是视图坐标，不会再出现"两套坐标系混用"导致的漂移。
     */
    private val viewport = ScreenshotViewport(image.width, image.height)
    private fun applyCanvasBase(w: Int, h: Int, resetView: Boolean = false) {
        if (w <= 0 || h <= 0 || image.width <= 0 || image.height <= 0) return
        // 编辑已保存的截图时，画布范围 = 当初框选的那一块：底图是整屏原始像素，
        // 直接铺满会让用户看到一张陌生的全屏截图、找不到自己截的内容（2026-11-14 用户反馈）。
        // 新截图流程相反——必须看到全屏才能框选，所以那里保持整图。
        val region = if (project != null) (picked ?: Rect(0, 0, image.width, image.height))
        else Rect(0, 0, image.width, image.height)
        viewport.setContentRect(region.left.toFloat(), region.top.toFloat(),
            region.right.toFloat(), region.bottom.toFloat())
        val fit = ScreenshotCanvasFit.of(viewport.contentWidth.toInt(), viewport.contentHeight.toInt(), w, h)
        canvasFit = fit
        // 单位按整幅（= 画布范围）宽度冻结：缩放不改变已存笔画的像素粗细与字号。
        drawing.freezeUnits((viewport.contentWidth * fit.containScale).toInt().coerceAtLeast(1))
        // 首次布局整幅居中；窗口尺寸变化（旋转/分屏/键盘）保留当前倍率与视野中心，
        // 不把用户重新丢回整幅。恢复工程后画布范围会变，那次必须重新居中（resetView）。
        if (resetView || !canvasPositioned) { canvasPositioned = true; viewport.fit(w.toFloat(), h.toFloat(), fit) }
        else viewport.resize(w.toFloat(), h.toFloat(), fit)
        drawing.viewport = viewport
        selector.viewport = viewport
        backdropView.viewport = viewport
        drawing.backdrop = backdropTiles
        // 底图改由 ScreenshotCanvasSurface 直接采样源纹理，ImageView 那份低分辨率绘制必须让位，
        // 否则同一张图会被画两遍（旧的一份在上层放大后端就是"两层影像"）。
        background.visibility = View.GONE
        invalidateCanvas()
        if (fit.canFitWidth && !zoomHinted) {
            zoomHinted = true
            root.post { if (!disposed) showFeedback("双指缩放可看细节，双击在整幅与满宽之间切换") }
        }
    }
    private var zoomHinted = false
    private var canvasPositioned = false
    private var canvasFit: ScreenshotCanvasFit.Fit? = null
    /** 以整幅为 1 的缩放倍率（对外语义不变），实际比例由 [viewport] 持有。 */
    private val zoom: Float
        get() = canvasFit?.let { fit ->
            val min = fit.containScale
            if (min > 0f) viewport.scale / min else 1f
        } ?: 1f
    private var zoomAnimator: ValueAnimator? = null
    /** 双击动画/惯性仍在跑时，任何新的 zoom/translation 写者都要先停掉它：
     *  动画每帧绝对写 offset，与捏合/拖动的增量写叠加就是重影与「捏不动」。 */
    private fun cancelZoomAnimation() { zoomAnimator?.cancel() }
    private val maxZoom: Float get() = ScreenshotCanvasZoom.maxZoom(canvasFit)
    /** 「适应宽度」对应的缩放倍数（整幅为 1）。 */
    private val widthZoom: Float get() = ScreenshotCanvasZoom.widthZoom(canvasFit)
    private val root: FrameLayout = object : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean { interaction(); return super.dispatchTouchEvent(event) }
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width = View.MeasureSpec.getSize(widthSpec)
            if (width > 0 && toolbarAvailable != width-bars.left-bars.right-dp(16)) rebuildToolbar(width)
            super.onMeasure(widthSpec,heightSpec)
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w,h,oldw,oldh)
            applyCanvasBase(w,h)
        }
    }
    /**
     * 画布手势：双指捏合缩放 + 双指平移，单指在放大后拖动平移，双击切换整幅/满宽。
     * 与编辑工具的交接：双指优先，子层收到 CANCEL（绘图层会丢弃当前这一笔）；
     * 单指仅在「没有绘制工具，或工具是 SELECT」时接管，避免和画线/框选抢。
     */
    private val content: FrameLayout = object : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var panning = false
        private var twoFinger = false
        private var lastFocusX = 0f
        private var lastFocusY = 0f
        private var lastTapAt = 0L
        private var lastTapX = 0f
        private var lastTapY = 0f
        /** 本次按下是否够格记为一次「画布点按」：只有画布手势准入成立（无工具、非编辑态等）
         *  才参与双击判定。SELECT 下点选元素的两次点按不会再去触发整幅↔满宽跳变。 */
        private var tapEligible = false
        // 惯性滑动：长图一甩就滚过去，和看照片一致。速度来自 VelocityTracker（屏幕像素/s），
        // OverScroller 只负责衰减曲线；到边界时 translation 被 clamp 顶住，检测到不再移动就停。
        private val flingScroller = OverScroller(context)
        private val flingVelocity = VelocityTracker.obtain()
        private var flingLastX = 0
        private var flingLastY = 0
        // QuickScale：双击后不松手继续上下拖 → 连续缩放，与相册/标注 App 的手感一致。
        private var quickScaleArmed = false
        private var quickScaleStartY = 0f
        private var quickScaleAnchorX = 0f
        private var quickScaleAnchorY = 0f
        private var quickScaleTarget = 1f
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 检测器需要完整的 DOWN → POINTER_DOWN → MOVE 序列才能建立跨度。
                    scaleDetector.onTouchEvent(event)
                    // 按下即打断惯性，手指接住画面；并从头收集速度。
                    cancelZoomAnimation()
                    flingScroller.abortAnimation()
                    flingVelocity.clear(); flingVelocity.addMovement(event)
                    gestureActive = true
                    downX = event.x; downY = event.y; lastX = event.x; lastY = event.y
                    panning = false; twoFinger = false
                    tapEligible = canPanCanvas() && !busy && !editing
                    // 双击＝整幅 ↔ 满宽。手势要在拦截层判定：单指未被拦截时子层消费事件，
                    // 外面那层 GestureDetector 根本收不到第二次 DOWN。
                    val now = SystemClock.uptimeMillis()
                    val doubleTap = now - lastTapAt < 280L &&
                        abs(event.x - lastTapX) < dp(32) && abs(event.y - lastTapY) < dp(32)
                    if (doubleTap) {
                        lastTapAt = 0L
                        // 双击立即起动画（保留"双击有反应"的直觉），同时武装 QuickScale：
                        // 手指不抬起继续上下拖就接管成连续缩放——手机上看照片/标注的通行手势。
                        quickScaleArmed = true
                        quickScaleStartY = event.y
                        quickScaleAnchorX = event.x
                        quickScaleAnchorY = event.y
                        quickScaleTarget = handleDoubleTap(event.x, event.y) ?: viewport.scale
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        // 拦截后 POINTER_DOWN 不会再进 onTouchEvent，必须在这里喂一次，
                        // 否则 ScaleGestureDetector 拿不到初始跨度，双指只剩平移、没有缩放。
                        scaleDetector.onTouchEvent(event)
                        twoFinger = true
                        lastFocusX = focusX(event); lastFocusY = focusY(event)
                        // 第二根手指按下就接管：正在画的一笔由子层 CANCEL 丢弃，不会留半截。
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (quickScaleArmed) {
                        val dy = event.y - quickScaleStartY
                        if (abs(dy) > dp(10)) {
                            // 接管：停掉双击动画，从双击的目标比例起按位移指数缩放（向上拖=放大）。
                            // 用指数而不是线性，小位移时不会过冲，长拖也能到上限。
                            quickScaleArmed = false
                            cancelZoomAnimation()
                            val span = root.height.coerceAtLeast(1) * 0.6f
                            val factor = exp(-dy / span)
                            if (viewport.zoomTo(quickScaleTarget * factor, quickScaleAnchorX, quickScaleAnchorY, canvasFit)) {
                                gestureActive = true
                                invalidateCanvas()
                            }
                        }
                        return true
                    }
                    if (twoFinger) return true
                    if (panning) return true
                    if (!canPanCanvas()) return false
                    val dx = event.x - downX; val dy = event.y - downY
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    if (dx * dx + dy * dy > slop * slop) {
                        panning = true
                        lastX = event.x; lastY = event.y
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_UP -> {
                    // 只有够格的画布点按才参与双击判定：intercept 层总能收到 UP（哪怕被子层消费），
                    // SELECT 下连续两次点选元素若不设门槛会被误判成双击，画布突然跳档。
                    if (tapEligible && !panning && !twoFinger) {
                        lastTapAt = SystemClock.uptimeMillis(); lastTapX = event.x; lastTapY = event.y
                    }
                    tapEligible = false
                    panning = false; twoFinger = false; quickScaleArmed = false
                    gestureActive = false
                    placeControls()
                    return false
                }
                MotionEvent.ACTION_CANCEL -> {
                    tapEligible = false
                    panning = false; twoFinger = false; quickScaleArmed = false
                    gestureActive = false
                    placeControls()
                    return false
                }
            }
            return false
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            // POINTER_DOWN 已在拦截层喂过：这里再喂一次会让检测器重复建立跨度。
            if (event.actionMasked != MotionEvent.ACTION_POINTER_DOWN) scaleDetector.onTouchEvent(event)
            flingVelocity.addMovement(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    twoFinger = event.pointerCount >= 2
                    lastFocusX = focusX(event); lastFocusY = focusY(event)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (twoFinger) {
                        // 双指整体移动 = 平移；缩放交给 scaleDetector（它只报比例变化）。
                        // focus 与 viewport.offset 同属视图坐标（content 不再做 View 变换），
                        // 所以增量直接就是屏幕像素，不需要再乘 zoom。
                        val fx = focusX(event); val fy = focusY(event)
                        panByScreen(fx - lastFocusX, fy - lastFocusY)
                        lastFocusX = fx; lastFocusY = fy
                        return true
                    }
                    if (panning) {
                        panByScreen(event.x - lastX, event.y - lastY)
                        // VERBOSE 才输出：INFO 级每帧一条会自己把拖动拖慢。
                        if (android.util.Log.isLoggable("VoxFix", android.util.Log.VERBOSE)) {
                            android.util.Log.v("VoxFix", "CANVAS_DY oy=${viewport.offsetY} scale=${viewport.scale}")
                        }
                        lastX = event.x; lastY = event.y
                        return true
                    }
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // 抬起一根后剩下的那根继续拖，不跳。
                    if (event.pointerCount <= 2) {
                        twoFinger = false
                        val remaining = if (event.actionIndex == 0) 1 else 0
                        lastX = event.getX(remaining); lastY = event.getY(remaining)
                        panning = canPanCanvas()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val wasZoomed = twoFinger
                    twoFinger = false
                    quickScaleArmed = false
                    if (panning && !wasZoomed && canPanCanvas()) {
                        // 松手甩出去：OverScroller 按系统的摩擦曲线衰减，速度够才启动。
                        flingVelocity.computeCurrentVelocity(1000, 10000f)
                        val vx = flingVelocity.xVelocity
                        val vy = flingVelocity.yVelocity
                        // VERBOSE：INFO 每次松手一条不算重，但保持全文件一致，只在排障时打开。
                        if (android.util.Log.isLoggable("VoxFix", android.util.Log.VERBOSE)) {
                            android.util.Log.v("VoxFix", "CANVAS_FLING vx=$vx vy=$vy")
                        }
                        if (abs(vx) > 100f || abs(vy) > 100f) startFling(vx.toInt(), vy.toInt())
                    }
                    panning = false
                    flingVelocity.clear()
                    gestureActive = false
                    placeControls()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    twoFinger = false; panning = false; quickScaleArmed = false
                    flingScroller.abortAnimation()
                    flingVelocity.clear()
                    gestureActive = false
                    placeControls()
                    return true
                }
            }
            return true
        }
        /**
         * 惯性帧循环。不能用 computeScroll：content 没有背景（willNotDraw=true），
         * invalidate 不触发绘制管线，fling 会原地不动；postOnAnimation 是自驱的，每帧回调。
         * 到边界（translation 被 clamp 顶住）就停，不贴着墙打转。
         */
        private fun startFling(vx: Int, vy: Int) {
            flingLastX = 0; flingLastY = 0
            // 双击动画还在跑就先停：绝对写 translation 与 fling 的增量写不能同时存在。
            cancelZoomAnimation()
            gestureActive = true
            flingScroller.fling(0, 0, vx, vy, -1_000_000, 1_000_000, -1_000_000, 1_000_000)
            postOnAnimation { flingTick() }
        }
        private fun flingTick() {
            if (!flingScroller.computeScrollOffset()) {
                gestureActive = false
                placeControls()
                return
            }
            val dx = flingScroller.currX - flingLastX
            val dy = flingScroller.currY - flingLastY
            flingLastX = flingScroller.currX; flingLastY = flingScroller.currY
            val beforeX = viewport.offsetX
            val beforeY = viewport.offsetY
            panByScreen(dx.toFloat(), dy.toFloat())
            val stuckX = dx != 0 && viewport.offsetX == beforeX
            val stuckY = dy != 0 && viewport.offsetY == beforeY
            if ((stuckX && stuckY) || (stuckX && dy == 0) || (stuckY && dx == 0)) {
                flingScroller.abortAnimation()
                gestureActive = false
                placeControls()
                return
            }
            postOnAnimation { flingTick() }
        }
        private fun focusX(event: MotionEvent): Float {
            var sum = 0f
            for (i in 0 until event.pointerCount) sum += event.getX(i)
            return sum / event.pointerCount.coerceAtLeast(1)
        }
        private fun focusY(event: MotionEvent): Float {
            var sum = 0f
            for (i in 0 until event.pointerCount) sum += event.getY(i)
            return sum / event.pointerCount.coerceAtLeast(1)
        }
    }
    /** 双指捏合：以双指中心为锚点缩放，手感与看照片一致。 */
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (busy) return false
            // 捏合开始即接管：双击动画若还在跑，它的绝对写入会和这里的增量写打架
            // （表现为重影/捏不动）。写者唯一化——先停动画，本次手势成为唯一来源。
            cancelZoomAnimation()
            gestureActive = true
            // focusX/focusY 就是视图坐标（content 不再做 View 变换），视口内部
            // 用 `offset' = focus - (focus - offset) * ratio` 保证焦点下的像素不动，
            // 这里不需要任何手工补偿——旧实现少乘 zoom 的漂移从此不存在。
            if (viewport.zoomBy(detector.scaleFactor, detector.focusX, detector.focusY, canvasFit)) invalidateCanvas()
            return true
        }
    })
    /**
     * 单指平移的准入：无工具时整条手势交给画布（含惯性）。
     * SELECT 时不拦截——点到对象由选择逻辑接管，点到空白由绘图层经 onCanvasPan 转发平移，
     * 这样"拖对象"和"拖画布"互不抢。
     */
    private fun canPanCanvas(): Boolean =
        drawing.tool == null && !editing && annotationEditor == null && stackPopup == null &&
            regionOperation == null && !moveMode && !selector.selectionInProgress

    /** 双击：整幅 ↔ 满宽，以双击点为锚点放大，和看照片一致。@return 目标比例（无档位可切时为 null）。 */
    private fun handleDoubleTap(x: Float, y: Float): Float? {
        if (busy || editing) return null
        val targetZoom = ScreenshotCanvasZoom.doubleTapTarget(zoom, canvasFit) ?: return null
        val min = canvasFit?.containScale?.takeIf { it > 0f } ?: 1f
        val target = targetZoom * min
        animateZoomTo(target, x, y)
        return target
    }

    /**
     * 屏幕像素平移。双指/单指/惯性 fling 传进来的都是视图坐标增量
     * （content 不再做 View 变换，event.x 就是屏幕像素）。
     * 平移也是 touch 驱动的写者：拖动接手时先停掉可能还在跑的双击动画，并持有 gestureActive
     * （动画取消回调会把它复位，这里重新置位，保证手势期间 placeControls 不插进来重排）。
     */
    private fun panByScreen(dx: Float, dy: Float) {
        cancelZoomAnimation()
        gestureActive = true
        // 贴边时继续拖不重绘：长图整幅显示时纵向正好铺满，任何拖动都会被边界吃掉。
        if (viewport.panBy(dx, dy)) invalidateCanvas()
    }
    /** 松手吸附已移除：吸附会在整幅与满宽之间来回跳，编辑长图时根本没法停在中间比例。
     *  档位切换只通过双击显式触发。 */
    /**
     * 双击缩放动画：以视图坐标 [focusX]/[focusY] 为锚点，220ms 插值到 [targetScale]。
     * 每帧按「起始比例 + 起始偏移 + 锚点」绝对计算，不做增量累加（增量会和捏合叠出重影）。
     */
    private fun animateZoomTo(targetScale: Float, focusX: Float, focusY: Float) {
        cancelZoomAnimation()
        val start = viewport.scale
        if (start <= 0f || targetScale <= 0f) { placeControls(); return }
        val baseX = viewport.offsetX
        val baseY = viewport.offsetY
        gestureActive = true
        zoomAnimator = ValueAnimator.ofFloat(start, targetScale).apply {
            duration = 220
            addUpdateListener {
                val value = it.animatedValue as Float
                val ratio = value / start
                if (viewport.set(value, baseX + focusX * (1f - ratio), baseY + focusY * (1f - ratio), canvasFit)) invalidateCanvas()
            }
            // 自然结束与被取消（捏合/拖动接管）都会走到这里：gestureActive 复位与控制栏
            // 重排收口在一个地方，取消路径不再泄漏 gestureActive（否则 placeControls 永远不跑）。
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    gestureActive = false
                    invalidateCanvas()
                    placeControls()
                }
            })
            start()
        }
    }
    /**
     * 视口变化后刷新所有画布层。
     *
     * 这里不再改 View 变换——content 尺寸恒等于窗口，缩放与平移全部由视口换算后重绘。
     * 手势进行中不整栏重排：placeControls 要 measure 二级栏两次并 requestLayout，
     * 每帧跑一遍就是抽搐与掉帧残留（用户口中的"重影"）的主因。
     */
    private fun invalidateCanvas() {
        drawing.viewZoom = zoom
        // 底图只在视口变化时重画；标注变化走 drawing 自己的 invalidate，不会连累它。
        backdropView.invalidate()
        drawing.invalidate()
        selector.invalidate()
        resultLayer.invalidate()
        qrMarkers.invalidate()
        if (!gestureActive && zoomAnimator?.isRunning != true) scheduleControls()
    }
    /** 手势结束后的整栏重排走这里；手势中由各入口置位。 */
    private var gestureActive = false
    private var controlsSyncQueued = false
    private fun scheduleControls() {
        if (controlsSyncQueued) return
        controlsSyncQueued = true
        root.post {
            controlsSyncQueued = false
            if (!disposed) placeControls()
        }
    }
    // 画布在 root 中的原点与比例：root↔图片坐标的所有换算都走这里，
    // 否则画布被缩放/平移后，工具栏和状态条会锚在错误位置。
    // 画布在 root 中的原点与比例：root ↔ 图片坐标的所有换算都走 viewport。
    // content 恒等于 root 的尺寸（不再居中缩小、也不再做 View 变换），所以 viewport 的
    // offset 直接就是 root 坐标，不需要再叠加 content.left/top——旧实现正因为多叠了一次
    // 局部偏移，缩放后工具栏与状态条会锚在错误位置。
    private fun canvasLeft() = viewport.offsetX
    private fun canvasTop() = viewport.offsetY
    private fun canvasScaleX() = viewport.scale
    private fun canvasScaleY() = viewport.scale
    private fun canvasX(imageX: Int) = viewport.toScreenX(imageX.toFloat())
    private fun canvasY(imageY: Int) = viewport.toScreenY(imageY.toFloat())
    private val background = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(image) }
    /** 分块底图缓存：底图层与绘图层（仅高亮时）共享同一份。 */
    private val backdropTiles by lazy { ScreenshotTileBackdrop(image) }
    private val backdropView = ScreenshotBackdropView(context).apply {
        backdrop = backdropTiles
    }
    private var picked: Rect? = null
    private var busy = false
    /** 忙碌来源：ocr=识别文字，qr=二维码识别；转圈归属见 updateBusySpinner。 */
    private var busySource: String? = null
    /** 转圈最短显示时长：它只是交互反馈，不反映真实加载进度。 */
    private val SPINNER_MIN_MS = 900L
    private var recapturing = false
    internal val isRecapturing get() = recapturing
    internal var onRecaptureRequested: (() -> Unit)? = null
    internal var onScrollRequested: ((Rect, Int, Boolean) -> Unit)? = null
    /** Starts a region recording for the current rectangular selection. */
    internal var onRecordRequested: ((Rect) -> Unit)? = null
    private val recaptureDrafts = linkedSetOf<String>()
    private var editing = false
    private var showingOriginal = false
    private var translating = false
    private var translateAfterRecognition = false
    private var discardRecognition = false
    private var showingTranslation = false
    private var translationComplete = false
    private var pair = TranslatePair(
        TranslateLanguage.fromPreference(
            ScreenTextSettingsActivity.preferences(context).getString("translate.source", null),
            TranslateLanguage.AUTO
        ),
        TranslateLanguage.fromPreference(
            ScreenTextSettingsActivity.preferences(context).getString("translate.target", null),
            TranslateLanguage.ZH
        )
    ).normalized()
    private var pairBar: TranslatePairBar? = null
    private var feedback: TextView? = null
    private val hideFeedback = Runnable { feedback?.visibility = View.GONE }
    private var translationLayer: ScreenshotTextLayer? = null
    private var textLayer: ScreenshotTextLayer? = null
    private var bars = Insets.NONE
    private var imeBottom = 0
    private var windows: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var toolbarWidth = 0
    private var toolbarHeight = dp(BUTTON+PANEL_V*2)
    private var toolbarAvailable = 0
    private val toolbarLayout = ScreenshotToolbarPreferences.combined(context)
    private var stackPopup: View? = null
    private var stackBackdrop: View? = null
    private var stackGroup: Set<ScreenshotAction>? = null
    private val groupEntries = mutableMapOf<Set<ScreenshotAction>,ScreenshotAction>()
    private val drawing = ScreenshotDrawingLayer(context,image,toolMemory) { rebuildDrawingStyles(); sync() }
    private val drawingStyles: android.widget.ScrollView = object : android.widget.ScrollView(context) {
        override fun onMeasure(widthSpec: Int,heightSpec: Int) {
            val available=(root.height-maxOf(bars.bottom,imeBottom)-bars.top).takeIf { it>0 } ?: image.height
            val limit=minOf(dp(240),(available*.4f).toInt()).coerceAtLeast(dp(44))
            val parentLimit=if(View.MeasureSpec.getMode(heightSpec)==View.MeasureSpec.UNSPECIFIED) limit else View.MeasureSpec.getSize(heightSpec)
            super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(minOf(limit,parentLimit),View.MeasureSpec.AT_MOST))
        }
    }.apply {
        background = surface(PANEL_RADIUS)
        setPadding(dp(12),dp(4),dp(12),dp(4)); elevation = dp(6).toFloat(); visibility = View.GONE
    }
    /** Selected-object actions live in the bar's right column, not a full-width bottom row. */
    private val selectSideActions = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.TOP
        setPadding(dp(2),dp(4),dp(2),dp(4)); contentDescription = "选中对象操作"; visibility = View.GONE
    }
    private var selectActionKey: String? = null
    /** 主栏结构签名：未变就跳过 rebuildToolbar（见 sync 内守卫）。 */
    private var toolbarSignature: List<Any?>? = null
    /** 二级栏贴上/贴下方向的滞回状态，防止在临界值来回翻转（屏闪抽搐来源之一）。 */
    private var secondaryAbove = false
    private var annotationEditor: View? = null
    private var annotationBackdrop: View? = null
    private var annotationField: ScreenshotTextEditor? = null
    private var annotationInput: ScreenshotAnnotationInput? = null
    private var drawingControlKey: ScreenshotDrawingTool?=null
    private var resultTool: ScreenshotAction?=null
    private var toolbarAnchorKey=""
    private var toolbarAnchor=android.graphics.Point()
    /** Set once the user drags the grip: the bar then stays where they put it. */
    private var toolbarManual: android.graphics.Point?=null
    private val detailBody = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
    private var secondaryMaxHeight=dp(ScreenshotToolbarMetrics.DETAIL_MAX)
    private val secondaryPanel=object : LinearLayout(context) {
        override fun onMeasure(widthSpec: Int,heightSpec: Int) {
            val parentLimit=if(View.MeasureSpec.getMode(heightSpec)==View.MeasureSpec.UNSPECIFIED) secondaryMaxHeight else View.MeasureSpec.getSize(heightSpec)
            super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(minOf(parentLimit,secondaryMaxHeight),View.MeasureSpec.AT_MOST))
        }
    }.apply {
        orientation=LinearLayout.VERTICAL; background=surface(PANEL_RADIUS); elevation=dp(6).toFloat()
        contentDescription="当前工具详细操作"; visibility=View.GONE
    }
    /**
     * 二级栏的头部：左边把手可拖动，右边是显眼的「收起」。
     * 收起后面板让位，主栏下方保留一个「详细设置 ▴」把它叫回来，避免入口消失。
     */
    private var secondaryCollapsed = false
    private var secondaryWanted = false
    private var secondaryManual: android.graphics.Point? = null
    private var secondaryDragBaseX = 0f
    private var secondaryDragBaseY = 0f
    private var secondaryDragFromX = 0f
    private var secondaryDragFromY = 0f
    private val secondaryGrip = TextView(context).apply {
        text = "⋮⋮"; textSize = 14f; setTextColor(COLOR_TEXT)
        gravity = Gravity.CENTER; setPadding(dp(10),0,dp(10),0)
        contentDescription = "拖动详细栏"
    }
    private val secondaryCollapse = TextView(context).apply {
        text = "收起 ▾"; textSize = 12f; setTextColor(COLOR_PRIMARY)
        setPadding(dp(12),dp(5),dp(12),dp(5)); background = surface(PANEL_RADIUS)
        isClickable = true; isFocusable = true
        contentDescription = "收起详细设置"; tooltipText = contentDescription
        setOnClickListener { setSecondaryCollapsed(true) }
    }
    private val secondaryHeader = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4),dp(4),dp(8),dp(2))
        addView(secondaryGrip, LinearLayout.LayoutParams(-2,-1))
        addView(View(context), LinearLayout.LayoutParams(0,1,1f))
        addView(secondaryCollapse, LinearLayout.LayoutParams(-2,dp(30)))
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    secondaryDragFromX = event.rawX; secondaryDragFromY = event.rawY
                    secondaryDragBaseX = secondaryPanel.translationX; secondaryDragBaseY = secondaryPanel.translationY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    secondaryManual = android.graphics.Point(
                        (secondaryDragBaseX + event.rawX - secondaryDragFromX).toInt(),
                        (secondaryDragBaseY + event.rawY - secondaryDragFromY).toInt())
                    placeControls(); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }
    /** 收起后的重新展开入口，和「显示选择栏」同款，保证二级栏不会变成死路。 */
    private val secondaryRestore = TextView(context).apply {
        text = "详细设置 ▴"; textSize = 12f; setTextColor(COLOR_PRIMARY)
        setPadding(dp(10),dp(6),dp(10),dp(6)); background = surface(PANEL_RADIUS)
        elevation = dp(6).toFloat(); isClickable = true; isFocusable = true
        contentDescription = "展开详细设置"; tooltipText = contentDescription
        visibility = View.GONE
        setOnClickListener { setSecondaryCollapsed(false) }
    }
    private fun setSecondaryCollapsed(value: Boolean) {
        if (secondaryCollapsed == value) return
        secondaryCollapsed = value
        if (!value) { secondaryManual = null; secondaryAbove = false }   // 重新展开时回到「贴着一级栏」，不留在被拖走的位置
        sync()
    }
    private val resultActions=LinearLayout(context).apply {
        gravity=Gravity.CENTER_VERTICAL; setPadding(dp(8),dp(4),dp(8),dp(4)); visibility=View.GONE
        contentDescription="识别与翻译详细操作"
    }
    private val languageDivider=View(context).apply { setBackgroundColor(COLOR_SEPARATOR); visibility=View.GONE }
    private var actionGeneration = 0
    private var disposed = false
    private var closeRequested = false
    private val actionWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val selector = ScreenTextRegionView(context,image.width,image.height,null,::select)
    private var smartPath = emptyList<ScreenshotDocRect>()
    private var smartIndex = 0
    private val smartBar = LinearLayout(context).apply {
        background = android.graphics.drawable.InsetDrawable(surface(20), 0, dp(4), 0, dp(4))
        setPadding(0, 0, 0, 0)
        elevation = dp(4).toFloat()
        visibility = View.GONE
    }
    // Phone-side difference (user request 2026-11-06): the separate element/window toggle button
    // was removed as redundant — a tap always picks the deepest element and 扩大一层 walks up to
    // the window, so the bar only carries level navigation. Original toggles the two targets
    // via a key/menu (screenshotintelligentselectionmodel toggleSelectionTarget).
    private val smartInner = smartButton(R.drawable.snow_region_reduce, "缩小一层") { commitSmartSelection(smartPath, smartIndex - 1) }
    private val smartOuter = smartButton(R.drawable.snow_region_add, "扩大一层") { commitSmartSelection(smartPath, smartIndex + 1) }

    internal fun enableSmartSelection(snapshot: ScreenshotSmartSelection?) {
        val frame = image
        var text: ScreenshotSmartText? = null
        selector.smartFrame = frame
        selector.smartHit = { x, y ->
            val native = snapshot?.path(x, y, frame.width, frame.height).orEmpty()
            text?.refine(x, y, native) ?: native
        }
        selector.onSmartCommit = ::commitSmartSelection
        if (picked == null) status.text = "轻点选区，按住后滑动探查，也可直接拖框"
        syncSmartSelection()
        val input = runCatching { frame.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        actionWorker.execute {
            val geometry = try { runCatching { ScreenshotSmartText.detect(input) }.getOrNull() }
                finally { input.recycle() }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (disposed || image !== frame || geometry == null) return@post
                text = geometry
                selector.refineLastSmartTap()
            }
        }
    }

    private fun smartButton(icon: Int, label: String, action: () -> Unit) = android.widget.ImageButton(context).apply {
        setImageResource(icon)
        setColorFilter(COLOR_TEXT)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(13), dp(13), dp(13), dp(13))
        background = RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), null, surface(20))
        contentDescription = label
        tooltipText = label
        isFocusable = true
        setOnClickListener { action() }
        smartBar.addView(this, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun commitSmartSelection(path: List<ScreenshotDocRect>, index: Int) {
        val hit = path.getOrNull(index) ?: return
        // Accessibility bounds and ML Kit line boxes hug the glyphs; grow the committed region by
        // the same pad the preview shows so ascenders and the OCR crop are not clipped.
        val pad = (6 * context.resources.displayMetrics.density * image.width / maxOf(1, root.width))
            .toInt().coerceAtLeast(2)
        selector.selectRegion(ScreenshotSmartSelection.padded(hit, pad, image.width, image.height).toRect())
        smartPath = path
        smartIndex = index
        syncSmartSelection()
        placeControls()
    }

    private fun syncSmartSelection() {
        val visible = selector.smartHit != null && !busy && !editing && !translating &&
            resultTool == null && drawing.tool == null && !moveMode && regionShape == ScreenshotRegionShape.RECTANGLE &&
            captureRegion == null
        selector.smartEnabled = visible
        smartInner.visibility = if (smartIndex > 0 && smartPath.size > 1) View.VISIBLE else View.GONE
        smartOuter.visibility = if (smartIndex < smartPath.lastIndex) View.VISIBLE else View.GONE
        smartBar.visibility = if (visible &&
            (smartInner.visibility == View.VISIBLE || smartOuter.visibility == View.VISIBLE)) View.VISIBLE else View.GONE
    }
    private val resultLayer = FrameLayout(context).apply { visibility = View.INVISIBLE }
    private fun surface(radius: Int) = GradientDrawable().apply { setColor(COLOR_PANEL); cornerRadius = dp(radius).toFloat() }
    private val toolbar = ScreenshotToolbarPanel(context)
    /** A10/A11: the selection readout switches between physical pixels and density-independent dp. */
    private var logicalUnits = false
    private val status = TextView(context).apply {
        text = "拖动框选，可移动和拉边调整"; textSize = 12f; setTextColor(COLOR_TEXT)
        setPadding(dp(10),dp(6),dp(10),dp(6)); background = surface(PANEL_RADIUS)
        elevation = dp(6).toFloat(); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        isClickable = true
        setOnClickListener {
            if (selectActionsScroll.visibility == View.VISIBLE && regionOperation == null && picked != null) {
                logicalUnits = !logicalUnits; sync()
            }
        }
    }
    /**
     * A12：原版 `setSelectionToolbarHiddenForSession` 会把整条选区栏收起来（会话内有效，新一次截图复位）。
     * 整条收起后必须留下可见的恢复入口，否则主栏之外没有任何回到选区栏的线索。
     */
    private val hideBarRestore = TextView(context).apply {
        text = "显示选择栏"; textSize = 12f; setTextColor(COLOR_PRIMARY)
        setPadding(dp(10),dp(6),dp(10),dp(6)); background = surface(PANEL_RADIUS)
        elevation = dp(6).toFloat(); isClickable = true; isFocusable = true
        // 与栏内那颗眼睛按钮的“显示选择工具栏”区分开，避免同一屏出现两个同名入口。
        contentDescription = "恢复选区栏"; tooltipText = contentDescription
        visibility = View.GONE
        setOnClickListener {
            selectionReadoutHidden = false
            hideBarButton.contentDescription = "隐藏选择工具栏"
            hideBarButton.tooltipText = hideBarButton.contentDescription
            sync()
        }
    }
    private fun selectionSize(rect: Rect): String {
        if (!logicalUnits) return "选区 ${rect.width()} × ${rect.height()} px"
        val density = context.resources.displayMetrics.density
        val scale = if (density > 0f) density else 1f
        return "选区 ${Math.round(rect.width() / scale)} × ${Math.round(rect.height() / scale)} dp"
    }
    private val full = Action(ScreenshotAction.FULL_SCREEN, R.drawable.ic_screenshot_fullscreen) { selectFull() }
    private val recognizeAction = Action(ScreenshotAction.RECOGNIZE, R.drawable.snow_recognize) { toggleRecognition() }
    private val saveAction = Action(ScreenshotAction.SAVE, R.drawable.snow_save) { beginSave() }
    private val copyAction = Action(ScreenshotAction.COPY_TEXT, R.drawable.snow_copy) { copyText() }
    private val editAction = Action(ScreenshotAction.EDIT, R.drawable.snow_edit) { setEditing(!editing, document = true) }
    private val translateAction = Action(ScreenshotAction.TRANSLATE, R.drawable.snow_translate) { requestTranslation() }
    private val eyeAction = Action(ScreenshotAction.SHOW_ORIGINAL, R.drawable.snow_eye) { toggleOriginal() }
    private val closeAction = Action(ScreenshotAction.CLOSE, R.drawable.snow_close, danger = true) {
        closeRequested = true
        translator.cancel()
        exit()
    }
    private fun editedLayer() = if (showingTranslation) translationLayer else textLayer
    private val undoAction = Action(ScreenshotAction.UNDO, R.drawable.snow_undo) { if (editing) editedLayer()?.undo() else drawing.undo() }
    private val redoAction = Action(ScreenshotAction.REDO, R.drawable.snow_redo) { if (editing) editedLayer()?.redo() else drawing.redo() }
    private val drawingActions = ScreenshotDrawingTools.actions.map { (tool,action) -> Action(action,ScreenshotToolbarEditor.icon(action)) { selectDrawing(tool) } }
    /** Moves recognised text blocks; a plain button because it is not a drawing tool. */
    private val textLayoutButton = ImageView(context).apply {
        setImageResource(R.drawable.snow_select); setColorFilter(COLOR_TEXT)
        setPadding(dp(10),dp(10),dp(10),dp(10)); isClickable = true; isFocusable = true
        contentDescription = "调整文字位置"; tooltipText = "拖动文字块移动，右下角改大小"
        setOnClickListener { toggleTextLayout() }
    }
    /**
     * 结果栏的两个文本变换下拉（上游 Formatting／Punctuation 两个 AdSelect）。
     * 原版是可清空的下拉，手机用同尺寸的文字按钮 + 弹出菜单表达同一组选项。
     */
    private val textFormatButton = textTransformButton { showTextTransformMenu(it, formatting = true) }
    private val textPunctButton = textTransformButton { showTextTransformMenu(it, formatting = false) }
    private fun textTransformButton(run: (View) -> Unit) = TextView(context).apply {
        textSize = 12f; setTextColor(COLOR_TEXT); gravity = Gravity.CENTER
        setPadding(dp(8),0,dp(8),0); minWidth = dp(44)
        background = surface(PANEL_RADIUS); isClickable = true; isFocusable = true
        setOnClickListener { run(it) }
    }
    private fun formatLabel(value: String) = when (value) {
        ScreenshotTextTransforms.KEEP -> "保留换行"
        ScreenshotTextTransforms.REMOVE -> "移除换行"
        else -> "格式"
    }
    private fun punctuationLabel(value: String) = when (value) {
        ScreenshotTextTransforms.HALF -> "半角"
        ScreenshotTextTransforms.FULL -> "全角"
        else -> "标点"
    }
    /** 上游下拉可清空：已选值时额外给「恢复默认」，清空后文本回到基线。 */
    private fun showTextTransformMenu(anchor: View, formatting: Boolean) {
        if (busy || translating || showingTranslation) return
        val layer = editedLayer() ?: return
        val current = if (formatting) layer.transformFormatting else layer.transformPunctuation
        android.widget.PopupMenu(context, anchor).apply {
            menu.add(0,1,0,if (formatting) "保留换行" else "半角")
            menu.add(0,2,1,if (formatting) "移除换行" else "全角")
            if (current.isNotEmpty()) menu.add(0,3,2,"恢复默认")
            setOnMenuItemClickListener { choice ->
                val value = when (choice.itemId) {
                    1 -> if (formatting) ScreenshotTextTransforms.KEEP else ScreenshotTextTransforms.HALF
                    2 -> if (formatting) ScreenshotTextTransforms.REMOVE else ScreenshotTextTransforms.FULL
                    else -> ""
                }
                applyTextTransform(formatting,value)
                true
            }
            show()
        }
    }
    /** 上游 `applyTextFormatting`：不在编辑态时先 `beginTextEditing()` 再改草稿。 */
    internal fun applyTextTransform(formatting: Boolean, value: String) {
        if (busy || translating || showingTranslation) return
        if (!editing) setEditing(true, document = true)
        val layer = editedLayer() ?: return
        if (formatting) layer.setFormatting(value,picked) else layer.setPunctuation(value,picked)
        sync()
    }
    private val barcodeAction = Action(ScreenshotAction.BARCODE,R.drawable.snow_barcode) { scanBarcode() }
    private val pinAction = Action(ScreenshotAction.PIN,R.drawable.snow_pin) { pinImage() }
    private val shareAction = Action(ScreenshotAction.SHARE,R.drawable.snow_share) { shareImage() }
    private val scrollAction = Action(ScreenshotAction.SCROLL,R.drawable.snow_scroll) { chooseScrollAxis(false) }.apply {
        tooltipText = "长截屏，轻点纵向，长按选择方向"
        setOnLongClickListener { chooseScrollAxis(true); true }
    }
    private val recordAction = Action(ScreenshotAction.RECORD,R.drawable.snow_record) { requestRecording() }.apply {
        tooltipText = "录制选区，点按开始"
    }
    /** Edit-selection mode: add/subtract parts of the capture area, as Snow Shot's move bar does. */
    private val moveAction = Action(ScreenshotAction.MOVE,R.drawable.snow_move) { toggleMoveMode() }
    private val actions = listOf(full,recognizeAction,saveAction,closeAction,copyAction,eyeAction,editAction,translateAction,undoAction,redoAction,barcodeAction,pinAction,shareAction,scrollAction,recordAction,moveAction)+drawingActions
    private var moveMode = false
    private var captureRegion: ScreenshotRegion?=null
    private var regionOperation: ScreenshotRegionOp?=null
    /** Region shape for the next selection, persisted like the original's region_type preference. */
    private var regionShape = loadRegionShapePreference()
    private val selectActions=LinearLayout(context).apply {
        // Two rows so every control is visible on a phone; the scroll wrapper only guards narrow cases.
        orientation=LinearLayout.VERTICAL; setPadding(dp(4),dp(2),dp(4),dp(2)); visibility=View.VISIBLE
        contentDescription="选区操作"
    }
    private val addRegionButton=ImageView(context)
    private val subtractRegionButton=ImageView(context)
    private val regionRectButton=ImageView(context)
    private val regionPolylineButton=ImageView(context)
    private val regionCurveButton=ImageView(context)
    private val regionFreehandButton=ImageView(context)
    private val recaptureButton=ImageView(context)
    private val recaptureDraftButton=TextView(context).apply {
        text = "上次文字草稿"
        contentDescription = text
        gravity = Gravity.CENTER
        setTextColor(COLOR_TEXT)
        textSize = 12f
        isFocusable = true
        visibility = View.GONE
        setOnClickListener {
            showValueEditor("上次截图文字草稿",recaptureDrafts.joinToString("\n\n"),"复制") { value ->
                runCatching { context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("截图文字草稿",value)) }
                    .onSuccess { showFeedback("已复制文字草稿") }
                    .onFailure { showFeedback("复制失败，请重试") }
            }
        }
    }
    private val qrButton=ImageView(context)
    /** 二维码按钮的忙碌转圈（qrButton 是普通 ImageView，不走 Action 的 busy）。 */
    private val qrBusyDrawable=ScreenshotBusySpinnerDrawable(context)
    /** A09：二维码标记层。原版标记只在「编辑选区 + 移动工具」下显示，手机同条件。 */
    private val qrMarkers=ScreenshotQrMarkers(context).apply {
        visibility=View.GONE
        onMarker=::showQrPopover
    }
    private var qrHits: List<ScreenshotBarcodeHit>?=null
    private var qrVisible=false
    private var qrScanning=false
    private var qrPopup: android.widget.PopupWindow?=null
    private val hideBarButton=ImageView(context)
    private var selectionReadoutHidden = false
    private val selectActionsScroll=android.widget.HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled=false; visibility=View.GONE
    }
    /** 结果栏加上两个文本变换下拉后可能超宽，窄屏仍要能横向滚动到全部入口。 */
    private val resultActionsScroll=android.widget.HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled=false; visibility=View.GONE
    }
    init {
        // 等比画布缩小后四周会露出画布外的区域，统一用纯黑，避免透出宿主应用造成「画面错乱」。
        root.setBackgroundColor(0xFF000000.toInt())
        root.addView(content,FrameLayout.LayoutParams(-1,-1))
        drawing.colorSamplingBase={ if(resultLayer.visibility==View.VISIBLE) (if(showingTranslation) translationLayer else textLayer)?.render(Rect(0,0,image.width,image.height)) else null }
        content.addView(background,FrameLayout.LayoutParams(-1,-1))
        // 底图独立成层：静态像素只在自己失效时重绘，标注变化完全不碰它。
        content.addView(backdropView,FrameLayout.LayoutParams(-1,-1))
        content.addView(selector,FrameLayout.LayoutParams(-1,-1))
        content.addView(resultLayer,FrameLayout.LayoutParams(-1,-1))
        content.addView(drawing,FrameLayout.LayoutParams(-1,-1))
        content.addView(qrMarkers,FrameLayout.LayoutParams(-1,-1))
        selector.onRegionMoved = ::select
        selector.onRegionOperation = ::applyRegionOperation
        selector.onDraftCommit = ::commitRegionShape
        selector.regionShape = regionShape
        selector.contentDescription = "拖动框选截图区域，可在框内移动或拉边调整，松手后可识别或保存"
        selector.setOnTouchListener { _,_ -> busy || editing || translating || project!=null }
        root.addView(toolbar,FrameLayout.LayoutParams(dp(200),dp(BUTTON+PANEL_V*2)))
        root.addView(status,FrameLayout.LayoutParams(-2,-2))
        root.addView(smartBar,FrameLayout.LayoutParams(-2,-2))
        detailBody.addView(drawingStyles,LinearLayout.LayoutParams(0,-2,1f))
        detailBody.addView(selectSideActions,LinearLayout.LayoutParams(dp(76),-2))
        // 头部放在最上面：拖动把手 + 收起按钮（收起后由 secondaryRestore 叫回来）。
        secondaryPanel.addView(secondaryHeader,0,LinearLayout.LayoutParams(-1,dp(36)))
        secondaryPanel.addView(detailBody,LinearLayout.LayoutParams(-1,-2))
        initSelectionActions()
        selectActionsScroll.addView(selectActions,android.widget.FrameLayout.LayoutParams(-2,-2))
        secondaryPanel.addView(selectActionsScroll,LinearLayout.LayoutParams(-1,dp(88)))
        resultActions.addView(eyeAction,LinearLayout.LayoutParams(dp(44),dp(44)))
        resultActions.addView(View(context).apply { setBackgroundColor(COLOR_SEPARATOR) },LinearLayout.LayoutParams(dp(1),dp(16)).apply { marginStart=dp(8); marginEnd=dp(8) })
        resultActions.addView(editAction,LinearLayout.LayoutParams(dp(44),dp(44)))
        resultActions.addView(textFormatButton,LinearLayout.LayoutParams(-2,dp(44)).apply { marginStart=dp(6) })
        resultActions.addView(textPunctButton,LinearLayout.LayoutParams(-2,dp(44)).apply { marginStart=dp(6) })
        resultActions.addView(textLayoutButton,LinearLayout.LayoutParams(dp(44),dp(44)))
        resultActions.addView(copyAction,LinearLayout.LayoutParams(dp(44),dp(44)))
        resultActionsScroll.addView(resultActions,android.widget.FrameLayout.LayoutParams(-2,-2))
        secondaryPanel.addView(resultActionsScroll,LinearLayout.LayoutParams(-1,-2))
        root.addView(secondaryPanel,FrameLayout.LayoutParams(dp(320),-2))
        root.addView(secondaryRestore,FrameLayout.LayoutParams(-2,-2))
        root.addView(hideBarRestore,FrameLayout.LayoutParams(-2,-2))
        drawing.onTextRequest = { point,index ->
            beginDrawingText(point,index)
        }
        toolbar.onDragStart = { closeStack() }
        toolbar.onDrag = ::dragToolbar
        root.setOnApplyWindowInsetsListener { _,insets ->
            bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
            applyImeShift(); placeControls(); insets
        }
        root.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> placeControls() }
        restoreProject()
        if (project != null) {
            // 布局可能已经先跑过一轮，那一轮 `picked` 还是 null，画布范围被当成整图——
            // 结果就是"显示整屏截图、找不到自己框的那块"。这里无论先后都按选区重排一次。
            root.post { if (!disposed) applyCanvasBase(root.width, root.height, resetView = true) }
        }
        sync()
    }
    /** Reopen a saved record: same base image, same marks, same text, viewport no longer draggable. */
    private fun restoreProject() {
        val restored=project ?: return
        val document=restored.document
        picked=document.viewport.toRect()
        captureRegion=document.regionOperands?.let { value ->
            runCatching { ScreenshotRegion.fromJson(org.json.JSONArray(value)) }.getOrNull()
        } ?: document.regions.takeIf { it.isNotEmpty() }?.let { ScreenshotRegion.ofRects(it.map(ScreenshotDocRect::toRect)) }
        drawing.region=picked?.let(::Rect)
        drawing.savedPixelUnit=document.drawingUnit
        drawing.savedFontUnit=document.fontUnit
        drawing.history.load(document.objects, document.watermark ?: ScreenshotWatermark())
        drawing.invalidate()
        // 编辑已有图片时不要再画「选区框 + 遮罩」：那层框会把整张图圈起来，
        // 看上去像"整个屏幕都被选中了"。picked 仍保留（保存裁切与绘图范围要用）。
        picked?.let { drawing.region = Rect(it) }
        // 恢复上次的工具与样式：用户上次在画箭头/写字，重开就还是那支笔。
        // 没记录工具时保持「无工具」：打开长图第一件事是滑动浏览，别让 SELECT 挡住平移。
        // 顺序不能反：先设工具（setter 会按 本地记忆/默认值 取样式并把上一支笔存入缓存），
        // 再用文档里的 activeStyle 覆盖——反过来的话 setter 会把文档样式顶成旧记忆。
        // 文档样式随后留在 toolStyles 里，后续任何一次切走再切回都拿得回来。
        drawing.tool = document.activeTool?.let { name ->
            runCatching { ScreenshotDrawingTool.valueOf(name) }.getOrNull()
        }
        document.activeStyle?.let { drawing.restoreStyle(it) }
        drawing.onCanvasPan = ::panByScreen
        rebuildDrawingStyles()
        // 本函数没有配对 sync（其余 rebuildDrawingStyles 调用点都有），面板定位在这里补一次。
        placeControls()
        status.visibility=View.GONE
        // 选区层只服务"新截图框选"：编辑已有图片时整层隐藏，画面上只剩图片本身与工具。
        // 必须放在下面两处早退之前——无文字工程会在 text 判空处直接 return，
        // 那之后再设 GONE 永远走不到；而该层默认 VISIBLE、rect 为空，
        // 空 RectF 会让它的四块遮罩退化成一整幅 50% 黑，并且它的触摸监听会吞掉
        // 绘图/平移的全部手势（现象：进编辑器一片暗、点哪儿都没反应）。
        selector.readOnly = true
        selector.visibility=View.GONE
        val text=document.text ?: return
        if(text.blocks.isEmpty()) return
        resultTool=ScreenshotAction.RECOGNIZE
        val layer=ScreenshotTextLayer(context,image,text.blocks,::textChanged,{ setEditing(true,document = true) })
        layer.viewport=viewport
        layer.historyChanged=::sync
        textLayer=layer
        resultLayer.removeAllViews()
        resultLayer.addView(layer,FrameLayout.LayoutParams(-1,-1))
        if(text.translation.isNotEmpty()) {
            val translated=ScreenshotTextLayer(context,image,text.translation,{},{ setEditing(true,document = true) },
                fitToBlock=true,translationRegion=picked)
            translated.viewport=viewport
            translated.historyChanged=::sync
            translationLayer=translated
            resultLayer.addView(translated,FrameLayout.LayoutParams(-1,-1))
            translationComplete=true
        }
        showingTranslation=text.mode==ScreenshotTextMode.TRANSLATION && translationLayer!=null
        if(showingTranslation) { textLayer?.visibility=View.GONE; showPairBar() } else translationLayer?.visibility=View.GONE
        resultLayer.visibility=View.VISIBLE
    }
    /** The style card only exists when the active tool really has options; an empty card is a white bar. */
    private fun drawingStyleCardVisible(): Boolean =
        drawing.tool!=null && annotationEditor==null &&
            (drawingStyles.getChildAt(0) as? ScreenshotDrawingControls)?.hasContent==true
    /** 模块开关关闭的工具从工具条消失；其余可见性规则不变。 */
    private fun moduleHiddenActions(): Set<ScreenshotAction> {
        val hidden = HashSet<ScreenshotAction>()
        if (!Modules.isOn(context, Module.TRANSLATE)) hidden.add(ScreenshotAction.TRANSLATE)
        if (!Modules.isOn(context, Module.RECORD)) hidden.add(ScreenshotAction.RECORD)
        if (!Modules.isOn(context, Module.SCROLL)) hidden.add(ScreenshotAction.SCROLL)
        if (!Modules.isOn(context, Module.WATERMARK)) hidden.add(ScreenshotAction.WATERMARK)
        return hidden
    }
    private fun state() = ScreenshotToolbarState(
        picked != null,textLayer != null,busy,editing,showingOriginal,translating || translateAfterRecognition,showingTranslation,
        drawing.history.canUndo || drawing.history.canRedo, moduleHiddenActions(),
        projectMode = project != null
    )
    private fun sync() {
        val state = state()
        eyeAction.active = showingOriginal
        recognizeAction.active=resultTool==ScreenshotAction.RECOGNIZE
        editAction.active = editing
        translateAction.active = resultTool==ScreenshotAction.TRANSLATE
        // 识别/编辑/选区态下不允许绘图工具驻留；幂等 guard 防御未来的回调链改动。
        if ((busy || editing || translating || resultTool!=null || moveMode) && drawing.tool!=null) drawing.tool = null
        drawingActions.forEach { it.active = ScreenshotDrawingTools.actions[drawing.tool] == it.action }
        // 上游「显示原图」只隐藏识别内容容器，画布上的对象照常可见。
        drawing.visibility = if (showingTranslation || translating || editing) View.INVISIBLE else View.VISIBLE
        drawingStyles.visibility = if (drawingStyleCardVisible()) View.VISIBLE else View.GONE
        syncSelectSideActions()
        translateAction.setImageResource(R.drawable.snow_translate)
        actions.forEach {
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            val detail=it.action in ScreenshotToolbarState.secondaryActions
            it.visibility = if (if(detail) resultTool!=null && state.hasText else state.mainVisible(it.action)) View.VISIBLE else View.GONE
            it.isEnabled = if(detail) state.enabled(it.action) else state.mainEnabled(it.action)
            val label = state.label(it.action)
            it.contentDescription = label; it.tooltipText = label
            it.refreshTint()
        }
        undoAction.isEnabled=state.mainEnabled(ScreenshotAction.UNDO) && (if (editing) editedLayer()?.canUndo==true else drawing.history.canUndo)
        redoAction.isEnabled=state.mainEnabled(ScreenshotAction.REDO) && (if (editing) editedLayer()?.canRedo==true else drawing.history.canRedo)
        scrollAction.isEnabled = scrollAction.isEnabled && onScrollRequested != null
        recordAction.isEnabled = recordAction.isEnabled && onRecordRequested != null
        if(annotationEditor!=null) actions.filter { it.action!=ScreenshotAction.CLOSE }.forEach { it.isEnabled=false }
        // 主栏重建守卫：rebuildToolbar 的全部结构性输入都在签名里（state、busy 分支、翻译链、
        // 组记忆、最近工具指纹）。sync 有约 57 处调用点，每次都 removeAllViews 整条重建主栏
        // 是「点一下闪一下」的主因之一。
        // ⚠️ rebuildToolbar 新增状态输入时必须同步扩这个签名。
        closeStack()
        val toolbarNext=listOf<Any?>(state,busySource,translateAfterRecognition,
            groupEntries.hashCode(),toolMemory?.fingerprint())
        if(toolbarNext!=toolbarSignature) { toolbarSignature=toolbarNext; rebuildToolbar() }
        val working = busy || translating
        pairBar?.visibility = if (resultTool==ScreenshotAction.TRANSLATE && !editing) View.VISIBLE else View.GONE
        pairBar?.isEnabled=!working
        languageDivider.visibility=if(pairBar?.visibility==View.VISIBLE) View.VISIBLE else View.GONE
        resultActions.visibility=if(resultTool!=null && textLayer!=null && annotationEditor==null) View.VISIBLE else View.GONE
        resultActionsScroll.visibility=resultActions.visibility
        // 上游 setTextTransformSelections：下拉显示当前会话的 formatting／punctuation，
        // 空值即占位文案；译文流式输出与锁定时禁用，避免改动正在替换的文本。
        val textLayerForTransforms=editedLayer()
        val transformsEnabled=!busy && !translating && !showingTranslation && textLayerForTransforms!=null
        textFormatButton.isEnabled=transformsEnabled
        textPunctButton.isEnabled=transformsEnabled
        textFormatButton.text=formatLabel(textLayerForTransforms?.transformFormatting.orEmpty())
        textPunctButton.text=punctuationLabel(textLayerForTransforms?.transformPunctuation.orEmpty())
        textFormatButton.contentDescription="格式化：${textFormatButton.text}"
        textPunctButton.contentDescription="标点：${textPunctButton.text}"
        moveAction.active=moveMode
        val showSelection=moveMode && picked!=null && !busy && !editing && !translating && resultTool==null && annotationEditor==null
        // A12：隐藏时整条选区栏（读数 + 两行按钮）一起收起，只在原位留一颗恢复胶囊。
        selectActionsScroll.visibility=if(showSelection && !selectionReadoutHidden) View.VISIBLE else View.GONE
        hideBarRestore.visibility=if(showSelection && selectionReadoutHidden) View.VISIBLE else View.GONE
        recaptureButton.isEnabled = showSelection && canRecapture()
        recaptureDraftButton.visibility = if(recaptureDrafts.isEmpty()) View.GONE else View.VISIBLE
        applyCellStyle(addRegionButton,regionOperation==ScreenshotRegionOp.ADD)
        applyCellStyle(subtractRegionButton,regionOperation==ScreenshotRegionOp.SUBTRACT)
        applyCellStyle(regionRectButton,regionShape==ScreenshotRegionShape.RECTANGLE)
        applyCellStyle(regionPolylineButton,regionShape==ScreenshotRegionShape.POLYLINE)
        applyCellStyle(regionCurveButton,regionShape==ScreenshotRegionShape.CURVE)
        applyCellStyle(regionFreehandButton,regionShape==ScreenshotRegionShape.FREEHAND)
        applyCellStyle(hideBarButton, selectionReadoutHidden)
        // A09：标记只在「编辑选区 + 无绘图工具」时显示，且中心必须落在选区内（上游 refreshMarkers）。
        val showQr=canShowQrMarkers()
        qrMarkers.visibility=if(showQr) View.VISIBLE else View.GONE
        if(showQr) refreshQrMarkers() else if(!qrVisible) qrMarkers.setMarkers(emptyList())
        val qrLabel=if(qrVisible) "隐藏二维码标记" else "显示二维码标记"
        qrButton.contentDescription=qrLabel; qrButton.tooltipText=qrLabel
        updateBusySpinner()
        if(showSelection && regionOperation==null) picked?.let {
            status.text=selectionSize(it)
            status.contentDescription="${selectionSize(it)}，点击切换单位"
            status.visibility=if(selectionReadoutHidden) View.GONE else View.VISIBLE
        }
        secondaryWanted = annotationEditor==null && (drawingStyles.visibility==View.VISIBLE || resultActions.visibility==View.VISIBLE ||
            selectActionsScroll.visibility==View.VISIBLE || pairBar?.visibility==View.VISIBLE)
        // 收起只是把面板藏起来，入口换成主栏下方的「详细设置 ▴」，不是把功能丢掉。
        secondaryPanel.visibility = if (secondaryWanted && !secondaryCollapsed) View.VISIBLE else View.GONE
        secondaryRestore.visibility = if (secondaryWanted && secondaryCollapsed) View.VISIBLE else View.GONE
        if (working || showingTranslation) status.visibility = View.GONE
        if (qrMarkers.visibility==View.VISIBLE) refreshQrMarkers()
        syncSmartSelection()
        placeControls()
    }
    /** Snow Shot's move-tool bar: icon controls only, no text labels. */
    /** Second-row buttons are outlined tiles: grey border idle, blue border and icon when active. */
    private fun applyCellStyle(view: ImageView,active: Boolean) {
        val stroke=if(active) COLOR_PRIMARY else COLOR_SEPARATOR
        val shape=GradientDrawable().apply {
            setColor(0x00000000); cornerRadius=dp(8).toFloat()
            setStroke(dp(1).coerceAtLeast(1),stroke)
        }
        view.background=RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF),shape,null)
        view.setColorFilter(if(active) COLOR_PRIMARY else COLOR_TEXT,PorterDuff.Mode.SRC_IN)
    }
    private fun initSelectionActions() {
        fun icon(view: ImageView,res: Int,label: String,run:()->Unit) {
            view.setImageResource(res)
            view.setPadding(dp(7),dp(7),dp(7),dp(7))
            view.isClickable=true; view.isFocusable=true
            view.contentDescription=label; view.tooltipText=label
            applyCellStyle(view,false)
            view.setOnClickListener { run() }
        }
        icon(addRegionButton,R.drawable.snow_region_add,"添加截图区域") { toggleRegionOperation(ScreenshotRegionOp.ADD) }
        icon(subtractRegionButton,R.drawable.snow_region_reduce,"减少截图区域") { toggleRegionOperation(ScreenshotRegionOp.SUBTRACT) }
        icon(regionRectButton,R.drawable.snow_region_rect,"矩形截图区域") { setRegionShape(ScreenshotRegionShape.RECTANGLE) }
        icon(regionPolylineButton,R.drawable.snow_region_polyline,"折线截图区域") { setRegionShape(ScreenshotRegionShape.POLYLINE) }
        icon(regionCurveButton,R.drawable.snow_region_curve,"曲线截图区域") { setRegionShape(ScreenshotRegionShape.CURVE) }
        icon(regionFreehandButton,R.drawable.snow_region_freehand,"自由手绘截图区域") { setRegionShape(ScreenshotRegionShape.FREEHAND) }
        icon(recaptureButton,R.drawable.snow_recapture,"重新截图") { if (canRecapture()) onRecaptureRequested?.invoke() }
        // 上游选区栏这颗按钮是二维码标记的显隐开关，不是重新扫描；可用性来自识别结果。
        icon(qrButton,R.drawable.snow_barcode,if(qrVisible) "隐藏二维码标记" else "显示二维码标记") { toggleQrMarkers() }
        icon(hideBarButton,R.drawable.snow_eye_off,"隐藏选择工具栏") {
            selectionReadoutHidden = !selectionReadoutHidden
            val label = if (selectionReadoutHidden) "显示选择工具栏" else "隐藏选择工具栏"
            hideBarButton.contentDescription = label
            hideBarButton.tooltipText = label
            sync()
        }
        val row1=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        val row2=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        // 行高与二级栏单行统一为 40dp：改造前两行 36dp 塞进 80dp 容器，比同一面板里的其他行矮一截。
        selectActions.addView(row1,LinearLayout.LayoutParams(-2,dp(40)))
        selectActions.addView(row2,LinearLayout.LayoutParams(-2,dp(40)).apply { topMargin=dp(4) })
        fun cell(row: LinearLayout,view: View,width: Int)=row.addView(view,
            LinearLayout.LayoutParams(width,dp(40)).apply { marginEnd=dp(4) })
        fun divider(row: LinearLayout) {
            val box=FrameLayout(context)
            box.addView(View(context).apply { setBackgroundColor(COLOR_SEPARATOR) },
                FrameLayout.LayoutParams(dp(1),dp(16),Gravity.CENTER))
            cell(row,box,dp(9))
        }
        cell(row1,addRegionButton,dp(40)); cell(row1,subtractRegionButton,dp(40)); divider(row1)
        for(button in listOf(regionRectButton,regionPolylineButton,regionCurveButton,regionFreehandButton)) cell(row1,button,dp(40))
        for(button in listOf(recaptureButton,qrButton)) cell(row2,button,dp(40))
        divider(row2)
        cell(row2,hideBarButton,dp(40))
        cell(row2,recaptureDraftButton,dp(100))
    }
    /** Edit-selection mode mirrors Snow Shot's move tool: the bar owns the add/subtract controls. */
    private fun toggleMoveMode() {
        // 再编辑模式下选区层已隐藏（restoreProject 里 selector.visibility=GONE），
        // 编辑选区只会得到一条按钮全部无效的操作条：双保险拦截（可见性已在 ToolbarState 挡了）。
        if(busy || translating || editing || project!=null) return
        moveMode=!moveMode
        if(!moveMode) setRegionOperation(null)
        else {
            drawing.tool=null; resultTool=null; showingOriginal=false; showingTranslation=false; rebuildDrawingStyles()
            // 上游 `startAutomaticQrRecognition`：进入编辑态就识别，默认设置 auto_recognize_qr_code = true，
            // 标记默认可见（`m_visible = true`）。手机在进入选区编辑时补做一次后台识别。
            autoScanQr()
        }
        moveAction.active=moveMode
        sync()
    }
    /** 进入选区编辑时的后台识码；不占 busy，失败静默（用户仍可手动点开关重试）。 */
    private fun autoScanQr() {
        if (qrHits != null || qrScanning || picked == null) {
            if (qrHits?.isNotEmpty() == true) qrVisible = true
            return
        }
        val bitmap = try { currentImage() } catch(error: Exception) { return }
        val token = ++actionGeneration
        qrScanning = true
        ScreenshotBarcode.scan(bitmap,{ hits ->
            qrScanning = false
            if (token==actionGeneration && !disposed) {
                qrHits=hits
                if (hits.isNotEmpty()) qrVisible=true
                sync()
            }
        },{ qrScanning=false })
    }
    private fun setRegionOperation(op: ScreenshotRegionOp?) {
        regionOperation=op
        selector.opMode=op!=null
        if(op==null) { status.visibility=View.GONE; return }
        drawing.tool=null
        status.text=if(op==ScreenshotRegionOp.ADD) "拖动要加进来的区域" else "拖动要去掉的区域"
        status.visibility=View.VISIBLE
    }
    private fun toggleRegionOperation(op: ScreenshotRegionOp) {
        if(busy || translating || editing || picked==null) return
        setRegionOperation(if(regionOperation==op) null else op)
        rebuildDrawingStyles(); sync()
    }
    private fun applyRegionOperation(box: Rect) {
        applyRegionShape(rectVertices(box), ScreenshotRegionShape.RECTANGLE)
    }

    private fun rectVertices(box: Rect) = listOf(
        PointF(box.left.toFloat(), box.top.toFloat()), PointF(box.right.toFloat(), box.top.toFloat()),
        PointF(box.right.toFloat(), box.bottom.toFloat()), PointF(box.left.toFloat(), box.bottom.toFloat()))

    /** Region-type switching: construction drops, a confirmed selection survives (setRegionType). */
    private fun setRegionShape(value: ScreenshotRegionShape) {
        if (busy || translating || editing || project != null) return
        if (regionShape == value) return
        selector.cancelConstruction()
        selector.regionShape = value
        regionShape = value
        context.getSharedPreferences("screenshot-region-type", Context.MODE_PRIVATE).edit()
            .putString("screenshot_selection/region_type", value.id).apply()
        sync()
    }

    private fun loadRegionShapePreference() = ScreenshotRegionShape.fromId(
        context.getSharedPreferences("screenshot-region-type", Context.MODE_PRIVATE)
            .getString("screenshot_selection/region_type", null))

    /** A finished custom shape lands here: replace the selection, or feed the armed operation. */
    private fun commitRegionShape(vertices: List<PointF>, shape: ScreenshotRegionShape) {
        if (project != null || busy || translating || editing) return
        if (regionOperation != null) { applyRegionShape(vertices, shape); return }
        val next = ScreenshotRegion.ofShape(vertices, shape)
        if (next.isEmpty) return
        captureRegion = next.takeIf { it.isCustom || !it.isRectangle }
        picked = Rect(next.bounds)
        drawing.region = picked?.let(::Rect); drawing.invalidate()
        clearText()
        selector.showRegion(next.path())
        status.visibility = View.GONE
        moveMode = false; moveAction.active = false
        sync()
    }

    private fun applyRegionShape(vertices: List<PointF>, shape: ScreenshotRegionShape) {
        val op=regionOperation ?: return
        val base=captureRegion ?: picked?.let { ScreenshotRegion.ofRects(listOf(Rect(it))) } ?: return
        val next=base.combined(vertices, shape, op)
        if(next.isEmpty) {
            // 上游 `confirmSelection()`：`commitRegionOperation()` 之后若 `hasPixelSelection()` 为假，
            // 直接 `returnToSelectionMode(false)` —— 减法把选区扣空时选区真的变空，用户要重新框选，
            // 不保留旧框（旧版手机的「提示 + 恢复旧框」属未记录的差异，已按原版改掉）。
            setRegionOperation(null); rebuildDrawingStyles()
            captureRegion=null; picked=null; drawing.region=null; drawing.invalidate()
            clearText(); status.visibility=View.GONE
            sync()
            showFeedback("选区已为空，请重新框选")
            return
        }
        captureRegion=next.takeIf { it.isCustom || !it.isRectangle }
        picked=Rect(next.bounds)
        drawing.region=picked?.let(::Rect); drawing.invalidate()
        clearText()
        if (next.isCustom) selector.showRegion(next.path()) else selector.showParts(next.rects)
        // 上游加减是**一次性**模式：`confirmSelection()` 里 `commitRegionOperation()` 把
        // `m_regionOperation` 复位为 Replace，画完一个形状就退出，不像旧版驻留到再点一次按钮。
        setRegionOperation(null); rebuildDrawingStyles()
        status.visibility=View.GONE
        sync()
    }
    private fun canRecapture() = project == null && onRecaptureRequested != null && !recapturing &&
        !busy && !editing && !translating && !translateAfterRecognition && annotationEditor == null &&
        moveMode && picked != null && regionOperation == null && !selector.selectionInProgress

    internal fun hideForRecapture(): Boolean {
        if (!canRecapture()) return false
        recapturing = true
        closeStack()
        root.visibility = View.INVISIBLE
        return true
    }

    internal fun replaceCaptureSource(next: Bitmap, snapshot: ScreenshotSmartSelection?) {
        listOf("识别文字" to textLayer, "译文" to translationLayer).forEach { (label, layer) ->
            if (layer != null && (layer.canUndo || layer.canRedo)) {
                layer.text().takeIf { it.isNotBlank() }?.let { recaptureDrafts.add("$label\n$it") }
            }
        }
        clearText()
        translationLayer = null
        onText("")
        image = next
        background.setImageBitmap(next)
        drawing.replaceSource(next)
        smartPath = emptyList()
        smartIndex = 0
        selector.smartFrame = null
        selector.smartHit = null
        enableSmartSelection(snapshot)
    }

    internal fun restoreAfterRecapture(error: String?) {
        recapturing = false
        root.visibility = View.VISIBLE
        root.requestFocus()
        sync()
        showFeedback(error ?: if (recaptureDrafts.isEmpty()) "已重新截图" else "已重新截图，已编辑文字保留在「上次文字草稿」")
    }

    private fun chooseScrollAxis(showDirections: Boolean) {
        val capture = onScrollRequested ?: return
        val crop = picked ?: return
        if (busy || editing || translating || annotationEditor != null) return
        if (captureRegion?.let { it.isCustom || it.rects.size != 1 } == true) {
            showFeedback("长截屏需要矩形选区")
            return
        }
        val top = ScreenshotScrollCapture.controlTop(crop.top,crop.bottom,bars.top,image.height-bars.bottom,
            dp(ScreenshotScrollCapture.BAR_DP))
        if (top == null) {
            showFeedback("请在选区上方或下方留出长截屏控制栏的空间")
            return
        }
        if (!showDirections) {
            capture(Rect(crop), top, false)
            return
        }
        android.widget.PopupMenu(context,toolbar).apply {
            menu.add(0,1,0,"纵向长截屏")
            menu.add(0,2,1,"横向长截屏")
            setOnMenuItemClickListener { choice ->
                capture(Rect(crop),top,choice.itemId == 2)
                true
            }
            show()
        }
    }

    private fun requestRecording() {
        val start = onRecordRequested ?: return
        val crop = picked ?: return
        if (busy || editing || translating || annotationEditor != null) return
        if (captureRegion?.let { it.isCustom || it.rects.size != 1 } == true) {
            showFeedback("录屏需要矩形选区")
            return
        }
        start(Rect(crop))
    }

    internal fun setScrollCaptureActive(active: Boolean, message: String? = null) {
        root.visibility = if (active) View.INVISIBLE else View.VISIBLE
        if (!active) {
            root.requestFocus()
            message?.let(::showFeedback)
        }
    }
    private fun rebuildToolbar(availableWidth: Int = root.width) {
        closeStack()
        actions.filter { it.action !in ScreenshotToolbarState.secondaryActions }.forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        toolbarAvailable=(availableWidth.takeIf { it>0 } ?: image.width)-bars.left-bars.right-dp(16)
        val cells=mutableListOf<Pair<View,Boolean>>()
        for(column in toolbarLayout.main(state())) {
            if(column==listOf(ScreenshotAction.CLOSE)) continue
            if(column==listOf(ScreenshotAction.SEPARATOR)) {
                cells.add(View(context).apply { setBackgroundColor(COLOR_SEPARATOR) } to true); continue
            }
            val visible=column.map { id -> actions.first { it.action==id } }
            val remembered=toolMemory?.entry(column) ?: groupEntries[column.toSet()]
            // 忙碌时组触发位必须显示正在转圈的那个按钮（等价原版 setActiveTool 切活动工具）：
            // 主动识别→识别按钮；翻译及其触发的自动识别阶段→翻译按钮
            // （用户 2026-11-08：转圈独属翻译且必须可见，否则触发位停在别的图标上圈就"消失"了）。
            val busyAction=when {
                busy && busySource=="ocr" && !translateAfterRecognition -> ScreenshotAction.RECOGNIZE
                busy && busySource=="ocr" -> ScreenshotAction.TRANSLATE
                translating -> ScreenshotAction.TRANSLATE
                else -> null
            }
            val entry=visible.firstOrNull { it.action==busyAction }
                ?: visible.firstOrNull { it.action==remembered }
                ?: visible.first()
            val cell=if(visible.size==1) entry else ScreenshotToolGroupView(context,entry,
                "展开工具组："+visible.joinToString("、") { it.contentDescription }) { anchor -> showStack(anchor,visible) }
            cells.add(cell to false)
        }
        val availableHeight=(root.height.takeIf { it>0 } ?: image.height)-maxOf(bars.bottom,imeBottom)-bars.top
        toolbar.setTools(cells,closeAction,toolbarAvailable,availableHeight)
        toolbarWidth=toolbar.packedWidth; toolbarHeight=toolbar.packedHeight
    }
    private fun closeStack() {
        stackPopup?.let { root.removeView(it) }; stackPopup = null
        stackBackdrop?.let { root.removeView(it) }; stackBackdrop = null
        stackGroup = null
        drawingStyles.visibility = if (drawingStyleCardVisible()) View.VISIBLE else View.GONE
    }
    private fun showStack(anchor: View, group: List<Action>) {
        val ids=group.map { it.action }.toSet()
        if(stackPopup!=null && stackGroup==ids) { closeStack(); return }
        closeStack()
        stackGroup=ids
        drawingStyles.visibility = View.GONE
        val available = (root.width-bars.left-bars.right-dp(16)).coerceAtLeast(dp(48))
        val columns = ((available-dp(16))/dp(48)).coerceIn(1,group.size)
        val panelWidth = dp(16+columns*48)
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8),dp(4),dp(8),dp(4)) }
        for (chunk in group.chunked(columns)) {
            val row = LinearLayout(context)
            chunk.forEach { action -> row.addView(ImageView(context).apply {
                contentDescription = action.contentDescription; tooltipText = contentDescription
                setImageDrawable(action.drawable.constantState?.newDrawable()?.mutate()); setColorFilter(if (action.active) COLOR_PRIMARY else COLOR_TEXT)
                setPadding(dp(12),dp(12),dp(12),dp(12)); isEnabled = action.isEnabled
                background = RippleDrawable(ColorStateList.valueOf(0x14000000),null,null)
                setOnClickListener {
                    groupEntries[group.map { it.action }.toSet()] = action.action
                    closeStack(); action.performClick(); sync()
                }
            },LinearLayout.LayoutParams(dp(48),dp(48))) }
            grid.addView(row)
        }
        val safeBottom = root.height-maxOf(bars.bottom,imeBottom)-dp(8)
        val regionTop = picked?.let { canvasY(it.top).toInt() } ?: toolbar.translationY.toInt()
        val regionBottom = picked?.let { canvasY(it.bottom).toInt() } ?: regionTop
        val toolbarAbove = toolbar.translationY+toolbarHeight <= regionTop
        val toolbarBelow = toolbar.translationY >= regionBottom
        val topEdge = if (toolbarAbove) toolbar.translationY.toInt() else regionTop
        val bottomEdge = if (toolbarBelow) toolbar.translationY.toInt()+toolbarHeight else regionBottom
        val topSpace = (topEdge-bars.top-dp(16)).coerceAtLeast(0)
        val bottomSpace = (safeBottom-bottomEdge-dp(8)).coerceAtLeast(0)
        val external = maxOf(topSpace,bottomSpace) >= dp(56)
        val limit = if (external) maxOf(topSpace,bottomSpace) else
            ((safeBottom-bars.top)*.25f).toInt().coerceAtLeast(dp(56))
        val panelHeight = minOf(dp(8+((group.size+columns-1)/columns)*48),dp(200),limit)
        // Keep the main bar above the dismiss surface so its group triggers can toggle or switch.
        stackBackdrop = View(context).apply { setOnClickListener { closeStack() } }.also {
            root.addView(it,root.indexOfChild(toolbar),FrameLayout.LayoutParams(-1,-1))
        }
        val panel = android.widget.ScrollView(context).apply {
            background = surface(PANEL_RADIUS); elevation = dp(8).toFloat(); addView(grid)
            contentDescription = "工具组选项"
        }
        stackPopup = panel
        root.addView(panel,FrameLayout.LayoutParams(panelWidth,panelHeight))
        val anchorBounds=Rect(0,0,anchor.width,anchor.height)
        root.offsetDescendantRectToMyCoords(anchor,anchorBounds)
        val x = anchorBounds.left.coerceIn(bars.left+dp(8),maxOf(bars.left+dp(8),root.width-bars.right-panelWidth-dp(8)))
        val below = toolbar.translationY.toInt()+toolbarHeight+dp(8)
        val y = if (external) {
            if (topSpace >= bottomSpace) topEdge-panelHeight-dp(8) else bottomEdge+dp(8)
        } else if (below+panelHeight <= safeBottom) below else toolbar.translationY.toInt()-panelHeight-dp(8)
        panel.translationX = x.toFloat()
        panel.translationY = y.coerceIn(bars.top+dp(8),maxOf(bars.top+dp(8),safeBottom-panelHeight)).toFloat()
    }
    private fun select(region: Rect) {
        // A reopened project keeps the viewport it was saved with; text and marks stay valid.
        if(project!=null) return
        // While adding or subtracting, the drag is reported through onRegionOperation instead.
        if(regionOperation!=null) return
        smartPath = emptyList()
        captureRegion=null
        moveMode=false; moveAction.active=false
        picked = region.takeIf { it.width() >= 2 && it.height() >= 2 }?.let(::Rect)
        drawing.region = picked?.let(::Rect); drawing.invalidate()
        // Blocks belong to the previous region; moving the box invalidates them.
        clearText()
        // 二维码标记同样属于旧选区：识别框坐标按旧裁切换算，换框即失效（上游按选区包含过滤）。
        qrHits=null; qrVisible=false; qrPopup?.dismiss(); qrMarkers.setMarkers(emptyList())
        status.text = "拖动框选，可移动和拉边调整"
        status.visibility = if (picked == null) View.VISIBLE else View.GONE
        sync()
    }
    private fun selectDrawing(tool: ScreenshotDrawingTool) {
        if (busy || translating || editing || picked == null) return
        // 切工具前必须退出区域加/减（不只是编辑选区）：残留的 opMode 会把 canPanCanvas 卡住、
        // selector 还在改选区，工具栏却已高亮新工具——「工具栏冲突/反直觉」的直接来源之一。
        if(moveMode || regionOperation!=null) { moveMode=false; setRegionOperation(null) }
        // 换工具时二级栏回到「展开 + 贴着主栏」：第一次点开永远是展开的，收起状态不跨工具残留。
        if (drawing.tool != tool) { secondaryCollapsed = false; secondaryManual = null; secondaryAbove = false }
        resultTool=null; showingOriginal=false; showingTranslation=false
        resultLayer.visibility=if(textLayer!=null) View.VISIBLE else View.INVISIBLE
        textLayer?.visibility=View.VISIBLE; translationLayer?.visibility=View.GONE; pairBar?.visibility=View.GONE
        drawing.tool = if (drawing.tool == tool) ScreenshotDrawingTool.SELECT else tool
        rebuildDrawingStyles(); sync()
    }
    private fun rebuildDrawingStyles() {
        val scroll=drawingStyles.scrollY
        // key 只看工具，不再含 selectedIndex：DrawingControls 的全部状态都是 getter 实时读、
        // 子面板每次 refresh 重新构建，选中变化走 refresh() 就够——否则每点一次对象就整块
        // 新建控件，横向滚动位置随之丢失（「点一下回到最前面」的来源之一）。
        val key=drawing.inspectedTool
        val controls=(drawingStyles.getChildAt(0) as? ScreenshotDrawingControls)?.takeIf { drawingControlKey==key }?.also { it.refresh() }
            ?: ScreenshotDrawingControls(context,drawing,::editDrawingValue,showSelectActions=false,
                showDrawTemplates=::showDrawTemplates).also {
                drawingStyles.removeAllViews(); drawingStyles.addView(it); drawingControlKey=key
            }
        // 必须等布局完成再滚：post{scrollTo} 在 extent=0 时被夹成 0，位置静默丢失。
        ScreenshotScrollMemory.applyAfterLayout(drawingStyles,scroll,horizontal=false)
        drawingStyles.visibility=if(controls.hasContent && drawing.tool!=null && annotationEditor==null) View.VISIBLE else View.GONE
        syncSelectSideActions()
        // placeControls 由配对的 sync() 提供（全部调用方都跟着 sync）；同帧跑两遍
        // placeControls（各含 measure×2 + requestLayout）是「点一下闪一下」的另一半。
    }
    /**
     * Selected-object actions sit in the detail bar's right column: the rows above rarely fill the
     * card width, and a full-width bottom row cost a whole 44dp band for two chips.
     */
    private fun syncSelectSideActions() {
        val key = drawing.selected?.takeIf { drawing.selectedIndices.size==1 }?.let { "${it.tool}:${it.id}:${drawing.overlapCount}" }
        if (key != selectActionKey) { selectActionKey = key; rebuildSelectActions() }
        selectSideActions.visibility = if (key != null && drawingStyles.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }
    private fun rebuildSelectActions() {
        selectSideActions.removeAllViews()
        if (drawing.selected == null) return
        if (drawing.overlapCount > 1)
            selectSideActions.addView(selectAction("切换重叠","切换重叠对象，共 ${drawing.overlapCount} 个") { drawing.cycleSelection(); sync() })
        selectSideActions.addView(selectAction("删除标注","删除选中的标注") { drawing.deleteSelected(); sync() })
    }
    private fun selectAction(label: String, description: String, run: () -> Unit) = TextView(context).apply {
        text=label; textSize=12f; gravity=Gravity.CENTER; setTextColor(COLOR_TEXT)
        contentDescription=description; tooltipText=description; isFocusable=true; minHeight=dp(30)
        setPadding(dp(4),dp(4),dp(4),dp(4))
        background=RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF),null,null)
        setOnClickListener { run() }
    }
    private fun closeDrawingEditor() {
        annotationInput?.close(); annotationInput=null
        annotationEditor?.let { root.removeView(it) }; annotationEditor=null; annotationField=null
        annotationBackdrop?.let { root.removeView(it) }; annotationBackdrop=null
        hideKeyboard()
    }
    private fun beginDrawingText(point: ScreenshotDrawingPoint,index: Int?) {
        closeStack(); closeDrawingEditor()
        val input=ScreenshotAnnotationInput(root,drawing,point,index,{
            val rect=drawing.textBounds(point,index)
            android.graphics.RectF(viewport.toScreenX(rect.left),viewport.toScreenY(rect.top),
                viewport.toScreenX(rect.right),viewport.toScreenY(rect.bottom))
        }, {
            val top=toolbar.translationY+toolbarHeight+dp(8)
            android.graphics.RectF(toolbar.translationX,top,toolbar.translationX+maxOf(toolbarWidth,dp(176)),top+dp(44))
        }) { closeDrawingEditor(); sync() }
        annotationInput=input; annotationEditor=input.actions; annotationField=input.field
        drawingStyles.visibility=View.GONE; sync()
    }
    private fun editDrawingValue(title: String,value: String,save: (String)->Unit) = showValueEditor(title,value,"保存",save=save)
    private val drawTemplates by lazy { ScreenshotDrawTemplateStore(context) }
    private var drawTemplatePopup: android.widget.PopupWindow? = null

    private fun showDrawTemplates(anchor: View) {
        if (!anchor.isAttachedToWindow) return
        val names = runCatching { drawTemplates.names() }.getOrElse {
            showFeedback("无法读取绘图模板"); return
        }
        drawTemplatePopup?.dismiss()
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8),dp(4),dp(8),dp(4)) }
        val scroll = android.widget.ScrollView(context).apply { addView(list) }
        val popup = android.widget.PopupWindow(scroll, minOf(dp(280),root.width-dp(16)).coerceAtLeast(1),
            dp((44 * (names.size + 1)).coerceIn(132,300)), true).apply {
            setBackgroundDrawable(surface(PANEL_RADIUS))
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
        }
        drawTemplatePopup = popup
        popup.setOnDismissListener { if(drawTemplatePopup === popup) drawTemplatePopup = null }
        fun action(label: String, run: () -> Unit) = selectAction(label,label,run).apply { minHeight=dp(44) }
        list.addView(action("将所选对象存为模板") {
            popup.dismiss()
            val template = drawing.selectedTemplate() ?: return@action
            val suffix = generateSequence(1) { it + 1 }.first { n -> names.none { it == "模板 $n" } }
            showValueEditor("模板名称", "模板 $suffix", "保存") { value ->
                runCatching { drawTemplates.save(value,template) }
                    .onSuccess { showFeedback("绘图模板已保存") }
                    .onFailure { showFeedback(it.message ?: "无法保存绘图模板") }
            }
        }.apply { isEnabled = drawing.selectedIndices.isNotEmpty(); alpha = if(isEnabled) 1f else .4f })
        for (name in names) {
            val row = LinearLayout(context)
            row.addView(action(name) {
                popup.dismiss()
                val inserted = runCatching { drawing.insertTemplate(drawTemplates.load(name)) }.getOrDefault(false)
                if (!inserted) showFeedback("无法插入绘图模板")
            },LinearLayout.LayoutParams(0,dp(44),1f))
            row.addView(action("删除") {
                list.removeAllViews()
                list.addView(TextView(context).apply { text="删除「$name」后无法撤销"; setTextColor(COLOR_TEXT); minHeight=dp(44) })
                list.addView(action("确认删除") {
                    popup.dismiss()
                    runCatching { drawTemplates.delete(name) }.onFailure { showFeedback("无法删除绘图模板") }
                })
                list.addView(action("取消") { popup.dismiss() })
            },LinearLayout.LayoutParams(dp(64),dp(44)))
            list.addView(row)
        }
        popup.showAtLocation(root,Gravity.CENTER,0,0)
    }

    private fun showValueEditor(title: String, value: String, positive: String, link: android.net.Uri? = null, save: (String)->Unit) {
        closeStack(); closeDrawingEditor()
        val panel=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL; background=surface(PANEL_RADIUS); elevation=dp(8).toFloat()
            setPadding(dp(8),dp(4),dp(8),dp(4))
        }
        panel.addView(TextView(context).apply { text=title; textSize=16f; setTextColor(COLOR_TEXT) })
        val field=ScreenshotTextEditor(context,value).apply { contentDescription=title }
        if(title=="模板名称") { field.isSingleLine = true; field.filters = arrayOf(android.text.InputFilter.LengthFilter(80)) }
        // The original stores font size as a double; the badge and the text tool clamp at different bounds.
        if(title=="字号") field.inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        else if(title=="序号") field.inputType=android.text.InputType.TYPE_CLASS_NUMBER
        panel.addView(field,LinearLayout.LayoutParams(-1,0,1f))
        val buttons=LinearLayout(context)
        fun button(label: String, run:()->Unit)=TextView(context).apply {
            text=label; textSize=16f; gravity=Gravity.CENTER; contentDescription=label; setTextColor(COLOR_PRIMARY)
            setOnClickListener { run() }
        }
        buttons.addView(button("取消") { closeDrawingEditor(); sync() },LinearLayout.LayoutParams(0,dp(44),1f))
        if(link!=null) buttons.addView(button("打开") {
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,link).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); exit() }
                .onFailure { showFeedback("没有可打开此链接的应用") }
        },LinearLayout.LayoutParams(0,dp(44),1f))
        buttons.addView(button(positive) {
            val result=field.text.toString()
            val fontTool=drawing.selected?.tool ?: drawing.tool
            val valid=when(title) {
                "颜色" -> result.matches(Regex("#[0-9a-fA-F]{6}"))
                "字号" -> ScreenshotFontSize.parse(fontTool,result)!=null
                "序号" -> result.toLongOrNull()?.let { it>=0 }==true
                else -> true
            }
            if(!valid) showFeedback(when(title) {
                "颜色" -> "请输入 #RRGGBB 颜色"
                "字号" -> "字号范围为 ${ScreenshotFontSize.format(ScreenshotFontSize.MIN)}–${ScreenshotFontSize.format(ScreenshotFontSize.max(fontTool))}"
                else -> "请输入非负整数"
            })
            else { closeDrawingEditor(); save(result); sync() }
        },LinearLayout.LayoutParams(0,dp(44),1f))
        panel.addView(buttons)
        annotationBackdrop=View(context).apply { setOnClickListener { } }.also { root.addView(it,FrameLayout.LayoutParams(-1,-1)) }
        annotationEditor=panel; annotationField=field
        root.addView(panel,FrameLayout.LayoutParams(minOf(dp(360),root.width.coerceAtLeast(dp(160))),dp(240)))
        drawingStyles.visibility=View.GONE; placeControls()
        field.requestFocus()
        sync()
        root.postDelayed({ if(annotationField===field) context.getSystemService(InputMethodManager::class.java).showSoftInput(field,InputMethodManager.SHOW_IMPLICIT) },80)
    }
    private fun selectFull() {
        if (busy || editing) return
        selector.selectRegion(Rect(0,0,image.width,image.height))
    }
    private fun composeCurrent(region: Rect): Bitmap? {
        // 上游 `ScreenshotRecognitionWindow::setShowOriginalImage` 只做 `m_contentContainer->setVisible(!show)`，
        // `m_showOriginalImage` 不进任何导出/保存路径。所以「显示原图」是纯预览开关：导出永远是完整结果
        // （当前内容层＋绘图对象），预览态不能改变保存下来的像素。
        val contentVisible = resultLayer.visibility==View.VISIBLE || showingOriginal
        val composed=if(textLayer!=null && contentVisible)
            (if(showingTranslation) translationLayer else textLayer)?.render(region) else null
        return if(!showingTranslation) drawing.render(region,composed) else composed
    }
    private fun currentImage(): Bitmap {
        val region=picked ?: error("请先框选截图区域")
        val shaped=captureRegion
        val base=composeCurrent(region) ?: Bitmap.createBitmap(image,region.left,region.top,region.width(),region.height()).let {
            if(it===image) image.copy(Bitmap.Config.ARGB_8888,false) else it
        }
        if(shaped==null) return base
        return shaped.mask(if(base.isMutable) base else base.copy(Bitmap.Config.ARGB_8888,true),region)
    }
    private fun scanBarcode() {
        if(busy || translating || editing) return
        val bitmap=try { currentImage() } catch(error: Exception) { showFeedback("无法读取选区，请重试"); return }
        val token=++actionGeneration
        busy=true; busySource="qr"; sync()
        ScreenshotBarcode.scan(bitmap,{ hits ->
            if(token==actionGeneration && !disposed) {
                busy=false; busySource=null
                // 主栏识码同时留下标记数据，选区栏的显隐开关随后可直接用。
                qrHits=hits
                val codes=hits.map { it.text }.distinct()
                sync()
                if(codes.isEmpty()) showFeedback("未识别到二维码或条码，可重新框选")
                else showValueEditor("识别结果",codes.joinToString("\n\n"),"复制",
                    codes.singleOrNull()?.let(ScreenshotQrLinks::webUrl)?.let(android.net.Uri::parse)) { text ->
                    runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("识别结果",text)) }
                        .onFailure { showFeedback("复制失败，请重试") }
                }
            }
        },{ message -> if(token==actionGeneration && !disposed) { busy=false; sync(); showFeedback(message) } })
    }
    /** A09：显示／隐藏二维码标记；首次点按才做识别（原版在进入编辑态时自动识别）。 */
    private fun toggleQrMarkers() {
        if (busy || editing || translating || annotationEditor!=null) return
        if (qrVisible) { qrVisible=false; qrPopup?.dismiss(); sync(); return }
        val cached=qrHits
        if (cached!=null) {
            if (cached.isEmpty()) { showFeedback("未识别到二维码或条码，可重新框选"); return }
            qrVisible=true; sync(); return
        }
        val bitmap=try { currentImage() } catch(error: Exception) { showFeedback("无法读取选区，请重试"); return }
        val token=++actionGeneration
        busy=true; busySource="qr"; sync()
        ScreenshotBarcode.scan(bitmap,{ hits ->
            if(token==actionGeneration && !disposed) {
                busy=false; busySource=null; qrHits=hits
                if(hits.isEmpty()) showFeedback("未识别到二维码或条码，可重新框选") else qrVisible=true
                sync()
            }
        },{ message -> if(token==actionGeneration && !disposed) { busy=false; sync(); showFeedback(message) } })
    }
    /** 上游标记中心落在识别框四角质心，按当前视口换算成视图坐标。 */
    private fun refreshQrMarkers() {
        val crop=picked
        if (crop==null || !viewport.hasSize) { qrMarkers.setMarkers(emptyList()); return }
        // centersFor 内部按「图片坐标 × 显示宽/图片宽」换算，这里把显示宽高给成
        // viewport 的实际显示尺寸，再补上图片原点的视图偏移——缩放/平移后标记才跟着图走。
        val centers=ScreenshotQrMarkers.centersFor(qrHits.orEmpty(),crop,
            viewport.displayWidth.toInt().coerceAtLeast(1),viewport.displayHeight.toInt().coerceAtLeast(1),
            image.width,image.height)
        qrMarkers.setMarkers(centers.map { android.graphics.PointF(it.x+viewport.offsetX,it.y+viewport.offsetY) })
    }
    /** 原版 setOcrBusy/setQrBusy/翻译 streaming：把蓝色转圈盖在对应按钮的图标之上。 */
    /** 原版 setOcrBusy/setQrBusy/翻译 streaming：忙碌时对应按钮的图标被蓝色转圈替代。 */
    private fun updateBusySpinner() {
        // 转圈归发起动作的按钮：识别在识别按钮、翻译在翻译按钮、二维码在二维码按钮。
        // 例外（用户 2026-11-08 指定）：从翻译进入的自动识别阶段，圈也独属于翻译按钮——
        // 点了翻译却让识别按钮转圈显得反知觉；这与原版 setOcrBusy 不同，属手机侧交互差异。
        recognizeAction.setBusy(busy && busySource == "ocr" && !translateAfterRecognition)
        translateAction.setBusy(translating || (busy && translateAfterRecognition))
        val qrBusy = (busy && busySource == "qr") || qrScanning
        if (qrBusy && qrButton.visibility == View.VISIBLE) {
            if (qrButton.drawable !== qrBusyDrawable) {
                qrBusyDrawable.start(); qrButton.setImageDrawable(qrBusyDrawable)
            }
        } else if (qrButton.drawable === qrBusyDrawable) {
            qrBusyDrawable.stop(); qrButton.setImageResource(R.drawable.snow_barcode)
        }
    }
    private fun canShowQrMarkers() = qrVisible && moveMode && !busy && !editing && !translating &&
        annotationEditor==null && drawing.tool==null && regionOperation==null && picked!=null
    private fun showQrPopover(index: Int) {
        val hit=qrHits?.getOrNull(index) ?: return
        val center=qrMarkers.centerAt(index) ?: return
        qrPopup?.dismiss()
        val panel=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL; background=surface(PANEL_RADIUS)
            setPadding(dp(12),dp(8),dp(12),dp(4)); elevation=dp(8).toFloat()
        }
        panel.addView(TextView(context).apply {
            text=hit.text; textSize=13f; setTextColor(COLOR_TEXT); setTextIsSelectable(true)
            maxLines=6; ellipsize=android.text.TextUtils.TruncateAt.END
            contentDescription="二维码内容：${hit.text.take(40)}"
        })
        val row=LinearLayout(context)
        fun action(label: String,run:()->Unit)=TextView(context).apply {
            text=label; textSize=14f; gravity=Gravity.CENTER; setTextColor(COLOR_PRIMARY)
            contentDescription=label; setOnClickListener { run() }
        }
        row.addView(action("复制") {
            runCatching { context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("识别结果",hit.text)) }
                .onSuccess { showFeedback("已复制二维码内容") }
                .onFailure { showFeedback("复制失败，请重试") }
            qrPopup?.dismiss()
        },LinearLayout.LayoutParams(0,dp(44),1f))
        // 只有整段就是一个 http(s) 地址时才给「打开」（上游 webUrl 判定）。
        ScreenshotQrLinks.webUrl(hit.text)?.let { url ->
            row.addView(action("打开") {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { showFeedback("没有可打开此链接的应用") }
                qrPopup?.dismiss()
            },LinearLayout.LayoutParams(0,dp(44),1f))
        }
        row.addView(action("关闭") { qrPopup?.dismiss() },LinearLayout.LayoutParams(0,dp(44),1f))
        panel.addView(row)
        val width=minOf(dp(280),maxOf(dp(160),root.width-dp(16)))
        val popup=android.widget.PopupWindow(panel,width,-2,true).apply {
            elevation=dp(8).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            isOutsideTouchable=true
        }
        // 弹层放在标记下方并夹在屏内；关闭时只收起弹层，不结束截图（原版会 closeEditor）。
        val x=(qrMarkers.left+center.x-width/2f).toInt()
            .coerceIn(dp(8),maxOf(dp(8),root.width-width-dp(8)))
        val y=(qrMarkers.top+center.y+dp(12)+dp(8)).toInt()
        popup.showAtLocation(root,Gravity.TOP or Gravity.START,x,y)
        qrPopup=popup
    }
    private fun pinImage() {
        if(busy || translating || editing) return
        val region=picked ?: return
        if(!viewport.hasSize) return
        val location=IntArray(2); content.getLocationOnScreen(location)
        val screenRect=Rect((location[0]+canvasX(region.left)).toInt(),(location[1]+canvasY(region.top)).toInt(),
            (location[0]+canvasX(region.right)).toInt(),(location[1]+canvasY(region.bottom)).toInt())
        try { ScreenshotPins.show(context,currentImage(),screenRect,currentDocument(region),image); exit() }
        catch(error: Exception) { showFeedback("无法贴图，请检查截图服务是否启用") }
    }
    private fun shareImage() {
        if(busy || translating || editing) return
        val bitmap=try { currentImage() } catch(error: Exception) { showFeedback("无法导出选区，请重试"); return }
        val token=++actionGeneration
        busy=true; sync()
        actionWorker.execute {
            try {
                val uri=ScreenshotShare.write(context,bitmap)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if(token==actionGeneration && !disposed) {
                        try { ScreenshotShare.open(context,uri); exit() }
                        catch(error: Exception) { busy=false; sync(); showFeedback("无法打开分享，请重试") }
                    }
                }
            } catch(error: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { if(token==actionGeneration && !disposed) { busy=false; sync(); showFeedback("导出失败，请检查可用空间") } }
            } finally { bitmap.recycle() }
        }
    }
    private fun beginRecognize(forTranslation: Boolean = false) {
        val region = picked ?: return
        android.util.Log.i("VoxFix","SCREENSHOT_OCR_TAP region=${region.width()}x${region.height()}")
        if (busy) return
        resultTool=if(forTranslation) ScreenshotAction.TRANSLATE else ScreenshotAction.RECOGNIZE
        setEditing(false)
        translateAfterRecognition = forTranslation
        discardRecognition = false
        busy = true; busySource = "ocr"
        status.text = "正在识别…"; status.visibility = View.VISIBLE
        sync(); recognize(Rect(region))
    }
    fun textResult(blocks: List<ScreenshotTextBlock>) {
        busy = false; busySource = null
        val translateNext = translateAfterRecognition
        translateAfterRecognition = false
        if (discardRecognition) {
            discardRecognition = false
            sync()
            return
        }
        translationGeneration++
        translationComplete = false
        translating = false; showingTranslation = false
        translationLayer?.visibility = View.GONE
        pairBar?.visibility = View.GONE
        if (blocks.isEmpty()) {
            clearText()
            status.text = "未识别到文字，可重新框选"; status.visibility = View.VISIBLE
            sync(); return
        }
        if(resultTool==null) resultTool=ScreenshotAction.RECOGNIZE
        val layer = ScreenshotTextLayer(context,image,blocks,::textChanged, { setEditing(true, document = true) })
        layer.viewport=viewport
        layer.historyChanged=::sync
        translationLayer = null
        textLayer = layer; showingOriginal = false
        resultLayer.removeAllViews()
        resultLayer.addView(layer,FrameLayout.LayoutParams(-1,-1))
        resultLayer.visibility = View.VISIBLE
        // The capture range must stay visible; only the recognized text loses its own frame.
        // 识别结果依赖这一层画出捕获范围，所以这里显式退出只读态（工程编辑模式下
        // restoreProject 会把它设为只读，识别后要能重新看到框）。
        selector.readOnly = false
        selector.visibility = View.VISIBLE
        status.visibility = View.GONE
        sync()
        if (translateNext) { showPairBar(); startTranslation() }
    }
    fun failure(message: String) {
        busy = false
        translateAfterRecognition = false
        discardRecognition = false
        status.text = message; status.visibility = View.VISIBLE
        sync()
    }
    private fun textChanged(value: String) { if(!showingTranslation) translationComplete=false; onText(value) }
    private var translationGeneration = 0

    private fun requestTranslation() {
        if (translateAfterRecognition || translating) { abortTranslation(); return }
        if (busy || editing) return
        if (resultTool==ScreenshotAction.TRANSLATE && showingTranslation) { leaveResultTool(); return }
        resultTool=ScreenshotAction.TRANSLATE
        if (textLayer == null) { beginRecognize(forTranslation = true); return }
        if (textLayer?.textForTranslation().isNullOrBlank()) return
        showPairBar()
        if(translationComplete && translationLayer!=null) {
            showingOriginal=false; showingTranslation=true; resultLayer.visibility=View.VISIBLE
            translationLayer?.visibility=View.VISIBLE; textLayer?.visibility=View.GONE; sync(); return
        }
        startTranslation()
    }
    private fun toggleRecognition() {
        if(busy || translating || editing) return
        if(resultTool==ScreenshotAction.RECOGNIZE) { leaveResultTool(); return }
        resultTool=ScreenshotAction.RECOGNIZE
        if(textLayer==null) { beginRecognize(); return }
        showingTranslation=false; showingOriginal=false
        translationLayer?.visibility=View.GONE; textLayer?.visibility=View.VISIBLE; resultLayer.visibility=View.VISIBLE
        sync()
    }
    private fun leaveResultTool() {
        setEditing(false); resultTool=null; showingOriginal=true; showingTranslation=false
        translationLayer?.visibility=View.GONE; textLayer?.visibility=View.VISIBLE
        resultLayer.visibility=View.INVISIBLE; pairBar?.visibility=View.GONE; status.visibility=View.GONE
        sync()
    }

    private fun startTranslation() {
        val blocks = ScreenshotParagraphs.merge(textLayer?.currentBlocks() ?: return)
        resultTool=ScreenshotAction.TRANSLATE
        translationComplete = false
        val token = ++translationGeneration
        translating = true
        showingTranslation = false
        translationLayer?.let { resultLayer.removeView(it) }
        val streamingLayer=ScreenshotTextLayer(context,image,blocks, {}, { },fitToBlock=true,translationRegion=picked)
        streamingLayer.viewport=viewport
        for (index in 0 until streamingLayer.childCount) streamingLayer.getChildAt(index).isEnabled=false
        translationLayer=streamingLayer
        resultLayer.addView(streamingLayer,FrameLayout.LayoutParams(-1,-1))
        textLayer?.visibility = View.GONE
        showingOriginal=false
        resultLayer.visibility=View.VISIBLE
        status.visibility = View.GONE
        sync()
        android.util.Log.i("VoxFix", "TRANSLATE_TAP blocks=${blocks.size} pair=${pair.label}")
        translateScreenshotBlocks(translator, blocks, pair, { token == translationGeneration },
            result@{ result ->
                if (token != translationGeneration) return@result
                translating = false
                if (result.isEmpty()) {
                    showFeedback("翻译结果为空，请重试")
                } else {
                    translationLayer?.let { resultLayer.removeView(it) }
                    val layer = ScreenshotTextLayer(context,image,result, {}, { setEditing(true, document = true) }, fitToBlock = true, translationRegion=picked)
                    layer.viewport=viewport
                    layer.historyChanged=::sync
                    translationLayer = layer
                    resultLayer.addView(layer, FrameLayout.LayoutParams(-1,-1))
                    textLayer?.visibility = View.GONE
                    showingOriginal = false
                    resultLayer.visibility = View.VISIBLE
                    showingTranslation = true
                    translationComplete = true
                }
                sync()
            },
            failure@{ message ->
                if (token != translationGeneration) return@failure
                translating = false
                translationLayer?.visibility=View.GONE
                textLayer?.visibility=View.VISIBLE
                status.text=message
                status.visibility=View.VISIBLE
                if (message == "翻译模型未下载") {
                    runCatching {
                        context.startActivity(android.content.Intent(context, ScreenTextSettingsActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                sync()
            }, update = { index,text ->
                if (token == translationGeneration) {
                    streamingLayer.updateLine(index,text)
                }
            })
    }

    private fun abortTranslation() {
        if (translateAfterRecognition) discardRecognition = true
        translateAfterRecognition = false
        translationGeneration++
        translating = false
        translator.cancel()
        resultTool=null; showingOriginal=true; resultLayer.visibility=View.INVISIBLE
        translationLayer?.visibility = View.GONE
        textLayer?.visibility = View.VISIBLE
        status.visibility = View.GONE
        sync()
    }

    private fun showPairBar() {
        val bar = pairBar ?: TranslatePairBar(
            context,
            onOpenSource = { if(!translating && !busy) pairBar?.showPicker(TranslatePairBar.Side.SOURCE, pair) },
            onSwap = { swapPair() },
            onOpenTarget = { if(!translating && !busy) pairBar?.showPicker(TranslatePairBar.Side.TARGET, pair) },
            onChoose = { language -> chooseLanguage(language) }
        ).also {
            pairBar = it
            secondaryPanel.addView(languageDivider,LinearLayout.LayoutParams(-1,dp(1).coerceAtLeast(1)).apply { marginStart=dp(12); marginEnd=dp(12) })
            secondaryPanel.addView(it,LinearLayout.LayoutParams(-1,dp(44)))
        }
        bar.showPair(pair)
        bar.visibility = View.VISIBLE
    }

    private fun chooseLanguage(language: TranslateLanguage) {
        if (translating || busy) return
        val side = pairBar?.currentSide() ?: return
        val next = (if (side == TranslatePairBar.Side.SOURCE) pair.copy(source = language) else pair.copy(target = language)).normalized()
        if (next == pair) { pairBar?.showPair(pair); return }
        pair = next
        persistPair()
        pairBar?.showPair(pair)
        retranslate()
    }

    private fun swapPair() {
        if (translating || busy) return
        pair = pair.swapped().normalized()
        persistPair()
        pairBar?.showPair(pair)
        retranslate()
    }

    private fun persistPair() {
        ScreenTextSettingsActivity.preferences(context).edit()
            .putString("translate.source", pair.source.preference)
            .putString("translate.target", pair.target.preference)
            .apply()
    }

    private fun retranslate() {
        if (translating || busy) return
        if (textLayer?.textForTranslation().isNullOrBlank()) return
        startTranslation()
    }

    private fun clearText() {
        resultTool=null
        translationComplete = false
        translateAfterRecognition = false
        discardRecognition = false
        translationGeneration++
        translating = false; showingTranslation = false
        translationLayer?.visibility = View.GONE
        pairBar?.visibility = View.GONE
        if (textLayer == null && resultLayer.childCount == 0) return
        setEditing(false)
        textLayer = null; showingOriginal = false
        resultLayer.removeAllViews(); resultLayer.visibility = View.INVISIBLE
        applyImeShift()
    }
    private fun copyText() {
        if (showingTranslation) {
            val translated = translationLayer?.copyText()?.takeIf { it.isNotBlank() } ?: return
            try {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("截图译文", translated))
                showFeedback("已复制译文")
            } catch (_: Exception) {
                showFeedback("复制失败，请重试")
            }
            return
        }
        val value = textLayer?.copyText()?.takeIf { it.isNotBlank() } ?: return
        android.util.Log.i("VoxFix","SCREENSHOT_COPY selection=${value.length} total=${textLayer?.text()?.length ?: 0}")
        try {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("截图文字",value))
            // An in-overlay pill is used instead of Toast: background toast limits on Android 12+
            // can drop it while the screenshot overlay is the only visible window.
            showFeedback("已复制文字")
        } catch (_: Exception) {
            showFeedback("复制失败，请重试")
        }
    }
    private fun showFeedback(message: String) {        val view = feedback ?: TextView(context).apply {
            textSize = 12f
            // 面板背景是深色 COLOR_PANEL，文字必须用白色——2026-11-09 用户反馈黑底黑字看不清。
            setTextColor(COLOR_TEXT)
            setPadding(dp(10),dp(6),dp(10),dp(6))
            background = surface(PANEL_RADIUS)
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }.also {
            feedback = it
            root.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        view.removeCallbacks(hideFeedback)
        view.text = message
        view.visibility = View.VISIBLE
        view.postDelayed(hideFeedback, 1600)
        placeControls()
    }
    private fun toggleOriginal() {
        if (textLayer == null) return
        showingOriginal = !showingOriginal
        if (showingOriginal) {
            setEditing(false)
        }
        resultLayer.visibility = if (showingOriginal) View.INVISIBLE else View.VISIBLE
        sync()
    }
    private fun setEditing(next: Boolean, document: Boolean = false) {
        if (editing == next) return
        if (next && textLayer == null) return
        if (next && translating) {
            translationGeneration++
            translating = false; showingTranslation = false
            translationLayer?.visibility = View.GONE
            pairBar?.visibility = View.GONE
        }
        editing = next
        val layer = if (showingTranslation) translationLayer else textLayer
        if (next) {
            layer?.endLayoutMode(); textLayoutButton.setColorFilter(COLOR_TEXT)
            showingOriginal = false
            resultLayer.visibility = View.VISIBLE
            // The overlay window is focusable from creation, so the fields can own the input
            // connection; toggling FLAG_NOT_FOCUSABLE later never binds the IME.
            root.requestFocus()
            root.postDelayed({
                if (!editing) return@postDelayed
                if (document) picked?.let { layer?.beginDocumentEditing(it) } else layer?.beginEditing()
                applyImeShift()
                placeControls()
                layer?.focusedField()?.let {
                    context.getSystemService(InputMethodManager::class.java).showSoftInput(it,InputMethodManager.SHOW_IMPLICIT)
                }
            },80)
        } else {
            layer?.endEditing(); hideKeyboard(); invalidateCanvas()
        }
        sync()
    }
    private fun hideKeyboard() {
        root.windowToken?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it,0) }
    }
    private fun applyImeShift() {
        val layer = if (showingTranslation) translationLayer else textLayer
        // 不重置缩放/平移：那是用户选定的视角，键盘弹起只改文字层可视范围。
        invalidateCanvas()
        if (!editing || layer == null || root.height == 0) return
        // 文字层与画布共用同一套视口映射，所以可视范围直接给屏幕坐标（旧口径要除以 zoom）。
        layer.setEditingViewport(bars.top+dp(8), root.height-maxOf(imeBottom,bars.bottom)-dp(96))
    }
    /** The grip moves the whole bar instead of letting the auto anchor decide again. */
    private fun dragToolbar(dx: Float,dy: Float) {
        if(root.width==0) return
        val base=toolbarManual ?: android.graphics.Point(toolbar.translationX.toInt(),toolbar.translationY.toInt())
        toolbarManual=android.graphics.Point(base.x+dx.toInt(),base.y+dy.toInt())
        placeControls()
    }
    private fun placeControls() {
        val width=root.width; val height=root.height
        if(width==0 || height==0) return
        if(toolbarAvailable!=width-bars.left-bars.right-dp(16)) rebuildToolbar()
        val safeTop=bars.top+dp(8)
        val safeBottom=height-maxOf(bars.bottom,imeBottom)-dp(8)
        val rect=picked
        // 画布可能不是满宽（等比居中）且有垂直平移，锚点必须走画布坐标而不是 root 宽高。
        val left=rect?.let { canvasX(it.left).toInt() } ?: (width-toolbarWidth)/2
        val top=rect?.let { canvasY(it.top).toInt() } ?: safeBottom
        val bottom=rect?.let { canvasY(it.bottom).toInt() } ?: safeBottom
        val browsing = selector.smartEnabled && smartPath.isNotEmpty()
        // 锚点缓存必须包含画布偏移与缩放：缩放/平移后选区的屏幕位置变了，工具栏要跟着重算，
        // 否则会停在首次布局前算出的旧位置。
        val anchorKey="$width,$height,$rect,${canvasLeft()},${canvasTop()},$zoom,${bars.top},${bars.bottom},$imeBottom,$toolbarWidth,$toolbarHeight,$browsing"
        val manual=toolbarManual
        if(manual!=null) {
            toolbarAnchor=android.graphics.Point(
                manual.x.coerceIn(bars.left+dp(8),maxOf(bars.left+dp(8),width-bars.right-toolbarWidth-dp(8))),
                manual.y.coerceIn(safeTop,maxOf(safeTop,safeBottom-toolbarHeight)))
        } else if(toolbarAnchorKey!=anchorKey) {
            // The bar hugs the selection (user request 2026-11-06): below its bottom edge when it
            // fits, else directly above its top edge. The old 104dp detail-panel reserve pushed
            // the bar far off a low or tall selection — up to the screen top — which read as a
            // jump; the detail panel may now overlap the frozen image instead.
            val proposed=when {
                bottom+toolbarHeight+dp(8)<=safeBottom -> bottom+dp(8)
                top-toolbarHeight-dp(8)>=safeTop -> top-toolbarHeight-dp(8)
                else -> safeBottom-toolbarHeight
            }
            val minX = bars.left+dp(8)
            val maxX = maxOf(minX,width-bars.right-toolbarWidth-dp(8))
            toolbarAnchor = if (browsing) android.graphics.Point((minX+maxX)/2, (safeBottom-toolbarHeight).coerceAtLeast(safeTop))
            else android.graphics.Point(left.coerceIn(minX,maxX),
                proposed.coerceIn(safeTop,maxOf(safeTop,safeBottom-toolbarHeight-dp(48))))
            toolbarAnchorKey=anchorKey
        }
        val x=toolbarAnchor.x
        val y=toolbarAnchor.y
        toolbar.translationX=x.toFloat(); toolbar.translationY=y.toFloat()
        if (smartBar.visibility == View.VISIBLE) {
            val safe = ScreenshotDocRect(bars.left+dp(8), safeTop, width-bars.right-dp(8), safeBottom)
            val selection = rect?.let {
                ScreenshotDocRect(left, top, canvasX(it.right).toInt(), bottom)
            }
            val panel = if (toolbar.visibility == View.VISIBLE)
                ScreenshotDocRect(x, y, x+toolbarWidth, y+toolbarHeight) else null
            val buttonCount = listOf(smartInner, smartOuter).count { it.visibility == View.VISIBLE }
            val dock = ScreenshotSmartSelection.dock(safe, selection, panel, dp(48)*buttonCount, dp(48), dp(8))
            smartBar.translationX = dock.left.toFloat()
            smartBar.translationY = dock.top.toFloat()
        }
        val detailWidth=minOf(maxOf(toolbarWidth,dp(320)),width-bars.left-bars.right-dp(16)).coerceAtLeast(1)
        val lp=secondaryPanel.layoutParams
        if(lp.width!=detailWidth || lp.height!=-2) secondaryPanel.layoutParams=FrameLayout.LayoutParams(detailWidth,-2)
        // 先按内容测高，再决定贴上还是贴下：旧判据只看「下方还剩 104dp」，三行详细栏（比如滤镜的
        // 类型／面积／强度）会超出屏幕底部被裁掉——看得见最后一行却点不到滑杆。
        // 单行化之后上限从 280dp 收到 120dp：内容本来就只有一条 40dp 的行，多出来的高度只是遮挡画布。
        val detailMax=dp(ScreenshotToolbarMetrics.DETAIL_MAX)
        if(secondaryMaxHeight!=detailMax) secondaryMaxHeight=detailMax
        if(secondaryPanel.visibility==View.VISIBLE) {
            secondaryPanel.measure(View.MeasureSpec.makeMeasureSpec(detailWidth,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        }
        val contentHeight=secondaryPanel.measuredHeight.coerceIn(1,detailMax)
        val below=(safeBottom-y-toolbarHeight-dp(8)).coerceAtLeast(0)
        val above=(y-safeTop-dp(8)).coerceAtLeast(0)
        // 滞回：贴上/贴下在临界值来回翻转就是「二级栏上下跳」。只有上方明显更宽裕（24dp）
        // 才翻上去；翻回来同理。用户拖过（secondaryManual）与刚换工具/重新展开时重置。
        val wantAbove=below<contentHeight && above>below
        val detailsAbove=when {
            secondaryPanel.visibility!=View.VISIBLE -> false
            secondaryManual!=null -> secondaryAbove
            wantAbove && !secondaryAbove && above-below>=dp(24) -> true
            !wantAbove && secondaryAbove && below>=contentHeight+dp(24) -> false
            else -> secondaryAbove
        }
        secondaryAbove=detailsAbove
        val detailHeight=(if(detailsAbove) above else below).coerceAtLeast(1).coerceAtMost(detailMax)
        val detailTop=if(detailsAbove) y-dp(8)-detailHeight else y+toolbarHeight+dp(8)
        if(secondaryMaxHeight!=detailHeight) { secondaryMaxHeight=detailHeight; secondaryPanel.requestLayout() }
        if(secondaryPanel.visibility==View.VISIBLE) {
            secondaryPanel.measure(View.MeasureSpec.makeMeasureSpec(detailWidth,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(detailHeight,View.MeasureSpec.AT_MOST))
        }
        // 用户拖过就听用户的；没拖过就贴着主栏（下沿放不下改贴上沿）。
        val secondaryPos = secondaryManual
        if (secondaryPos != null) {
            secondaryPanel.translationX = secondaryPos.x.coerceIn(bars.left+dp(8),
                maxOf(bars.left+dp(8), width-bars.right-detailWidth-dp(8))).toFloat()
            secondaryPanel.translationY = secondaryPos.y.coerceIn(safeTop,
                maxOf(safeTop, safeBottom-detailHeight)).toFloat()
        } else {
            secondaryPanel.translationX=x.coerceAtMost(width-bars.right-detailWidth-dp(8)).coerceAtLeast(bars.left+dp(8)).toFloat()
            secondaryPanel.translationY=detailTop.toFloat()
        }
        // 收起后的「详细设置 ▴」跟主栏走，和「显示选择栏」同一套锚点。
        secondaryRestore.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dp(48),View.MeasureSpec.AT_MOST))
        secondaryRestore.translationX=x.coerceAtMost(width-bars.right-secondaryRestore.measuredWidth-dp(8))
            .coerceAtLeast(bars.left+dp(8)).toFloat()
        secondaryRestore.translationY=(y+toolbarHeight+dp(8))
            .coerceAtMost(maxOf(safeTop,safeBottom-secondaryRestore.measuredHeight)).toFloat()
        annotationInput?.place(safeTop,safeBottom)
        annotationEditor?.takeIf { annotationInput==null }?.let { panel ->
            val panelWidth=minOf(dp(360),width-bars.left-bars.right-dp(16)).coerceAtLeast(1)
            val panelHeight=minOf(dp(240),(safeBottom-safeTop-dp(8)).coerceAtLeast(dp(80)))
            panel.layoutParams=FrameLayout.LayoutParams(panelWidth,panelHeight)
            // 默认贴着主栏：下方放得下就接下沿，放不下就移到上沿，不再固定吊在屏幕底部。
            val belowTop = y + toolbarHeight + dp(8)
            val fitsBelow = belowTop + panelHeight <= safeBottom
            panel.translationX=x.coerceAtMost(width-bars.right-panelWidth-dp(8)).coerceAtLeast(bars.left+dp(8)).toFloat()
            panel.translationY=(if (fitsBelow) belowTop else y-panelHeight-dp(8))
                .coerceIn(safeTop,maxOf(safeTop,safeBottom-panelHeight)).toFloat()
        }
        hideBarRestore.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dp(48),View.MeasureSpec.AT_MOST))
        hideBarRestore.translationX=x.coerceAtMost(width-bars.right-hideBarRestore.measuredWidth-dp(8)).coerceAtLeast(bars.left+dp(8)).toFloat()
        hideBarRestore.translationY=(if (detailsAbove) detailTop else y+toolbarHeight+dp(8)).toFloat()
        status.maxWidth=(width-bars.left-bars.right-dp(16)).coerceAtLeast(1)
        status.translationX=x.toFloat(); status.translationY=(y-dp(34)).coerceAtLeast(safeTop).toFloat()
        feedback?.let { view ->
            if(view.visibility!=View.VISIBLE) return@let
            view.maxWidth=(width-bars.left-bars.right-dp(32)).coerceAtLeast(1)
            view.measure(View.MeasureSpec.makeMeasureSpec(view.maxWidth,View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(dp(40),View.MeasureSpec.AT_MOST))
            view.translationX=((width-view.measuredWidth)/2).coerceAtLeast(bars.left+dp(8)).toFloat()
            view.translationY=(y-view.measuredHeight-dp(8)).coerceAtLeast(safeTop).toFloat()
        }
    }
    private fun beginSave() {
        val region = picked ?: return
        if (busy) return
        busy = true; status.text = "正在保存…"; status.visibility = View.VISIBLE
        sync()
        val composed = try {
            // Save what the user sees: an edited result layer is exported, original view is not.
            val rendered=composeCurrent(region)
            val shaped=captureRegion
            when {
                shaped==null -> rendered
                rendered!=null -> shaped.mask(if(rendered.isMutable) rendered else rendered.copy(Bitmap.Config.ARGB_8888,true),region)
                else -> {
                    val base=Bitmap.createBitmap(image,region.left,region.top,region.width(),region.height())
                    shaped.mask(if(base.isMutable) base else base.copy(Bitmap.Config.ARGB_8888,true),region)
                }
            }
        } catch (error: Exception) {
            android.util.Log.w("VoxFix","SCREENSHOT_RENDER_FAILED",error)
            busy = false; status.text = "保存失败，请重试"; sync(); return
        }
        save(ScreenshotSaveRequest(Rect(region),composed,currentDocument(region),
            project?.replaceId,project?.revision,project?.copySourceFromId))
    }
    /** Everything needed to reopen this screenshot, captured before the session is released. */
    private fun currentDocument(region: Rect)=ScreenshotDocument(image.width,image.height,
        ScreenshotDocRect.of(region),drawing.history.items,currentText(),captureRegion?.rects?.map(ScreenshotDocRect::of).orEmpty(),
        captureRegion?.takeIf { it.isCustom }?.let { region -> region.toJson().toString() },
        drawing.pixelUnit,drawing.fontUnit,drawing.watermark.takeIf { it.isVisible() },
        // 记下当前工具与样式，重开这张图时可以接着用同一把笔，而不是回到默认红箭头。
        drawing.tool?.name,drawing.style)
    private fun currentText(): ScreenshotDocumentText? {
        val layer=textLayer ?: return null
        val blocks=layer.currentBlocks()
        if(blocks.isEmpty()) return null
        val translated=translationComplete && translationLayer!=null
        return ScreenshotDocumentText(blocks,
            if(translated) translationLayer?.currentBlocks().orEmpty() else emptyList(),
            if(showingTranslation && translated) ScreenshotTextMode.TRANSLATION else ScreenshotTextMode.ORIGINAL,
            translated)
    }
    /** Place recognised lines by hand instead of only editing their characters. */
    private fun toggleTextLayout() {
        if (busy || translating) return
        val layer = editedLayer() ?: return
        if (layer.isLayoutMode) { layer.endLayoutMode(); sync(); return }
        if (showingOriginal) { showingOriginal = false; resultLayer.visibility = View.VISIBLE }
        if (showingTranslation) { translationLayer?.visibility = View.VISIBLE; textLayer?.visibility = View.GONE }
        else { translationLayer?.visibility = View.GONE; textLayer?.visibility = View.VISIBLE }
        setEditing(false)
        layer.beginLayoutMode()
        if (!layer.isLayoutMode) showFeedback("译文按段落排版，改回原文后可拖动")
        else showFeedback("拖动文字块移动，右下角改大小")
        textLayoutButton.setColorFilter(if (layer.isLayoutMode) COLOR_PRIMARY else COLOR_TEXT)
        sync()
    }
    /** Back key: leave editing first, otherwise the service closes the whole capture. */
    fun back(): Boolean {
        if (recapturing) return false
        if (annotationEditor != null) { closeDrawingEditor(); sync(); return true }
        if (regionOperation != null) { setRegionOperation(null); sync(); return true }
        if (moveMode) { toggleMoveMode(); return true }
        editedLayer()?.takeIf { it.isLayoutMode }?.let { it.endLayoutMode(); textLayoutButton.setColorFilter(COLOR_TEXT); sync(); return true }
        if (stackPopup != null) { closeStack(); return true }
        if (drawing.tool != null && drawing.tool!=ScreenshotDrawingTool.SELECT) {
            drawing.tool = ScreenshotDrawingTool.SELECT; rebuildDrawingStyles(); sync(); return true
        }
        if (!editing) return false
        setEditing(false); return true
    }
    val isEditing: Boolean get() = editing || annotationEditor != null
    /** An idle timeout must not tear down a record the user opened from the clipboard. */
    val isProjectEditor: Boolean get() = project != null
    fun retainedResult(): ScreenTextOverlay.ResultState? {
        val source = textLayer?.retainedResult()
        val translated = if (translationComplete) translationLayer?.retainedResult()?.copy(translation = true) else null
        return if (showingTranslation && translated != null) translated.copy(alternate = source)
            else source?.copy(alternate = translated) ?: translated ?: recaptureDrafts.takeIf { it.isNotEmpty() }?.let {
                val draft = it.joinToString("\n\n")
                ScreenTextOverlay.ResultState(draft, draft.length, draft.length, false)
            }
    }
    fun show(windowManager: WindowManager) {
        windows = windowManager
        val layout = WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT; setFitInsetsTypes(0); windowAnimations = 0
            // Focusable from the start: text fields must own a real input connection.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        params = layout
        windowManager.addView(root,layout); root.requestFocus(); root.requestApplyInsets()
        applyDebugZoomIfRequested()
    }
    /**
     * 调试用：`settings put global voxfix_debug_zoom 4.2` 后打开记录，直接把画布放到该倍率。
     *
     * adb 无法模拟双指，没有这个入口就测不到"放大后滚动"的真实帧耗时——而长图只有放大后
     * 才需要滚动，整幅时高度正好等于视口。只在可调试包生效。
     */
    private fun applyDebugZoomIfRequested() {
        val debuggable = (context.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        val value = runCatching {
            android.provider.Settings.Global.getString(context.contentResolver, "voxfix_debug_zoom")
        }.getOrNull()?.toFloatOrNull() ?: return
        root.post {
            if (disposed) return@post
            val current = zoom
            if (current > 0f && value > 0f && viewport.zoomBy(value / current, root.width / 2f, root.height / 2f, canvasFit)) {
                invalidateCanvas()
            }
        }
    }
    /**
     * 把整个编辑器窗口画到一张位图上。
     *
     * 调试取证用：本机 `screencap` 抓 accessibility overlay 会冻结在某一帧
     * （实测连续点击工具后 `gfxinfo` 帧数在涨、截图字节完全不变），
     * 只有进程内绘制能拿到真实合成结果。
     */
    fun snapshot(): Bitmap {
        val w = root.width.coerceAtLeast(1)
        val h = root.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }
    fun dismiss(windowManager: WindowManager) {
        disposed=true; actionGeneration++; actionWorker.shutdown()
        // 画布动画随窗口一起收：双击缩放动画若继续跑，会在已移除的 root 上回调 placeControls。
        cancelZoomAnimation()
        drawTemplatePopup?.dismiss()
        closeDrawingEditor(); drawing.close()
        // 分块底图随窗口一起释放：不回收的话 30MB 分块会留在内存里。
        backdropTiles.release(); drawing.backdrop = null
        closeStack()
        hideKeyboard(); background.setImageDrawable(null)
        translationGeneration++; translating = false
        feedback?.removeCallbacks(hideFeedback)
        runCatching { windowManager.removeViewImmediate(root) }
        pairBar = null; translationLayer = null; feedback = null
        params = null; windows = null
    }
    private inner class Action(
        val action: ScreenshotAction,
        icon: Int,
        private val danger: Boolean = false,
        run: () -> Unit
    ) : ImageView(context) {
        var active = false
        private val cellRadius = dp(6)
        /**
         * 手机侧扩展：原版未选中完全透明，靠鼠标 hover 出浅底提示「可点」；
         * 触屏没有 hover，照抄就等于一排看不见边界的图标（用户找不到 T 文本入口的直接原因）。
         */
        private val idleSurface = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT); cornerRadius = cellRadius.toFloat()
            setStroke(dp(1).coerceAtLeast(1), ScreenshotUiTokens.OUTLINE_IDLE_MAIN)
        }
        /** 原版选中态是 antd `Tonal`：浅主色底 + 主色图标（不是实心蓝底白图标）。 */
        private val tonalSurface = GradientDrawable().apply {
            setColor(ScreenshotUiTokens.TONAL_BG); cornerRadius = cellRadius.toFloat()
            setStroke(dp(1).coerceAtLeast(1), ScreenshotUiTokens.OUTLINE_SELECTED_MAIN)
        }
        /** 水波要裁成圆角；改造前 mask 为 null，按下时水波是方块。 */
        private val rippleMask = GradientDrawable().apply {
            setColor(android.graphics.Color.WHITE); cornerRadius = cellRadius.toFloat()
        }
        private val idleBackground = RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), idleSurface, rippleMask)
        private val tonalBackground = RippleDrawable(ColorStateList.valueOf(0x331677FF), tonalSurface, rippleMask)
        private val idleIcon = icon
        private val busyDrawable = ScreenshotBusySpinnerDrawable(context)
        private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var busyShownAt = 0L
        private val restoreBusy = Runnable { restoreIcon() }
        /** 忙碌结束后的图标恢复；转圈只是交互动画，太短也要看得见（最短显示 900ms）。 */
        private fun restoreIcon() {
            busyDrawable.stop(); setImageResource(idleIcon)
        }
        fun setBusy(value: Boolean) {
            uiHandler.removeCallbacks(restoreBusy)
            if (value) {
                if (drawable !== busyDrawable) {
                    busyShownAt = android.os.SystemClock.uptimeMillis()
                    busyDrawable.start(); setImageDrawable(busyDrawable)
                    // ImageView 级 ColorFilter 会残留到新 drawable；转圈自带蓝底白弧，不吃染色。
                    colorFilter = null
                }
            } else if (drawable === busyDrawable) {
                val elapsed = android.os.SystemClock.uptimeMillis() - busyShownAt
                uiHandler.postDelayed(restoreBusy, (SPINNER_MIN_MS - elapsed).coerceAtLeast(0))
            }
        }
        init {
            setImageResource(icon); scaleType = ImageView.ScaleType.CENTER_INSIDE
            // 32dp 格 + 24dp 图标 = 4dp 内边距；设备断言锁定这两个值，描边只能画在 background 里。
            setPadding(dp((BUTTON-ICON)/2),dp((BUTTON-ICON)/2),dp((BUTTON-ICON)/2),dp((BUTTON-ICON)/2))
            background = idleBackground
            isClickable = true; isFocusable = true
            setOnClickListener { if (!disposed && !closeRequested) { toolMemory?.remember(action); run() } }
        }
        fun refreshTint() {
            if (drawable is ScreenshotBusySpinnerDrawable) {
                // 原版 busy 指示器固定主题色（busyIndicatorColor=主色×0.72），
                // 不跟随按钮 disabled 变灰——灰弧在深色面板上看不见。
                colorFilter = null
                return
            }
            val color = when {
                !isEnabled -> COLOR_TEXT_DISABLED
                danger -> COLOR_DANGER
                active -> COLOR_PRIMARY
                else -> COLOR_TEXT
            }
            setColorFilter(color,PorterDuff.Mode.SRC_IN)
            // 只在状态切换时换对象，避免每次 drawableStateChanged 都新建 drawable 打断水波。
            val wanted = if(active) tonalBackground else idleBackground
            if (background !== wanted) background = wanted
        }
        override fun drawableStateChanged() { super.drawableStateChanged(); refreshTint() }
    }
}
