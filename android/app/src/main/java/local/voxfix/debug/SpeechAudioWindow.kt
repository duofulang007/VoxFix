package local.voxfix.debug

/** Bounded original audio for VAD boundary padding; offsets stay relative to the recording. */
internal class SpeechAudioWindow(private val capacity: Int) {
    init { require(capacity > 0) }
    private val samples = FloatArray(capacity)
    var end: Int = 0
        private set

    fun append(chunk: FloatArray) {
        val retained = minOf(chunk.size, capacity)
        var source = chunk.size - retained
        var target = (end + source) % capacity
        while (source < chunk.size) {
            val count = minOf(chunk.size - source, capacity - target)
            chunk.copyInto(samples, target, source, source + count)
            source += count
            target = 0
        }
        end += chunk.size
    }

    fun read(first: Int, last: Int): FloatArray? {
        if (first < maxOf(0, end - capacity) || last > end || last < first) return null
        val result = FloatArray(last - first)
        val start = first % capacity
        val count = minOf(result.size, capacity - start)
        samples.copyInto(result, 0, start, start + count)
        if (count < result.size) samples.copyInto(result, count, 0, result.size - count)
        return result
    }
}
