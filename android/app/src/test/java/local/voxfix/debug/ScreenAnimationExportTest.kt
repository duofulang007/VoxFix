package local.voxfix.debug

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Structure tests for the self-written animated exporters.
 *
 * Snow Shot hands GIF/APNG/WebP to FFmpeg; the phone build writes the containers itself, so the
 * tests parse what it produces instead of trusting the writer. Real decoders are exercised by the
 * `screenshot-recording-format` device check.
 */
class ScreenAnimationExportTest {

    // ---- palette (port of snow-recording-export/src/gif_palette.rs) ---------------------------

    @Test
    fun palettePreservesFlatColorsAndRefreshesAfterSceneChange() {
        val width = 17
        val height = 9
        val quantizer = GifPaletteQuantizer()
        for (colors in listOf(
            intArrayOf(0x172533, 0xD1974D),
            intArrayOf(0xFA11B4, 0x0CEFDD)
        )) {
            val argb = IntArray(width * height) { if (it % 2 == 0) colors[0] else colors[1] }
            val frame = quantizer.convert(argb, width, height)
            for (i in argb.indices) {
                assertEquals(
                    "flat colour $i must map to itself",
                    argb[i] and 0xFFFFFF,
                    frame.palette[frame.indices[i].toInt() and 0xFF]
                )
            }
            val palette = quantizer.palette().copyOf()
            val indices = frame.indices.copyOf()
            // A second identical frame keeps both palette and indices: upstream only rebuilds when
            // the current palette no longer represents the scene.
            val again = quantizer.convert(argb, width, height)
            assertArrayEquals(palette, quantizer.palette())
            assertArrayEquals(indices, again.indices)
        }
    }

    @Test
    fun bayerPatternIsSpatiallyStableAndBalanced() {
        val values = ArrayList<Int>()
        for (y in 0 until 8) for (x in 0 until 8) values.add(bayer(x, y))
        assertEquals((0 until 64).toList(), values.sorted())
        assertEquals(bayer(3, 5), bayer(11, 13))
    }

    // ---- GIF -----------------------------------------------------------------------------------

    @Test
    fun gifWritesLoopExtensionAndDecodableFrames() {
        val output = ByteArrayOutputStream()
        val encoder = GifEncoder(output, 8, 6, loop = true)
        encoder.writeHeader()
        val quantizer = GifPaletteQuantizer()
        val first = IntArray(48) { if (it % 8 < 4) 0xFF0000 else 0x00FF00 }
        val second = IntArray(48) { if (it < 24) 0x0000FF else 0xFFFFFF }
        val firstFrame = quantizer.convert(first, 8, 6)
        val secondFrame = quantizer.convert(second, 8, 6)
        encoder.writeFrame(firstFrame, 100)
        encoder.writeFrame(secondFrame, 250)
        encoder.finish()

        val decoded = GifReader(output.toByteArray()).read()
        assertEquals(8, decoded.width)
        assertEquals(6, decoded.height)
        assertTrue(decoded.loopsForever)
        assertEquals(2, decoded.frames.size)
        assertEquals(10, decoded.frames[0].delayCentiseconds)
        assertEquals(25, decoded.frames[1].delayCentiseconds)
        // LZW round-trip: the decoded indices must match what the quantizer produced.
        assertArrayEquals(firstFrame.indices, decoded.frames[0].indices)
        assertArrayEquals(secondFrame.indices, decoded.frames[1].indices)
    }

