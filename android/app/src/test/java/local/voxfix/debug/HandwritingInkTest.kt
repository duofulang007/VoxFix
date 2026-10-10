package local.voxfix.debug

import org.junit.Assert.*
import org.junit.Test

class HandwritingInkTest {
    @Test fun blankInkHasNoTensorAndStrokesProducePaddedUint8Input() {
        assertEquals(0, HandwritingInk.preprocessMelnyk(IntArray(256 * 256) { -1 }, 256, 256).size)
        val pixels = IntArray(256 * 256) { -1 }
        for (y in 124..132) for (x in 20..230) pixels[y * 256 + x] = 0xff000000.toInt()
        val input = HandwritingInk.preprocessMelnyk(pixels, 256, 256)
        assertEquals(96 * 96, input.size)
        assertTrue(input.any { it != 0.toByte() })
        assertTrue(input.take(4 * 96).all { it == 0.toByte() })
    }
}
