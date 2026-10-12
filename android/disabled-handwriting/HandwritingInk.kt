// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Feelime v1.3.7; see vendor/handwriting/UPSTREAM.md.
package local.voxfix.debug
import kotlin.math.*
data class InkCandidate(val text: String, val score: Float)
object HandwritingInk {
    data class InkPoint(val x: Float, val y: Float)
    data class InkRequest(
        val width: Float,
        val height: Float,
        val strokes: List<List<InkPoint>>,
    )

    /** payload 解析（§3）：{"w":..,"h":..,"strokes":[[[x,y],..],..]}。
     * 结构非法返回 null（native 侧防御；JS 侧已先行校验）。w/h 只承载
     * 书写区尺寸——渲染按内容 bbox 紧致化（§4.1），不参与几何。 */
    fun parseInkRequest(json: String): InkRequest? = runCatching {
        val root = org.json.JSONObject(json)
        val width = root.optDouble("w").takeIf { it.isFinite() }?.toFloat() ?: return null
        val height = root.optDouble("h").takeIf { it.isFinite() }?.toFloat() ?: return null
        if (width <= 0f || height <= 0f || width > 4096f || height > 4096f) return null
        val rawStrokes = root.optJSONArray("strokes") ?: return null
        if (rawStrokes.length() !in 1..MAX_STROKES) return null
        val strokes = (0 until rawStrokes.length()).map { strokeIndex ->
            val rawPoints = rawStrokes.optJSONArray(strokeIndex) ?: return null
            if (rawPoints.length() !in 1..MAX_POINTS_PER_STROKE) return null
            (0 until rawPoints.length()).map { pointIndex ->
                val rawPoint = rawPoints.optJSONArray(pointIndex) ?: return null
                if (rawPoint.length() != 2) return null
                val x = rawPoint.optDouble(0).toFloat()
                val y = rawPoint.optDouble(1).toFloat()
                if (!x.isFinite() || !y.isFinite() || x !in 0f..width || y !in 0f..height) return null
                InkPoint(x, y)
            }
        }
        if (strokes.sumOf { it.size } > 2000) return null
        InkRequest(width, height, strokes)
    }.getOrNull()

    /** §4.1：内容 bbox → 画布等比映射（长边撑满、短边居中）。 */
    data class InkTransform(val scale: Float, val offsetX: Float, val offsetY: Float)

    fun inkTransform(strokes: List<List<InkPoint>>, size: Float): InkTransform {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        strokes.forEach { stroke -> stroke.forEach { point ->
            minX = min(minX, point.x)
            minY = min(minY, point.y)
            maxX = max(maxX, point.x)
            maxY = max(maxY, point.y)
        } }
        if (minX > maxX || minY > maxY) return InkTransform(1f, 0f, 0f)
        val spanX = max(maxX - minX, 1f)
        val spanY = max(maxY - minY, 1f)
        val scale = size / max(spanX, spanY)
        return InkTransform(
            scale = scale,
            offsetX = (size - spanX * scale) / 2f - minX * scale,
            offsetY = (size - spanY * scale) / 2f - minY * scale,
        )
    }

    /** 裁剪产物：像素（行主序）+ 自身宽高（预处理缩放要用）。 */
    data class CroppedImage(val pixels: IntArray, val width: Int, val height: Int)

    /** §4.2 步骤 1：内容 bbox（pixel<200），四边外扩 10%（每边至少 4px），
     * 裁剪到图像边界。无内容（全白）返回空像素。 */
    fun cropToContent(
        pixels: IntArray,
        width: Int,
        height: Int,
        threshold: Int = HandwritingEngine.BBOX_THRESHOLD,
    ): CroppedImage {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if ((pixels[row + x] and 0xFF) >= threshold) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return CroppedImage(IntArray(0), 0, 0)
        val padX = max((maxX - minX + 1) * HandwritingEngine.BBOX_PAD_RATIO,
            HandwritingEngine.BBOX_PAD_MIN_PX.toFloat()).toInt()
        val padY = max((maxY - minY + 1) * HandwritingEngine.BBOX_PAD_RATIO,
            HandwritingEngine.BBOX_PAD_MIN_PX.toFloat()).toInt()
        val left = max(0, minX - padX)
        val top = max(0, minY - padY)
        val right = min(width - 1, maxX + padX)
        val bottom = min(height - 1, maxY + padY)
        val cropWidth = right - left + 1
        val cropHeight = bottom - top + 1
        return CroppedImage(
            IntArray(cropWidth * cropHeight) { index ->
                pixels[(top + index / cropWidth) * width + left + index % cropWidth]
            },
            cropWidth,
            cropHeight,
        )
    }

