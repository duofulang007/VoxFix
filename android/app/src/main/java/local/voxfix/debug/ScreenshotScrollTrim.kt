package local.voxfix.debug

/** Half-open interval along the stitch axis. At least one source pixel must survive. */
internal class ScreenshotScrollTrim(extent: Int) {
    init { require(extent > 0) }
    var extent: Int = extent
        private set
    var start: Int = 0
        private set
    var end: Int = extent
        private set
    val size get() = end - start

    fun trimHead(pixels: Int) { start = pixels.coerceIn(0,end - 1) }
    fun trimTail(pixels: Int) { end = extent - pixels.coerceIn(0,extent - start - 1) }

    fun grow(nextExtent: Int, prepend: Boolean) {
        if (nextExtent <= extent) return
        if (prepend) {
            end += nextExtent - extent
            start = 0
        } else end = nextExtent
        extent = nextExtent
    }
}
