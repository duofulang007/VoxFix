package local.voxfix.debug

import local.voxfix.debug.ScreenshotStyleProperty.*
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView

/** Searchable device families; selection changes the style only once, when the popup closes. */
internal class ScreenshotFontPanel(context: Context,current: String,choose:(String)->Unit) : LinearLayout(context) {
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    val search=EditText(context).apply {
        contentDescription="搜索字体"; hint="搜索字体"; textSize=14f; setSingleLine(true)
        setTextColor(Color.WHITE); setHintTextColor(0xFF9A9A9E.toInt()); setPadding(dp(8),0,dp(8),0)
    }
    val list=ListView(context).apply { contentDescription="可用字体"; choiceMode=ListView.CHOICE_MODE_SINGLE }
    private val choiceColors=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(0xFF1677FF.toInt(),0xFF6A6A6E.toInt()))
    private val fonts=object : ArrayAdapter<String>(context,android.R.layout.simple_list_item_single_choice, mutableListOf()) {
        override fun getView(position: Int,convertView: View?,parent: ViewGroup): View =
            (super.getView(position,convertView,parent) as CheckedTextView).apply {
                val family=getItem(position)!!
                typeface=Typeface.create(family,Typeface.NORMAL); textSize=14f
                setTextColor(if(family==current) 0xFF69B1FF.toInt() else Color.WHITE)
                setSingleLine(true); ellipsize=TextUtils.TruncateAt.END; tooltipText=family
                isChecked=family==current; checkMarkTintList=choiceColors
                contentDescription="字体 $family"; minHeight=dp(44)
            }
    }
    private fun filter(query: String,current: String) {
        val matches=ScreenshotFontFamilies.names.filter { it.contains(query.trim(),ignoreCase=true) }
        fonts.clear(); fonts.addAll(matches)
        list.clearChoices()
        matches.indexOf(current).takeIf { it>=0 }?.let { list.setItemChecked(it,true); list.setSelection(it) }
    }
    init {
        orientation=VERTICAL; contentDescription="字体选择器"; setPadding(dp(8),dp(8),dp(8),dp(8))
        background=GradientDrawable().apply { setColor(0xFF1C1C1E.toInt()); cornerRadius=dp(8).toFloat() }
        addView(search,LayoutParams(-1,dp(44)))
        val empty=TextView(context).apply {
            text="没有匹配的字体"; gravity=Gravity.CENTER; setTextColor(Color.WHITE); contentDescription=text
        }
        addView(empty,LayoutParams(-1,dp(44)))
        addView(list,LayoutParams(-1,dp(240)))
        list.adapter=fonts; list.emptyView=empty
        list.setOnItemClickListener { _,_,position,_ -> fonts.getItem(position)?.let(choose) }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int)=filter(s.toString(),current)
            override fun afterTextChanged(s:Editable?) {}
        })
        filter("",current)
    }
}

internal object ScreenshotFontPicker {
    /** Device family list with no style session, for the watermark config. */
    fun showFamily(anchor: View,current: String,choose:(String)->Unit): PopupWindow {
        val context=anchor.context
        lateinit var popup: PopupWindow
        val panel=ScreenshotFontPanel(context,current) { family -> choose(family); popup.dismiss() }
        popup=PopupWindow(panel,minOf((280*context.resources.displayMetrics.density).toInt(),context.resources.displayMetrics.widthPixels),-2,true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); isOutsideTouchable=true
            elevation=8*context.resources.displayMetrics.density
            inputMethodMode=PopupWindow.INPUT_METHOD_NEEDED; softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        popup.setOnDismissListener {
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(panel.windowToken,0)
        }
        popup.showAtLocation(anchor.rootView,Gravity.CENTER,0,0)
        return popup
    }
    fun show(anchor: View,drawing: ScreenshotDrawingLayer): PopupWindow {
        val context=anchor.context
        val base=drawing.inspectedStyle
        drawing.beginStylePreview()
        lateinit var popup: PopupWindow
        val panel=ScreenshotFontPanel(context,if(drawing.isStyleMixed(FONT_FAMILY)) "" else base.fontFamily) { family ->
            drawing.previewStyle(base.copy(fontFamily=family),FONT_FAMILY); popup.dismiss()
        }
        popup=PopupWindow(panel,minOf((280*context.resources.displayMetrics.density).toInt(),context.resources.displayMetrics.widthPixels),-2,true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); isOutsideTouchable=true
            elevation=8*context.resources.displayMetrics.density
            inputMethodMode=PopupWindow.INPUT_METHOD_NEEDED; softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val session=ScreenshotColorSession(drawing::finishStylePreview)
        val detach=object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}
            override fun onViewDetachedFromWindow(view: View) { session.detach(); popup.dismiss() }
        }
        anchor.addOnAttachStateChangeListener(detach)
        popup.setOnDismissListener {
            anchor.removeOnAttachStateChangeListener(detach)
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(panel.windowToken,0)
            session.dismiss {}
        }
        try { popup.showAtLocation(anchor.rootView,Gravity.CENTER,0,0) }
        catch(error: RuntimeException) { anchor.removeOnAttachStateChangeListener(detach); session.detach(); throw error }
        return popup
    }
}
