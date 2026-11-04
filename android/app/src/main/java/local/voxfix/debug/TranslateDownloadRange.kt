package local.voxfix.debug

internal object TranslateDownloadRange {
    fun valid(value: String?, offset: Long, total: Long): Boolean {
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(value ?: return false) ?: return false
        val (start,end,size) = match.destructured
        return start.toLongOrNull()==offset && size.toLongOrNull()==total &&
            end.toLongOrNull()?.let { it >= offset && it < total }==true
    }
}
