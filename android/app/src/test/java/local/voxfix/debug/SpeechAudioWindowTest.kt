package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class SpeechAudioWindowTest {
    @Test fun retainsBoundaryAudioAcrossWrapsAndRejectsEvictedRanges() {
        val audio = SpeechAudioWindow(8)
        audio.append(floatArrayOf(0f, 1f, 2f, 3f, 4f))
        audio.append(floatArrayOf(5f, 6f, 7f, 8f, 9f))
        assertEquals(10, audio.end)
        assertArrayEquals(floatArrayOf(2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f), audio.read(2, 10), 0f)
        assertArrayEquals(floatArrayOf(6f, 7f, 8f), audio.read(6, 9), 0f)
        assertNull(audio.read(1, 5))
        assertNull(audio.read(5, 11))
        assertNull(audio.read(5, 4))
    }

    @Test fun oversizedChunksKeepAbsoluteOffsetsAndOnlyTheNewestSamples() {
        val audio = SpeechAudioWindow(4)
        audio.append(floatArrayOf(0f, 1f))
        audio.append(FloatArray(10) { (it + 2).toFloat() })
        assertEquals(12, audio.end)
        assertArrayEquals(floatArrayOf(8f, 9f, 10f, 11f), audio.read(8, 12), 0f)
        assertEquals(0, audio.read(12, 12)!!.size)
        assertNull(audio.read(7, 12))
    }
}
