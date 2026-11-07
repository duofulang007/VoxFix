package local.voxfix.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import org.json.JSONArray

/**
 * T21 non-rectangular capture areas: the operand model (`ScreenshotRegionGeometry`), the export
 * mask over custom shapes, and the three construction gestures on `ScreenTextRegionView`
 * (polyline/curve tap-per-vertex with a double-tap close, freehand press-drag-release).
 */
internal object ScreenshotRegionChecks {
    fun run(context: Context) {
        model(context)
        mask(context)
        gesture(context)
        operation(context)
    }

    private fun triangle() = listOf(PointF(10f, 10f), PointF(90f, 10f), PointF(10f, 90f))

    private fun model(context: Context) {
        val custom = ScreenshotRegion.ofShape(triangle(), ScreenshotRegionShape.POLYLINE)
        check(custom.isCustom) { "折线选区应记为自定义形状" }
        check(custom.rects.isEmpty()) { "自定义选区不应再输出矩形列表" }
        check(custom.bounds == Rect(10, 10, 90, 90)) { "折线边界算错：${custom.bounds}" }
        check(!custom.path().isEmpty) { "折线路径为空" }
        val round = ScreenshotRegion.fromJson(custom.toJson())
        check(round != null && round.isCustom && round.bounds == custom.bounds) { "operands JSON 往返失真" }
        val rects = ScreenshotRegion.ofRects(listOf(Rect(0, 0, 50, 50), Rect(60, 0, 100, 50)))
        check(!rects.isCustom && rects.rects.size == 2) { "矩形链应保持精确矩形表示" }
        check(rects.bounds == Rect(0, 0, 100, 50)) { "矩形链边界算错" }
        val rectsRound = ScreenshotRegion.fromJson(rects.toJson())
        check(rectsRound != null && rectsRound.rects.size == 2 && !rectsRound.isCustom) { "矩形链 JSON 往返失真" }
        val base = ScreenshotRegion.ofRects(listOf(Rect(0, 0, 100, 100)))
        val cut = base.combined(triangle(), ScreenshotRegionShape.POLYLINE, ScreenshotRegionOp.SUBTRACT)
        check(cut.isCustom && cut.bounds == Rect(0, 0, 100, 100)) { "扣除后边界应保持整框" }
        val grown = base.combined(listOf(PointF(80f, 80f), PointF(150f, 80f), PointF(150f, 150f), PointF(80f, 150f)),
            ScreenshotRegionShape.POLYLINE, ScreenshotRegionOp.ADD)
        check(grown.bounds == Rect(0, 0, 150, 150)) { "添加后边界应扩展：${grown.bounds}" }
        check(ScreenshotRegion.fromJson(JSONArray("[[1,\"polyline\",[[1,2],[3,4]]]]")) == null) { "顶点不足应拒绝" }
        check(ScreenshotRegion.fromJson(JSONArray("[[2,\"polyline\",[[0,0],[9,0],[0,9]]]]")) == null) { "首操作必须为添加" }
    }

    private fun mask(context: Context) {
        val base = ScreenshotRegion.ofRects(listOf(Rect(0, 0, 100, 100)))
        val cut = base.combined(triangle(), ScreenshotRegionShape.POLYLINE, ScreenshotRegionOp.SUBTRACT)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        cut.mask(bitmap)
        fun pixel(x: Int, y: Int) = bitmap.getPixel(x, y)
        check(pixel(20, 20) == Color.TRANSPARENT) { "折线扣除的三角内部应导出为空" }
        check(pixel(80, 80) == Color.WHITE) { "扣除后剩余部分应保持原像素" }
        // A rectangle region must keep the exact fast path: no mask when it contains the viewport.
        val plain = ScreenshotRegion.ofRects(listOf(Rect(0, 0, 100, 100)))
        val keep = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        check(plain.mask(keep) === keep) { "纯矩形选区不应重绘遮罩" }
    }

