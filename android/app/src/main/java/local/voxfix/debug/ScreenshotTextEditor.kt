package local.voxfix.debug

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText

/** A stable-size document keeps typing readable instead of squeezing it into OCR line boxes. */
internal class ScreenshotTextEditor(context: Context, value: String) : EditText(context) {
    init {
        setText(value)
        textSize = 16f
        setTextColor(Color.BLACK)
        setBackgroundColor(Color.WHITE)
        gravity = Gravity.TOP or Gravity.START
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setHorizontallyScrolling(false)
        isVerticalScrollBarEnabled = true
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        val inset = (8*resources.displayMetrics.density).toInt()
        setPadding(inset,inset,inset,inset)
        contentDescription = "整段编辑文字，可跨行选择"
    }
}
