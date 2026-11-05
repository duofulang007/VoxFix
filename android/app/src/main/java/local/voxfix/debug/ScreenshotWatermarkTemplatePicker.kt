package local.voxfix.debug

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved watermark templates, matching Snow Shot's select-with-delete model
 * (`screenshottoolpalettestylecontrols.cpp`: options carry `watermark-template:<index>`, each option
 * has a delete action, the create modal names a template with up to 80 characters). Stored as a JSON
 * list in app preferences; the newest 20 survive.
 */
internal class ScreenshotWatermarkTemplateStore(context: Context) {
    private val preferences=context.getSharedPreferences("screenshot-watermark-templates",Context.MODE_PRIVATE)
    fun templates(): List<Pair<String,String>> = runCatching {
        val array=JSONArray(preferences.getString("list","[]").orEmpty())
        (0 until array.length()).mapNotNull { index ->
            val row=array.optJSONObject(index) ?: return@mapNotNull null
            val name=row.optString("name"); val value=row.optString("value")
            if(name.isEmpty() || value.isEmpty()) null else name to value
        }
    }.getOrDefault(emptyList())
    fun save(name: String,value: String) {
        val cleanName=name.trim().take(80); val cleanValue=value.trim().take(200)
        if(cleanName.isEmpty() || cleanValue.isEmpty()) return
        write((templates().filter { it.first!=cleanName }+(cleanName to cleanValue)).takeLast(20))
    }
    fun delete(name: String) { write(templates().filter { it.first!=name }) }
    private fun write(list: List<Pair<String,String>>) {
        val array=JSONArray()
        list.forEach { array.put(JSONObject().put("name",it.first).put("value",it.second)) }
        preferences.edit().putString("list",array.toString()).apply()
    }
}

/** Saved-template picker: tap applies, long-press deletes, matching the original's per-option delete. */
internal class ScreenshotTemplatePanel(context: Context,private val store: ScreenshotWatermarkTemplateStore,
    private val apply:(String)->Unit,private val dismiss:()->Unit) : LinearLayout(context) {
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private val rows=LinearLayout(context).apply { orientation=VERTICAL }
    init {
        orientation=VERTICAL; contentDescription="水印模板列表"; setPadding(dp(8),dp(8),dp(8),dp(8))
        background=GradientDrawable().apply { setColor(0xFF1C1C1E.toInt()); cornerRadius=dp(8).toFloat() }
        addView(TextView(context).apply {
            text="还没有模板，先在参数里设置模板再「存为模板」"; gravity=Gravity.CENTER; setTextColor(Color.WHITE)
            setPadding(0,dp(12),0,dp(12)); contentDescription="没有模板"
        },LayoutParams(-1,dp(44)))
        addView(rows,LayoutParams(-1,-2))
        rebuild()
    }
    private fun rebuild() {
        rows.removeAllViews()
        val list=store.templates()
        list.forEach { (name,value) ->
            val row=TextView(context).apply {
                text=name; textSize=15f; gravity=Gravity.CENTER_VERTICAL; setTextColor(Color.WHITE)
                setPadding(dp(12),0,dp(12),0); minHeight=dp(44); maxWidth=dp(280); ellipsize=android.text.TextUtils.TruncateAt.END
                tooltipText=value; contentDescription="水印模板 $name"
                background=GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius=dp(6).toFloat() }
                setOnClickListener { apply(value); dismiss() }
                setOnLongClickListener { store.delete(name); rebuild(); true }
            }
            rows.addView(row,LayoutParams(-1,dp(44)))
        }
        (getChildAt(0) as TextView).visibility=if(list.isEmpty()) View.VISIBLE else View.GONE
    }
}

internal object ScreenshotWatermarkTemplatePicker {
    fun show(anchor: View,store: ScreenshotWatermarkTemplateStore,apply:(String)->Unit): PopupWindow {
        val context=anchor.context
        lateinit var popup: PopupWindow
        val panel=ScreenshotTemplatePanel(context,store,apply,{ popup.dismiss() })
        popup=PopupWindow(panel,(280*context.resources.displayMetrics.density).toInt().coerceAtMost(
            context.resources.displayMetrics.widthPixels),-2,true).apply {
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
}
