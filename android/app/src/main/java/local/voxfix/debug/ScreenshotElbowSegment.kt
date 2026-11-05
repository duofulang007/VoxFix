package local.voxfix.debug

import kotlin.math.abs

/** Unbound branch of move_fixed_segment_to_point / handle_segment_move (Snow Shot 1.1.8). */
internal object ScreenshotElbowSegment {
    data class Route(val points: List<ScreenshotDrawingPoint>, val fixed: Set<Int>, val active: Int)

    fun isElbow(stroke: ScreenshotDrawingStroke) =
        stroke.tool == ScreenshotDrawingTool.ARROW && stroke.style.arrowType == ScreenshotArrowType.ELBOW

    fun isOrthogonal(points: List<ScreenshotDrawingPoint>) =
        points.zipWithNext().all { (a, b) -> abs(a.x - b.x) < 1f || abs(a.y - b.y) < 1f }

    private fun horizontal(from: ScreenshotDrawingPoint, to: ScreenshotDrawingPoint): Boolean {
        val dx = to.x - from.x
        val dy = abs(to.y - from.y)
        return dx > dy || dx <= -dy
    }

    private fun same(a: ScreenshotDrawingPoint, b: ScreenshotDrawingPoint) =
        abs(a.x - b.x) <= 1e-6f && abs(a.y - b.y) <= 1e-6f

    // elbow_update_endpoints.rs, without bindables or special endpoint legs.
    fun moveEndpoint(stroke: ScreenshotDrawingStroke, index: Int, target: ScreenshotDrawingPoint): ScreenshotDrawingStroke {
        val points = stroke.points
        if (same(points[index], target)) return stroke
        val start = if (index == 0) target else points.first()
        val end = if (index == points.lastIndex) target else points.last()
        val fixed = stroke.fixedElbowSegments.filterTo(linkedSetOf()) { it in 1 until points.size }
        if (fixed.isEmpty() || points.size < 3 || !isOrthogonal(points)) {
            return stroke.copy(points = ScreenshotElbowRouter.route(start, end), fixedElbowSegments = emptySet())
        }
        val second = points[1]
        val penultimate = points[points.lastIndex - 1]
        val startBend = if (horizontal(second, points[2])) second.copy(x = start.x) else second.copy(y = start.y)
        val endBend = if (horizontal(penultimate, points[points.lastIndex - 2])) {
            penultimate.copy(x = end.x)
        } else penultimate.copy(y = end.y)
        val reconnected = buildList {
            add(start)
            add(startBend)
            for (i in 2 until points.lastIndex - 1) add(points[i])
            add(endBend)
            add(end)
        }
        return stroke.copy(points = reconnected, fixedElbowSegments = fixed)
    }

    fun move(route: Route, pointer: ScreenshotDrawingPoint): Route {
        val points = route.points
        val endIndex = route.active
        if (endIndex !in 1 until points.size) return route
        val startIndex = endIndex - 1
        val oldStart = points[startIndex]
        val oldEnd = points[endIndex]
        val alongX = horizontal(oldStart, oldEnd)
        fun moved(point: ScreenshotDrawingPoint) =
            if (alongX) point.copy(y = pointer.y) else point.copy(x = pointer.x)

        val start = moved(oldStart)
        val end = moved(oldEnd)
        if (endIndex in route.fixed && same(oldStart, start) && same(oldEnd, end)) return route

        val next = points.toMutableList()
        if (startIndex > 0 && !same(points[startIndex - 1], oldStart)) {
            val previous = points[startIndex - 1]
            next[startIndex - 1] = if (horizontal(previous, oldStart)) previous.copy(y = start.y)
                else previous.copy(x = start.x)
        }
        next[startIndex] = start
        next[endIndex] = end
        if (endIndex < points.lastIndex && !same(oldEnd, points[endIndex + 1])) {
            val following = points[endIndex + 1]
            next[endIndex + 1] = if (horizontal(oldEnd, following)) following.copy(y = end.y)
                else following.copy(x = end.x)
        }

        val prepend = startIndex == 0 && 1 !in route.fixed
        if (prepend) {
            next.add(0, if (horizontal(end, start)) start.copy(y = points.first().y)
                else start.copy(x = points.first().x))
        }
        if (endIndex == points.lastIndex && points.lastIndex !in route.fixed) {
            next.add(if (horizontal(points.last(), points.first())) end.copy(y = points.last().y)
                else end.copy(x = points.last().x))
        }
        val shift = if (prepend) 1 else 0
        val fixed = (route.fixed + endIndex).mapTo(linkedSetOf()) { it + shift }
        return Route(next, fixed, endIndex + shift)
    }
}
