package local.voxfix.debug

import local.voxfix.debug.ScreenshotStyleProperty.*
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/** Mask colour + opacity from Snow Shot 1.1.8 (`spotlight`: mask-colour, opacity). */
internal class ScreenshotSpotlightControls(context: Context,private val drawing: ScreenshotDrawingLayer) : ScreenshotStyleRow(context) {
    private val style get()=drawing.inspectedStyle
    private fun swatch(label: String,value: Int,custom: Boolean=false) {
        addCell(cell(label, selected=!custom && style.color==value, draw={ canvas,paint ->
            // 28dp 逻辑坐标：StyleRow 已经把画布缩放到 dp。
            paint.color=value; paint.style=Paint.Style.FILL
            canvas.drawCircle(14f,14f,7f,paint)
            paint.color=Color.WHITE; paint.style=Paint.Style.STROKE; paint.strokeWidth=1f
            canvas.drawCircle(14f,14f,8f,paint)
        }) { anchor ->
            if(custom) {
                val base=style; drawing.beginStylePreview()
                ScreenshotColorPicker.show(anchor,"遮罩颜色",base.color,false,
                    { color -> drawing.previewStyle(base.copy(color=color!!),COLOR) },
                    drawing::finishStylePreview,sample=drawing::pickColor)
            } else drawing.applyStyle(style.copy(color=value),COLOR)
        })
    }

    private fun opacity() {
        val percent=(style.opacity*100).roundToInt().coerceIn(0,100)
        val mixed=OPACITY in drawing.mixedStyleProperties
        val line=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        val readout=TextView(context).apply {
            text=if(mixed) "透明度 -" else "透明度 $percent%"
            textSize=12f; setTextColor(ScreenshotUiTokens.TEXT)
            contentDescription=if(mixed) "透明度 Mixed" else "透明度 $percent%"
        }
        line.addView(readout,LayoutParams(dp(56),dp(ScreenshotToolbarMetrics.STYLE_CELL)))
        var base=style
        var tracking=false
        var cancelled=false
        line.addView(SeekBar(context).apply {
            max=100; progress=percent; contentDescription="透明度"
            // 滑杆处于横向滚动行内：DOWN 时声明父级不得拦截（同滤镜强度）。
            bindUninterruptedDrag { cancelled=true; tracking=false; drawing.finishStylePreview(false) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { base=style; cancelled=false; tracking=true; drawing.beginStylePreview() }
                override fun onProgressChanged(bar: SeekBar,value: Int,fromUser: Boolean) {
                    readout.text="透明度 $value%"; bar.stateDescription="$value%"
                    if(fromUser) {
                        if(tracking) drawing.previewStyle(base.copy(opacity=value/100f),OPACITY)
                        else drawing.applyStyle(style.copy(opacity=value/100f),OPACITY)
                    }
                }
                override fun onStopTrackingTouch(bar: SeekBar) { if(tracking) drawing.finishStylePreview(!cancelled); tracking=false }
            })
        },LayoutParams(dp(140),dp(ScreenshotToolbarMetrics.STYLE_CELL)))
        addCell(line,204)
    }

    init {
        contentDescription="聚光灯遮罩颜色"
        swatch("自定义遮罩颜色",style.color,true)
        listOf(0xFFF5222D.toInt(),0xFF52C41A.toInt(),0xFF1677FF.toInt(),0xFFFADB14.toInt(),Color.BLACK)
            .forEachIndexed { index,value -> swatch("遮罩颜色 ${listOf("红色","绿色","蓝色","黄色","黑色")[index]}",value) }
        groupBreak()
        opacity()
    }
}
