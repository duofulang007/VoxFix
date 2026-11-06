package local.voxfix.debug

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Animated-image exporters for screen recording (GIF / APNG / WebP).
 *
 * Snow Shot delegates these containers to FFmpeg (`streaming.rs` sets only the looping option and
 * lets libavformat write the frames). Android has no public animated-image encoder, so the phone
 * build writes the containers itself:
 *
 * - the palette stage is a line-by-line port of `snow-recording-export/src/gif_palette.rs`
 *   (32³ histogram, 255 median-cut colours, 8×8 Bayer dither, palette kept while the scene holds);
 * - GIF adds a plain LZW writer, which the format defines bit for bit;
 * - APNG is PNG chunks plus a zlib stream per frame, again fully specified by the format;
 * - WebP reuses the platform's still-image VP8/VP8L bitstream and only assembles the animation
 *   container (`VP8X` / `ANIM` / `ANMF`), so no VP8 encoder is vendored.
 *
 * Everything here is plain Kotlin so the JVM tests can validate structure without a device.
 */

// ---- GIF palette (port of gif_palette.rs) -------------------------------------------------------

private const val BINS = 32 * 32 * 32
private const val PALETTE_COLORS = 255 // one entry stays free, matching the upstream reserve
private const val PALETTE_RMS_LIMIT = 8L
private const val BIN_RMS_LIMIT = 32L
private const val BAYER_SCALE = 3

internal class GifFrame(val indices: ByteArray, val palette: IntArray)

/**
 * Content-adaptive GIF quantizer.
 *
 * The palette is kept while it still represents the frame (RMS below eight levels) and rebuilt on
 * scene changes, exactly like upstream. Bins cache their nearest colour so per-pixel work stays a
 * single lookup.
 */
internal class GifPaletteQuantizer {
    private val counts = LongArray(BINS)
    private val sumR = LongArray(BINS)
    private val sumG = LongArray(BINS)
    private val sumB = LongArray(BINS)
    private val lookup = IntArray(BINS) { -1 }
    private var colors = IntArray(0) // packed 0xRRGGBB, sorted

    fun palette(): IntArray = colors

