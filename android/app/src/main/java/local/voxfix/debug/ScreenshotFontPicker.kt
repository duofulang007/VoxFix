package local.voxfix.debug

import local.voxfix.debug.ScreenshotStyleProperty.*
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
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

/**
 * 可搜索的字体族列表。
 *
 * 「点不动 / 点不进去」的修法有三层，缺一层就会复现：
 * 1. **焦点不能落在搜索框**——弹窗 focusable 时系统会把焦点给第一个可获焦子 View（这里就是
 *    `EditText`），输入法随之拉起（本机默认输入法就是 VoxFix 自己），盖住列表下半部；
 *    对照 `ScreenshotColorPicker` 调了 `panel.requestFocus()`，这里原来没有。
 * 2. **弹窗不许主动要输入法**——`INPUT_METHOD_NOT_NEEDED` + `ADJUST_NOTHING`，只有用户
 *    点搜索框时才 `showSoftInput`。
 * 3. **高度必须弹性**——列表按屏高收缩并可滚动，否则窄屏/横屏下半部永远够不到。
 */
internal class ScreenshotFontPanel(context: Context,current: String?,choose:(String)->Unit) : LinearLayout(context) {
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private val currentFamily: String?=current
    val search=EditText(context).apply {
        contentDescription="搜索字体"; hint="搜索字体"; textSize=14f; setSingleLine(true)
        setTextColor(ScreenshotUiTokens.TEXT); setHintTextColor(ScreenshotUiTokens.TEXT_DIM)
        setPadding(dp(8),0,dp(8),0)
        isFocusableInTouchMode=true
        // 只有用户主动点搜索框才请求键盘；弹窗出现时绝不自动弹。
        setOnFocusChangeListener { view,focused ->
            if(focused) context.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(view,InputMethodManager.SHOW_IMPLICIT)
        }
    }
    val list=ListView(context).apply { contentDescription="可用字体"; choiceMode=ListView.CHOICE_MODE_SINGLE }
    private val choiceColors=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),
        intArrayOf(ScreenshotUiTokens.PRIMARY,ScreenshotUiTokens.TEXT_DIM))
    private val fonts=object : ArrayAdapter<String>(context,android.R.layout.simple_list_item_single_choice,mutableListOf()) {
        override fun getView(position: Int,convertView: View?,parent: ViewGroup): View =
            (super.getView(position,convertView,parent) as CheckedTextView).apply {
                val family=getItem(position)!!
                typeface=Typeface.create(family,Typeface.NORMAL); textSize=14f
                setTextColor(if(family==currentFamily) ScreenshotUiTokens.TEXT_ACCENT else ScreenshotUiTokens.TEXT)
                setSingleLine(true); ellipsize=TextUtils.TruncateAt.END; tooltipText=family
                isChecked=family==currentFamily; checkMarkTintList=choiceColors
                contentDescription="字体 $family"; minHeight=dp(44)
            }
    }
    private fun filter(query: String,current: String?) {
        val matches=ScreenshotFontFamilies.matching(query)
        fonts.clear(); fonts.addAll(matches)
        list.clearChoices()
        if(current!=null) matches.indexOf(current).takeIf { it>=0 }?.let { list.setItemChecked(it,true); list.setSelection(it) }
    }
    init {
        orientation=VERTICAL; contentDescription="字体选择器"
        setPadding(dp(8),dp(8),dp(8),dp(8))
        // 面板自己可获焦，才能把焦点从搜索框上拿走。
        isFocusable=true; isFocusableInTouchMode=true
        background=GradientDrawable().apply {
            setColor(ScreenshotUiTokens.PANEL); cornerRadius=dp(8).toFloat()
        }
        addView(TextView(context).apply {
            text=if(current.isNullOrBlank()) "当前：Mixed（多选）" else "当前：$current"
            textSize=11f; setTextColor(ScreenshotUiTokens.TEXT_DIM)
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(8),0,dp(8),0)
            contentDescription=text
        },LayoutParams(-1,dp(24)))
        addView(search,LayoutParams(-1,dp(44)))
        val empty=TextView(context).apply {
            text="没有匹配的字体"; gravity=Gravity.CENTER
            setTextColor(ScreenshotUiTokens.TEXT); contentDescription=text
        }
        addView(empty,LayoutParams(-1,dp(44)))
        // 屏高 40% 上限（至少 160dp）：横屏与分屏下也留得住列表。
        val listHeight=minOf(dp(240),maxOf(dp(160),(resources.displayMetrics.heightPixels*.4f).toInt()))
        addView(list,LayoutParams(-1,listHeight))
        list.adapter=fonts; list.emptyView=empty
        list.setOnItemClickListener { _,_,position,_ -> fonts.getItem(position)?.let(choose) }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int)=filter(s.toString(),current)
            override fun afterTextChanged(s:Editable?) {}
        })
        filter("",current)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }
}

