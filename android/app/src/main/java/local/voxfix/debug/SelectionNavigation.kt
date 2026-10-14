package local.voxfix.debug

// 回读可以排序，Shift 保留活动端；导航时重写选区会破坏 WebView 方向和滚动。
internal class SelectionNavigation(
    private val drag: SelectionDrag,
    private val read: () -> Pair<Int, Int>?,
    private val keys: (Int, Int, Int) -> Unit,
    private val post: (Long, () -> Unit) -> Unit,
    private val report: (Boolean) -> Unit,
    private val canMove: (Int, Int, Int) -> Boolean,
) {
    private val edits = ArrayDeque<() -> Unit>()
    private var draining = false
    private var serial = 0
    private var origin = -1
    private var direction = Triple(0, 0, 0)
    private var blockedAt = -1
    private var blockedDirection = direction
    var busy = false; private set

    fun run(action: () -> Unit) { edits.addLast(action); drain() }
    private fun drain() {
        if (draining) return
        draining = true
        try { while (!busy && edits.isNotEmpty()) edits.removeFirst()() }
        finally { draining = false }
    }
    fun cancel() { serial++; busy = false; edits.clear(); blockedAt = -1 }
    fun resetBoundary() { blockedAt = -1 }

    fun move(columns: Int, lines: Int, edge: Int) {
        if (drag.anchor < 0 || drag.head < 0) return
        direction = Triple(columns.compareTo(0), lines.compareTo(0), edge.compareTo(0))
        if (drag.head == blockedAt && direction == blockedDirection) return
        if (!canMove(drag.head, columns, lines)) {
            blockedAt = drag.head; blockedDirection = direction; report(true); return
        }
        if (kotlin.math.abs(lines) > 1 || kotlin.math.abs(columns) > 1) {
            val steps = mutableListOf<Triple<Int, Int, Int>>()
            repeat(kotlin.math.abs(lines)) { steps.add(Triple(0, lines.compareTo(0), 0)) }
            repeat(kotlin.math.abs(columns)) { steps.add(Triple(columns.compareTo(0), 0, 0)) }
            val last = steps.last(); steps[steps.lastIndex] = Triple(last.first, last.second, edge)
            for (step in steps.asReversed()) edits.addFirst { move(step.first, step.second, step.third) }
            return
        }
        busy = true
        origin = drag.head
        val ticket = ++serial
        keys(columns, lines, edge)
        post(180) { if (busy && serial == ticket) finish() }
    }
    fun observe(start: Int, end: Int): Boolean {
        if (!busy) return false
        val ticket = ++serial
        post(16) { if (busy && serial == ticket) finish() }
        return true
    }
    private fun finish() {
        val actual = read()
        val head = when {
            actual == null -> origin
            actual.first == drag.anchor -> actual.second
            actual.second == drag.anchor -> actual.first
            else -> { cancel(); drag.clear(); report(true); return }
        }
        drag.beginPositions(drag.anchor, head)
        serial++; busy = false
        if (drag.head == origin) { blockedAt = origin; blockedDirection = direction }
        report(drag.head == origin)
        drain()
    }
}
