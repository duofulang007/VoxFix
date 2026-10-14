package local.voxfix.debug

// 只把「同一次收起键盘 → 再呼出键盘」之间宿主给出的选区带到键盘打开之后。
//
// 端点不能当硬条件：宿主在 show 的过程中经常把选区收成光标、或干脆报不出端点（-1），
// 旧版要求端点完全相等，于是"长按选中多字却打不开编辑框"。现在改成：
// 同宿主 + 有效期之内就核销一次；宿主现在报得出有效非空选区就按现状用（同端点时保留请求里的方向），
// 报不出才退回请求里那份端点。窗口足够短，"换输入框"由 onFinishInputView/onFinishInput 的 cancel 兜住。
internal class HostSelectionRequest(private val windowMs: Long = 3000L) {
    private var owner: String? = null
    private var range: Pair<Int, Int>? = null
    private var stamp = 0L

    fun request(owner: String, start: Int, end: Int) = request(owner, start, end, System.currentTimeMillis())

    fun request(owner: String, start: Int, end: Int, now: Long) {
        // 空选区（start==end 的纯光标）不是「选中文字」，不该建立呼出编辑框的请求——
        // 密码框点格子/删字符只会移动光标，若这里照单全收就会每点一下弹一次键盘。
        if (start < 0 || end < 0 || start == end) { cancel(); return }
        this.owner = owner; range = start to end; stamp = now
    }

    /**
     * 键盘收起时宿主长按出新选区，部分宿主不再回调 onUpdateSelection，只把选区挂在 onStartInput 上。
     * 那种情况没有「旧值→新值」可比，所以只认有效非空选区：空光标和无效端点都不该弹编辑框。
     */
    fun fromInitial(owner: String, start: Int, end: Int) = fromInitial(owner, start, end, System.currentTimeMillis())

    fun fromInitial(owner: String, start: Int, end: Int, now: Long) {
        if (start < 0 || end < 0 || start == end) return
        request(owner, start, end, now)
    }

    fun cancel() { owner = null; range = null; stamp = 0L }

    fun pending(owner: String, now: Long = System.currentTimeMillis()) =
        this.owner == owner && range != null && now - stamp <= windowMs

    /** 有没有"某个宿主在收起键盘时选了字"留下的有效请求（换过包名也算）。 */
    fun armed(now: Long = System.currentTimeMillis()) = range != null && now - stamp <= windowMs

    fun take(owner: String, start: Int, end: Int, now: Long = System.currentTimeMillis()): Pair<Int, Int>? {
        val value = range ?: return null
        if (this.owner != owner || now - stamp > windowMs) return null
        cancel()
        val same = start >= 0 && end >= 0 &&
            minOf(start, end) == minOf(value.first, value.second) &&
            maxOf(start, end) == maxOf(value.first, value.second)
        return when {
            // 宿主只是把端点排序了：用请求里的顺序，别丢掉「活动端在哪一侧」。
            same -> value
            // 宿主现在报得出另一个有效非空选区：按它现在的样子开。
            start >= 0 && end >= 0 && start != end -> start to end
            // 报不出（-1）或在 show 过程中被收成光标：退回请求里的选区。
            else -> value
        }
    }

    fun describe() = "owner=$owner range=$range age=${System.currentTimeMillis() - stamp}"
}