    private fun gesture(context: Context) {
        val view = ScreenTextRegionView(context, 400, 400, null, {})
        view.layout(0, 0, 400, 400)
        var committed: Pair<List<PointF>, ScreenshotRegionShape>? = null
        view.onDraftCommit = { vertices, shape -> committed = vertices to shape }
        fun event(action: Int, x: Float, y: Float, at: Long, down: Long = at) =
            MotionEvent.obtain(down, at, action, x, y, 0)
        fun tap(x: Float, y: Float, at: Long) {
            view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x, y, at))
            view.onTouchEvent(event(MotionEvent.ACTION_UP, x, y, at + 10, at))
        }

        // Polyline: three taps plant vertices, a later close-by tap closes the region.
        view.regionShape = ScreenshotRegionShape.POLYLINE
        tap(50f, 50f, SystemClock.uptimeMillis())
        tap(200f, 60f, SystemClock.uptimeMillis())
        tap(60f, 200f, SystemClock.uptimeMillis())
        check(committed == null) { "双击前不应提交" }
        tap(62f, 198f, SystemClock.uptimeMillis())
        val polyline = committed
        check(polyline?.second == ScreenshotRegionShape.POLYLINE) { "双击应闭合折线选区" }
        check(polyline!!.first.size == 3) { "双击改写末点而非追加：${polyline.first.size} 个顶点" }

        // Long-press takes the last vertex back (phone stand-in for the original's Backspace).
        committed = null
        tap(20f, 20f, SystemClock.uptimeMillis())
        tap(200f, 40f, SystemClock.uptimeMillis())
        tap(30f, 60f, SystemClock.uptimeMillis())
        check(view.draftState().first.size == 3) { "三次点击应有三个顶点: ${view.draftState()}" }
        check(committed == null) { "双击前不应提交" }
        val hold = SystemClock.uptimeMillis()
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 220f, 40f, hold))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, 220f, 40f, hold + 800, hold))
        check(view.draftState().first.size == 2) { "长按应删掉末点: ${view.draftState()}" }
        tap(30f, 210f, SystemClock.uptimeMillis())
        check(committed == null) { "补点仍属单击，不应提交: ${view.draftState()}" }
        check(view.draftState().first.size == 3) { "补点后应有三个顶点: ${view.draftState()}" }
        tap(32f, 208f, SystemClock.uptimeMillis())
        check(committed?.first?.size == 3) { "补点后双击应提交三个顶点: got=$committed draft=${view.draftState()}" }

        // A collinear stroke has no filled area and must not commit (the original rejects lines).
        committed = null
        tap(10f, 300f, SystemClock.uptimeMillis())
        tap(150f, 300f, SystemClock.uptimeMillis())
        tap(300f, 300f, SystemClock.uptimeMillis())
        tap(300f, 300f, SystemClock.uptimeMillis())
        check(committed == null) { "共线点不构成区域" }

        // Freehand: press-drag-release closes on its own; an L-shaped stroke keeps its corner.
        committed = null
        view.regionShape = ScreenshotRegionShape.FREEHAND
        val start = SystemClock.uptimeMillis()
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, 100f, 100f, start))
        var step = 0
        for (index in 1..10) { step++; view.onTouchEvent(
            event(MotionEvent.ACTION_MOVE, 100f + index * 14f, 100f, start + step * 16)) }
        for (index in 1..10) { step++; view.onTouchEvent(
            event(MotionEvent.ACTION_MOVE, 240f, 100f + index * 10f, start + step * 16)) }
        view.onTouchEvent(event(MotionEvent.ACTION_UP, 240f, 200f, start + (step + 1) * 16, start))
        val freehand = committed
        check(freehand?.second == ScreenshotRegionShape.FREEHAND) { "松手应闭合手绘选区: draft=${view.draftState()}" }
        check((freehand!!.first.size) in 3..128) { "手绘顶点应经过简化：${freehand.first.size}" }
        check(freehand.first.first().x < 130f) { "手绘首点应靠近起笔处" }
        val last = freehand.first.last()
        check(last.x > 220f && last.y > 180f) { "手绘末点应落在抬手处：($last.x,$last.y)" }

        // Switching shape mid-construction drops the draft but keeps the view usable.
        view.regionShape = ScreenshotRegionShape.POLYLINE
        committed = null
        view.regionShape = ScreenshotRegionShape.CURVE
        tap(40f, 40f, SystemClock.uptimeMillis())
        tap(200f, 50f, SystemClock.uptimeMillis())
        tap(60f, 220f, SystemClock.uptimeMillis())
        tap(60f, 220f, SystemClock.uptimeMillis())
        val curve = committed
        check(curve?.second == ScreenshotRegionShape.CURVE) { "曲线选区应经双击闭合" }
        check(view.regionShape == ScreenshotRegionShape.CURVE) { "切换形状应生效" }
    }

