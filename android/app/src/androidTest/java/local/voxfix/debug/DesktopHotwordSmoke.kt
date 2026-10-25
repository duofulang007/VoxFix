package local.voxfix.debug

import android.content.Context
import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Device checks for the dictionary module and the CTC rescue.
 *
 * Expected transcripts come from the desktop reference run of `hotwords.py` over the same
 * clips (R5 int8, no dictionary, no ITN): `mix-huihui-5` reads "这个 API返回的是 J SON 数据。"
 * plainly and spells "JSON" once the trie biases it.
 */
internal object DesktopHotwordSmoke {
    private const val SPACED = "这个 API返回的是 J SON 数据。"
    private val words = listOf("Python", "API", "JSON", "PDF", "WiFi", "Codex", "VoxFix",
        "Kotlin", "Java", "git status", "git commit", "sol", "GitHub", "APP")

    fun run(context: Context, testAssets: AssetManager): String {
        var checks = 0
        var timing = ""

        // 1. The bundled dictionary must stay the desktop file, not a paraphrase of it.
        val bundled = VoiceDictionary.parse(context.assets.open(VoiceDictionaryStore.ASSET).use { it.readBytes().toString(Charsets.UTF_8) })
        check(bundled.entries.size == 14) { "bundled words ${bundled.entries.size}" }
        check(bundled.entries.sumOf { it.variants.size } == 60) { "bundled variants ${bundled.entries.sumOf { it.variants.size }}" }
        check(bundled.entries.none { it.context.isNotEmpty() }) { "context zh must stay removed" }
        // 2. Serialisation round-trips byte for byte, so the desktop can read it back.
        val text = bundled.serialize()
        check(VoiceDictionary.parse(text).serialize() == text) { "round-trip changed the file" }
        check(text.contains("\"hotwords\": [") && text.contains("\"to\": \"JSON\"")) { "unexpected format" }
        // 3. Paste cleaning follows the desktop editor.
        check(VoiceDictionary.cleanVariants("col death。koex。KOEX,cols；kos|koldex", true) ==
            listOf("col death", "koex", "cols", "kos", "koldex")) { "split/dedupe" }
        check(VoiceDictionary.cleanVariants("C++。.NET。node.js。don't。git　 status", true) ==
            listOf("C++", ".NET", "node.js", "don't", "git status")) { "symbols / fullwidth space" }
        check(VoiceDictionary.cleanVariants("\"a,b\"；「x|y」；(a,b)；foo!bar?", true) ==
            listOf("a,b", "x|y", "(a,b)", "foo!bar?")) { "quoted separators" }
        check(VoiceDictionary.cleanVariants("a,b\r\nA,B\r\n(a,b)", false).size == 2) { "save never re-splits" }
        // 4. Conflicts are refused, rules keep whole word edges and longer variants first.
        val conflicted = VoiceDictionary.parse("{\"hotwords\":[\"API\",\"iOS\"],\"rules\":[{\"to\":\"API\",\"from\":[\"a b\"]},{\"to\":\"iOS\",\"from\":[\"a b\"]}]}")
        check(conflicted.conflicts() != null) { "duplicate variant accepted" }
        val dictionary = DesktopVoiceDictionary.parse(text)
        check(dictionary.correct("把 Jason 的配置发我") == "把 JSON 的配置发我")
        check(dictionary.correct("请把代码提交到 get up") == "请把代码提交到 GitHub")
        check(dictionary.correct("forget upper get up") == "forget upper GitHub")
        check(dictionary.correct("我今天用了 sov 这个词") == "我今天用了 sov 这个词")
        checks++

        // 5. The rescue pool is the dictionary's own words, capped like the desktop.
        check(dictionary.hotwords.size == 14 && dictionary.hotwords.contains("VoxFix")) { "pool ${dictionary.hotwords}" }
        check(VoiceDictionary.HOTWORD_LIMIT == 64)
        checks++

        // 6. Greedy CTC on the device must reproduce the desktop feature contract.
        val openedAt = System.nanoTime()
        val session = DesktopCtcModel.open(context.assets, context.cacheDir, DesktopCtcModel.tokens(context.assets))
        val openMs = (System.nanoTime() - openedAt) / 1_000_000
        try {
            val clip = read(testAssets, "mix-huihui-5.wav")
            check(DesktopVoiceFbank.frameCount(clip.size) == 548) { "frame count ${DesktopVoiceFbank.frameCount(clip.size)}" }
            val decodedAt = System.nanoTime()
            val plain = session.greedy(clip, null)
            val plainMs = (System.nanoTime() - decodedAt) / 1_000_000
            // int8 kernels differ slightly per CPU, so compare characters, not tokens.
            val ratio = 1.0 - DesktopHotwords.editDistance(plain, SPACED, SPACED.length) / maxOf(plain.length, SPACED.length).toDouble()
            check(ratio >= 0.85) { "plain decode differs from the desktop reference (${"%.2f".format(ratio)}): '$plain'" }
            val trie = DesktopHotwords.buildTrie(words, session.pieces.pieces)
            val biasedAt = System.nanoTime()
            val biased = session.greedy(clip, trie)
            val biasedMs = (System.nanoTime() - biasedAt) / 1_000_000
            check(biased.contains("JSON")) { "bias did not spell JSON: '$biased'" }
            check(DesktopHotwords.accept(plain, biased, words).contains("JSON")) { "guard rejected the rescue: '$biased'" }
            val short = read(testAssets, "word-15.wav")
            val shortPlain = session.greedy(short, null)
            check(shortPlain.replace(" ", "").uppercase().startsWith("AP")) { "short decode mismatch: '$shortPlain'" }
            checks++
            timing = "openMs=$openMs plainMs=$plainMs biasedMs=$biasedMs"
        } finally { session.close() }
        return "HOTWORD_PASS checks=$checks dictionary=14/60 file-roundtrip paste-clean conflicts ctc=plain/bias/guard $timing"
    }