    fun convert(argb: IntArray, width: Int, height: Int): GifFrame {
        require(argb.size >= width * height) { "帧尺寸不足" }
        counts.fill(0)
        sumR.fill(0)
        sumG.fill(0)
        sumB.fill(0)
        val total = width * height
        for (i in 0 until total) {
            val pixel = argb[i]
            val r = pixel shr 16 and 0xFF
            val g = pixel shr 8 and 0xFF
            val b = pixel and 0xFF
            val bin = (r shr 3 shl 10) or (g shr 3 shl 5) or (b shr 3)
            counts[bin]++
            sumR[bin] += r.toLong()
            sumG[bin] += g.toLong()
            sumB[bin] += b.toLong()
        }
        var error = 0L
        var worst = 0L
        if (colors.isNotEmpty()) {
            for (bin in 0 until BINS) {
                val count = counts[bin]
                if (count == 0L) continue
                val color = binColor(bin)
                val distance = nearest(color).second
                error += distance.toLong() * count
                if (distance > worst) worst = distance.toLong()
            }
        }
        if (colors.isEmpty() || error > total.toLong() * 3 * PALETTE_RMS_LIMIT * PALETTE_RMS_LIMIT ||
            worst > 3 * BIN_RMS_LIMIT * BIN_RMS_LIMIT
        ) {
            rebuild()
        }

        val indices = ByteArray(total)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val pixel = argb[row + x]
                val color = pixel and 0xFFFFFF
                val index = index(color)
                if (colors[index] == color) {
                    indices[row + x] = index.toByte()
                } else {
                    val offset = (bayer(x, y) shr BAYER_SCALE) - (1 shl (5 - BAYER_SCALE))
                    val r = ((color shr 16 and 0xFF) + offset).coerceIn(0, 255)
                    val g = ((color shr 8 and 0xFF) + offset).coerceIn(0, 255)
                    val b = ((color and 0xFF) + offset).coerceIn(0, 255)
                    indices[row + x] = index((r shl 16) or (g shl 8) or b).toByte()
                }
            }
        }
        return GifFrame(indices, colors)
    }

    private fun binColor(bin: Int): Int {
        val count = counts[bin]
        if (count == 0L) return 0
        val r = ((sumR[bin] + count / 2) / count).toInt()
        val g = ((sumG[bin] + count / 2) / count).toInt()
        val b = ((sumB[bin] + count / 2) / count).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    private fun distance(a: Int, b: Int): Int {
        val dr = (a shr 16 and 0xFF) - (b shr 16 and 0xFF)
        val dg = (a shr 8 and 0xFF) - (b shr 8 and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db
    }

    /** Nearest palette entry and its squared distance; upstream `nearest()`. */
    private fun nearest(color: Int): Pair<Int, Int> {
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (i in colors.indices) {
            val d = distance(color, colors[i])
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best to bestDistance
    }

    private fun index(color: Int): Int {
        val bin = (color shr 19 and 0x1F shl 10) or (color shr 11 and 0x1F shl 5) or (color shr 3 and 0x1F)
        if (lookup[bin] < 0) {
            val center = ((bin shr 10) * 8 + 4 shl 16) or (((bin shr 5) and 31) * 8 + 4 shl 8) or ((bin and 31) * 8 + 4)
            lookup[bin] = nearest(center).first
        }
        return lookup[bin]
    }

    /** Median cut weighted by population × colour range, then keep the sorted box averages. */
    private fun rebuild() {
        val boxes = ArrayList<MutableList<Int>>()
        val first = ArrayList<Int>()
        for (bin in 0 until BINS) if (counts[bin] != 0L) first.add(bin)
        if (first.isEmpty()) first.add(0)
        boxes.add(first)
        while (boxes.size < PALETTE_COLORS) {
            var bestBox = -1
            var bestAxis = 0
            var bestScore = -1L
            for (i in boxes.indices) {
                val bins = boxes[i]
                if (bins.size <= 1) continue
                var lowR = 255; var lowG = 255; var lowB = 255
                var highR = 0; var highG = 0; var highB = 0
                var population = 0L
                for (bin in bins) {
                    val color = binColor(bin)
                    val r = color shr 16 and 0xFF
                    val g = color shr 8 and 0xFF
                    val b = color and 0xFF
                    if (r < lowR) lowR = r
                    if (r > highR) highR = r
                    if (g < lowG) lowG = g
                    if (g > highG) highG = g
                    if (b < lowB) lowB = b
                    if (b > highB) highB = b
                    population += counts[bin]
                }
                val ranges = intArrayOf(highR - lowR, highG - lowG, highB - lowB)
                val axis = if (ranges[0] >= ranges[1] && ranges[0] >= ranges[2]) 0 else if (ranges[1] >= ranges[2]) 1 else 2
                val score = population * ranges[axis].toLong() * ranges[axis].toLong()
                if (score > bestScore) {
                    bestScore = score
                    bestBox = i
                    bestAxis = axis
                }
            }
            if (bestBox < 0) break
            val bins = boxes[bestBox]
            bins.sortBy { channel(binColor(it), bestAxis) }
            val half = (bins.sumOf { counts[it] } + 1) / 2
            var accumulated = 0L
            var split = 0
            for (i in bins.indices) {
                accumulated += counts[bins[i]]
                if (accumulated >= half) {
                    split = i + 1
                    break
                }
            }
            val clamp = split.coerceAtMost(bins.size - 1).coerceAtLeast(1)
            val tail = ArrayList(bins.subList(clamp, bins.size))
            bins.subList(clamp, bins.size).clear()
            boxes.add(tail)
        }
        val packed = IntArray(boxes.size)
        for (i in boxes.indices) {
            var count = 0L
            var r = 0L
            var g = 0L
            var b = 0L
            for (bin in boxes[i]) {
                count += counts[bin]
                r += sumR[bin]
                g += sumG[bin]
                b += sumB[bin]
            }
            packed[i] = if (count == 0L) 0 else
                ((r + count / 2) / count).toInt() shl 16 or
                    (((g + count / 2) / count).toInt() shl 8) or
                    ((b + count / 2) / count).toInt()
        }
        packed.sort()
        colors = packed
        lookup.fill(-1)
    }

    private fun channel(color: Int, axis: Int): Int = when (axis) {
        0 -> color shr 16 and 0xFF
        1 -> color shr 8 and 0xFF
        else -> color and 0xFF
    }

    private companion object {
        /** 8×8 Bayer matrix equivalent to FFmpeg's `bayer_scale=3` ordered dithering. */
        fun bayer(x: Int, y: Int): Int {
            var value = 0
            for (bit in 0 until 3) {
                value = value or ((((x shr bit) xor (y shr bit)) and 1) shl (5 - bit * 2))
                value = value or (((y shr bit) and 1) shl (4 - bit * 2))
            }
            return value
        }
    }
}

// ---- GIF container ------------------------------------------------------------------------------

/** Minimal GIF89a writer: local colour table per frame, no inter-frame delta optimisation. */
internal class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    private val loop: Boolean
) {
    private var frames = 0

    fun writeHeader() {
        out.write("GIF89a".toByteArray(Charsets.ISO_8859_1))
        writeShort(width)
        writeShort(height)
        out.write(0x70) // no global colour table, 8-bit colour resolution
        out.write(0x00) // background colour index
        out.write(0x00) // pixel aspect ratio
        if (loop) {
            out.write(0x21)
            out.write(0xFF)
            out.write(0x0B)
            out.write("NETSCAPE2.0".toByteArray(Charsets.ISO_8859_1))
            out.write(0x03)
            out.write(0x01)
            writeShort(0) // 0 = repeat forever
            out.write(0x00)
        }
    }

    fun writeFrame(frame: GifFrame, delayMs: Int) {
        val delay = ((delayMs + 5) / 10).coerceIn(1, 65_535)
        // Graphic control extension: disposal "do not dispose", no transparency.
        out.write(0x21)
        out.write(0xF9)
        out.write(0x04)
        out.write(0x04)
        writeShort(delay)
        out.write(0x00)
        out.write(0x00)
        // Image descriptor with a 256-entry local colour table.
        out.write(0x2C)
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
        out.write(0x87)
        val table = IntArray(256)
        for (i in 0 until 256) table[i] = frame.palette.getOrElse(i) { 0 }
        for (entry in table) {
            out.write(entry shr 16 and 0xFF)
            out.write(entry shr 8 and 0xFF)
            out.write(entry and 0xFF)
        }
        writeLzw(frame.indices)
        frames++
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    val frameCount: Int get() = frames

    private fun writeShort(value: Int) {
        out.write(value and 0xFF)
        out.write(value shr 8 and 0xFF)
    }

    private fun writeLzw(indices: ByteArray) {
        out.write(8) // minimum code size
        val clear = 1 shl 8
        val end = clear + 1
        var codeSize = 9
        var next = end + 1
        val dictionary = HashMap<Int, Int>(4096)
        val bits = BitWriter(out)
        bits.emit(clear, codeSize)
        if (indices.isEmpty()) {
            bits.emit(end, codeSize)
            bits.finish()
            return
        }
        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val symbol = indices[i].toInt() and 0xFF
            val found = dictionary[(prefix shl 8) or symbol]
            if (found != null) {
                prefix = found
                continue
            }
            bits.emit(prefix, codeSize)
            dictionary[(prefix shl 8) or symbol] = next
            next++
            if (next > 4095) {
                bits.emit(clear, codeSize)
                dictionary.clear()
                codeSize = 9
                next = end + 1
            } else if (next > (1 shl codeSize) && codeSize < 12) {
                // The decoder builds the same dictionary one entry later, so the wider code size
                // only takes effect after the encoder's next slot passes the current limit by one.
                // Switching at `==` desynchronises the two and is what made Skia reject large frames
                // while tolerant decoders still accepted them.
                codeSize++
            }
            prefix = symbol
        }
        bits.emit(prefix, codeSize)
        bits.emit(end, codeSize)
        bits.finish()
    }

    /** LSB-first bit packer that flushes into 255-byte GIF sub-blocks. */
    private class BitWriter(private val out: OutputStream) {
        private val block = ByteArray(255)
        private var blockSize = 0
        private var bits = 0
        private var bitCount = 0

        fun emit(code: Int, size: Int) {
            bits = bits or (code shl bitCount)
            bitCount += size
            while (bitCount >= 8) {
                push(bits and 0xFF)
                bits = bits ushr 8
                bitCount -= 8
            }
        }

        fun finish() {
            if (bitCount > 0) {
                push(bits and 0xFF)
                bits = 0
                bitCount = 0
            }
            flushBlock()
            out.write(0x00)
        }

        private fun push(value: Int) {
            block[blockSize++] = value.toByte()
            if (blockSize == 255) flushBlock()
        }

        private fun flushBlock() {
            if (blockSize == 0) return
            out.write(blockSize)
            out.write(block, 0, blockSize)
            blockSize = 0
        }
    }
}

// ---- APNG container -----------------------------------------------------------------------------

/**
 * APNG writer.
 *
 * `acTL` has to state the frame count up front but frames stream in, so the count is written as a
 * placeholder and patched when the recording stops; the chunk CRC is recomputed on patch.
 */
internal class ApngEncoder(
    private val file: File,
    private val width: Int,
    private val height: Int,
    private val loop: Boolean
) {
    private val out = RandomAccessFile(file, "rw")
    private var sequence = 0
    private var frames = 0
    private var actlFramesOffset = -1L

    fun writeHeader() {
        out.setLength(0)
        out.write(PNG_SIGNATURE)
        val header = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
        // Colour type 2 (truecolour): a screen recording is opaque, and dropping the alpha byte
        // removes a quarter of the bytes that would otherwise go through deflate every frame.
        header.putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0)
        writeChunk("IHDR", header.array())
        // Animation control: frame count is patched in finish().
        val control = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(0).putInt(if (loop) 0 else 1)
        actlFramesOffset = out.filePointer + 8
        writeChunk("acTL", control.array())
    }

    fun writeFrame(argb: IntArray, delayMs: Int) {
        require(argb.size >= width * height) { "帧尺寸不足" }
        val compressed = compressFrame(argb)
        val delay = delayMs.coerceIn(1, 65_535)
        val control = ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN)
        control.putInt(sequence++).putInt(width).putInt(height).putInt(0).putInt(0)
            .putShort(delay.toShort()).putShort(1000).put(0).put(0)
        writeChunk("fcTL", control.array())
        if (frames == 0) {
            writeChunk("IDAT", compressed)
        } else {
            val payload = ByteBuffer.allocate(4 + compressed.size).order(ByteOrder.BIG_ENDIAN)
            payload.putInt(sequence++).put(compressed)
            writeChunk("fdAT", payload.array())
        }
        frames++
    }

    fun finish() {
        writeChunk("IEND", ByteArray(0))
        if (actlFramesOffset >= 0) {
            // Patch the frame count, then rebuild the CRC over the chunk type and all eight data
            // bytes — the play count is part of the covered range even when it did not change.
            out.seek(actlFramesOffset)
            out.writeInt(frames)
            out.writeInt(if (loop) 0 else 1)
            out.seek(actlFramesOffset - 4)
            val crcInput = ByteArray(12)
            out.readFully(crcInput)
            val crc = CRC32().apply { update(crcInput, 0, 12) }.value.toInt()
            out.seek(actlFramesOffset + 8)
            out.writeInt(crc)
        }
        out.close()
    }

    val frameCount: Int get() = frames

    /**
     * Filters every row (None / Sub / Up, chosen by the sum of absolute deviations) before deflating.
     *
     * Screen content repeats rows and runs, so `Up` turns most of a frame into zeros; without it the
     * writer managed only ~4 fps and produced a six-megabyte nine-second recording.
     */
    private fun compressFrame(argb: IntArray): ByteArray {
        val stride = width * 3
        val raw = ByteArray(height * (stride + 1))
        var row = ByteArray(stride)
        var previous = ByteArray(stride)
        val none = ByteArray(stride)
        val sub = ByteArray(stride)
        val up = ByteArray(stride)
        var offset = 0
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                val pixel = argb[base + x]
                row[x * 3] = (pixel shr 16 and 0xFF).toByte()
                row[x * 3 + 1] = (pixel shr 8 and 0xFF).toByte()
                row[x * 3 + 2] = (pixel and 0xFF).toByte()
            }
            var scoreNone = 0L
            var scoreSub = 0L
            var scoreUp = 0L
            for (i in 0 until stride) {
                val value = row[i].toInt() and 0xFF
                none[i] = value.toByte()
                val left = if (i >= 3) row[i - 3].toInt() and 0xFF else 0
                sub[i] = (value - left).toByte()
                val above = previous[i].toInt() and 0xFF
                up[i] = (value - above).toByte()
                scoreNone += Math.abs(value)
                scoreSub += Math.abs(sub[i].toInt())
                scoreUp += Math.abs(up[i].toInt())
            }
            // Ties go to `Up`: a repeated row then costs a run of zeros.
            val chosen = when {
                scoreUp <= scoreNone && scoreUp <= scoreSub -> 2 to up
                scoreSub <= scoreNone -> 1 to sub
                else -> 0 to none
            }
            raw[offset++] = chosen.first.toByte()
            System.arraycopy(chosen.second, 0, raw, offset, stride)
            offset += stride
            // Swap instead of copying: a 720p frame is ~2.6 MB per row buffer.
            val swap = previous
            previous = row
            row = swap
        }
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val buffer = ByteArray(raw.size + 1024)
            var size = 0
            while (!deflater.finished() && size < buffer.size) {
                val written = deflater.deflate(buffer, size, buffer.size - size)
                if (written <= 0) break
                size += written
            }
            return buffer.copyOf(size)
        } finally {
            deflater.end()
        }
    }

    private fun writeChunk(type: String, data: ByteArray) {
        out.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        out.writeInt(crc.value.toInt())
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}