/**
 * A01/A02：上游 `ScreenshotOverlayInputHandler::beginRegionOperation` 是**一次性**布尔操作——
 * 画完一个形状走 `confirmSelection()` → `commitRegionOperation()`，把 `m_regionOperation` 复位为
 * `Replace`，所以模式不会驻留；减法把选区扣空时 `hasPixelSelection()` 为假，直接
 * `returnToSelectionMode(false)`，选区真的变空而不是恢复旧框。
 */
    private fun operation(context: Context) {
        val image = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val overlay = ScreenshotCaptureOverlay(context, image, {}, {}, {}, {}, {}, object : ScreenshotTranslator {
            override fun translate(text: String, pair: TranslatePair, onResult: (String) -> Unit,
                                   onFailure: (String) -> Unit, onUpdate: (String) -> Unit) {}
            override fun cancel() {}
            override fun available() = true
        })
        fun field(name: String): Any? = overlay.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(overlay)
        fun call(name: String, vararg args: Pair<Class<*>, Any?>) {
            val method = overlay.javaClass.getDeclaredMethod(name, *args.map { it.first }.toTypedArray())
            method.isAccessible = true
            method.invoke(overlay, *args.map { it.second }.toTypedArray())
        }
        try {
            // `selectRegion` 在 view 还没有尺寸时只把选区存成 pending，不会回调 `select`。
            val root = field("root") as android.view.ViewGroup
            root.measure(android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 600, 600)
            (field("selector") as ScreenTextRegionView).selectRegion(Rect(100, 100, 500, 500))
            check(field("picked") != null) { "初始框选没有生效" }

            // 添加：画一个矩形后模式必须退出，而不是留在模式里等第二次点击。
            call("toggleRegionOperation", ScreenshotRegionOp::class.java to ScreenshotRegionOp.ADD)
            check(field("regionOperation") == ScreenshotRegionOp.ADD) { "没有进入添加选区模式" }
            val add = listOf(PointF(480f, 480f), PointF(580f, 480f), PointF(580f, 580f), PointF(480f, 580f))
            call("applyRegionShape", List::class.java to add, ScreenshotRegionShape::class.java to ScreenshotRegionShape.RECTANGLE)
            check(field("regionOperation") == null) { "添加一个形状后应退出加减模式（上游 commitRegionOperation）" }
            val merged = field("picked") as Rect?
            check(merged != null && merged.right >= 580) { "添加后的选区没有扩大：$merged" }

            // 减少：扣空整个选区后应当没有选区，且模式已退出。
            call("toggleRegionOperation", ScreenshotRegionOp::class.java to ScreenshotRegionOp.SUBTRACT)
            check(field("regionOperation") == ScreenshotRegionOp.SUBTRACT) { "没有进入减少选区模式" }
            val wipe = listOf(PointF(0f, 0f), PointF(599f, 0f), PointF(599f, 599f), PointF(0f, 599f))
            call("applyRegionShape", List::class.java to wipe, ScreenshotRegionShape::class.java to ScreenshotRegionShape.RECTANGLE)
            check(field("regionOperation") == null) { "扣除后应退出加减模式" }
            check(field("picked") == null) { "扣空后应没有选区（上游 returnToSelectionMode），而不是恢复旧框" }
            check(field("captureRegion") == null) { "扣空后不应残留自定义选区" }
        } finally { image.recycle() }
    }

}
