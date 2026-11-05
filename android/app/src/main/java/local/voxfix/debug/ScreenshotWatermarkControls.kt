package local.voxfix.debug

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * T23 watermark palette: text, size presets + wheel, device font list, colour, angle, gap and
 * opacity (`screenshottoolpalettestylecontrols.cpp` watermark family). The original also offers
 * saved templates with `{text}`/timestamp placeholders; those are not implemented yet.
 */
internal class ScreenshotWatermarkControls(context: Context,private val drawing: ScreenshotDrawingLayer,
    private val edit: (String,String,(String)->Unit) -> Unit) : LinearLayout(context) {
    private val watermark get()=drawing.watermark
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun set(value: ScreenshotWatermark)=drawing.setWatermark(value)
    private fun chip(text: String,selected: Boolean=false,description: String=text,run:()->Unit)=TextView(context).apply {
        this.text=text; textSize=13f; gravity=Gravity.CENTER; contentDescription=description; tooltipText=description
        setTextColor(if(selected) 0xFF69B1FF.toInt() else 0xFFFFFFFF.toInt())
        background=GradientDrawable().apply { setColor(if(selected) 0x331677FF else Color.TRANSPARENT); cornerRadius=dp(4).toFloat() }
        minHeight=dp(44); isFocusable=true; setOnClickListener { run() }
    }
    private fun row(views: List<View>)=LinearLayout(context).also { line ->
        views.forEach { line.addView(it,LayoutParams(0,dp(44),1f)) }; addView(line,LayoutParams(-1,-2))
    }
    private fun wheel(view: View,run:(Int)->Unit)=view.apply {
        setOnGenericMotionListener { _,event ->
            val delta=event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if(event.actionMasked==MotionEvent.ACTION_SCROLL && delta.isFinite() && delta!=0f) { run(if(delta>0) 1 else -1); true }
            else false
        }
    }
    private fun slider(label: String,value: Int,max: Int,readout: String,block:(Int)->Unit) {
        val line=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        val text=TextView(context).apply { text="$label $readout"; textSize=12f; setTextColor(0xFFFFFFFF.toInt()) }
        line.addView(text,LayoutParams(dp(96),dp(44)))
        line.addView(SeekBar(context).apply {
            this.max=max; progress=value.coerceIn(0,max); contentDescription=label
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onProgressChanged(bar: SeekBar,progress: Int,fromUser: Boolean) { if(fromUser) block(progress) }
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        },LayoutParams(0,dp(44),1f))
        addView(line)
    }
    init {
        orientation=VERTICAL; contentDescription="水印参数"
        row(listOf(chip(if(watermark.text.isEmpty()) "水印文字" else "水印文字：${watermark.text.take(8)}") {
            edit("水印文字",watermark.text) { drawing.setWatermark(watermark.copy(text=it)) }
        }))
        // Templates reference `{text}` / timestamp tokens; applying one stamps the time.
        row(listOf(chip(if(watermark.template.isEmpty()) "水印模板" else "水印模板：${watermark.template.take(8)}",
            selected=watermark.template.isNotEmpty(),description="水印模板，支持 {text} 与日期占位") {
            edit("水印模板",watermark.template) { value ->
                if(value.isBlank()) drawing.setWatermark(watermark.copy(template="",appliedAt=0L))
                else drawing.setWatermark(watermark.copy(template=value,appliedAt=System.currentTimeMillis()))
            }
        }))
        val store=ScreenshotWatermarkTemplateStore(context)
        lateinit var picker: TextView
        picker=chip("选择模板",description="已保存的水印模板，点按应用、长按删除") {
            ScreenshotWatermarkTemplatePicker.show(picker,store) { value ->
                drawing.setWatermark(watermark.copy(template=value,appliedAt=System.currentTimeMillis()))
            }
        }
        row(listOf(picker,chip("存为模板",description="把当前水印模板按名称存入列表") {
            if(watermark.template.isBlank()) return@chip
            edit("模板名称","模板 ${store.templates().size+1}") { name -> store.save(name,watermark.template) }
        }))
        // `watermarkFontSizes()`: S/M/L/XL are 12/16/24/30, the summary button steps by one.
        val size=ScreenshotFontSize.format(watermark.fontSize)
        val sizeRow=mutableListOf<View>(wheel(chip("字号 $size",description="水印字号 $size") {
            edit("水印字号",size) { value ->
                value.toFloatOrNull()?.takeIf { it.isFinite() && it in ScreenshotFontSize.MIN..ScreenshotFontSize.MAX_WATERMARK }
                    ?.let { drawing.setWatermark(watermark.copy(fontSize=it)) }
            }
        }) { delta -> drawing.setWatermark(watermark.copy(fontSize=(watermark.fontSize+delta).coerceIn(ScreenshotFontSize.MIN,ScreenshotFontSize.MAX_WATERMARK))) })
        listOf(12f,16f,24f,30f).forEach { preset ->
            sizeRow.add(chip(ScreenshotFontSize.format(preset),watermark.fontSize==preset,"水印字号 ${ScreenshotFontSize.format(preset)}") {
                drawing.setWatermark(watermark.copy(fontSize=preset))
            })
        }
        row(sizeRow)
        lateinit var family: TextView
        family=chip(watermark.fontFamily,description="水印字体") { ScreenshotFontPicker.showFamily(family,watermark.fontFamily) { value -> drawing.setWatermark(watermark.copy(fontFamily=value)) } }
        row(listOf(family))
        lateinit var color: TextView
        color=chip("水印颜色",description="水印颜色") {
            val base=watermark
            ScreenshotColorPicker.show(color,"水印颜色",base.color,false,
                { value -> value?.let { drawing.setWatermark(watermark.copy(color=it)) } },
                { committed -> if(!committed) drawing.setWatermark(base) },sample=drawing::pickColor)
        }
        row(listOf(color))
        slider("角度",(watermark.angle+90).roundToInt(),180,"${watermark.angle.roundToInt()}°") { value ->
            drawing.setWatermark(watermark.copy(angle=(value-90).toFloat()))
        }
        slider("间距",(watermark.gap-10).roundToInt(),190,"${watermark.gap.roundToInt()}") { value ->
            drawing.setWatermark(watermark.copy(gap=(value+10).toFloat()))
        }
        slider("透明度",(watermark.opacity*100).roundToInt(),100,"${(watermark.opacity*100).roundToInt()}%") { value ->
            drawing.setWatermark(watermark.copy(opacity=value/100f))
        }
    }
}
