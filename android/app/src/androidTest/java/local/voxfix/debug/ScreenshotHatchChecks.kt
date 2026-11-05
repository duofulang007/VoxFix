package local.voxfix.debug

import android.graphics.*

internal object ScreenshotHatchChecks {
    fun run() {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        fun render(pattern: ScreenshotFillPattern,left: Float=0f)=Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888).also {
            ScreenshotHatchRendering.draw(Canvas(it),Path().apply { addRect(left,0f,160f,160f,Path.Direction.CW) },
                0x80FF0000.toInt(),pattern,1f,1f,1f,paint)
            check(paint.shader==null && !paint.isFilterBitmap) { "纹理画笔状态泄漏" }
        }
        val line=render(ScreenshotFillPattern.LINE)
        val cross=render(ScreenshotFillPattern.CROSS_LINE)
        val shifted=render(ScreenshotFillPattern.LINE,20f)
        try {
            val alphas=(0 until 160).flatMap { y -> (0 until 160).map { x -> Color.alpha(line.getPixel(x,y)) } }
            check(alphas.any { it==0 } && alphas.any { it in 1..127 } && alphas.max()==128) { "4倍纹理未抗锯齿或alpha错误" }
            for(y in 25..130) for(x in 25..130) {
                check(shifted.getPixel(x,y)==line.getPixel(x,y)) { "纹理没有锚定画布坐标" }
                check(Color.alpha(cross.getPixel(x,y))>=Color.alpha(line.getPixel(x,y)))
            }
            check((20..130).any { y -> (20..130).any { x -> Color.alpha(cross.getPixel(x,y))>128 } }) { "交叉线未双向绘制" }
        } finally { line.recycle(); cross.recycle(); shifted.recycle() }
    }
}