    /** §4.2（v2）：内容 bbox 裁剪（复用 [cropToContent]）→ pad 成正方形
     * （白）→ 双线性缩到 [HandwritingEngine.MELNYK_INNER]×88 → 四边
     * [HandwritingEngine.MELNYK_MARGIN]px 白边 → 反色 → uint8 NHWC
     * (96,96,1)。反色后笔画=高值（训练口径 preprocess_bitmap：255-bitmap），
     * 值域保持 0-255 uint8（BN 吸收尺度，不归一）。
     * 空内容返回空数组（调用方按无识别处理）。 */
    fun preprocessMelnyk(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): ByteArray {
        val crop = cropToContent(pixels, width, height)
        if (crop.pixels.isEmpty() || crop.width <= 0 || crop.height <= 0) return ByteArray(0)
        // 二值化（otsu，resize 前）：训练数据（CASIA）是扫描位图，笔画边缘
        // 干净；Android Canvas 抗锯齿的灰边若带进 resize 会让有效线宽变糊，
        // 真机实测「就」因此 rank4，硬边重渲染 rank1（issue #28 真迹采集
        // 2026-09-22）。阈值化在缩放前做，细节先落定再缩。
        val binarized = otsuBinarize(crop.pixels)
        // pad 成正方形（短边两侧补白）
        val pad = abs(crop.width - crop.height) / 2
        val squareSize = max(crop.width, crop.height)
        val square = IntArray(squareSize * squareSize) { 0xFFFFFFFF.toInt() }
        for (y in 0 until crop.height) {
            val targetY = y + (if (crop.height < crop.width) pad else 0)
            for (x in 0 until crop.width) {
                val targetX = x + (if (crop.width < crop.height) pad else 0)
                square[targetY * squareSize + targetX] = binarized[y * crop.width + x]
            }
        }
        val inner = HandwritingEngine.MELNYK_INNER
        val resized = resizeBilinear(square, squareSize, squareSize, inner, inner)
        val size = HandwritingEngine.MELNYK_SIZE
        val margin = HandwritingEngine.MELNYK_MARGIN
        val output = ByteArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val inside = x in margin until margin + inner && y in margin until margin + inner
                // 反色：白(255)→0，笔画(黑→反成高值)
                val value = if (inside) {
                    255 - ((resized[(y - margin) * inner + (x - margin)] and 0xFF))
                } else 0
                output[y * size + x] = value.toByte()
            }
        }
        return output
    }

    /** otsu 二值化（256 级直方图，类间方差最大阈值），输出纯黑/纯白
     * ARGB。空输入返回原数组。 */
    fun otsuBinarize(pixels: IntArray): IntArray {
        if (pixels.isEmpty()) return pixels
        val histogram = IntArray(256)
        for (pixel in pixels) histogram[pixel and 0xFF]++
        val total = pixels.size
        var sumAll = 0.0
        for (t in 0 until 256) sumAll += t.toDouble() * histogram[t]
        var weightBg = 0.0
        var sumBg = 0.0
        var bestThreshold = 127
        var bestVariance = -1.0
        for (t in 0 until 256) {
            weightBg += histogram[t]
            if (weightBg == 0.0) continue
            val weightFg = total - weightBg
            if (weightFg == 0.0) break
            sumBg += t.toDouble() * histogram[t]
            val meanBg = sumBg / weightBg
            val meanFg = (sumAll - sumBg) / weightFg
            val variance = weightBg * weightFg * (meanBg - meanFg) * (meanBg - meanFg)
            if (variance > bestVariance) {
                bestVariance = variance
                bestThreshold = t
            }
        }
        return IntArray(pixels.size) { index ->
            if ((pixels[index] and 0xFF) <= bestThreshold) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }

    /** §4.3（v2）：softmax 输出直排 top-[limit]。概率已是模型归一值，
     * score 原样输出（不再二次归一）；下标 = 词表下标。 */
    fun rankSoftmax(
        probs: FloatArray,
        vocabulary: List<String>,
        limit: Int,
    ): List<InkCandidate> {
        val order = Array(probs.size) { it }
        order.sortByDescending { probs[it] }
        return order.take(limit.coerceAtMost(probs.size))
            .mapNotNull { index ->
                vocabulary.getOrNull(index)?.takeIf { it.isNotEmpty() }
                    ?.let { InkCandidate(it, probs[index]) }
            }
    }

    /** 双线性缩放（cv2 INTER_LINEAR 同构：目标像素中心对齐）。 */
    fun resizeBilinear(
        pixels: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): IntArray {
        if (sourceWidth <= 0 || sourceHeight <= 0) return IntArray(0)
        val output = IntArray(targetWidth * targetHeight)
        val scaleX = sourceWidth.toDouble() / targetWidth
        val scaleY = sourceHeight.toDouble() / targetHeight
        for (y in 0 until targetHeight) {
            val sourceY = (y + 0.5) * scaleY - 0.5
            val y0 = sourceY.toInt().coerceIn(0, sourceHeight - 1)
            val y1 = (y0 + 1).coerceAtMost(sourceHeight - 1)
            val fy = (sourceY - y0).coerceIn(0.0, 1.0)
            for (x in 0 until targetWidth) {
                val sourceX = (x + 0.5) * scaleX - 0.5
                val x0 = sourceX.toInt().coerceIn(0, sourceWidth - 1)
                val x1 = (x0 + 1).coerceAtMost(sourceWidth - 1)
                val fx = (sourceX - x0).coerceIn(0.0, 1.0)
                val topLeft = pixels[y0 * sourceWidth + x0]
                val topRight = pixels[y0 * sourceWidth + x1]
                val bottomLeft = pixels[y1 * sourceWidth + x0]
                val bottomRight = pixels[y1 * sourceWidth + x1]
                var blended = 0xFF shl 24
                for (channel in 0 until 3) {
                    val shift = 16 - 8 * channel
                    val value = ((topLeft shr shift) and 0xFF) * (1 - fx) * (1 - fy) +
                        ((topRight shr shift) and 0xFF) * fx * (1 - fy) +
                        ((bottomLeft shr shift) and 0xFF) * (1 - fx) * fy +
                        ((bottomRight shr shift) and 0xFF) * fx * fy
                    blended += (round(value).toInt() and 0xFF) shl shift
                }
                output[y * targetWidth + x] = blended
            }
        }
        return output
    }

    private const val MAX_STROKES = 64
    private const val MAX_POINTS_PER_STROKE = 512
}
