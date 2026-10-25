package local.voxfix.debug

internal data class DesktopVoiceBounds(val start: Int, val end: Int)
internal data class DesktopVoiceFrame(val at: Int, val finals: List<DesktopVoiceBounds>, val key: Int,
                                      val draft: DesktopVoiceBounds?)

/** Desktop boundary padding, forced-cut carry and latest-draft clock; raw PCM stays in memory. */
internal class DesktopVoiceSegments(private val audio: FloatArray) {
    var samples = 0
        private set
    private var lastEnd = 0
    private var lastPreview = 0
    private var carry = false
    private var activeKey = -1

    fun append(frame: FloatArray) {
        require(frame.isNotEmpty() && frame.size <= DesktopVoiceRules.FRAME && frame.all { it.isFinite() && it in -1.001f..1.001f })
        check(samples + frame.size <= minOf(audio.size, DesktopVoiceRules.MAX_SAMPLES)) { "每段录音最长三分钟" }
        frame.copyInto(audio, samples)
        samples += frame.size
    }

    fun frame(currentStart: Int, completed: List<DesktopVoiceBounds>, flushing: Boolean = false): DesktopVoiceFrame {
        val finals = mutableListOf<DesktopVoiceBounds>()
        fun queue(start: Int, end: Int, forced: Boolean) {
            if (end > start) {
                check(start >= 0 && end <= samples)
                finals.add(DesktopVoiceBounds(start, end)); lastEnd = end; carry = forced
            }
            activeKey = -1
        }
        for (segment in completed) {
            var start = if (carry) lastEnd else maxOf(lastEnd, segment.start - 6400)
            if (flushing && lastEnd == 0 && samples <= 6 * DesktopVoiceRules.RATE) start = 0
            var end = minOf(samples, segment.end + 6400)
            val forced = segment.end - segment.start >= 152000 && !flushing
            if (forced) {
                val target = minOf(samples, segment.end)
                val search = maxOf(start + DesktopVoiceRules.RATE, target - 24000)
                val count = (target - search) / 160
                if (count > 0) {
                    var best = Double.POSITIVE_INFINITY
                    for (index in 0 until count) {
                        val offset = search + index * 160
                        var energy = 0.0
                        for (i in offset until offset + 160) energy += audio[i].toDouble() * audio[i]
                        if (energy < best) { best = energy; end = offset + 80 }
                    }
                }
            }
            if (flushing) end = samples
            queue(start, end, forced)
        }
        if (flushing && carry && lastEnd < samples) queue(lastEnd, samples, false)
        var draft: DesktopVoiceBounds? = null
        if (!flushing && currentStart >= 0) {
            val start = if (carry) lastEnd else maxOf(lastEnd, currentStart - 6400)
            activeKey = start
            if (samples - start >= 8800 && samples - lastPreview >= 5600) {
                draft = DesktopVoiceBounds(start, samples); lastPreview = samples
            }
        }
        return DesktopVoiceFrame(samples, finals, activeKey, draft)
    }
}

/** Mirror v1.13.8/v4 buffer indices; neural VAD remains sherpa-onnx. See sherpa UPSTREAM.md. */
internal class DesktopVadPosition {
    // shortcut: pinned v4/1.13.8 indices only; revalidate or expose native current-segment on upgrades.
    var start = -1
        private set
    private var total = 0
    private var head = 0

    fun accepted(size: Int, speaking: Boolean, completed: List<DesktopVoiceBounds>) {
        total += size
        val tail = total / 512 * 512
        if (speaking) {
            if (start < 0) start = maxOf(tail - 2 * 512 - 1920, head)
        } else {
            if (start < 0) head = maxOf(head, tail - 2 * 512 - 1920)
            start = -1
        }
        completed.lastOrNull()?.let { head = it.end }
    }
}
