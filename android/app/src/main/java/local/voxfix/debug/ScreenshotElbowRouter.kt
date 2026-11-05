package local.voxfix.debug

import kotlin.math.abs
import kotlin.math.hypot

/** Unbound endpoint branch of Snow Shot 1.1.8 elbow_router_search.rs (Apache-2.0). */
internal object ScreenshotElbowRouter {
    private const val EPS = 1e-6
    private const val PADDING = 40.0

    private data class Point(val x: Double, val y: Double) {
        fun distance(other: Point) = abs(x - other.x) + abs(y - other.y)
        fun gridKey() = Point(Math.rint((x + 0.0) * 1e6), Math.rint((y + 0.0) * 1e6))
        fun heading(other: Point): Heading {
            val dx = other.x - x
            val dy = other.y - y
            return when {
                dx > abs(dy) -> Heading.RIGHT
                dx <= -abs(dy) -> Heading.LEFT
                dy > abs(dx) -> Heading.DOWN
                else -> Heading.UP
            }
        }
    }

    private enum class Heading(val dx: Int, val dy: Int) {
        UP(0, -1), RIGHT(1, 0), DOWN(0, 1), LEFT(-1, 0);
        val opposite get() = entries[(ordinal + 2) % entries.size]
    }

    private data class Bounds(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        fun contains(p: Point) = p.x > left && p.x < right && p.y > top && p.y < bottom
        fun union(other: Bounds) = Bounds(
            minOf(left, other.left), minOf(top, other.top),
            maxOf(right, other.right), maxOf(bottom, other.bottom)
        )
        fun dongle(p: Point, heading: Heading) = when (heading) {
            Heading.UP -> p.copy(y = top)
            Heading.RIGHT -> p.copy(x = right)
            Heading.DOWN -> p.copy(y = bottom)
            Heading.LEFT -> p.copy(x = left)
        }
    }

    fun route(start: ScreenshotDrawingPoint, end: ScreenshotDrawingPoint): List<ScreenshotDrawingPoint> {
        val a = Point(start.x.toDouble(), start.y.toDouble())
        val b = Point(end.x.toDouble(), end.y.toDouble())
        val routed = search(a, b)?.let { clean(clean(it)) }
        val points = routed?.takeIf { path ->
            path.zipWithNext().all { (p, q) -> abs(p.x - q.x) < 1.0 || abs(p.y - q.y) < 1.0 }
        } ?: orthogonal(listOf(a, b))
        return points.map { ScreenshotDrawingPoint((it.x + 0.0).toFloat(), (it.y + 0.0).toFloat()) }
    }

    private fun pointBounds(p: Point) = Bounds(p.x - 2, p.y - 2, p.x + 2, p.y + 2)

    private fun dynamicBounds(a: Bounds, b: Bounds, common: Bounds, heading: Heading, overlap: Boolean): Bounds {
        fun edge(own: Double, otherNear: Double, otherFar: Double, outer: Double, side: Heading, separate: Boolean): Double {
            val pad = if (overlap || heading == side) 0.0 else PADDING
            return when {
                own < otherFar -> {
                    val mid = (own + otherFar) / 2
                    if (separate) maxOf(mid, own + pad) else mid
                }
                own < otherNear -> own + pad
                else -> outer + pad
            }
        }
        val separateX = a.left > b.right || a.right < b.left
        val separateY = a.top > b.bottom || a.bottom < b.top
        return Bounds(
            -edge(-a.left, -b.left, -b.right, -common.left, Heading.LEFT, separateY),
            -edge(-a.top, -b.top, -b.bottom, -common.top, Heading.UP, separateX),
            edge(a.right, b.right, b.left, common.right, Heading.RIGHT, separateY),
            edge(a.bottom, b.bottom, b.top, common.bottom, Heading.DOWN, separateX)
        )
    }

    private fun splitBounds(a: Bounds, b: Bounds, first: Bounds, second: Bounds): Pair<Bounds, Bounds> {
        val union = first.union(second)
        if (first.right - first.left + second.right - second.left <= union.right - union.left + 1e-11 ||
            first.bottom - first.top + second.bottom - second.top <= union.bottom - union.top + 1e-11) {
            return first to second
        }
        val center = Point((second.left + second.right) / 2, (second.top + second.bottom) / 2)
        fun cross(ax: Double, ay: Double, bx: Double, by: Double) =
            (ax - center.x) * (by - center.y) - (ay - center.y) * (bx - center.x)

        val right = a.right < b.left
        val left = a.left > b.right
        val down = a.bottom < b.top
        val up = a.top > b.bottom
        if (!(left || right) || !(up || down)) return first to second
        val x = if (right) first.right + (second.left - first.right) / 2
            else second.right + (first.left - second.right) / 2
        val y = if (down) first.bottom + (second.top - first.bottom) / 2
            else second.bottom + (first.top - second.bottom) / 2
        val splitX = when {
            right && up -> cross(a.right, a.top, a.left, a.bottom) > 0
            right && down -> cross(a.left, a.top, a.right, a.bottom) <= 0
            left && down -> cross(a.right, a.top, a.left, a.bottom) > 0
            else -> cross(a.left, a.top, a.right, a.bottom) > 0
        }
        return if (splitX) {
            if (right) first.copy(right = x) to second.copy(left = x)
            else first.copy(left = x) to second.copy(right = x)
        } else {
            if (down) first.copy(bottom = y) to second.copy(top = y)
            else first.copy(top = y) to second.copy(bottom = y)
        }
    }

    private data class Visit(val parent: Int, val g: Double, val f: Double, var closed: Boolean = false)

