package local.voxfix.debug

/** Pure text preparation for the translator: joining OCR lines, chunking, prompt and cleanup.
 *  Kept free of Android types so the chunk boundaries can be unit-tested. */
object TranslateRequest {
    /** A token may end halfway through UTF-8; keep it pending instead of displaying �. */
    fun decodePartial(bytes:ByteArray):String {
        val decoder=Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
        val output=java.nio.CharBuffer.allocate(bytes.size)
        decoder.decode(java.nio.ByteBuffer.wrap(bytes),output,false)
        output.flip()
        return output.toString()
    }
    /** Roughly 320 Chinese characters keep one chunk's English output inside a 20–30 s decode on
     *  the K60; larger chunks only lengthen the wait before the first finished paragraph. */
    const val MAX_CHUNK_CHARS = 320

    /** A chunk plus whether it opens a new paragraph in the source, so the joined译文 keeps
     *  paragraph breaks without turning every split into one. */
    data class Chunk(val text: String, val paragraphStart: Boolean)

    private val SPECIAL_TOKENS = listOf(
        "<|im_end|>", "<|im_start|>", "<|endoftext|>", "<s>", "</s>",
        "<｜hy_begin▁of▁sentence｜>", "<｜hy_end▁of▁sentence｜>", "<｜hy_User｜>", "<｜hy_Assistant｜>",
        "<｜hy_EOT｜>"
    )
    private val LEADING_LABELS = listOf("翻译：", "翻译:", "译文：", "译文:", "Translation:", "Translation：")
    private const val SENTENCE_ENDS = "。！？；.!?;"
    private const val BOS = "<｜hy_begin▁of▁sentence｜>"
    private const val USER = "<｜hy_User｜>"
    private const val ASSISTANT = "<｜hy_Assistant｜>"

    fun joinBlocks(blocks: List<ScreenshotTextBlock>): String =
        blocks.map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    /** Hy-MT2 is a decoder-only model with its own chat markers; the CLI and JNI must both see
     *  them verbatim, and the tokenizer must parse them as special tokens (add_special=false). */
    fun buildPrompt(pair: TranslatePair, text: String): String =
        BOS + USER + "将以下文本翻译为${pair.target.promptName}，注意只需要输出翻译后的结果，不要额外解释：\n" + text + ASSISTANT

    /** Splits on newlines first and only then on sentence ends, so a paragraph is never cut
     *  mid-sentence unless it alone exceeds the limit. Surrogate pairs stay intact. */
    fun chunk(text: String, limit: Int = MAX_CHUNK_CHARS): List<String> =
        chunkParts(text, limit).map { it.text }

    fun chunkParts(text: String, limit: Int = MAX_CHUNK_CHARS): List<Chunk> {
        require(limit >= 2) { "limit too small" }
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return emptyList()
        val parts = mutableListOf<Chunk>()
        val current = StringBuilder()
        var startsParagraph = true

        fun flush(nextStartsParagraph: Boolean) {
            val value = current.toString().trim()
            if (value.isNotEmpty()) parts.add(Chunk(value, startsParagraph))
            current.setLength(0)
            startsParagraph = nextStartsParagraph
        }

        fun appendLine(value: String) {
            if (current.isNotEmpty()) current.append('\n')
            current.append(value)
        }

        for (line in normalized.split('\n')) {
            // A blank line is the only real paragraph break; a lone newline is just a wrapped line.
            if (line.isBlank()) {
                if (current.isNotEmpty()) flush(true)
                continue
            }
            if (current.isNotEmpty() && current.length + 1 + line.length > limit) flush(false)
            if (line.length <= limit) {
                appendLine(line)
                continue
            }
            var start = 0
            while (start < line.length) {
                val remaining = line.length - start
                if (remaining <= limit) {
                    appendLine(line.substring(start))
                    break
                }
                var end = start + limit
                val sentence = findSentenceEnd(line, start, end)
                if (sentence > start) end = sentence
                if (end > start + 1 && line[end - 1].isHighSurrogate()) end -= 1
                // A cut inside one line continues the same paragraph, so the next part must not
                // be joined with a paragraph break.
                if (current.isNotEmpty()) flush(false)
                val piece = line.substring(start, end).trim()
                if (piece.isNotEmpty()) parts.add(Chunk(piece, startsParagraph))
                startsParagraph = false
                start = end
                while (start < line.length && line[start].isWhitespace()) start++
            }
        }
        flush(false)
        return parts
    }

    /** The native deadline covers tokenize + prefill + decode, so one fixed budget cannot fit both
     *  a sentence and a full page: a 320-character Chinese chunk spends ~4 s on prefill and 20–30 s
     *  decoding on a warm K60. Scale with the work, but keep a hard cap so a degenerate repeat
     *  cannot hang the overlay. */
    fun chunkTimeoutMs(chars: Int): Long = (25_000L + chars * 150L).coerceIn(45_000L, 120_000L)

    /** Stitches translated fragments without inventing paragraph breaks or dropping the space
     *  Latin targets need between sentences. */
    fun joinTranslated(previous: String, next: String, pair: TranslatePair, paragraphStart: Boolean): String {
        if (previous.isEmpty()) return next
        if (next.isEmpty()) return previous
        val target = pair.normalized().target
        val separator = when {
            paragraphStart -> "\n"
            target == TranslateLanguage.ZH || target == TranslateLanguage.JA || target == TranslateLanguage.KO -> ""
            previous.last().isWhitespace() || next.first().isWhitespace() -> ""
            else -> " "
        }
        return previous.trimEnd() + separator + next.trimStart()
    }

    private fun findSentenceEnd(value: String, start: Int, preferred: Int): Int {
        for (index in preferred - 1 downTo maxOf(start + 1, preferred - 120)) {
            if (SENTENCE_ENDS.indexOf(value[index]) >= 0) return index + 1
        }
        return preferred
    }

    fun sanitize(output: String): String {
        var value = output
        for (token in SPECIAL_TOKENS) value = value.replace(token, "")
        value = value.trim()
        for (label in LEADING_LABELS) {
            if (value.startsWith(label)) {
                value = value.removePrefix(label).trim()
                break
            }
        }
        return value.trim()
    }
}