    /**
     * A frame big enough to grow the LZW dictionary past 512 and 4096 entries.
     *
     * This is the regression guard for the code-width switch: switching one code early still decodes
     * with tolerant decoders but is rejected by stricter ones (Skia refused every frame larger than
     * a few hundred pixels while the tiny-frame test passed).
     */
    @Test
    fun gifRoundTripsAcrossCodeWidthTransitions() {
        val width = 200
        val height = 150
        val colors = intArrayOf(
            0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00,
            0x0000FF, 0xFFFF00, 0x00FFFF, 0xFF00FF
        )
        var seed = 12_345L
        val pixels = IntArray(width * height) {
            seed = seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            colors[((seed ushr 33).toInt() and 0x7FFFFFFF) % colors.size]
        }
        val frame = GifPaletteQuantizer().convert(pixels, width, height)
        val output = ByteArrayOutputStream()
        val encoder = GifEncoder(output, width, height, loop = true)
        encoder.writeHeader()
        encoder.writeFrame(frame, 100)
        encoder.finish()
        val decoded = GifReader(output.toByteArray()).read()
        assertEquals(1, decoded.frames.size)
        assertArrayEquals(frame.indices, decoded.frames[0].indices)
    }

    @Test
    fun gifOmitsLoopExtensionWhenLoopingIsOff() {        val output = ByteArrayOutputStream()
        val encoder = GifEncoder(output, 4, 4, loop = false)
        encoder.writeHeader()
        encoder.writeFrame(GifPaletteQuantizer().convert(IntArray(16) { 0x123456 }, 4, 4), 40)
        encoder.finish()
        val decoded = GifReader(output.toByteArray()).read()
        assertFalse(decoded.loopsForever)
        assertEquals(1, decoded.frames.size)
        assertEquals(4, decoded.frames[0].delayCentiseconds)
    }

    /**
     * Stable palette: FFmpeg's `gifflags = offsetting | transdiff` writes only the changed rectangle
     * and turns unchanged pixels inside it into the reserved transparent index.
     */
    @Test
    fun gifWritesOnlyTheChangedRectangleForAStablePalette() {
        val width = 8
        val height = 6
        val quantizer = GifPaletteQuantizer()
        val base = IntArray(width * height) { if (it % 2 == 0) 0xFF0000 else 0x00FF00 }
        val changed = base.copyOf()
        // Invert a 2x2 block: every pixel inside changes, the palette itself does not.
        for (y in 2..3) for (x in 3..4) {
            val at = y * width + x
            changed[at] = if (base[at] == 0xFF0000) 0x00FF00 else 0xFF0000
        }
        val first = quantizer.convert(base, width, height)
        val second = quantizer.convert(changed, width, height)
        assertArrayEquals("palette must stay stable for the delta path", first.palette, second.palette)

        val output = ByteArrayOutputStream()
        val encoder = GifEncoder(output, width, height, loop = true)
        encoder.writeHeader()
        encoder.writeFrame(first, 100)
        encoder.writeFrame(second, 100)
        encoder.finish()

        val decoded = GifReader(output.toByteArray()).read()
        assertEquals(2, decoded.frames.size)
        val full = decoded.frames[0]
        assertEquals(0, full.left)
        assertEquals(0, full.top)
        assertEquals(width, full.width)
        assertEquals(height, full.height)
        assertFalse(full.transparent)
        assertArrayEquals(first.indices, full.indices)

        val delta = decoded.frames[1]
        assertEquals(3, delta.left)
        assertEquals(2, delta.top)
        assertEquals(2, delta.width)
        assertEquals(2, delta.height)
        assertTrue("changed pixels must be transparent-coded", delta.transparent)
        for (y in 0 until 2) for (x in 0 until 2) {
            val expected = second.indices[(2 + y) * width + 3 + x]
            assertEquals("pixel ($x,$y) must carry the new index", expected, delta.indices[y * 2 + x])
        }
    }

