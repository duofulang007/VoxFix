package local.voxfix.debug

/**
 * Bridges the keyboard's image clipboard to the accessibility service, which is the only component
 * that may add a full-screen editing window. The keyboard never builds a window on its own thread.
 */
internal object ScreenshotEditorHost {
    interface Handler {
        /** @return false when no editor can be opened right now; [done] reports the save result. */
        fun open(id: String, done: (Boolean) -> Unit): Boolean
    }
    private var handler: Handler? = null
    fun register(value: Handler?) { handler = value }
    fun available() = handler != null
    fun open(id: String, done: (Boolean) -> Unit): Boolean = handler?.open(id, done) ?: false
}
