// SPDX-License-Identifier: GPL-3.0-only
// Rendering and preprocessing parameters adapted from the pinned Feelime source.
package local.voxfix.debug

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.json.JSONArray
import java.nio.ByteBuffer
import java.security.MessageDigest

class HandwritingEngine(context: Context) {
    private val environment = OrtEnvironment.getEnvironment()
    private val vocabulary: List<String>
    private val session: OrtSession
    init {
        fun verified(name: String, digest: String): ByteArray {
            val bytes = context.assets.open("handwriting/$name").use { it.readBytes() }
            check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == digest) { "手写资源校验失败" }
            return bytes
        }
        val vocab = JSONArray(verified("vocab.json", "83863afd12846c337c8880348f1a7e275450be87ba04cf17445cca3ffd0939bd").toString(Charsets.UTF_8))
        vocabulary = List(vocab.length()) { vocab.getString(it) }
        check(vocabulary.size == 3755 && vocabulary.all { it.length == 1 })
        val model = verified("model.onnx", "04bf65859482f2e9f4836fb872c5a881940a8f6f81bd0994542187617793e932")
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            environment.createSession(model, options)
        }
    }

    fun recognize(payload: String): List<String> {
        require(payload.length <= 100000) { "笔画过多" }
        val request = requireNotNull(HandwritingInk.parseInkRequest(payload)) { "笔画坐标无效" }
        val transform = HandwritingInk.inkTransform(request.strokes, SIZE_PX.toFloat())
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(SIZE_PX * SIZE_PX)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                strokeWidth = SIZE_PX * .043f
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE
            }
            for (stroke in request.strokes) {
                if (stroke.size == 1) canvas.drawPoint(stroke[0].x * transform.scale + transform.offsetX, stroke[0].y * transform.scale + transform.offsetY, paint)
                else {
                    val path = Path()
                    stroke.forEachIndexed { index, point ->
                        val x = point.x * transform.scale + transform.offsetX
                        val y = point.y * transform.scale + transform.offsetY
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    canvas.drawPath(path, paint)
                }
            }
            bitmap.getPixels(pixels, 0, SIZE_PX, 0, 0, SIZE_PX, SIZE_PX)
        } finally { bitmap.recycle() }
        val input = HandwritingInk.preprocessMelnyk(pixels, SIZE_PX, SIZE_PX)
        if (input.isEmpty()) return emptyList()
        OnnxTensor.createTensor(environment, ByteBuffer.wrap(input), longArrayOf(1, 96, 96, 1), OnnxJavaType.UINT8).use { tensor ->
            session.run(mapOf(session.inputNames.single() to tensor)).use { output ->
                val buffer = (output[0] as OnnxTensor).floatBuffer
                val probabilities = FloatArray(buffer.remaining()).also { buffer.get(it) }
                check(probabilities.size == vocabulary.size && probabilities.all { it.isFinite() }) { "手写输出无效" }
                return HandwritingInk.rankSoftmax(probabilities, vocabulary, 9).map { it.text }
            }
        }
    }
    fun release() = session.close()
    companion object {
        const val SIZE_PX = 256
        const val BBOX_THRESHOLD = 200
        const val BBOX_PAD_RATIO = .10f
        const val BBOX_PAD_MIN_PX = 4
        const val MELNYK_SIZE = 96
        const val MELNYK_INNER = 88
        const val MELNYK_MARGIN = 4
    }
}
