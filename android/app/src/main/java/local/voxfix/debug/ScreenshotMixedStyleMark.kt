package local.voxfix.debug

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** drawMixedValueMark, screenshottoolpalettebuttons.cpp at the fixed Snow Shot commit. */
internal object ScreenshotMixedStyleMark {
    fun draw(canvas: Canvas,paint: Paint) {
        paint.reset(); paint.isAntiAlias=true; paint.color=Color.WHITE
        paint.style=Paint.Style.STROKE; paint.strokeWidth=1.75f; paint.strokeCap=Paint.Cap.ROUND
        canvas.drawLine(6f,22f,22f,6f,paint)
    }
}