    @Test
    fun gifCollapsesUnchangedFramesToOneTransparentPixel() {
        val width = 16
        val height = 12
        val quantizer = GifPaletteQuantizer()
        val pixels = IntArray(width * height) { if (it % 3 == 0) 0x336699 else 0xFFFFFF }
        val first = quantizer.convert(pixels, width, height)
        val again = quantizer.convert(pixels, width, height)

        val output = ByteArrayOutputStream()
        val encoder = GifEncoder(output, width, height, loop = true)
        encoder.writeHeader()
        encoder.writeFrame(first, 100)
        encoder.writeFrame(again, 100)
        encoder.finish()

        val bytes = output.toByteArray()
        val decoded = GifReader(bytes).read()
        assertEquals(2, decoded.frames.size)
        val still = decoded.frames[1]
        assertEquals(1, still.width)
        assertEquals(1, still.height)
        assertTrue(still.transparent)
        assertEquals(255, still.indices[0].toInt() and 0xFF)
        // One full frame plus one transparent pixel must stay well under two full frames.
        val oneFrame = ByteArrayOutputStream()
        GifEncoder(oneFrame, width, height, loop = true).let {
            it.writeHeader()
            it.writeFrame(first, 100)
            it.finish()
        }
        assertTrue("still frame must not cost a full image",
            bytes.size < oneFrame.size() + 1_024)
    }

    // ---- APNG ----------------------------------------------------------------------------------

    @Test
    fun apngWritesChunksAndPatchesFrameCount() {
        val file = File.createTempFile("voxfix-apng", ".apng")
        try {
            val encoder = ApngEncoder(file, 6, 4, loop = true)
            encoder.writeHeader()
            encoder.writeFrame(IntArray(24) { 0xFF3366 }, 100)
            encoder.writeFrame(IntArray(24) { 0x00CC99 }, 100)
            encoder.writeFrame(IntArray(24) { 0x9966FF }, 100)
            encoder.finish()

            val chunks = PngReader(file.readBytes()).read()
            assertEquals(6, chunks.width)
            assertEquals(4, chunks.height)
            assertEquals(3, chunks.frameCount)
            assertEquals(0, chunks.playCount)
            assertEquals(3, chunks.controlChunks)
            assertEquals(1, chunks.idat)
            assertEquals(2, chunks.fdat)
            assertEquals(1, chunks.trailer)
        } finally {
            file.delete()
        }
    }

    @Test
    fun apngPlaysOnceWhenLoopingIsOff() {
        val file = File.createTempFile("voxfix-apng", ".apng")
        try {
            val encoder = ApngEncoder(file, 2, 2, loop = false)
            encoder.writeHeader()
            encoder.writeFrame(IntArray(4) { 0x000000 }, 50)
            encoder.finish()
            val chunks = PngReader(file.readBytes()).read()
            assertEquals(1, chunks.frameCount)
            assertEquals(1, chunks.playCount)
        } finally {
            file.delete()
        }
    }

    // ---- WebP ----------------------------------------------------------------------------------

