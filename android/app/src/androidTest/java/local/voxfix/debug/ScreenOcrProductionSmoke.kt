package local.voxfix.debug

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Synthetic fixtures only. Exercises the actual production OCR and readable export. */
internal object ScreenOcrProductionSmoke {
    fun run(test: Instrumentation): String {
        ScreenTextPaddleRecognizer.selfCheck()
        val rows=JSONArray()
        val expected="离线屏幕取字\n本月营业收入128,600.00元。\n请检查屏幕亮度"
        for (size in listOf(24,36,64)) for (dark in listOf(false,true)) {
            val image=Bitmap.createBitmap(size*34,size*7,Bitmap.Config.ARGB_8888)
            Canvas(image).apply {
                drawColor(if(dark) 0xff202124.toInt() else Color.WHITE)
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize=size.toFloat(); color=if(dark) Color.WHITE else Color.BLACK }
                expected.lines().forEachIndexed { i,line -> drawText(line,size.toFloat(),size*(1.5f+i*1.5f),paint) }
            }
            try {
                val start=System.nanoTime()
                val actual=ScreenOcr.recognize(test.targetContext,image) { true }.joinToString("\n") { it.text }
                val ms=(System.nanoTime()-start)/1_000_000
                rows.put(JSONObject().put("size",size).put("dark",dark).put("actual",actual).put("ms",ms))
                check(actual.replace("\n","")==expected.replace("\n","")) { "OCR mismatch: $actual" }
            } finally { image.recycle() }
        }
        test.runOnMainSync {
            val image=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val region=Rect(100,600,980,900)
            val text="这是一段较长的译文，需要正常字号展示，滚动和保存都必须保留完整内容。".repeat(20)
            val layer=ScreenshotTextLayer(test.targetContext,image,listOf(ScreenshotTextBlock(100,600,980,640,text)),{},{},true,region)
            fun layout() {
                layer.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
                layer.layout(0,0,1080,2400)
            }
            layout()
            val scroll=layer.getChildAt(0) as ScrollView
            val field=(scroll.getChildAt(0) as ViewGroup).getChildAt(0) as EditText
            check(field.textSize>=16*test.targetContext.resources.displayMetrics.scaledDensity)
            check(scroll.canScrollVertically(1)) { "long translation cannot scroll" }
            val exported=layer.render(region)
            check(exported.height>region.height()) { "export dropped off-screen text" }
            check(layer.text()==text)
            File(test.targetContext.cacheDir,"translation-reflow-smoke.png").outputStream().use { exported.compress(Bitmap.CompressFormat.PNG,100,it) }
            exported.recycle()
            layer.beginDocumentEditing(region)
            layer.focusedField()!!.setText("改过的译文")
            layer.undo(); check(layer.text()==text)
            layer.redo(); check(layer.text()=="改过的译文")
            layer.endEditing(); check(layer.text()=="改过的译文")
            image.recycle()
        }
        File(test.targetContext.cacheDir,"production-ocr-smoke.json").writeText(rows.toString(2))
        return "PRODUCTION_OCR_PASS images=${rows.length()} reflow_export_undo=pass timings=$rows"
    }

    /** Layout fixtures separate from the plain-text accuracy set: a cross-column headline, a
     *  perspective quad whose corners are not a rotated rectangle, and text over a gradient. */
    fun layouts(test: Instrumentation): String {
        val size = 32
        val cases = listOf(
            Triple("columns", columnsImage(size), COLUMNS_TEXT),
            Triple("perspective", perspectiveImage(size), "透视校正测试第一行\n透视校正测试第二行"),
            Triple("textured", texturedImage(size), "纹理背景第一行\n纹理背景第二行")
        )
        val rows = JSONArray()
        val failures = mutableListOf<String>()
        for ((name, image, expected) in cases) {
            try {
                val start = System.nanoTime()
                val blocks = ScreenOcr.recognize(test.targetContext, image) { true }
                val ms = (System.nanoTime() - start) / 1_000_000
                val actual = blocks.joinToString("\n") { it.text }
                val score = similarity(expected, actual)
                // Column order is checked separately: a correct character set in the wrong order is
                // still a reading-order failure.
                val orderOk = name != "columns" || blocks.map { it.text } == COLUMNS_TEXT.split("\n")
                rows.put(JSONObject().put("case", name).put("ms", ms).put("score", score)
                    .put("orderOk", orderOk).put("actual", actual))
                if (score < 0.85 || !orderOk) failures.add("$name score=$score order=$orderOk text=$actual")
            } finally {
                image.recycle()
            }
        }
        File(test.targetContext.cacheDir, "production-ocr-layouts.json").writeText(rows.toString(2))
        check(failures.isEmpty()) { "layout OCR failed: $failures" }
        return "PRODUCTION_OCR_LAYOUT_PASS cases=${rows.length()} $rows"
    }

    private val COLUMNS_TEXT = "第二季度经营简报\n左栏第一季度收入\n左栏回款情况\n右栏成本控制\n右栏下月计划"

    private fun columnsImage(size: Int): Bitmap {
        val image = Bitmap.createBitmap(size * 40, size * 8, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size.toFloat(); color = Color.BLACK }
        canvas.drawText("第二季度经营简报", size.toFloat(), size * 1.4f, paint)
        canvas.drawText("左栏第一季度收入", size.toFloat(), size * 3.4f, paint)
        canvas.drawText("左栏回款情况", size.toFloat(), size * 4.6f, paint)
        canvas.drawText("右栏成本控制", size * 21f, size * 3.4f, paint)
        canvas.drawText("右栏下月计划", size * 21f, size * 4.6f, paint)
        return image
    }

    /** Trapezoid: both top and bottom edges stay horizontal, so only the edge lengths differ. */
    private fun perspectiveImage(size: Int): Bitmap {
        val w = size * 24
        val h = size * 5
        val source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size.toFloat(); color = Color.BLACK }
        listOf("透视校正测试第一行", "透视校正测试第二行").forEachIndexed { index, line ->
            canvas.drawText(line, size.toFloat(), size * (1.5f + index * 1.8f), paint)
        }
        val target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val targetCanvas = Canvas(target)
        targetCanvas.drawColor(Color.WHITE)
        val matrix = Matrix()
        matrix.setPolyToPoly(
            floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()), 0,
            floatArrayOf(size * 1.2f, 0f, w - size * 1.2f, 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()), 0, 4)
        targetCanvas.drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        source.recycle()
        return target
    }

    private fun texturedImage(size: Int): Bitmap {
        val w = size * 26
        val h = size * 5
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(0xff123a5a.toInt())
        val shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xff123a5a.toInt(), 0xff2a6f6a.toInt(), 0xff4b3b6f.toInt()), null,
            android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })
        val deco = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33ffffff }
        canvas.drawCircle(w * .8f, h * .3f, size * 1.6f, deco)
        canvas.drawRect(size * .5f, h * .7f, size * 6f, h * .95f, deco)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size.toFloat(); color = Color.WHITE
            setShadowLayer(size * .12f, size * .06f, size * .06f, 0x66000000)
        }
        listOf("纹理背景第一行", "纹理背景第二行").forEachIndexed { index, line ->
            canvas.drawText(line, size.toFloat(), size * (1.6f + index * 1.9f), paint)
        }
        return image
    }

    /** Character-level similarity so a single近形 error does not hide a reading-order failure. */
    private fun similarity(expected: String, actual: String): Double {
        val a = expected.filterNot { it.isWhitespace() }
        val b = actual.filterNot { it.isWhitespace() }
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var previous = IntArray(b.length + 1)
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                current[j] = if (a[i - 1] == b[j - 1]) previous[j - 1] + 1 else maxOf(previous[j], current[j - 1])
            }
            val swap = previous; previous = current; current = swap
            current.fill(0)
        }
        return previous[b.length].toDouble() / a.length
    }
}
