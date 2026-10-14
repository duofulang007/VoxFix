package local.voxfix.debug

// 松手只结束触摸；保留两个有方向的端点，下一次长按才能续选。
internal class SelectionDrag {
    var anchor = -1; private set
    var head = -1; private set
    private var text = ""
    private var offset = 0
    private var hostSelectionPending = false

    fun clear() { anchor = -1; head = -1; text = ""; hostSelectionPending = false }
    fun lock(value: String?, startOffset: Int, position: Int) {
        anchor = position; head = position; text = value ?: ""; offset = startOffset
    }
    fun matches(value: String, startOffset: Int, start: Int, end: Int) = anchor >= 0 &&
        text == value && offset == startOffset &&
        minOf(start, end) == minOf(anchor, head) && maxOf(start, end) == maxOf(anchor, head)

    fun begin(value: String, startOffset: Int, start: Int, end: Int) {
        if (!matches(value, startOffset, start, end)) {
            anchor = start; head = end; text = value; offset = startOffset
        }
    }
    fun move(position: Int) { head = position.coerceIn(offset, offset + text.length) }
    fun beginPositions(start: Int, end: Int) {
        if (anchor < 0 || minOf(start, end) != minOf(anchor, head) || maxOf(start, end) != maxOf(anchor, head)) {
            anchor = start; head = end; text = ""
        }
    }
    fun beginHostSelection(oldStart: Int, oldEnd: Int, start: Int, end: Int): Boolean {
        if (start < 0 || end < 0 || start == end) { cancelHostSelection(); return false }
        if (!hostSelectionPending) {
            if (oldStart < 0 || oldEnd < 0) return false
            val oldMin = minOf(oldStart, oldEnd); val oldMax = maxOf(oldStart, oldEnd)
            val newMin = minOf(start, end); val newMax = maxOf(start, end)
            // 新选词可直接替换旧选区；共享一侧且仍重叠的是已有手柄调整。
            if (oldMin != oldMax && newMin < oldMax && newMax > oldMin &&
                (newMin == oldMin || newMax == oldMax)) return false
        }
        beginPositions(start, end)
        hostSelectionPending = true
        return true
    }
    fun finishHostSelection(): Boolean {
        val ready = hostSelectionPending && anchor >= 0 && head >= 0 && anchor != head
        hostSelectionPending = false
        return ready
    }
    fun cancelHostSelection() { if (hostSelectionPending) clear() }
    fun observe(start: Int, end: Int) {
        if (hostSelectionPending) {
            if (start >= 0 && end >= 0 && start != end) beginPositions(start, end) else clear()
            return
        }
        if (start == anchor) { head = end; return }
        if (end == anchor) { head = start; return }
        // 有一端还压在「活动端」上：宿主把另一端重算了（锚点跟着移），同样要跟随。
        if (start == head) { anchor = end; return }
        if (end == head) { anchor = start; return }
        // 两端都不等于锚点/活动端：宿主重算过词边界（长按选词后紧跟一次回调时非常常见，整段会平移一两个字）。
        // 这种情况要跟着它走，不能把刚建立的拖选状态整条清掉——清了面板就没了。
        if (anchor >= 0 && start != end) {
            if (start - anchor in -2..2) { anchor = start; head = end; return }
            if (end - anchor in -2..2) { anchor = end; head = start; return }
        }
        clear()
    }
    fun isExternalChange(start: Int, end: Int, lockedAnchor: Int, gestureActive: Boolean, ownChangePending: Boolean): Boolean {
        if (lockedAnchor < 0 || start < 0 || end < 0 || gestureActive) return false
        if (start == end) return start != lockedAnchor || !ownChangePending
        // 离当前选区 2 个字以上，无论是不是自己刚改的，都算手动重定位。
        val apart = anchor >= 0 && head >= 0 &&
            (minOf(start, end) > maxOf(anchor, head) + 2 || maxOf(start, end) < minOf(anchor, head) - 2)
        // 两端都和锁定的锚点不同、且不是"自己刚改"的窗口内 → 手动把光标挪到别处了。
        // 不能漏掉 ownChangePending：宿主长按选词后紧跟的那次边界重算会整段平移，漏掉它就会把
        // 刚打开的编辑框当成手动重定位收掉（真机症状：只出键盘、不进编辑框，约 90ms 后面板消失）。
        if (anchor >= 0 && start != lockedAnchor && end != lockedAnchor && (!ownChangePending || apart)) return true
        return !ownChangePending && (minOf(start, end) != minOf(anchor, head) || maxOf(start, end) != maxOf(anchor, head))
    }
}
