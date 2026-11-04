package local.voxfix.debug

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Always one row: `自动检测 ⇄ 简体中文`. Tapping a side turns the same row into language chips,
 *  so picking a language never adds a second line above the screenshot. */
internal class TranslatePairBar(
    context: Context,
    private val onOpenSource: () -> Unit,
    private val onSwap: () -> Unit,
    private val onOpenTarget: () -> Unit,
    private val onChoose: (TranslateLanguage) -> Unit
) : LinearLayout(context) {
    enum class Side { SOURCE, TARGET }

    private val density = resources.displayMetrics.density
    private var pair = TranslatePair.DEFAULT
    private var picking: Side? = null
    private var contentRow: LinearLayout?=null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(3), dp(4), dp(3))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(8).toFloat()
        }
        elevation = dp(6).toFloat()
        rebuild()
    }

    fun showPair(next: TranslatePair) {
        pair = next.normalized()
        picking = null
        rebuild()
    }

    fun showPicker(side: Side, next: TranslatePair) {
        pair = next.normalized()
        picking = side
        rebuild()
    }

    fun currentSide(): Side? = picking

    private fun rebuild() {
        removeAllViews()
        val row=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        contentRow=row
        addView(android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled=false; addView(row)
        },LayoutParams(-1,-2))
        val side = picking
        if (side == null) {
            addChip(pair.source.label, "源语言：" + pair.source.label, onOpenSource, false)
            row.addView(TextView(context).apply {
                text = "⇄"
                textSize = 15f
                setTextColor(0xFF666666.toInt())
                gravity = Gravity.CENTER
                contentDescription = "交换源语言和目标语言"
                isClickable = true
                isFocusable = true
                setOnClickListener { onSwap() }
            }, LayoutParams(dp(30), dp(32)))
            addChip(pair.target.label, "目标语言：" + pair.target.label, onOpenTarget, false)
        } else {
            val all = TranslateLanguage.values().toList()
            val options = if (side == Side.SOURCE) all else all.filter { it != TranslateLanguage.AUTO }
            val current = if (side == Side.SOURCE) pair.source else pair.target
            for (language in options) {
                addChip(language.label, language.label, { onChoose(language) }, language == current)
            }
        }
    }

    private fun addChip(text: String, description: String, action: () -> Unit, active: Boolean) {
        contentRow?.addView(TextView(context).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            contentDescription = description
            isClickable = true
            isFocusable = true
            setTextColor(if (active) COLOR_PRIMARY else COLOR_TEXT)
            background = if (active) rounded(0x1F1677FF) else null
            setOnClickListener { action() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)))
    }

    private fun dp(value: Int) = (value * density).toInt()
    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(6).toFloat()
    }

    private companion object {
        const val COLOR_TEXT = 0xE0000000.toInt()
        const val COLOR_PRIMARY = 0xFF1677FF.toInt()
    }
}
