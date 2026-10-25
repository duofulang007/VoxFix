package local.voxfix.debug

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer

/** Greedy CTC over the production int8 graph with optional trie biasing. */
internal interface DesktopCtcSession : AutoCloseable {
    val pieces: HotwordPieces
    fun greedy(samples: FloatArray, trie: HotwordTrie?, boost: Float = DesktopHotwords.BOOST): String
}

/**
 * The desktop `hotwords.Decoder` on Android. Everything here mirrors the pinned feature
 * contract in DesktopVoiceFbank; the session is created by the caller only when a rescue
 * is actually needed, because it costs a second copy of the graph in memory.
 */
internal class OrtCtcDecoder(model: String, tokens: String) : DesktopCtcSession {
    private val environment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(4)
        setInterOpNumThreads(1)
        setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
        // ALL fuses kernels and shifts low-margin frames; the desktop prototype pinned BASIC.
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
    }
    private val session = environment.createSession(model, options)
    private val metadata = session.metadata.customMetadata
    private val windowSize = metadata["lfr_window_size"]?.toIntOrNull() ?: 7
    private val windowShift = metadata["lfr_window_shift"]?.toIntOrNull() ?: 6
    private val mean = vector(metadata["neg_mean"])
    private val std = vector(metadata["inv_stddev"])
    private val language = metadata["lang_auto"]?.toIntOrNull() ?: 0
    private val textNorm = metadata["without_itn"]?.toIntOrNull() ?: 15
    private val scale = if (metadata["normalize_samples"] == "1") 1f else 32768f
    override val pieces = HotwordPieces(tokens)
    private val stride = (metadata["vocab_size"]?.toIntOrNull() ?: pieces.strings.size)
    private var closed = false

    init {
        require(mean.size == DesktopVoiceFbank.NUM_BINS * windowSize && std.size == mean.size) { "R5 metadata does not match the fbank front end" }
    }

    private fun vector(value: String?): FloatArray =
        value?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }?.toFloatArray() ?: FloatArray(0)

    override fun greedy(samples: FloatArray, trie: HotwordTrie?, boost: Float): String {
        check(!closed) { "热词会话已释放" }
        val stacked = DesktopVoiceFbank.stack(DesktopVoiceFbank.frames(samples, scale.toDouble()), windowSize, windowShift, mean, std)
        if (stacked.isEmpty()) return ""
        val frames = stacked.size
        val width = stacked[0].size
        val buffer = FloatBuffer.allocate(frames * width)
        for (row in stacked) buffer.put(row)
        buffer.rewind()
        val ids = ArrayList<Int>(64)
        OnnxTensor.createTensor(environment, buffer, longArrayOf(1, frames.toLong(), width.toLong())).use { x ->
            OnnxTensor.createTensor(environment, IntBuffer.wrap(intArrayOf(frames)), longArrayOf(1)).use { length ->
                OnnxTensor.createTensor(environment, IntBuffer.wrap(intArrayOf(language)), longArrayOf(1)).use { languageTensor ->
                    OnnxTensor.createTensor(environment, IntBuffer.wrap(intArrayOf(textNorm)), longArrayOf(1)).use { normTensor ->
                        val inputs = mapOf("x" to x, "x_length" to length, "language" to languageTensor, "text_norm" to normTensor)
                        session.run(inputs).use { result ->
                            val logits = (result.get(0) as OnnxTensor).floatBuffer
                            var active = if (trie != null) listOf(trie) else emptyList()
                            var previous = -1
                            for (frame in 0 until frames) {
                                val base = frame * stride
                                val token = if (frame < DesktopHotwords.TAG_TOKENS || active.isEmpty()) argmax(logits, base)
                                else biased(logits, base, active, boost)
                                if (token != 0 && token != previous) {
                                    ids.add(token)
                                    if (trie != null) {
                                        val following = ArrayList<HotwordTrie>(active.size + 1)
                                        for (node in active) node.children[token]?.let { following.add(it) }
                                        trie.children[token]?.let { if (it !in following) following.add(it) }
                                        active = following
                                    }
                                }
                                previous = token
                            }
                        }
                    }
                }
            }
        }
        return ids.drop(DesktopHotwords.TAG_TOKENS).joinToString("") { pieces.strings[it] ?: "" }
    }

    private fun argmax(logits: FloatBuffer, base: Int): Int {
        var best = 0
        var score = logits.get(base)
        for (token in 1 until stride) {
            val value = logits.get(base + token)
            if (value > score) { score = value; best = token }
        }
        return best
    }

    /** Same bonus the desktop decoder applies: every active prefix bonus for its children. */
    private fun biased(logits: FloatBuffer, base: Int, active: List<HotwordTrie>, boost: Float): Int {
        var best = 0
        var score = logits.get(base)
        for (token in 1 until stride) {
            val value = logits.get(base + token)
            if (value > score) { score = value; best = token }
        }
        val bonus = HashMap<Int, Int>()
        for (node in active) for (token in node.children.keys) bonus[token] = (bonus[token] ?: 0) + 1
        for ((token, count) in bonus) {
            val value = logits.get(base + token) + boost * count
            if (value > score) { score = value; best = token }
        }
        return best
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { session.close() }
        runCatching { options.close() }
    }
}

/** Gives the rescue its own copy of the R5 graph: an APK asset offset is not a file path. */
internal object DesktopCtcModel {
    const val ASSET = "sensevoice/model.int8.onnx"
    const val BYTES = 239234182L

    fun open(assets: AssetManager, cacheDir: File, tokens: String): DesktopCtcSession {
        val model = copy(assets, cacheDir)
        val started = System.nanoTime()
        val session = OrtCtcDecoder(model.absolutePath, tokens)
        Log.i("VoxFix", "HOTWORD_SESSION_READY ms=${(System.nanoTime() - started) / 1_000_000}")
        return session
    }

    fun tokens(assets: AssetManager): String = assets.open("sensevoice/tokens.txt").use {
        it.readBytes().toString(Charsets.UTF_8)
    }

    /** One copy in the app cache (about 228 MiB); a rescue only ever happens after this. */
    private fun copy(assets: AssetManager, cacheDir: File): File {
        val folder = File(cacheDir, "voice-rescue").apply { mkdirs() }
        val file = File(folder, "model.int8.onnx")
        if (file.isFile && file.length() == BYTES) return file
        val started = System.nanoTime()
        val temp = File(folder, "model.tmp")
        assets.open(ASSET).use { input -> temp.outputStream().use { output -> input.copyTo(output, 1 shl 16) } }
        check(temp.length() == BYTES) { "R5 asset size changed" }
        check(temp.renameTo(file)) { "无法保存热词模型副本" }
        Log.i("VoxFix", "HOTWORD_MODEL_COPIED ms=${(System.nanoTime() - started) / 1_000_000}")
        return file
    }
}
