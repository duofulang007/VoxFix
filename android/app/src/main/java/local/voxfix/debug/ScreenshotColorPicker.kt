package local.voxfix.debug

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.PopupWindow

/**
 * Bookkeeping for one palette session. The eyedropper detour, the "commit exactly once" rule and
 * the cancel path must not depend on the popup shell, so they live here and [ScreenshotColorPicker]
 * only forwards window events. That also makes the rules testable without a window token.
 */
internal class ScreenshotColorSession(private val finish: (Boolean) -> Unit) {
    var sampling = false
        private set
    var finished = false
        private set
    var cancelled = false
        private set

    /** True when an eyedropper session may start; asking twice or after completion does nothing. */
    fun beginSample(available: Boolean): Boolean {
        if (finished || sampling || !available) return false
        sampling = true; return true
    }

    /** The eyedropper returned. False means the palette must not reopen. */
    fun endSample(anchorAlive: Boolean): Boolean {
        sampling = false
        if (!anchorAlive || finished) { complete(false); return false }
        return true
    }

    fun cancel() { cancelled = true }

    /** The popup closed: commit once unless a sample is running or the user cancelled. */
    fun dismiss(commit: () -> Unit) {
        if (sampling || finished) return
        if (!cancelled) commit()
        complete(!cancelled)
    }

    /** The palette is gone for good (its anchor died): no commit, no reopen. */
    fun detach() { cancelled = true; complete(false) }

    private fun complete(accepted: Boolean) {
        if (finished) return
        finished = true; finish(accepted)
    }
}

/** One palette session, including eyedropper detours, commits one style-history entry. */
internal object ScreenshotColorPicker {
    fun show(anchor: View, title: String, initial: Int, allowNone: Boolean, preview: (Int?) -> Unit, finish: (Boolean) -> Unit,
        sample: (((Int?) -> Unit) -> Unit)? = null, pattern: ScreenshotStrokePattern? = null,
        patternPreview: ((ScreenshotStrokePattern) -> Unit)? = null): PopupWindow {
        val context = anchor.context
        fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
        val panel = ScreenshotColorPanel(context, initial, { preview(if (allowNone && Color.alpha(it) == 0) null else it) }, pattern, patternPreview)
        panel.contentDescription = "$title 调色板"
        val popup = PopupWindow(panel, minOf(dp(232), context.resources.displayMetrics.widthPixels - dp(16)), -2, true).apply {
            elevation = dp(8).toFloat(); setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED; softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        val session = ScreenshotColorSession(finish)
        lateinit var detach: View.OnAttachStateChangeListener
        fun hideKeyboard() { context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(panel.windowToken, 0) }
        fun open() { popup.showAtLocation(anchor.rootView, Gravity.CENTER, 0, 0); panel.requestFocus() }
        detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}
            override fun onViewDetachedFromWindow(view: View) { session.detach(); anchor.removeOnAttachStateChangeListener(this); if (!session.sampling) popup.dismiss() }
        }
        anchor.addOnAttachStateChangeListener(detach)
        panel.onCancel = { session.cancel(); popup.dismiss() }
        panel.onSample = {
            if (session.beginSample(sample != null)) {
                hideKeyboard(); popup.dismiss()
                sample!! { value ->
                    if (session.endSample(anchor.isAttachedToWindow)) {
                        if (value != null) panel.setColor(value)
                        open()
                    }
                }
            }
        }
        popup.setOnDismissListener {
            if (session.sampling) return@setOnDismissListener
            hideKeyboard()
            session.dismiss { panel.commitInputs() }
            anchor.removeOnAttachStateChangeListener(detach)
        }
        open()
        return popup
    }
}
