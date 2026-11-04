package local.voxfix.debug

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Shared touch color editor; a single accepted color is one undo operation. */
internal object ScreenshotColorPicker {
    fun show(anchor: View,title: String,initial: Int,allowNone: Boolean,preview:(Int?)->Unit,finish:(Boolean)->Unit) {
        val context=anchor.context
        fun dp(n: Int)=(n*context.resources.displayMetrics.density).toInt()
        val body=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(8))
            background=GradientDrawable().apply { setColor(Color.WHITE); cornerRadius=dp(8).toFloat() }
        }
        val popup=PopupWindow(body,minOf(dp(320),context.resources.displayMetrics.widthPixels-dp(16)),-2,true).apply {
            elevation=dp(8).toFloat(); setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable=true; inputMethodMode=PopupWindow.INPUT_METHOD_NOT_NEEDED
            softInputMode=android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        var accepted=false; var current: Int?=initial
        val hsv=FloatArray(3); Color.colorToHSV(initial,hsv)
        val swatch=TextView(context).apply { text=title; gravity=Gravity.CENTER; textSize=14f; minHeight=dp(44) }
        body.addView(swatch)
        fun update(color: Int?) {
            current=color; swatch.text=if(color==null) "$title · 无填充" else "$title · ${String.format("#%06X",color and 0xFFFFFF)}"
            swatch.setBackgroundColor(color ?: Color.WHITE)
            swatch.setTextColor(if(color!=null && Color.luminance(color)<.4) Color.WHITE else Color.BLACK)
            preview(color)
        }
        val presets=LinearLayout(context)
        listOf(Color.RED,0xFFFADB14.toInt(),0xFF52C41A.toInt(),0xFF1677FF.toInt(),Color.BLACK,Color.WHITE).forEach { color ->
            presets.addView(TextView(context).apply {
                text="●"; textSize=26f; gravity=Gravity.CENTER; setTextColor(color)
                contentDescription="颜色 ${String.format("#%06X",color and 0xFFFFFF)}"
                setOnClickListener { Color.colorToHSV(color,hsv); update(color); refreshSliders(body,hsv) }
            },LinearLayout.LayoutParams(0,dp(44),1f))
        }
        body.addView(presets)
        for((index,label) in listOf("色相","饱和度","明度").withIndex()) {
            val line=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
            line.addView(TextView(context).apply { text=label; setTextColor(Color.BLACK); textSize=13f },LinearLayout.LayoutParams(dp(60),dp(44)))
            line.addView(SeekBar(context).apply {
                tag=index; max=if(index==0) 360 else 100
                progress=(hsv[index]*if(index==0) 1 else 100).toInt(); contentDescription=label
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                    override fun onProgressChanged(bar: SeekBar,value: Int,fromUser: Boolean) {
                        if(fromUser) { hsv[index]=value.toFloat()/if(index==0) 1 else 100; update(Color.HSVToColor(hsv)) }
                    }
                })
            },LinearLayout.LayoutParams(0,dp(44),1f)); body.addView(line)
        }
        val hex=EditText(context).apply {
            setSingleLine(); setText(String.format("#%06X",initial and 0xFFFFFF)); contentDescription="十六进制颜色"
            visibility=View.GONE; textSize=14f; setTextColor(Color.BLACK)
        }
        body.addView(hex,LinearLayout.LayoutParams(-1,dp(44)))
        val actions=LinearLayout(context)
        fun action(label: String,run:()->Unit) { actions.addView(TextView(context).apply {
            text=label; gravity=Gravity.CENTER; textSize=14f; setTextColor(0xFF1677FF.toInt()); contentDescription=label
            setOnClickListener { run() }
        },LinearLayout.LayoutParams(0,dp(44),1f)) }
        if(allowNone) action("无填充") { update(null) }
        action("色值") { hex.visibility=View.VISIBLE; hex.setText(String.format("#%06X",(current ?: initial) and 0xFFFFFF)); popup.inputMethodMode=PopupWindow.INPUT_METHOD_NEEDED; popup.update() }
        action("取消") { popup.dismiss() }
        action("确定") {
            if(hex.visibility==View.VISIBLE) {
                val text=hex.text.toString()
                if(!text.matches(Regex("#[0-9a-fA-F]{6}"))) { hex.error="请输入 #RRGGBB"; return@action }
                update(Color.parseColor(text))
            }
            accepted=true; popup.dismiss()
        }
        body.addView(actions)
        val detach=object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}
            override fun onViewDetachedFromWindow(view: View) { popup.dismiss() }
        }
        anchor.addOnAttachStateChangeListener(detach)
        popup.setOnDismissListener { anchor.removeOnAttachStateChangeListener(detach); finish(accepted) }
        swatch.setBackgroundColor(initial); swatch.setTextColor(if(Color.luminance(initial)<.4) Color.WHITE else Color.BLACK)
        popup.showAtLocation(anchor.rootView,Gravity.CENTER,0,0)
    }
    private fun refreshSliders(body: LinearLayout,hsv: FloatArray) {
        for(i in 0 until body.childCount) (body.getChildAt(i) as? LinearLayout)?.let { row ->
            for(j in 0 until row.childCount) (row.getChildAt(j) as? SeekBar)?.let { bar ->
                val index=bar.tag as Int; bar.progress=(hsv[index]*if(index==0) 1 else 100).toInt()
            }
        }
    }
}
