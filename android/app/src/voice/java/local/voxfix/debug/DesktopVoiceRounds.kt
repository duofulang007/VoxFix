package local.voxfix.debug

/** Split only the recognizer's buffer; keep the microphone and every boundary sample. */
internal object DesktopVoiceRounds {
    fun run(next: () -> FloatArray?, cancelled: () -> Boolean, limit: Int = DesktopVoiceRules.MAX_SAMPLES,
            round: (() -> FloatArray?, Int) -> Unit) {
        require(limit > 0)
        var carry: FloatArray? = null
        var offset = 0
        var ended = false
        while (!cancelled() && !ended) {
            if (carry == null) carry = next() ?: break
            var used = 0
            round({
                if (cancelled() || used == limit || ended) null else {
                    val chunk = carry ?: next()
                    if (chunk == null) { ended = true; null } else {
                        require(chunk.isNotEmpty() && chunk.size <= 16000 && chunk.all { it.isFinite() && it in -1.001f..1.001f })
                        val size = minOf(chunk.size, limit - used)
                        carry = if (size == chunk.size) null else chunk.copyOfRange(size, chunk.size)
                        used += size
                        if (size == chunk.size) chunk else chunk.copyOf(size)
                    }
                }
            }, offset)
            check(cancelled() || ended || used == limit) { "识别提前结束，未消费完本轮音频" }
            offset = Math.addExact(offset, used)
        }
    }
}
