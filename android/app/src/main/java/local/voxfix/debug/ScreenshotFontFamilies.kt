package local.voxfix.debug

import android.util.Xml
import java.io.File
import org.xmlpull.v1.XmlPullParser

internal object ScreenshotFontFamilies {
    val names: List<String> by lazy {
        val result=mutableSetOf("sans-serif","serif","monospace")
        for(path in listOf("/system/etc/fonts.xml","/product/etc/fonts_customization.xml","/system_ext/etc/fonts_customization.xml")) {
            runCatching {
                val file=File(path)
                if(file.isFile && file.length()<=3_000_000) file.inputStream().use { input ->
                    val parser=Xml.newPullParser(); parser.setInput(input,null)
                    while(parser.next()!=XmlPullParser.END_DOCUMENT) {
                        if(parser.eventType==XmlPullParser.START_TAG && parser.name in setOf("family","alias"))
                            parser.getAttributeValue(null,"name")?.takeIf { it.isNotBlank() && it.length<=40 }?.let(result::add)
                    }
                }
            }
        }
        result.sorted()
    }
    /** Unknown names silently fall back on Android; validate against the device's published families. */
    fun resolve(value: String): String? {
        val family=value.trim()
        if(family.isEmpty() || family.length>40 || family.any { it.isISOControl() }) return null
        return family.takeIf { it in names }
    }
}
