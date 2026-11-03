package local.voxfix.debug

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Selection
import android.text.Spannable
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.graphics.Rect
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit

object ScreenTextSmoke {
    fun run(test: Instrumentation): String {
        val bitmap = Bitmap.createBitmap(1200, 500, Bitmap.Config.ARGB_8888)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val evidence = mutableListOf<String>()
            for (size in listOf(64f, 96f, 128f)) {
                val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size }
                canvas.drawText("离线屏幕取字", 40f, 180f, paint)
                canvas.drawText("VoxFix 12345", 40f, 380f, paint)
                val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS).text
                // A smoke check reports recognition errors; it does not certify perfect OCR.
                val chinese = result.lineSequence().firstOrNull().orEmpty().replace(" ", "")
                val expected = "离线屏幕取字"
                val matches = expected.indices.count { chinese.getOrNull(it) == expected[it] }
                evidence.add("${size.toInt()}px=$matches/6:$chinese")
                check(matches >= 5 && chinese.length == 6) { "Chinese OCR missing/major failure: $result" }
                check(result.contains("12345") && result.contains("VoxFix")) { "Latin OCR: $result" }
            }
            test.runOnMainSync {
                var picked: Rect? = null
                lateinit var overlay: ScreenTextOverlay
                overlay = ScreenTextOverlay(test.targetContext, bitmap, { picked = it; overlay.loading() }, {})
                overlay.result("第一行文字\n第二行文字", null)
                val field = overlay.javaClass.getDeclaredField("text").apply { isAccessible = true }.get(overlay) as TextView
                field.requestFocus()
                Selection.setSelection(field.text as Spannable, 2, 9)
                check(field.text.subSequence(field.selectionStart, field.selectionEnd).toString() == "行文字\n第二行")
                val root = overlay.javaClass.getDeclaredField("root").apply { isAccessible = true }.get(overlay) as ViewGroup
                fun find(view: View): View? {
                    if (view is TextView && view.text.toString() == "重选") return view
                    if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                    return null
                }
                check(find(root)!!.performClick())
                root.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 1200, 1800)
                val now = android.os.SystemClock.uptimeMillis()
                for ((action, x, y) in listOf(Triple(MotionEvent.ACTION_DOWN, 120f, 180f), Triple(MotionEvent.ACTION_MOVE, 1080f, 1620f), Triple(MotionEvent.ACTION_UP, 1080f, 1620f))) {
                    val event = MotionEvent.obtain(now, now, action, x, y, 0)
                    try { check(root.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                check(picked == Rect(120, 50, 1080, 450)) { "Crop scaling or touch cancellation failed: $picked" }
            }
            return "SCREEN_TEXT_BUNDLED_OCR_AND_SELECTION_PASS ${evidence.joinToString("; ")}"
        } finally { recognizer.close(); bitmap.recycle() }
    }
}