    /**
     * Seeds the real dictionary file once and proves the seed left the pinyin store and the
     * archived voice rows untouched. Run after installing; the editor opens the same file.
     */
    fun migrate(context: Context): String {
        PersonalLexicon(context).use { store ->
            val entries = store.count()
            val manual = store.archivedManualWords()
            val archive = store.archivedVoiceCounts()
            val file = VoiceDictionaryStore.ensure(context, store)
            check(store.count() == entries) { "pinyin rows changed: $entries -> ${store.count()}" }
            check(store.archivedManualWords() == manual) { "archived voice rows changed" }
            check(store.archivedVoiceCounts() == archive) { "archived voice rows changed" }
            val seeded = VoiceDictionary.parse(file.readText(Charsets.UTF_8))
            check(seeded.entries.size >= 14) { "seed lost words: ${seeded.entries.size}" }
            check(seeded.entries.any { it.word == "JSON" }) { "seed missing JSON" }
            manual.forEach { word -> check(seeded.indexOf(word) >= 0) { "manual word $word not carried over" } }
            val before = file.readText(Charsets.UTF_8)
            VoiceDictionaryStore.ensure(context, store)
            check(file.readText(Charsets.UTF_8) == before) { "second seed rewrote the file" }
            return "HOTWORD_MIGRATE_PASS entries=$entries archivedWords=${archive.first} archivedRules=${archive.second} manual=${manual.size} " +
                "dictionaryWords=${seeded.entries.size} rules=${seeded.ruleCount()} file=${file.name}"
        }
    }

    private fun read(assets: AssetManager, name: String): FloatArray {
        val bytes = assets.open(name).use { it.readBytes() }
        var offset = 12
        var data = 44
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") { data = offset + 8; break }
            offset += 8 + size + (size and 1)
        }
        val pcm = ByteBuffer.wrap(bytes, data, bytes.size - data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(pcm.remaining()) { pcm.get(it) / 32768f }
    }
}
