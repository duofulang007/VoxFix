package local.voxfix.debug

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets

class ScreenshotToolbarSettingsActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.setBackgroundColor(ScreenshotToolbarEditor.BACKGROUND)
        val pad = (8*resources.displayMetrics.density).toInt()
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(ScreenshotToolbarEditor.BACKGROUND)
            setPadding(pad,pad,pad,pad)
            setOnApplyWindowInsetsListener { view,insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(pad+bars.left,pad+bars.top,pad+bars.right,pad+bars.bottom); insets
            }
        }
        val tabs = android.widget.LinearLayout(this)
        val editorHost = android.widget.FrameLayout(this)
        var current = ScreenshotToolbarSection.DRAWING
        fun show(section: ScreenshotToolbarSection) {
            current = section; editorHost.removeAllViews()
            editorHost.addView(ScreenshotToolbarEditor(this,section),android.widget.FrameLayout.LayoutParams(-1,-1))
            for (index in ScreenshotToolbarSection.values().indices) (tabs.getChildAt(index) as android.widget.TextView).apply {
                setTextColor(if (ScreenshotToolbarSection.values()[index] == current) 0xFF1677FF.toInt() else 0xFFD9D9D9.toInt())
                isSelected = ScreenshotToolbarSection.values()[index] == current
            }
        }
        for (section in ScreenshotToolbarSection.values()) tabs.addView(android.widget.TextView(this).apply {
            text=section.label; textSize=18f; gravity=android.view.Gravity.CENTER
            contentDescription="${section.label}工具编排"; setOnClickListener { show(section) }
        },android.widget.LinearLayout.LayoutParams(0,(44*resources.displayMetrics.density).toInt(),1f))
        tabs.addView(android.widget.ImageView(this).apply {
            contentDescription="重置当前工具编排"; setImageResource(R.drawable.snow_reload); setColorFilter(0xFFD9D9D9.toInt())
            val inset=(12*resources.displayMetrics.density).toInt(); setPadding(inset,inset,inset,inset)
            setOnClickListener { (editorHost.getChildAt(0) as? ScreenshotToolbarEditor)?.reset() }
        },android.widget.LinearLayout.LayoutParams((44*resources.displayMetrics.density).toInt(),-1))
        root.addView(tabs); root.addView(editorHost,android.widget.LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root); show(current)
        window.decorView.post {
            window.insetsController?.setSystemBarsAppearance(0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        }
    }
}