// ---- WebP animation container -------------------------------------------------------------------

/**
 * Assembles an animated WebP around still-image bitstreams.
 *
 * The platform encoder produces a complete still WebP (`RIFF` + `VP8 ` or `VP8L`); the animation
 * container only needs those image chunks wrapped in `ANMF`, so no VP8 encoder is vendored.
 */
internal class WebpAnimationWriter(
    private val file: File,
    private val width: Int,
    private val height: Int,
    private val loop: Boolean
) {
    private val out = RandomAccessFile(file, "rw")
    private var frames = 0
    private var hasAlpha = false

    fun writeHeader() {
        out.setLength(0)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.writeInt(0) // patched in finish()
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        writeChunk("VP8X", vp8x(animated = true, alpha = false))
        writeChunk("ANIM", anim())
    }

    /** [stillImage] is a complete single-frame WebP file as produced by the platform encoder. */
    fun writeFrame(stillImage: ByteArray, delayMs: Int) {
        val chunks = imageChunks(stillImage)
        require(chunks.isNotEmpty()) { "平台编码器没有输出可用的 WebP 位流" }
        if (chunks.any { it.first == "ALPH" }) hasAlpha = true
        val payload = ByteBuffer.allocate(16 + chunks.sumOf { 8 + it.second.size + (it.second.size and 1) })
            .order(ByteOrder.LITTLE_ENDIAN)
        put24(payload, 0) // frame x / 2
        put24(payload, 0) // frame y / 2
        put24(payload, width - 1)
        put24(payload, height - 1)
        put24(payload, delayMs.coerceIn(1, 0xFFFFFF))
        payload.put(0x02) // blend: do not blend (opaque full-canvas frame), dispose: none
        for ((type, data) in chunks) {
            payload.put(type.toByteArray(Charsets.US_ASCII))
            payload.putInt(data.size)
            payload.put(data)
            if (data.size and 1 == 1) payload.put(0)
        }
        writeChunk("ANMF", payload.array())
        frames++
    }

    fun finish() {
        if (hasAlpha) {
            // The alpha flag lives in the VP8X header, written before the first frame was known.
            val pointer = out.filePointer
            out.seek(12 + 8) // RIFF/WEBP header, then VP8X chunk header
            out.write(0x12)
            out.seek(pointer)
        }
        val size = out.length()
        out.seek(4)
        // RIFF sizes are little-endian; RandomAccessFile.writeInt would write big-endian.
        val sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((size - 8).toInt()).array()
        out.write(sizeBytes)
        out.close()
    }

    val frameCount: Int get() = frames

    private fun vp8x(animated: Boolean, alpha: Boolean): ByteArray {
        var flags = 0
        if (alpha) flags = flags or 0x10
        if (animated) flags = flags or 0x02
        val buffer = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(flags.toByte())
        buffer.put(byteArrayOf(0, 0, 0))
        put24(buffer, width - 1)
        put24(buffer, height - 1)
        return buffer.array()
    }

    private fun anim(): ByteArray {
        val buffer = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0) // transparent background
        buffer.putShort((if (loop) 0 else 1).toShort())
        return buffer.array()
    }

    private fun put24(buffer: ByteBuffer, value: Int) {
        buffer.put((value and 0xFF).toByte())
        buffer.put((value shr 8 and 0xFF).toByte())
        buffer.put((value shr 16 and 0xFF).toByte())
    }

    /** Pulls `ALPH` + `VP8 `/`VP8L` out of a still WebP file. */
    private fun imageChunks(stillImage: ByteArray): List<Pair<String, ByteArray>> {
        if (stillImage.size < 16) return emptyList()
        val buffer = ByteBuffer.wrap(stillImage).order(ByteOrder.LITTLE_ENDIAN)
        if (String(stillImage, 0, 4, Charsets.US_ASCII) != "RIFF") return emptyList()
        if (String(stillImage, 8, 4, Charsets.US_ASCII) != "WEBP") return emptyList()
        val result = ArrayList<Pair<String, ByteArray>>()
        var offset = 12
        while (offset + 8 <= stillImage.size) {
            val type = String(stillImage, offset, 4, Charsets.US_ASCII)
            buffer.position(offset + 4)
            val size = buffer.int
            if (size < 0 || offset + 8 + size > stillImage.size) break
            val data = stillImage.copyOfRange(offset + 8, offset + 8 + size)
            if (type == "ALPH") {
                result.add(type to data)
            } else if (type == "VP8 " || type == "VP8L") {
                result.add(type to data)
                return result
            }
            offset += 8 + size + (size and 1)
        }
        // An ALPH chunk without an image chunk cannot be animated; drop the partial match.
        return emptyList()
    }

    private fun writeChunk(type: String, data: ByteArray) {
        out.write(type.toByteArray(Charsets.US_ASCII))
        // WebP is little-endian throughout; RandomAccessFile.writeInt would write big-endian.
        writeIntLe(data.size)
        out.write(data)
        if (data.size and 1 == 1) out.write(0)
    }

    private fun writeIntLe(value: Int) {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        out.write(bytes)
    }
}
