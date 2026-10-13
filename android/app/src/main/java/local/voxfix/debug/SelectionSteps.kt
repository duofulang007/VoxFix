package local.voxfix.debug

// 每次只累计新的手指位移，反向拖动不需要先抵消屏幕外的旧位移。
internal class SelectionSteps {
    private var x = 0f
    private var y = 0f
    fun clear() { x = 0f; y = 0f }
    fun take(dx: Float, dy: Float): Pair<Int, Int> {
        if (dx * x < 0) x = 0f
        if (dy * y < 0) y = 0f
        x += dx; y += dy
        val columns = (x / 11).toInt().coerceIn(-20, 20)
        val lines = (y / 28).toInt().coerceIn(-10, 10)
        x -= columns * 11; y -= lines * 28
        return columns to lines
    }
}
