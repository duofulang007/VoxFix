package local.voxfix.debug

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

/** Bundled scanner: QR and barcodes work without Play Services or an image upload. */
object ScreenshotBarcode {
    fun scan(bitmap: Bitmap,result: (List<String>)->Unit,failure: (String)->Unit) {
        val scanner=BarcodeScanning.getClient()
        try {
            scanner.process(InputImage.fromBitmap(bitmap,0))
                .addOnSuccessListener { values -> result(values.mapNotNull { it.rawValue?.takeIf(String::isNotBlank) }.distinct()) }
                .addOnFailureListener { failure("识别失败，请重新框选") }
                .addOnCompleteListener { scanner.close(); bitmap.recycle() }
        } catch(error: Exception) { scanner.close(); bitmap.recycle(); failure("识别失败，请重试") }
    }
}
