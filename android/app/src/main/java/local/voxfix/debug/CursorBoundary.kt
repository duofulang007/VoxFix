package local.voxfix.debug

// 光标（不是框选）贴到文本首/尾之后，还能不能继续发方向键。
//
// 宿主的 EditText 在文本首（尾）再收到一次方向键时不会"停住"：它把焦点交给下一个可聚焦的 View，
// 光标就此离开输入框 —— 之后滑轨怎么拖都找不回来。真机实测（K60，MainActivity 试用页）：
// 滑轨左滑后 uiautomator 里 `focused="true"` 的节点从 EditText 变成 TextView，输入框不再有光标。
//
// 所以只有确认目标方向还有字符时才发按键；拿不准一律放行，避免把正常移动封掉。
internal class CursorBoundary(
    private val hasSelection: Boolean,
    private val completeText: Boolean,
    private val startOffset: Int,
    private val selectionStart: Int,
    private val selectionEnd: Int,
    private val textLength: Int,
) {
    private val caret = startOffset + maxOf(selectionStart, selectionEnd)

    fun canStep(forward: Boolean): Boolean {
        // 有选区时方向键只做折叠，不会把焦点送出输入框。
        if (hasSelection) return true
        // 提取到的只是局部文本：边界留给方向键去跨段。
        if (!completeText) return true
        return if (forward) caret < startOffset + textLength else caret > startOffset
    }
}
