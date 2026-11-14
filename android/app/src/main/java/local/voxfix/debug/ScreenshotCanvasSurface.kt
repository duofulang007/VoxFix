package local.voxfix.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 底图绘制：按可见区域从**分块**的源纹理采样。
 *
 * 为什么必须分块：整张长图（916×10192 ≈ 37MB）作为一张纹理交给 GPU 时，
 * 会超出 HWUI 的纹理预算而每帧被驱逐、重新上传——实测本机
 * `dumpsys gfxinfo` 的 `Slow bitmap uploads: 12/12`、单帧 250ms，
 * 也就是用户说的「长截图编辑很卡」。
 *
 * 切成固定高度的分块后，一次绘制只触碰覆盖可见区域的 1–2 块（每块约 7MB），
 * 能稳定驻留；同时仍然**直接从源像素采样**，放大不发虚
 * （与分块采样库在大图上的做法一致）。
 *
 * 为什么不用 `ImageView` + View 变换放大：那是在**已经按布局尺寸栅格化过的纹理**上做
 * GPU 放大，长图布局到 216 宽再放大 4 倍必然发虚、边缘双线。
 */
class ScreenshotTileBackdrop(private val image: Bitmap) {

    private companion object {
        /** 每块高度：单块（916×2048 ≈ 7.5MB）落在安全纹理预算内。 */
        const val TILE_HEIGHT = 2048
        /**
         * 最多缓存几块。
         *
         * 必须整条长图都装得下：916×10192 切成 5 块，缓存小于 5 时滚动会反复驱逐再重建，
         * 每次重建都是一次 7.5MB 位图复制 + 一次纹理上传，正是"卡"的来源。
         * 6 块约 45MB，留一块余量。
         */
        const val TILE_CACHE = 6
    }

    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }

    private val tiles = object : LruCache<Int, Bitmap>(TILE_CACHE) {
        override fun create(key: Int): Bitmap {
            val top = key * TILE_HEIGHT
            val height = minOf(TILE_HEIGHT, image.height - top).coerceAtLeast(1)
            return Bitmap.createBitmap(image, 0, top, image.width, height)
        }

        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            // 被驱逐的分块没人再引用，必须回收，否则只是白占内存。
            if (oldValue !== newValue && !oldValue.isRecycled) oldValue.recycle()
        }
    }

    private val src = Rect()
    private val dst = RectF()

    /** 已缓存的分块数，供自检与日志使用。 */
    val cachedTiles: Int get() = tiles.size()

    fun draw(canvas: Canvas, viewport: ScreenshotViewport) {
        if (image.isRecycled || !viewport.hasSize) return
        val top = viewport.imageTop
        val bottom = viewport.imageBottom
        if (bottom <= top) return
        val last = tileCount
        val first = floor(top / TILE_HEIGHT).toInt().coerceIn(0, last - 1)
        val end = ceil(bottom / TILE_HEIGHT).toInt().coerceIn(first + 1, last)
        for (index in first until end) {
            val tile = tiles[index] ?: continue
            val tileTop = index * TILE_HEIGHT
            if (tileTop >= image.height) continue
            val tileBottom = tileTop + tile.height
            // 只画「分块 ∩ 可见区域」那一段。整块直接贴会把选区外的内容也画出来
            // （画布范围本来只有框选那一块），表现为整屏截图覆盖到画布之外。
            val visibleTop = maxOf(tileTop, floor(top).toInt())
            val visibleBottom = minOf(tileBottom, ceil(bottom).toInt())
            if (visibleBottom <= visibleTop) continue
            src.set(0, visibleTop - tileTop, tile.width, visibleBottom - tileTop)
            dst.set(
                viewport.toScreenX(0f),
                viewport.toScreenY(visibleTop.toFloat()),
                viewport.toScreenX(image.width.toFloat()),
                viewport.toScreenY(visibleBottom.toFloat()),
            )
            canvas.drawBitmap(tile, src, dst, paint)
        }
    }

    private val tileCount: Int
        get() = ceil(image.height / TILE_HEIGHT.toFloat()).toInt().coerceAtLeast(1)

    /** 释放全部分块（视图销毁或换图时调用）。 */
    fun release() {
        tiles.evictAll()
    }
}
