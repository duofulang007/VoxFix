package local.voxfix.debug

import android.content.Context
import android.graphics.Canvas
import android.view.View

/**
 * 底图独立层。
 *
 * 截图在按下保存那一刻就已经定了，编辑阶段只是在它上面加减标注——底图是静态像素，
 * 不应该跟着标注一起每帧重算。之前把底图塞进绘图层里，导致「画一笔 → 整张长图重新采样」，
 * 这是长截图编辑卡顿的直接原因。
 *
 * 现在底图只在自己被 invalidate 时绘制（视口变化、尺寸变化），标注变化完全不碰它。
 */
class ScreenshotBackdropView(context: Context) : View(context) {

    var backdrop: ScreenshotTileBackdrop? = null
    var viewport: ScreenshotViewport? = null

    init {
        // 纯绘制层：不吃触摸，不参与无障碍焦点。
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val tiles = backdrop ?: return
        val vp = viewport?.takeIf { it.hasSize } ?: return
        tiles.draw(canvas, vp)
    }
}
