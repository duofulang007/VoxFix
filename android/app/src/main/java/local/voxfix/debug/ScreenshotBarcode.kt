package local.voxfix.debug

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

/** One recognised code: its payload and where it sits in the captured bitmap. */
data class ScreenshotBarcodeHit(val text: String, val box: Rect)

/** Bundled scanner: QR and barcodes work without Play Services or an image upload. */
object ScreenshotBarcode {
    fun scan(bitmap: Bitmap,result: (List<ScreenshotBarcodeHit>)->Unit,failure: (String)->Unit) {
        val scanner=BarcodeScanning.getClient()
        try {
            scanner.process(InputImage.fromBitmap(bitmap,0))
                .addOnSuccessListener { values ->
                    // 上游按识别结果逐个打标记：同文本不同位置也各占一个标记，所以只去掉完全重复的项。
                    val hits=values.mapNotNull { value ->
                        val text=value.rawValue?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                        val box=value.boundingBox ?: Rect(0,0,1,1)
                        ScreenshotBarcodeHit(text,Rect(box))
                    }.distinctBy { "${it.text}|${it.box}" }
                    result(hits)
                }
                .addOnFailureListener { failure("识别失败，请重新框选") }
                .addOnCompleteListener { scanner.close(); bitmap.recycle() }
        } catch(error: Exception) { scanner.close(); bitmap.recycle(); failure("识别失败，请重试") }
    }

    /** 主栏识码仍用一份合并的结果文本（手机侧的结果面板）。 */
    fun scanText(bitmap: Bitmap,result: (List<String>)->Unit,failure: (String)->Unit) =
        scan(bitmap,{ hits -> result(hits.map { it.text }.distinct()) },failure)
}