    @Test
    fun webpWrapsStillBitstreamsIntoAnimationContainer() {
        val file = File.createTempFile("voxfix-webp", ".webp")
        try {
            val writer = WebpAnimationWriter(file, 120, 80, loop = true)
            writer.writeHeader()
            writer.writeFrame(stillWebp("VP8L", 40), 100)
            writer.writeFrame(stillWebp("VP8 ", 24), 100)
            writer.writeFrame(stillWebp("VP8L", 16), 100)
            writer.finish()

            val bytes = file.readBytes()
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
            assertEquals(bytes.size - 8, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
            val parsed = WebpReader(bytes).read()
            assertEquals(120, parsed.width)
            assertEquals(80, parsed.height)
            assertEquals(3, parsed.frames)
            assertEquals(0, parsed.loopCount)
            assertEquals(listOf(100, 100, 100), parsed.durations)
            assertTrue(parsed.imageChunks.all { it == "VP8L" || it == "VP8 " })
        } finally {
            file.delete()
        }
    }

    @Test
    fun webpUsesSinglePlayCountWhenLoopingIsOff() {
        val file = File.createTempFile("voxfix-webp", ".webp")
        try {
            val writer = WebpAnimationWriter(file, 32, 32, loop = false)
            writer.writeHeader()
            writer.writeFrame(stillWebp("VP8L", 12), 100)
            writer.finish()
            assertEquals(1, WebpReader(file.readBytes()).read().loopCount)
        } finally {
            file.delete()
        }
    }

    // ---- format-dependent settings -------------------------------------------------------------

    @Test
    fun animatedFormatsUseTheirOwnClarityFrameRateAndMime() {
        val options = ScreenRecordingOptions(format = ScreenRecordingOptions.Format.GIF)
        assertEquals(ScreenRecordingOptions.Clarity.HD_720, options.effectiveClarity)
        assertEquals(10, options.effectiveFrameRate)
        assertFalse(options.audioEnabled)
        assertTrue(options.format.animated)
        // Upstream exposes only 1080p/720p/480p and 5/10/15/24 for animated images.
        assertEquals(
            listOf(
                ScreenRecordingOptions.Clarity.FULL_HD,
                ScreenRecordingOptions.Clarity.HD_720,
                ScreenRecordingOptions.Clarity.SD_480
            ),
            options.clarityOptions
        )
        assertEquals(listOf(5, 10, 15, 24), options.frameRateOptions)
        assertEquals("image/gif", options.format.mime)

        val video = ScreenRecordingOptions()
        assertEquals(ScreenRecordingOptions.Clarity.QHD_2K, video.effectiveClarity)
        assertEquals(30, video.effectiveFrameRate)
        assertTrue(video.audioEnabled)
        assertEquals(5, video.clarityOptions.size)
        assertEquals("video/mp4", video.format.mime)
    }

    @Test
    fun fileNameFollowsTheSelectedFormat() {
        val stamp = 0L
        assertTrue(ScreenRecordingGeometry.fileName(stamp, "mp4").endsWith(".mp4"))
        assertTrue(ScreenRecordingGeometry.fileName(stamp, "gif").endsWith(".gif"))
        assertTrue(ScreenRecordingGeometry.fileName(stamp, "webp").endsWith(".webp"))
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun bayer(x: Int, y: Int): Int {
        var value = 0
        for (bit in 0 until 3) {
            value = value or ((((x shr bit) xor (y shr bit)) and 1) shl (5 - bit * 2))
            value = value or (((y shr bit) and 1) shl (4 - bit * 2))
        }
        return value
    }

    /** Minimal single-frame WebP, mirroring what the platform encoder emits. */
    private fun stillWebp(type: String, size: Int): ByteArray {
        val payload = ByteArray(size) { (it * 7).toByte() }
        val padded = if (size % 2 == 1) payload + byteArrayOf(0) else payload
        val buffer = ByteBuffer.allocate(12 + 8 + padded.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(4 + 8 + padded.size)
        buffer.put("WEBP".toByteArray(Charsets.US_ASCII))
        buffer.put(type.toByteArray(Charsets.US_ASCII))
        buffer.putInt(size)
        buffer.put(padded)
        return buffer.array()
    }

    private class GifFrame(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val indices: ByteArray,
        val delayCentiseconds: Int,
        val transparent: Boolean
    )

    private class GifDecoded(
        val width: Int,
        val height: Int,
        val loopsForever: Boolean,
        val frames: List<GifFrame>
    )

    /** Parses the subset of GIF89a the writer produces, including a full LZW decoder. */
    private class GifReader(private val bytes: ByteArray) {
        private var position = 0

        fun read(): GifDecoded {
            val signature = String(bytes, 0, 6, Charsets.ISO_8859_1)
            check(signature == "GIF89a") { "不是 GIF89a：$signature" }
            position = 6
            val width = readShort()
            val height = readShort()
            val packed = bytes[position].toInt() and 0xFF
            position += 3 // packed, background, aspect
            // The writer emits a 256-entry global colour table on the first frame.
            if (packed and 0x80 != 0) position += 3 * (1 shl ((packed and 0x07) + 1))
            var loops = false
            val frames = ArrayList<GifFrame>()
            while (position < bytes.size) {
                when (val marker = bytes[position].toInt() and 0xFF) {
                    0x3B -> return GifDecoded(width, height, loops, frames)
                    0x21 -> {
                        position++
                        val label = bytes[position].toInt() and 0xFF
                        position++
                        if (label == 0xF9) {
                            val size = bytes[position].toInt() and 0xFF
                            val flags = bytes[position + 1].toInt() and 0xFF
                            pendingTransparent = flags and 0x01 != 0
                            val delay = (bytes[position + 2].toInt() and 0xFF) or
                                ((bytes[position + 3].toInt() and 0xFF) shl 8)
                            // Size byte + `size` data bytes + the zero-length block terminator.
                            position += size + 2
                            pendingDelay = delay
                        } else if (label == 0xFF) {
                            val size = bytes[position].toInt() and 0xFF
                            val identifier = String(bytes, position + 1, size, Charsets.ISO_8859_1)
                            position += size + 1
                            position = skipSubBlocks(position)
                            if (identifier == "NETSCAPE2.0") loops = true
                        } else {
                            position = skipSubBlocks(position)
                        }
                    }
                    0x2C -> {
                        position++
                        val left = readShort()
                        val top = readShort()
                        val frameWidth = readShort()
                        val frameHeight = readShort()
                        val packed = bytes[position].toInt() and 0xFF
                        position++
                        if (packed and 0x80 != 0) {
                            position += 3 * (1 shl ((packed and 0x07) + 1))
                        }
                        val minimumCodeSize = bytes[position].toInt() and 0xFF
                        position++
                        val data = readSubBlocks()
                        val indices = decodeLzw(data, minimumCodeSize, frameWidth * frameHeight)
                        frames.add(GifFrame(left, top, frameWidth, frameHeight, indices,
                            pendingDelay, pendingTransparent))
                        pendingDelay = 0
                        pendingTransparent = false
                    }
                    else -> error("未知 GIF 块 0x${Integer.toHexString(marker)} @ $position")
                }
            }
            error("GIF 没有结束标记")
        }

        private var pendingDelay = 0
        private var pendingTransparent = false

        private fun readShort(): Int {
            val value = (bytes[position].toInt() and 0xFF) or ((bytes[position + 1].toInt() and 0xFF) shl 8)
            position += 2
            return value
        }

        private fun skipSubBlocks(start: Int): Int {
            var cursor = start
            while (true) {
                val size = bytes[cursor].toInt() and 0xFF
                cursor++
                if (size == 0) return cursor
                cursor += size
            }
        }

        private fun readSubBlocks(): ByteArray {
            val output = ByteArrayOutputStream()
            while (true) {
                val size = bytes[position].toInt() and 0xFF
                position++
                if (size == 0) return output.toByteArray()
                output.write(bytes, position, size)
                position += size
            }
        }

        /** Standard GIF LZW decoder; also proves the writer's code-size transitions. */
        private fun decodeLzw(data: ByteArray, minimumCodeSize: Int, expected: Int): ByteArray {
            val clear = 1 shl minimumCodeSize
            val end = clear + 1
            var codeSize = minimumCodeSize + 1
            var dictionary = ArrayList<IntArray>()
            fun reset() {
                dictionary = ArrayList<IntArray>(4096)
                for (i in 0 until clear) dictionary.add(intArrayOf(i))
                dictionary.add(intArrayOf())
                dictionary.add(intArrayOf())
                codeSize = minimumCodeSize + 1
            }
            reset()
            val output = ByteArrayOutputStream()
            var bitPosition = 0
            var previous = -1
            while (true) {
                if (bitPosition + codeSize > data.size * 8) break
                var code = 0
                for (bit in 0 until codeSize) {
                    val index = bitPosition + bit
                    val value = (data[index ushr 3].toInt() ushr (index and 7)) and 1
                    code = code or (value shl bit)
                }
                bitPosition += codeSize
                if (code == clear) {
                    reset()
                    previous = -1
                    continue
                }
                if (code == end) break
                val entry: IntArray = when {
                    code < dictionary.size -> dictionary[code]
                    previous >= 0 -> dictionary[previous] + dictionary[previous][0]
                    else -> error("LZW 码 $code 越界")
                }
                output.write(entry.map { it.toByte() }.toByteArray())
                if (previous >= 0 && dictionary.size < 4096) {
                    dictionary.add(dictionary[previous] + entry[0])
                    if (dictionary.size == (1 shl codeSize) && codeSize < 12) codeSize++
                }
                previous = code
            }
            val decoded = output.toByteArray()
            check(decoded.size >= expected) { "LZW 输出 ${decoded.size} 像素，少于 $expected" }
            return decoded.copyOf(expected)
        }
    }

    private class PngChunks(
        val width: Int,
        val height: Int,
        val frameCount: Int,
        val playCount: Int,
        val controlChunks: Int,
        val idat: Int,
        val fdat: Int,
        val trailer: Int
    )

    private class PngReader(private val bytes: ByteArray) {
        fun read(): PngChunks {
            check(bytes.size > 8 && bytes[1].toInt() == 0x50) { "不是 PNG/APNG" }
            var offset = 8
            var width = 0
            var height = 0
            var frames = 0
            var plays = 0
            var control = 0
            var idat = 0
            var fdat = 0
            var iend = 0
            while (offset + 8 <= bytes.size) {
                val length = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(offset)
                val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
                val data = offset + 8
                when (type) {
                    "IHDR" -> {
                        width = ByteBuffer.wrap(bytes).getInt(data)
                        height = ByteBuffer.wrap(bytes).getInt(data + 4)
                    }
                    "acTL" -> {
                        frames = ByteBuffer.wrap(bytes).getInt(data)
                        plays = ByteBuffer.wrap(bytes).getInt(data + 4)
                    }
                    "fcTL" -> control++
                    "IDAT" -> idat++
                    "fdAT" -> fdat++
                    "IEND" -> iend++
                }
                offset += 12 + length
            }
            return PngChunks(width, height, frames, plays, control, idat, fdat, iend)
        }
    }

    private class WebpParsed(
        val width: Int,
        val height: Int,
        val frames: Int,
        val loopCount: Int,
        val durations: List<Int>,
        val imageChunks: List<String>
    )

    private class WebpReader(private val bytes: ByteArray) {
        fun read(): WebpParsed {
            var offset = 12
            var width = 0
            var height = 0
            var frames = 0
            var loopCount = -1
            val durations = ArrayList<Int>()
            val imageChunks = ArrayList<String>()
            while (offset + 8 <= bytes.size) {
                val type = String(bytes, offset, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset + 4)
                val data = offset + 8
                when (type) {
                    "VP8X" -> {
                        width = read24(data + 4) + 1
                        height = read24(data + 7) + 1
                    }
                    "ANIM" -> loopCount = (bytes[data + 4].toInt() and 0xFF) or
                        ((bytes[data + 5].toInt() and 0xFF) shl 8)
                    "ANMF" -> {
                        frames++
                        durations.add(read24(data + 12))
                        var cursor = data + 16
                        val end = data + size
                        while (cursor + 8 <= end) {
                            val inner = String(bytes, cursor, 4, Charsets.US_ASCII)
                            imageChunks.add(inner)
                            val innerSize = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                                .getInt(cursor + 4)
                            cursor += 8 + innerSize + (innerSize and 1)
                        }
                    }
                }
                offset += 8 + size + (size and 1)
            }
            return WebpParsed(width, height, frames, loopCount, durations, imageChunks)
        }

        private fun read24(offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16)
    }
}
