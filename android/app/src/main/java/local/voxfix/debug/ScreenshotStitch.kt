package local.voxfix.debug

import android.graphics.Bitmap
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Single-worker owner of the fixed Snow Shot frame pool and stitch session. */
internal class ScreenshotStitch(private val frameWidth: Int, private val frameHeight: Int,
                                private val horizontal: Boolean) : AutoCloseable {
    companion object {
        init { System.loadLibrary("voxfix_snow_stitch") }
        const val INITIAL = 0
        const val DUPLICATE = 4
        const val UNMATCHED = 5
    }

    private var handle = create(frameWidth, frameHeight, horizontal).also { check(it != 0L) }
    var width = 0
        private set
    var height = 0
        private set
    /** Native diagnostics from the last push: [event,w,h,hasOffset,offset,score,second,coverage,fixed,inliers,features]. */
    var lastMetrics: IntArray? = null
        private set
    private val frameExtent = if (horizontal) frameWidth else frameHeight
    // 2026-11-09 用户要求扩大单次截取面积：瓦片画布按像素驻留内存（extent×宽×4 字节），
    // 1080 宽下 40000 行 ≈ 160MB 原生内存，K60 可承受；渲染按带（BAND_ROWS）出图不受影响。
    private val maxExtent = minOf(40_000, 40_000_000 / if (horizontal) frameHeight else frameWidth)
    /** 当前宽度的单段上限（px），UI 用于显示“已拼接 / 上限”。 */
    val limitExtent get() = maxExtent
    private val extent get() = if (horizontal) width else height
    val atLimit get() = extent > 0 && extent + frameExtent > maxExtent

    fun push(buffer: ByteBuffer, rowStride: Int, offset: Int): Int {
        val frame = checkNotNull(pushFrame(handle, buffer, rowStride, offset, horizontal)) { "长截图拼接失败" }
        lastMetrics = frame
        width = frame[1]; height = frame[2]
        return frame[0]
    }

    fun render(maxSide: Int? = null, start: Int = 0, end: Int = extent): Bitmap {
        require(start >= 0 && end > start && end <= extent)
        val width = if (horizontal) end-start else this.width
        val height = if (horizontal) this.height else end-start
        val scale = if (maxSide == null) 1f else minOf(1f, maxSide.toFloat() / maxOf(width, height))
        val result = Bitmap.createBitmap((width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        if (renderFrame(handle, result, scale < 1f, start, end)) return result
        result.recycle()
        error("无法生成长截图")
    }

    override fun close() {
        if (handle != 0L) destroy(handle)
        handle = 0
    }

    private external fun create(width: Int, height: Int, horizontal: Boolean): Long
    private external fun destroy(handle: Long)
    private external fun pushFrame(handle: Long, buffer: ByteBuffer, stride: Int, offset: Int, horizontal: Boolean): IntArray?
    private external fun renderFrame(handle: Long, bitmap: Bitmap, preview: Boolean, start: Int, end: Int): Boolean
}
