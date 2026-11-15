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

    /** 注册当前活着的服务实例；null 不再清空（见 [unregister]）。 */
    fun register(value: Handler?) { if (value != null) handler = value }

    /**
     * 只清除自己注册的那个实例。系统「先建新实例、后销毁旧实例」时，
     * 旧实例若无条件 register(null) 会把新实例的 handler 清掉，健康检查随即误报 CRASHED。
     */
    fun unregister(value: Handler) { if (handler === value) handler = null }

    fun available() = handler != null
    fun open(id: String, done: (Boolean) -> Unit): Boolean = handler?.open(id, done) ?: false
}
