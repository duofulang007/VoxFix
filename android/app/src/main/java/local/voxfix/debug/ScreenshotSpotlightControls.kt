package local.voxfix.debug

import local.voxfix.debug.ScreenshotStyleProperty.*
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/** Mask colour editor from Snow Shot 1.1.8; opacity remains in the host's shared slider. */
internal class ScreenshotSpotlightControls(context: Context,private val drawing: ScreenshotDrawingLayer) : HorizontalScrollView(context) {
    private val density=resources.displayMetrics.density
    private fun dp(value: Int)=(value*density).toInt()
    private val style get()=drawing.inspectedStyle
    init {
        isHorizontalScrollBarEnabled=false; contentDescription="聚光灯遮罩颜色"
        val row=LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL; setPadding(dp(6),dp(6),dp(6),dp(6))
        }
        fun swatch(label: String,value: Int,custom: Boolean=false) {
            row.addView(object : View(context) {
                private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(canvas: Canvas) {
                    paint.color=value; paint.style=Paint.Style.FILL
                    canvas.drawCircle(width/2f,height/2f,dp(7).toFloat(),paint)
                    paint.color=Color.WHITE; paint.style=Paint.Style.STROKE; paint.strokeWidth=density
                    canvas.drawCircle(width/2f,height/2f,dp(8).toFloat(),paint)
                }
            }.apply {
                contentDescription=label; tooltipText=label; isFocusable=true; isClickable=true
                isSelected=!custom && style.color==value
                background=GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); cornerRadius=dp(6).toFloat()
                    setStroke(dp(1).coerceAtLeast(1),if(isSelected) 0xFF1677FF.toInt() else 0xFF424242.toInt())
                }
                setOnClickListener { anchor ->
                    if(custom) {
                        val base=style; drawing.beginStylePreview()
                        ScreenshotColorPicker.show(anchor,"遮罩颜色",base.color,false,
                            { color -> drawing.previewStyle(base.copy(color=color!!),COLOR) },
                            drawing::finishStylePreview,sample=drawing::pickColor)
                    } else drawing.applyStyle(style.copy(color=value),COLOR)
                }
            },LinearLayout.LayoutParams(dp(28),dp(28)).apply { if(row.childCount>0) marginStart=dp(4) })
        }
        swatch("自定义遮罩颜色",style.color,true)
        listOf(0xFFF5222D.toInt(),0xFF52C41A.toInt(),0xFF1677FF.toInt(),0xFFFADB14.toInt(),Color.BLACK)
            .forEachIndexed { index,value -> swatch("遮罩颜色 ${listOf("红色","绿色","蓝色","黄色","黑色")[index]}",value) }
        ScreenshotTouchTargets.install(row)
        addView(row)
    }
}
