package local.voxfix.debug

/**
 * 识码结果里"能不能打开"的判定，对照上游 `ScreenshotQrController::webUrl`
 * （固定提交 `afdc340b14e7f92a294a95c374d13ada0d8c7a82`）：
 *
 * - 去首尾空白后整段必须是一个完整 URL，夹在句子里的链接不算；
 * - 任何空白或控制字符都判为否，所以多段文本不会被当成链接；
 * - 必须是绝对地址、host 非空，且 scheme 只认 http／https。
 *
 * 原版对每个识别结果各自判定；手机把结果收在一个面板里，只在"唯一一条结果"时给打开入口，
 * 因此这里只做判定本身，不做多结果的挑选。
 */
object ScreenshotQrLinks {
    fun webUrl(text: String): String? {
        val candidate = text.trim()
        if (candidate.isEmpty()) return null
        for (character in candidate) {
            if (character.isWhitespace() || character.isISOControl()) return null
        }
        val separator = candidate.indexOf("://")
        if (separator <= 0) return null
        val scheme = candidate.substring(0, separator).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = candidate.substring(separator + 3)
        if (rest.isEmpty()) return null
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        val host = authority.substringAfterLast('@').substringBefore(':')
        // QUrl 以 host 非空为有效；端口、路径、查询串不参与判定。
        return if (host.isNotEmpty()) candidate else null
    }
}
