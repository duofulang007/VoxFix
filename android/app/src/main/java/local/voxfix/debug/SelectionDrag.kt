package local.voxfix.debug

// 松手只结束触摸；保留两个有方向的端点，下一次长按才能续选。
internal class SelectionDrag {
    var anchor = -1; private set
    var head = -1; private set
    private var text = ""
    private var offset = 0

    fun clear() { anchor = -1; head = -1; text = "" }
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
    fun observe(start: Int, end: Int) {
        if (start == anchor) head = end
        else if (end == anchor) head = start
        else clear()
    }
}
