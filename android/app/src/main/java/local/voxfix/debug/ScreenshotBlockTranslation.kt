package local.voxfix.debug

/** Keep OCR geometry and the source draft separate from the translated result. */
internal fun translateScreenshotBlocks(
    translator: ScreenshotTranslator,
    blocks: List<ScreenshotTextBlock>,
    pair: TranslatePair,
    current: () -> Boolean,
    result: (List<ScreenshotTextBlock>) -> Unit,
    failure: (String) -> Unit,
    update: (Int,String) -> Unit = { _,_ -> }
) {
    val translated = mutableListOf<ScreenshotTextBlock>()
    fun next(index: Int) {
        if (!current()) return
        if (index == blocks.size) { result(translated.toList()); return }
        val block = blocks[index]
        if (block.text.isBlank()) {
            translated.add(block)
            next(index + 1)
            return
        }
        translator.translate(block.text, pair, { text ->
            if (current()) {
                if (text.isBlank()) failure("翻译结果为空，请重试")
                else { translated.add(block.copy(text = text)); next(index + 1) }
            }
        }, { message -> if (current()) failure(message) },
            { text -> if (current()) update(index,text) })
    }
    next(0)
}