    private fun search(start: Point, end: Point): List<Point>? {
        val a = pointBounds(start)
        val b = pointBounds(end)
        val overlap = a.contains(end) || b.contains(start)
        val common = a.union(b)
        val from = start.heading(end)
        val to = end.heading(start)
        val first = dynamicBounds(a, b, common, from, overlap)
        val second = dynamicBounds(b, a, common, to, overlap)
        val (left, right) = if (overlap) first to second else splitBounds(a, b, first, second)
        val entry = left.dongle(start, from)
        val exit = right.dongle(end, to)
        val checkBounds = !right.contains(entry) && !left.contains(exit)
        val xs = mutableListOf<Double>()
        val ys = mutableListOf<Double>()
        fun MutableList<Double>.addCoordinate(value: Double) {
            if (none { abs(it - value) <= EPS }) add(value)
        }
        for ((p, direction) in listOf(entry to from, exit to to)) {
            if (direction.dx == 0) xs.addCoordinate(p.x) else ys.addCoordinate(p.y)
        }
        for (bounds in listOf(left, right, common)) {
            xs.addCoordinate(bounds.left)
            xs.addCoordinate(bounds.right)
            ys.addCoordinate(bounds.top)
            ys.addCoordinate(bounds.bottom)
        }
        xs.sort()
        ys.sort()
        val cols = xs.size
        val nodes = ys.flatMap { y -> xs.map { x -> Point(x, y) } }
        val nodeIndices = nodes.indices.associateBy { nodes[it].gridKey() }
        val entryIndex = nodeIndices[entry.gridKey()]
        val exitIndex = nodeIndices[exit.gridKey()]
        val startIndex = entryIndex ?: nodeIndices[start.gridKey()] ?: return null
        val endIndex = exitIndex ?: nodeIndices[end.gridKey()] ?: return null

        val open = mutableListOf(startIndex)
        val visits = arrayOfNulls<Visit>(nodes.size)
        visits[startIndex] = Visit(-1, 0.0, 0.0)
        val span = nodes[startIndex].distance(nodes[endIndex])
        val bendCost = span * span * span
        while (open.isNotEmpty()) {
            open.sortBy { visits[it]!!.f }
            val index = open.removeAt(0)
            val visit = visits[index]!!
            val point = nodes[index]
            if (index == endIndex) {
                val path = mutableListOf<Point>()
                var cursor = index
                while (cursor >= 0) {
                    path.add(nodes[cursor])
                    cursor = visits[cursor]!!.parent
                }
                path.reverse()
                if (entryIndex != null) path.add(0, start)
                if (exitIndex != null) path.add(end)
                return path
            }
            visit.closed = true
            val previous = if (visit.parent < 0) from else nodes[visit.parent].heading(point)
            for (direction in Heading.entries) {
                val col = index % cols + direction.dx
                val row = index / cols + direction.dy
                if (col !in xs.indices || row !in ys.indices) continue
                val next = row * cols + col
                if (visits[next]?.closed == true || direction == previous.opposite ||
                    (next == startIndex && direction == from) || (next == endIndex && direction == to)) continue
                val candidate = nodes[next]
                val midpoint = Point((point.x + candidate.x) / 2, (point.y + candidate.y) / 2)
                if (checkBounds && (left.contains(midpoint) || right.contains(midpoint))) continue
                val g = visit.g + point.distance(candidate) + if (previous != direction) bendCost else 0.0
                if (g >= (visits[next]?.g ?: Double.POSITIVE_INFINITY)) continue
                val finish = nodes[endIndex]
                val h = candidate.distance(finish) + turns(candidate, finish, direction, to) * span * span
                visits[next] = Visit(index, g, g + h)
                if (next !in open) open.add(next)
            }
        }
        return null
    }

    private fun turns(a: Point, b: Point, from: Heading, to: Heading): Int {
        if (from == to) {
            val blocked = when (to) {
                Heading.RIGHT -> a.x >= b.x
                Heading.LEFT -> a.x <= b.x
                Heading.UP -> a.y >= b.y
                Heading.DOWN -> a.y <= b.y
            }
            val aligned = if (to.dx != 0) abs(a.y - b.y) <= EPS else abs(a.x - b.x) <= EPS
            return if (blocked) 4 else if (aligned) 0 else 2
        }
        if (from == to.opposite) {
            val aligned = if (to.dx != 0) abs(a.y - b.y) <= EPS else abs(a.x - b.x) <= EPS
            return if (aligned) 4 else 2
        }
        val dx = b.x - a.x
        val dy = b.y - a.y
        return if (dx * (from.dx + to.dx) > 0 && dy * (from.dy + to.dy) > 0) 1 else 3
    }

    private fun dedupe(points: List<Point>): List<Point> = buildList {
        add(points.first())
        for (index in 1 until points.lastIndex) {
            val a = last()
            val b = points[index]
            val c = points[index + 1]
            if ((abs(a.x - b.x) <= EPS && abs(b.x - c.x) <= EPS) ||
                (abs(a.y - b.y) <= EPS && abs(b.y - c.y) <= EPS)) continue
            add(b)
        }
        add(points.last())
    }

    private fun orthogonal(points: List<Point>): List<Point> = dedupe(buildList {
        add(points.first())
        for (next in points.drop(1)) {
            val previous = last()
            val dx = abs(next.x - previous.x)
            val dy = abs(next.y - previous.y)
            if (dx > EPS && dy > EPS) {
                add(if (dx >= dy) Point(next.x, previous.y) else Point(previous.x, next.y))
            }
            add(next)
        }
    })

    private fun clean(points: List<Point>): List<Point> = dedupe(buildList {
        val path = orthogonal(points)
        add(path.first())
        for (point in path.drop(1).dropLast(1)) {
            if (hypot(point.x - last().x, point.y - last().y) > 1.0) add(point)
        }
        add(path.last())
    })
}
