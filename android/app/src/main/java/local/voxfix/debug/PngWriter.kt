package local.voxfix.debug

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 流式 PNG 写出器（RGBA8，filter 0）。
 *
 * 长截图可能超出单张位图上限（16MP），无法用 `Bitmap.compress` 一次性编码；
 * 这里按行写入：每行 = 1 字节过滤头 + RGBA 原始数据，经 Deflater 压缩后分块写 IDAT。
 * 内存占用只与缓冲区有关，高度无上限。
 */
internal class PngWriter(file: File, private val width: Int, private val height: Long) : Closeable {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
    private val crc = CRC32()
    private val deflateBuffer = ByteArray(1 shl 16)
    private val rowBuffer = ByteArray(width * 4 + 1)
    private var rows = 0L
    private var closed = false

    init {
        require(width > 0 && height > 0) { "PNG 尺寸非法：${width}x$height" }
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val header = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
        header.putInt(width).putInt(height.toInt())
        header.put(8).put(6).put(0).put(0).put(0) // depth 8, RGBA, 无压缩/无滤波/无隔行
        writeChunk("IHDR", header.array())
    }

    /** 写入一整张 ARGB_8888 位图（宽度必须与构造时一致；按 rowBytes 逐行取，容忍行尾对齐填充）。 */
    fun write(bitmap: Bitmap) {
        check(bitmap.width == width) { "PNG 行宽不匹配：${bitmap.width} != $width" }
        val stride = bitmap.rowBytes
        val bytes = stride * bitmap.height
        val data = ByteBuffer.allocateDirect(bytes)
        bitmap.copyPixelsToBuffer(data)
        data.flip()
        val raw = ByteArray(bytes)
        (data.duplicate().order(ByteOrder.LITTLE_ENDIAN) as ByteBuffer).get(raw)
        var offset = 0
        for (y in 0 until bitmap.height) {
            rowBuffer[0] = 0
            System.arraycopy(raw, offset, rowBuffer, 1, width * 4)
            offset += stride
            feedRow()
        }
    }

    private fun feedRow() {
        deflater.setInput(rowBuffer, 0, rowBuffer.size)
        drain()
        rows++
        if (rows > height) throw IllegalStateException("PNG 行数超出声明高度")
    }

    private fun drain() {
        // 不能用 needsInput() 当循环条件：它只反映输入缓冲状态。close() 里 finish() 之后
        // 输入早已耗尽，needsInput() 提前为 true，最后一段压缩输出（含 zlib 终止块）永远
        // 写不进文件——产物结构完整但解码到倒数几行就 unexpected EOF（2026-11-09 真机
        // 回收站样本实测：13933 行只解出 13927.8 行）。改为泵到 deflate 返回 0 为止。
        while (true) {
            val n = deflater.deflate(deflateBuffer)
            if (n <= 0) break
            writeChunk("IDAT", deflateBuffer, n)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        deflater.finish()
        drain()
        deflater.end()
        // 行数必须与声明一致：不一致时抛错而不是写 IEND，避免产出查看器打不开的坏文件。
        check(rows == height) { "PNG 行数不符：写了 $rows 行，声明 $height 行" }
        writeChunk("IEND", ByteArray(0))
        out.flush()
        out.close()
    }

    private fun writeChunk(type: String, data: ByteArray, length: Int = data.size) {
        val head = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        head.putInt(length).put(type.toByteArray(Charsets.US_ASCII))
        out.write(head.array())
        out.write(data, 0, length)
        crc.reset()
        crc.update(head.array(), 4, 4)
        crc.update(data, 0, length)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.value.toInt()).array())
    }
}
