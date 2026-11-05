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
}