internal object ScreenshotFontPicker {
    /** 最近一次构建的面板与落点，供设备套件在无窗口 token 时断言焦点与定位。 */
    internal var panelForTest: ScreenshotFontPanel?=null
        private set
    internal var lastPlacement: Rect?=null
        private set

    private fun dp(context: Context,n: Int)=(n*context.resources.displayMetrics.density).toInt()

    private fun popup(anchor: View,panel: ScreenshotFontPanel): PopupWindow {
        val context=anchor.context
        return PopupWindow(panel,minOf(dp(context,280),context.resources.displayMetrics.widthPixels),-2,true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); isOutsideTouchable=true
            elevation=8*context.resources.displayMetrics.density
            // 不替用户叫输入法：弹窗一出现就弹键盘会盖住列表。
            inputMethodMode=PopupWindow.INPUT_METHOD_NOT_NEEDED
            softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
    }

    /** 贴锚点上下展开并把落点夹进安全区；`Gravity.CENTER` 会正好落在输入法区域。 */
    private fun place(anchor: View,panel: ScreenshotFontPanel,window: PopupWindow) {
        if(!anchor.isAttachedToWindow) return
        panel.measure(View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val spot=IntArray(2); anchor.getLocationOnScreen(spot)
        val screen=anchor.resources.displayMetrics
        val point=ScreenshotPopupPlacement.place(spot[0],spot[1],spot[0]+anchor.width,spot[1]+anchor.height,
            panel.measuredWidth,panel.measuredHeight,
            screen.widthPixels,screen.heightPixels,dp(anchor.context,4))
        lastPlacement=Rect(point.x,point.y,point.x+panel.measuredWidth,point.y+panel.measuredHeight)
        window.showAtLocation(anchor.rootView,Gravity.NO_GRAVITY,point.x,point.y)
        // 焦点归面板，搜索框只在用户点它时才拿焦点。
        panel.requestFocus()
    }

    private fun hideKeyboard(context: Context,panel: ScreenshotFontPanel) {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(panel.windowToken,0)
    }

    /** Device family list with no style session, for the watermark config. */
    fun showFamily(anchor: View,current: String,choose:(String)->Unit): PopupWindow {
        val context=anchor.context
        lateinit var window: PopupWindow
        val panel=ScreenshotFontPanel(context,current) { family -> choose(family); window.dismiss() }
        panelForTest=panel
        window=popup(anchor,panel)
        window.setOnDismissListener { hideKeyboard(context,panel) }
        place(anchor,panel,window)
        return window
    }
    fun show(anchor: View,drawing: ScreenshotDrawingLayer): PopupWindow {
        val context=anchor.context
        val base=drawing.inspectedStyle
        drawing.beginStylePreview()
        lateinit var window: PopupWindow
        val current=if(drawing.isStyleMixed(FONT_FAMILY)) null else base.fontFamily
        val panel=ScreenshotFontPanel(context,current) { family ->
            drawing.previewStyle(base.copy(fontFamily=family),FONT_FAMILY); window.dismiss()
        }
        panelForTest=panel
        window=popup(anchor,panel)
        val session=ScreenshotColorSession(drawing::finishStylePreview)
        val detach=object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}
            override fun onViewDetachedFromWindow(view: View) { session.detach(); window.dismiss() }
        }
        anchor.addOnAttachStateChangeListener(detach)
        window.setOnDismissListener {
            anchor.removeOnAttachStateChangeListener(detach)
            hideKeyboard(context,panel)
            session.dismiss {}
        }
        try { place(anchor,panel,window) }
        catch(error: RuntimeException) { anchor.removeOnAttachStateChangeListener(detach); session.detach(); throw error }
        return window
    }
}
