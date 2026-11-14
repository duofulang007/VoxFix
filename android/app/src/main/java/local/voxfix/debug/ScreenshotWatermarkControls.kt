package local.voxfix.debug

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 水印参数：一条横向属性行。
 *
 * 顺序对齐原版 `buildWatermarkFamily`：颜色 → 文字 → 字体（字号 + 族）→ 模板 → 角度 → 间距 → 透明度。
 * 改造前这里是 11 行 ≈500dp（三个滑杆各占一整行、每行 chip 等分整宽），
 * 二级栏上限 280dp，等于每改一次水印都要在纵向列表里找位置。
 */
internal class ScreenshotWatermarkControls(context: Context,private val drawing: ScreenshotDrawingLayer,
    private val edit: (String,String,(String)->Unit) -> Unit) : ScreenshotStyleRow(context) {
    private val watermark get()=drawing.watermark
    private fun set(value: ScreenshotWatermark)=drawing.setWatermark(value)

    /** 按住期间只预览，抬手才写回 history（否则一次拖动灌进几十条撤销）。 */
    private fun stepper(label: String,min: Float,max: Float,value: Float,fallback: Float,
                        description: (String) -> String, block:(ScreenshotWatermark,Float)->ScreenshotWatermark): View =
        ScreenshotStepper(context,label,min,max,value,fallback,
            begin={ drawing.beginWatermarkPreview() },
            apply={ next -> drawing.previewWatermark(block(watermark,next)) },
            commit={ drawing.finishWatermarkPreview(true) },
            format={ v -> ScreenshotFontSize.format(v) },
            description=description)

    private fun opacity() {
        val percent=(watermark.opacity*100).roundToInt().coerceIn(0,100)
        val line=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        val readout=TextView(context).apply {
            text="透明度 $percent%"; textSize=12f; setTextColor(ScreenshotUiTokens.TEXT)
            contentDescription="透明度 $percent%"
        }
        line.addView(readout,LayoutParams(dp(56),dp(ScreenshotToolbarMetrics.STYLE_CELL)))
        var base=watermark
        var tracking=false
        var cancelled=false
        line.addView(SeekBar(context).apply {
            max=100; progress=percent; contentDescription="透明度"
            // 滑杆处于横向滚动行内：DOWN 时声明父级不得拦截（同滤镜强度）。
            bindUninterruptedDrag { cancelled=true; tracking=false; drawing.finishWatermarkPreview(false) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { base=watermark; cancelled=false; tracking=true; drawing.beginWatermarkPreview() }
                override fun onProgressChanged(bar: SeekBar,value: Int,fromUser: Boolean) {
                    readout.text="透明度 $value%"; readout.contentDescription="透明度 $value%"; bar.stateDescription="$value%"
                    if(fromUser) {
                        val next=base.copy(opacity=value/100f)
                        if(tracking) drawing.previewWatermark(next) else set(next)
                    }
                }
                override fun onStopTrackingTouch(bar: SeekBar) { if(tracking) drawing.finishWatermarkPreview(!cancelled); tracking=false }
            })
        },LayoutParams(dp(130),dp(ScreenshotToolbarMetrics.STYLE_CELL)))
        addCell(line,196)
    }

    init {
        contentDescription="水印参数"
        val store=ScreenshotWatermarkTemplateStore(context)

        addCell(textCell(if(watermark.text.isEmpty()) "水印文字" else "水印文字：${watermark.text.take(8)}",
            watermark.text.isNotEmpty(),"水印文字") {
            edit("水印文字",watermark.text) { set(watermark.copy(text=it)) }
        },88)
        addCell(textCell(if(watermark.template.isEmpty()) "水印模板" else "水印模板：${watermark.template.take(8)}",
            watermark.template.isNotEmpty(),"水印模板，支持 {text} 与日期占位") {
            edit("水印模板",watermark.template) { value ->
                if(value.isBlank()) set(watermark.copy(template="",appliedAt=0L))
                else set(watermark.copy(template=value,appliedAt=System.currentTimeMillis()))
            }
        },88)
        lateinit var picker: TextView
        picker=textCell("选择模板",false,"已保存的水印模板，点按应用、长按删除") {
            ScreenshotWatermarkTemplatePicker.show(picker,store) { value ->
                set(watermark.copy(template=value,appliedAt=System.currentTimeMillis()))
            }
        }
        addCell(picker,80)
        addCell(textCell("存为模板",false,"把当前水印模板按名称存入列表") {
            if(watermark.template.isBlank()) return@textCell
            edit("模板名称","模板 ${store.templates().size+1}") { name -> store.save(name,watermark.template) }
        },80)
        groupBreak()

        // `watermarkFontSizes()`: S/M/L/XL are 12/16/24/30；摘要按钮逐格走。
        addCell(stepper("水印字号",ScreenshotFontSize.MIN,ScreenshotFontSize.MAX_WATERMARK,
            watermark.fontSize,24f,{ "水印字号 $it" }) { current,next -> current.copy(fontSize=next) },100)
        listOf(12f,16f,24f,30f).forEach { preset ->
            addCell(textCell(ScreenshotFontSize.format(preset),watermark.fontSize==preset,
                "水印字号 ${ScreenshotFontSize.format(preset)}") {
                set(watermark.copy(fontSize=preset))
            },36)
        }
        groupBreak()

        lateinit var family: TextView
        family=textCell(watermark.fontFamily,true,"水印字体") {
            ScreenshotFontPicker.showFamily(family,watermark.fontFamily) { value -> set(watermark.copy(fontFamily=value)) }
        }
        family.isSingleLine=true
        family.ellipsize=android.text.TextUtils.TruncateAt.END
        family.gravity=Gravity.START or Gravity.CENTER_VERTICAL
        addCell(family,76)
        lateinit var color: TextView
        color=textCell("水印颜色",true,"水印颜色") {
            val base=watermark
            // 颜色走预览通道：实时回调只 previewWatermark（每帧 invalidate，不重建整条工具栏），
            // dismiss 提交一次、cancel(false) 回滚。原实现每帧 setWatermark→changed()→全量重建，
            // 拖色环时整条二级栏+主栏都在闪。
            drawing.beginWatermarkPreview()
            ScreenshotColorPicker.show(color,"水印颜色",base.color,false,
                { value -> value?.let { drawing.previewWatermark(base.copy(color=it)) } },
                drawing::finishWatermarkPreview,sample=drawing::pickColor)
        }
        addCell(color,64)
        groupBreak()

        addCell(stepper("角度",ScreenshotStyleBounds.WATERMARK_ANGLE_MIN.toFloat(),
            ScreenshotStyleBounds.WATERMARK_ANGLE_MAX.toFloat(),watermark.angle,0f,
            { "角度 $it°" }) { current,next -> current.copy(angle=next) },100)
        addCell(stepper("间距",ScreenshotStyleBounds.WATERMARK_GAP_MIN.toFloat(),
            ScreenshotStyleBounds.WATERMARK_GAP_MAX.toFloat(),watermark.gap,30f,
            { "间距 $it" }) { current,next -> current.copy(gap=next) },100)
        groupBreak()
        opacity()
    }
}
