package local.voxfix.debug

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.util.UUID

object ScreenshotShare {
    fun write(context: Context,image: Bitmap): Uri {
        val directory=File(context.cacheDir,"screenshot-exports").apply { check(mkdirs() || isDirectory) }
        directory.listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{32}\\.png")) && it.lastModified()<System.currentTimeMillis()-86_400_000 }?.forEach { it.delete() }
        val file=File(directory,UUID.randomUUID().toString().replace("-","")+".png")
        try { file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) { "图片编码失败" } } }
        catch(error: Exception) { file.delete(); throw error }
        return Uri.parse("content://${context.packageName}.screenshots/exports/${file.name}")
    }
    fun intent(context: Context,uri: Uri)=Intent(Intent.ACTION_SEND).apply {
        type="image/png"; putExtra(Intent.EXTRA_STREAM,uri)
        clipData=ClipData.newUri(context.contentResolver,"截图",uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    fun open(context: Context,uri: Uri) {
        context.startActivity(Intent.createChooser(intent(context,uri),"分享图片").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
