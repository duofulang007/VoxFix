package local.voxfix.debug

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Geometry only. These snapshots belong to a single frozen frame. */
internal class ScreenshotSmartSelection(
    private val windows: List<Node>,
    private val windowIds: List<Int> = emptyList()
) {
    data class Node(val bounds: ScreenshotDocRect, val children: List<Node> = emptyList()) {
        fun path(x: Int, y: Int): List<ScreenshotDocRect>? {
            if (x < bounds.left || x >= bounds.right || y < bounds.top || y >= bounds.bottom) return null
            val inner = children.firstNotNullOfOrNull { it.path(x, y) }.orEmpty()
            return if (inner.lastOrNull() == bounds) inner else inner + bounds
        }
    }

    fun path(x: Int, y: Int, width: Int, height: Int): List<ScreenshotDocRect> {
        val result = mutableListOf<ScreenshotDocRect>()
        for (bounds in windows.firstNotNullOfOrNull { it.path(x, y) }.orEmpty()) {
            val clipped = ScreenshotDocRect(maxOf(0, bounds.left), maxOf(0, bounds.top),
                minOf(width, bounds.right), minOf(height, bounds.bottom))
            if (clipped.width >= 2 && clipped.height >= 2 && result.lastOrNull() != clipped) result.add(clipped)
        }
        return result
    }

    @Suppress("DEPRECATION")
    fun matchesWindows(service: AccessibilityService): Boolean {
        val current = service.windows.mapNotNull { window ->
            try {
                if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) null
                else window.id to Rect().also(window::getBoundsInScreen).toDocumentRect()
            } finally { window.recycle() }
        }.filter { (_, bounds) -> bounds.width >= 2 && bounds.height >= 2 }
        return current.map { it.first } == windowIds && current.map { it.second } == windows.map { it.bounds }
    }

    companion object {
        fun targetIndex(path: List<ScreenshotDocRect>, selection: ScreenshotDocRect?, x: Int, y: Int,
                        windowMode: Boolean): Int {
            if (selection != null && x >= selection.left && x < selection.right &&
                y >= selection.top && y < selection.bottom) {
                return path.indexOfFirst { it != selection && it.left >= selection.left &&
                    it.top >= selection.top && it.right <= selection.right && it.bottom <= selection.bottom }
            }
            return if (windowMode) path.lastIndex else if (path.isEmpty()) -1 else 0
        }

        fun dock(safe: ScreenshotDocRect, selection: ScreenshotDocRect?, toolbar: ScreenshotDocRect?,
                 width: Int, height: Int, gap: Int): ScreenshotDocRect {
            val right = maxOf(safe.left, safe.right - width)
            val bottom = maxOf(safe.top, safe.bottom - height)
            val columns = listOf((safe.left + right) / 2, safe.right - width, safe.left)
                .map { it.coerceIn(safe.left, right) }.distinct()
            val rows = listOfNotNull(bottom, toolbar?.let { it.top - height - gap },
                toolbar?.let { it.bottom + gap }, safe.top).map { it.coerceIn(safe.top, bottom) }.distinct()
            fun overlap(a: ScreenshotDocRect, b: ScreenshotDocRect?): Long = if (b == null) 0 else
                (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0).toLong() *
                    (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
            return rows.flatMap { y -> columns.map { x -> ScreenshotDocRect(x, y, x + width, y + height) } }
                .minWith(compareBy({ overlap(it, toolbar) }, { overlap(it, selection) }))
        }

        @Suppress("DEPRECATION")
        fun snapshot(service: AccessibilityService): ScreenshotSmartSelection? {
            val deadline = SystemClock.uptimeMillis() + 60
            var remaining = 1800
            fun collect(node: AccessibilityNodeInfo, clip: Rect, depth: Int): Node? {
                if (depth == 48 || remaining-- <= 0 || SystemClock.uptimeMillis() >= deadline ||
                    !node.isVisibleToUser) return null
                val bounds = Rect().also(node::getBoundsInScreen)
                if (!bounds.intersect(clip) || bounds.width() < 2 || bounds.height() < 2) return null
                val children = mutableListOf<Pair<Int, Node>>()
                for (index in 0 until node.childCount) {
                    if (remaining <= 0 || SystemClock.uptimeMillis() >= deadline) break
                    val child = node.getChild(index) ?: continue
                    try {
                        collect(child, bounds, depth + 1)?.let { children.add(child.drawingOrder to it) }
                    } finally { child.recycle() }
                }
                return Node(bounds.toDocumentRect(), children.asReversed().sortedByDescending { it.first }.map { it.second })
            }
            val windows = service.windows.mapNotNull { window ->
                try {
                    if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@mapNotNull null
                    val bounds = Rect().also(window::getBoundsInScreen)
                    if (bounds.width() < 2 || bounds.height() < 2) return@mapNotNull null
                    val root = if (SystemClock.uptimeMillis() < deadline && remaining > 0) window.root else null
                    val content = root?.let { try { collect(it, bounds, 0) } finally { it.recycle() } }
                    window.id to Node(bounds.toDocumentRect(), listOfNotNull(content))
                } finally { window.recycle() }
            }
            return windows.takeIf { it.isNotEmpty() }?.let { list ->
                ScreenshotSmartSelection(list.map { it.second }, list.map { it.first })
            }
        }

        private fun Rect.toDocumentRect() = ScreenshotDocRect(left, top, right, bottom)
    }
}
