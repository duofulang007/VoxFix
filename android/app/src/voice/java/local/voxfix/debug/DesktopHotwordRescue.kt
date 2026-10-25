package local.voxfix.debug

/**
 * Lazy rescue: no session, no extra memory and no decode until the sherpa text
 * actually looks like a near-miss of a user word. Ported from desktop
 * `HotwordRescue`, including the idle release that keeps a second graph from
 * staying resident for the whole session.
 */
internal class DesktopHotwordRescue(
    private val factory: () -> DesktopCtcSession,
    private val idleMillis: Long = IDLE_MILLIS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {}
) {
    internal class Stats { var attempts = 0; var rescued = 0; var failed = 0 }

    val stats = Stats()
    private val lock = Any()
    private var decoder: DesktopCtcSession? = null
    private var trie: HotwordTrie? = null
    private var words: List<String> = emptyList()
    private var timer: Thread? = null
    @Volatile private var usedAt = 0L
    /** Set when the extra session cannot even be created, so we do not retry on every sentence. */
    @Volatile private var broken = false

    /** Returns the sherpa `text`, or the biased transcript when it spells more user words. */
    fun rescore(samples: FloatArray, text: String, candidates: List<String>): String {
        val list = candidates.filter { it.isNotBlank() }.distinct()
        if (broken || list.isEmpty() || text.isEmpty()) return text
        if (DesktopHotwords.nearMiss(text, list).isEmpty()) return text
        stats.attempts++
        val biased = try {
            synchronized(lock) {
                if (samples.all { it == 0f }) return text
                val session = decoder ?: factory().also { decoder = it }
                if (trie == null || words != list) {
                    trie = DesktopHotwords.buildTrie(list, session.pieces.pieces)
                    words = list
                }
                session.greedy(samples, trie)
            }
        } catch (error: Throwable) {
            // Any failure keeps the sherpa text; a broken rescue must never lose words.
            stats.failed++
            if (decoder == null) broken = true
            log("HOTWORD_RESCUE_FAILED ${error.javaClass.simpleName} ${error.message}")
            release()
            return text
        }
        scheduleRelease()
        val chosen = DesktopHotwords.accept(text, biased, list)
        if (chosen != text) stats.rescued++
        return chosen
    }

    val active: Boolean get() = synchronized(lock) { decoder != null }
    internal val session: DesktopCtcSession? get() = synchronized(lock) { decoder }

    private fun scheduleRelease() {
        usedAt = clock()
        if (timer?.isAlive == true) return
        timer = Thread({
            while (true) {
                Thread.sleep(500)
                if (clock() - usedAt < idleMillis) continue
                release()
                return@Thread
            }
        }, "VoxFix-hotword-idle").apply { isDaemon = true; start() }
    }

    fun release() {
        synchronized(lock) {
            timer = null
            val session = decoder ?: return
            decoder = null; trie = null; words = emptyList()
            runCatching { session.close() }
            log("HOTWORD_RESCUE_RELEASED attempts=${stats.attempts} rescued=${stats.rescued} failed=${stats.failed}")
        }
    }

    companion object { const val IDLE_MILLIS = 180_000L }
}
