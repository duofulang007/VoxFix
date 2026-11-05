package local.voxfix.debug

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Object snapping for T22, ported from Snow Shot 1.1.8
 * `snow-draw-engine-core/src/snap.rs` + `snap/snap_candidates.rs` + `snap/snap_guides.rs` +
 * `snap/snap_scoring.rs` at `afdc340b14e7f92a294a95c374d13ada0d8c7a82` (Apache-2.0).
 *
 * Pure geometry: caller feeds a target rect plus reference rects (world coordinates), gets a
 * delta (dx, dy) and the guide lines to render. Not wired to any gesture yet — see
 * `docs/SnowShot-T22-绑定与吸附-2026-11-05.md`.
 */
internal object ScreenshotObjectSnapping {

    enum class Axis { X, Y }
    enum class Anchor { START, CENTER, END }
    enum class Kind { POINT, GAP_CENTER, GAP_SIDE }
    enum class GapSide { BEFORE, AFTER }
    enum class GuideKind { POINT, GAP }
    enum class GuideAxis { HORIZONTAL, VERTICAL }

    data class SnapPoint(val x: Double, val y: Double)

    data class SnapGuide(
        val kind: GuideKind,
        val axis: GuideAxis,
        val start: SnapPoint,
        val end: SnapPoint,
        val markers: List<SnapPoint>,
        val label: Double?
    )

    data class Result(val dx: Double, val dy: Double, val guides: List<SnapGuide>) {
        val hasSnap: Boolean get() = dx != 0.0 || dy != 0.0

        companion object {
            val NONE = Result(0.0, 0.0, emptyList())
        }
    }

    data class RectRequest(
        val targetRect: ScreenshotSnapping.DrawRect,
        val referenceRects: List<ScreenshotSnapping.DrawRect>,
        val snapDistance: Double,
        val targetAnchorsX: List<Anchor>,
        val targetAnchorsY: List<Anchor>,
        val enablePointSnaps: Boolean,
        val enableGapSnaps: Boolean
    )

    // ---------------------------------------------------------------- candidate model

    private data class Candidate(
        val axis: Axis,
        val offset: Double,
        val kind: Kind,
        val referenceRect: ScreenshotSnapping.DrawRect? = null,
        val targetAnchor: Anchor? = null,
        val referenceAnchor: Anchor? = null,
        val perpendicularDistance: Double? = null,
        val gapBeforeRect: ScreenshotSnapping.DrawRect? = null,
        val gapAfterRect: ScreenshotSnapping.DrawRect? = null,
        val gapSize: Double? = null,
        val gapSide: GapSide? = null,
        val gapFrequency: Int? = null
    ) {
        val distance: Double get() = abs(offset)
    }

    internal data class GapSegment(
        val before: ScreenshotSnapping.DrawRect,
        val after: ScreenshotSnapping.DrawRect,
        val gap: Double
    )

    private data class GapSizeBucket(val size: Double, var count: Int)

    // ---------------------------------------------------------------- constants (snap.rs)

    private const val EPSILON = 0.0001
    private val ALL_ANCHORS = listOf(Anchor.START, Anchor.CENTER, Anchor.END)
    private const val PRIORITY_DISTANCE_SLACK_FACTOR = 0.05
    private const val PRIORITY_DISTANCE_SLACK_MAX = 0.5
    private const val STRENGTH_SLACK = 0.05
    private const val POINT_DISTANCE_WEIGHT = 0.45
    private const val POINT_PERPENDICULAR_WEIGHT = 0.4
    private const val POINT_ANCHOR_WEIGHT = 0.15
    private const val GAP_DISTANCE_WEIGHT = 0.7
    private const val GAP_FREQUENCY_WEIGHT = 0.2
    private const val GAP_KIND_WEIGHT = 0.1
    private const val GAP_STRENGTH_SCALE = 0.9
    private const val PERPENDICULAR_SIZE_RANGE_FACTOR = 1.5
    private const val PERPENDICULAR_SNAP_RANGE_FACTOR = 4.0
    private const val MAX_ASSOCIATED_GAP_GUIDES = 4
    private const val MAX_ANCHOR_PRIORITY = 3.0

    // ---------------------------------------------------------------- public entry points

    /** `snap_move`: every anchor on both axes, point + gap snaps. */
    fun snapMove(
        targetRect: ScreenshotSnapping.DrawRect,
        referenceRects: List<ScreenshotSnapping.DrawRect>,
        snapDistance: Double,
        enablePointSnaps: Boolean,
        enableGapSnaps: Boolean
    ): Result = snapRect(
        RectRequest(
            targetRect, referenceRects, snapDistance,
            ALL_ANCHORS, ALL_ANCHORS, enablePointSnaps, enableGapSnaps
        )
    )

    /** `snap_resize`: caller-chosen anchors per axis, point snaps only (no gaps). */
    fun snapResize(
        targetRect: ScreenshotSnapping.DrawRect,
        referenceRects: List<ScreenshotSnapping.DrawRect>,
        snapDistance: Double,
        targetAnchorsX: List<Anchor>,
        targetAnchorsY: List<Anchor>,
        enablePointSnaps: Boolean
    ): Result = snapRect(
        RectRequest(
            targetRect, referenceRects, snapDistance,
            targetAnchorsX, targetAnchorsY, enablePointSnaps, enableGapSnaps = false
        )
    )

    fun snapRect(request: RectRequest): Result {
        if (request.snapDistance <= 0.0 ||
            request.referenceRects.isEmpty() ||
            (!request.enablePointSnaps && !request.enableGapSnaps) ||
            (request.targetAnchorsX.isEmpty() && request.targetAnchorsY.isEmpty())
        ) {
            return Result.NONE
        }

        val anchorsX = deduplicateAnchors(request.targetAnchorsX)
        val anchorsY = deduplicateAnchors(request.targetAnchorsY)

        val candidatesX = buildAxisCandidates(
            Axis.X, request.targetRect, request.referenceRects, anchorsX,
            request.snapDistance, request.enablePointSnaps, request.enableGapSnaps
        )
        val candidatesY = buildAxisCandidates(
            Axis.Y, request.targetRect, request.referenceRects, anchorsY,
            request.snapDistance, request.enablePointSnaps, request.enableGapSnaps
        )

        val xCandidate = selectBestCandidate(candidatesX, request.targetRect, request.snapDistance)
        val yCandidate = selectBestCandidate(candidatesY, request.targetRect, request.snapDistance)

        val dx = xCandidate?.offset ?: 0.0
        val dy = yCandidate?.offset ?: 0.0
        val snappedRect = request.targetRect.translate(dx, dy)

        val guides = mutableListOf<SnapGuide>()
        appendCandidateGuides(
            xCandidate, yCandidate, snappedRect, request.referenceRects,
            request.snapDistance, guides
        )
        appendCandidateGuides(
            yCandidate, xCandidate, snappedRect, request.referenceRects,
            request.snapDistance, guides
        )

        return Result(dx, dy, guides)
    }

    // ---------------------------------------------------------------- candidates (snap_candidates.rs)

    private fun buildAxisCandidates(
        axis: Axis,
        targetRect: ScreenshotSnapping.DrawRect,
        referenceRects: List<ScreenshotSnapping.DrawRect>,
        targetAnchors: List<Anchor>,
        snapDistance: Double,
        enablePointSnaps: Boolean,
        enableGapSnaps: Boolean
    ): List<Candidate> {
        if (targetAnchors.isEmpty()) return emptyList()
        val candidates = mutableListOf<Candidate>()
        if (enablePointSnaps) {
            candidates += buildPointCandidates(axis, targetRect, referenceRects, targetAnchors, snapDistance)
        }
        if (enableGapSnaps) {
            candidates += buildGapCandidates(axis, targetRect, referenceRects, targetAnchors, snapDistance)
        }
        return candidates
    }

    private fun buildPointCandidates(
        axis: Axis,
        targetRect: ScreenshotSnapping.DrawRect,
        referenceRects: List<ScreenshotSnapping.DrawRect>,
        targetAnchors: List<Anchor>,
        snapDistance: Double
    ): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        for (referenceRect in referenceRects) {
            val perpendicularDistance = rectPerpendicularDistance(targetRect, referenceRect, axis)
            for (targetAnchor in targetAnchors) {
                val targetPos = anchorPosition(targetRect, axis, targetAnchor)
                for (referenceAnchor in ALL_ANCHORS) {
                    val referencePos = anchorPosition(referenceRect, axis, referenceAnchor)
                    val offset = referencePos - targetPos
                    if (abs(offset) <= snapDistance) {
                        candidates += Candidate(
                            axis = axis, offset = offset, kind = Kind.POINT,
                            referenceRect = referenceRect, targetAnchor = targetAnchor,
                            referenceAnchor = referenceAnchor,
                            perpendicularDistance = perpendicularDistance
                        )
                    }
                }
            }
        }
        return candidates
    }

    private fun buildGapCandidates(
        axis: Axis,
        targetRect: ScreenshotSnapping.DrawRect,
        referenceRects: List<ScreenshotSnapping.DrawRect>,
        targetAnchors: List<Anchor>,
        snapDistance: Double
    ): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        val allowCenter = targetAnchors.contains(Anchor.CENTER)

        val filtered = resolveGapReferenceRects(axis, targetRect, referenceRects)
        if (filtered.size < 2) return candidates

        val segments = buildGapSegments(axis, filtered)
        if (segments.isEmpty()) return candidates

        val gapBuckets = gapSizeBuckets(segments)
        if (gapBuckets.isEmpty()) return candidates

        val targetCenter = anchorPosition(targetRect, axis, Anchor.CENTER)
        val targetSize = axisSize(targetRect, axis)

        val beforeNeighbor = closestNeighbor(axis, targetRect, filtered, GapSide.BEFORE)
        val afterNeighbor = closestNeighbor(axis, targetRect, filtered, GapSide.AFTER)

        for (segment in segments) {
            if (allowCenter) {
                val desiredCenter = (axisMax(segment.before, axis) + axisMin(segment.after, axis)) / 2.0
                val offset = desiredCenter - targetCenter
                if (abs(offset) <= snapDistance) {
                    val gapFrequency = gapFrequencyFor(gapBuckets, segment.gap)
                    candidates += Candidate(
                        axis = axis, offset = offset, kind = Kind.GAP_CENTER,
                        gapBeforeRect = segment.before, gapAfterRect = segment.after,
                        gapSize = segment.gap, gapFrequency = gapFrequency
                    )
                }
            }
        }

        for (bucket in gapBuckets) {
            addGapSideCandidateFromNeighbor(
                candidates, beforeNeighbor, axis, targetRect, targetSize, snapDistance,
                bucket.size, bucket.count, GapSide.AFTER
            )
            addGapSideCandidateFromNeighbor(
                candidates, afterNeighbor, axis, targetRect, targetSize, snapDistance,
                bucket.size, bucket.count, GapSide.BEFORE
            )
        }

        return candidates
    }

    /** `resolve_gap_reference_rects`: keep only rects overlapping perpendicular to the axis, sorted along it. */
    internal fun resolveGapReferenceRects(
        axis: Axis,
        targetRect: ScreenshotSnapping.DrawRect,
        referenceRects: List<ScreenshotSnapping.DrawRect>
    ): List<ScreenshotSnapping.DrawRect> = referenceRects
        .filter { overlapsPerpendicular(it, targetRect, axis) }
        .sortedBy { axisMin(it, axis) }

    /** `build_gap_segments`: consecutive gaps between sorted rects, positive only. */
    internal fun buildGapSegments(
        axis: Axis,
        sortedRects: List<ScreenshotSnapping.DrawRect>
    ): List<GapSegment> {
        if (sortedRects.size < 2) return emptyList()
        val segments = mutableListOf<GapSegment>()
        for (index in 0 until sortedRects.size - 1) {
            val before = sortedRects[index]
            val after = sortedRects[index + 1]
            val gap = axisMin(after, axis) - axisMax(before, axis)
            if (gap > 0.0) segments += GapSegment(before, after, gap)
        }
        return segments
    }

    private fun gapSizeBuckets(segments: List<GapSegment>): List<GapSizeBucket> {
        val buckets = mutableListOf<GapSizeBucket>()
        for (segment in segments) {
            val existing = buckets.indexOfFirst { abs(it.size - segment.gap) <= EPSILON }
            if (existing >= 0) buckets[existing].count += 1
            else buckets += GapSizeBucket(segment.gap, 1)
        }
        return buckets
    }

    private fun gapFrequencyFor(buckets: List<GapSizeBucket>, gap: Double): Int =
        buckets.firstOrNull { abs(it.size - gap) <= EPSILON }?.count ?: 0

    private fun closestNeighbor(
        axis: Axis,
        targetRect: ScreenshotSnapping.DrawRect,
        referenceRects: List<ScreenshotSnapping.DrawRect>,
        direction: GapSide
    ): ScreenshotSnapping.DrawRect? {
        val targetMin = axisMin(targetRect, axis)
        val targetMax = axisMax(targetRect, axis)
        var closest: ScreenshotSnapping.DrawRect? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for (referenceRect in referenceRects) {
            val distance = when (direction) {
                GapSide.BEFORE -> targetMin - axisMax(referenceRect, axis)
                GapSide.AFTER -> axisMin(referenceRect, axis) - targetMax
            }
            if (distance < -EPSILON || distance > bestDistance) continue
            bestDistance = distance
            closest = referenceRect
        }
        return closest
    }

    private fun addGapSideCandidateFromNeighbor(
        candidates: MutableList<Candidate>,
        neighbor: ScreenshotSnapping.DrawRect?,
        axis: Axis,
        targetRect: ScreenshotSnapping.DrawRect,
        targetSize: Double,
        snapDistance: Double,
        gapSize: Double,
        gapFrequency: Int,
        gapSide: GapSide
    ) {
        if (neighbor == null) return
        val desiredStart = when (gapSide) {
            GapSide.AFTER -> axisMax(neighbor, axis) + gapSize
            GapSide.BEFORE -> axisMin(neighbor, axis) - gapSize - targetSize
        }
        val offset = desiredStart - axisMin(targetRect, axis)
        if (abs(offset) > snapDistance) return
        candidates += Candidate(
            axis = axis, offset = offset, kind = Kind.GAP_SIDE,
            referenceRect = neighbor, gapSize = gapSize,
            gapFrequency = gapFrequency, gapSide = gapSide
        )
    }

    // ---------------------------------------------------------------- guides (snap_guides.rs)

    private fun appendCandidateGuides(
        candidate: Candidate?,
        perpendicularCandidate: Candidate?,
        snappedRect: ScreenshotSnapping.DrawRect,
        referenceRects: List<ScreenshotSnapping.DrawRect>,
        snapDistance: Double,
        guides: MutableList<SnapGuide>
    ) {
        if (candidate == null) return

        for (guide in buildGuidesForCandidate(candidate, snappedRect, perpendicularCandidate)) {
            if (guide !in guides) guides += guide
        }

        if (candidate.kind == Kind.POINT) return

        for (guide in buildAssociatedGapGuides(candidate, snappedRect, referenceRects, snapDistance)) {
            if (guide !in guides) guides += guide
        }
    }

    private fun buildGuidesForCandidate(
        candidate: Candidate,
        targetRect: ScreenshotSnapping.DrawRect,
        perpendicularCandidate: Candidate?
    ): List<SnapGuide> {
        if (candidate.kind == Kind.GAP_CENTER) {
            val splitGuides = buildSplitGapCenterGuides(candidate, targetRect)
            if (splitGuides.isNotEmpty()) return splitGuides
        }
        return listOf(buildGuide(candidate, targetRect, perpendicularCandidate))
    }

    private fun buildGuide(
        candidate: Candidate,
        targetRect: ScreenshotSnapping.DrawRect,
        perpendicularCandidate: Candidate?
    ): SnapGuide = when (candidate.kind) {
        Kind.POINT -> buildPointGuide(candidate, targetRect, perpendicularCandidate)
        Kind.GAP_CENTER, Kind.GAP_SIDE -> buildGapGuide(candidate, targetRect)
    }

    private fun buildAssociatedGapGuides(
        candidate: Candidate,
        targetRect: ScreenshotSnapping.DrawRect,
        referenceRects: List<ScreenshotSnapping.DrawRect>,
        snapDistance: Double
    ): List<SnapGuide> {
        val gapSize = candidate.gapSize ?: return emptyList()
        val gapTolerance = max(EPSILON, distanceSlack(snapDistance))

        val filtered = resolveGapReferenceRects(candidate.axis, targetRect, referenceRects)
        if (filtered.size < 2) return emptyList()

        val segments = buildGapSegments(candidate.axis, filtered)
        if (segments.isEmpty()) return emptyList()

        val matchingSegments = segments.filter { segment ->
            if (abs(segment.gap - gapSize) > gapTolerance) return@filter false
            !matchesGapSegment(candidate, segment)
        }
        if (matchingSegments.isEmpty()) return emptyList()

        val sorted = matchingSegments.sortedBy {
            abs(gapSegmentCenter(it, candidate.axis) - axisCenter(targetRect, candidate.axis))
        }

        return sorted.take(MAX_ASSOCIATED_GAP_GUIDES).map { segment ->
            buildGapGuide(
                Candidate(
                    axis = candidate.axis, offset = 0.0, kind = Kind.GAP_CENTER,
                    gapBeforeRect = segment.before, gapAfterRect = segment.after,
                    gapSize = gapSize, gapFrequency = candidate.gapFrequency
                ),
                targetRect
            )
        }
    }

    private fun matchesGapSegment(candidate: Candidate, segment: GapSegment): Boolean {
        if (candidate.kind != Kind.GAP_CENTER) return false
        return candidate.gapBeforeRect == segment.before && candidate.gapAfterRect == segment.after
    }

    private fun gapSegmentCenter(segment: GapSegment, axis: Axis): Double =
        (axisMax(segment.before, axis) + axisMin(segment.after, axis)) / 2.0

    private fun buildPointGuide(
        candidate: Candidate,
        targetRect: ScreenshotSnapping.DrawRect,
        perpendicularCandidate: Candidate?
    ): SnapGuide {
        val referenceRect = candidate.referenceRect!!
        val referenceAnchor = candidate.referenceAnchor!!
        val snapPos = anchorPosition(referenceRect, candidate.axis, referenceAnchor)

        val markers = resolvePointMarkers(
            candidate, targetRect, referenceRect, snapPos, perpendicularCandidate
        )

        return if (candidate.axis == Axis.X) {
            SnapGuide(
                kind = GuideKind.POINT, axis = GuideAxis.VERTICAL,
                start = SnapPoint(snapPos, min(referenceRect.minY, targetRect.minY)),
                end = SnapPoint(snapPos, max(referenceRect.maxY, targetRect.maxY)),
                markers = markers, label = null
            )
        } else {
            SnapGuide(
                kind = GuideKind.POINT, axis = GuideAxis.HORIZONTAL,
                start = SnapPoint(min(referenceRect.minX, targetRect.minX), snapPos),
                end = SnapPoint(max(referenceRect.maxX, targetRect.maxX), snapPos),
                markers = markers, label = null
            )
        }
    }

    private fun buildGapGuide(
        candidate: Candidate,
        targetRect: ScreenshotSnapping.DrawRect
    ): SnapGuide {
        val axis = candidate.axis
        val gapSize = candidate.gapSize!!
        val (start, end) = when (candidate.kind) {
            Kind.GAP_CENTER -> axisMax(candidate.gapBeforeRect!!, axis) to axisMin(candidate.gapAfterRect!!, axis)
            Kind.GAP_SIDE -> {
                val referenceRect = candidate.referenceRect!!
                if (candidate.gapSide == GapSide.AFTER) {
                    axisMax(referenceRect, axis) to axisMin(targetRect, axis)
                } else {
                    axisMax(targetRect, axis) to axisMin(referenceRect, axis)
                }
            }
            Kind.POINT -> error("gap bounds requested for non-gap candidate")
        }
        return buildGapGuideForBounds(axis, targetRect, start, end, gapSize)
    }

    private fun buildSplitGapCenterGuides(
        candidate: Candidate,
        targetRect: ScreenshotSnapping.DrawRect
    ): List<SnapGuide> {
        val before = candidate.gapBeforeRect ?: return emptyList()
        val after = candidate.gapAfterRect ?: return emptyList()
        val axis = candidate.axis

        val gapStart = axisMax(before, axis)
        val gapEnd = axisMin(after, axis)
        val targetStart = axisMin(targetRect, axis)
        val targetEnd = axisMax(targetRect, axis)

        if (targetStart <= gapStart + EPSILON || targetEnd >= gapEnd - EPSILON) return emptyList()

        val gapSize = candidate.gapSize ?: return emptyList()
        return listOf(
            buildGapGuideForBounds(axis, targetRect, gapStart, targetStart, gapSize),
            buildGapGuideForBounds(axis, targetRect, targetEnd, gapEnd, gapSize)
        )
    }

    private fun buildGapGuideForBounds(
        axis: Axis,
        targetRect: ScreenshotSnapping.DrawRect,
        start: Double,
        end: Double,
        gapSize: Double
    ): SnapGuide {
        return if (axis == Axis.X) {
            val y = targetRect.centerY
            val startPoint = SnapPoint(start, y)
            val endPoint = SnapPoint(end, y)
            SnapGuide(
                kind = GuideKind.GAP, axis = GuideAxis.HORIZONTAL,
                start = startPoint, end = endPoint,
                markers = listOf(startPoint, endPoint), label = gapSize
            )
        } else {
            val x = targetRect.centerX
            val startPoint = SnapPoint(x, start)
            val endPoint = SnapPoint(x, end)
            SnapGuide(
                kind = GuideKind.GAP, axis = GuideAxis.VERTICAL,
                start = startPoint, end = endPoint,
                markers = listOf(startPoint, endPoint), label = gapSize
            )
        }
    }

    private fun resolvePointMarkers(
        candidate: Candidate,
        targetRect: ScreenshotSnapping.DrawRect,
        referenceRect: ScreenshotSnapping.DrawRect,
        snapPos: Double,
        perpendicularCandidate: Candidate?
    ): List<SnapPoint> {
        val axis = candidate.axis
        val perpendicularAxis = perpendicularOf(axis)

        val targetPerpAnchor =
            if (perpendicularCandidate?.axis == perpendicularAxis) perpendicularCandidate.targetAnchor else null
        val referencePerpAnchor =
            if (perpendicularCandidate?.axis == perpendicularAxis &&
                perpendicularCandidate.referenceRect == referenceRect
            ) perpendicularCandidate.referenceAnchor else null

        val targetPerp = targetPerpAnchor
            ?.let { anchorPosition(targetRect, perpendicularAxis, it) }
            ?: axisCenter(targetRect, perpendicularAxis)
        val referencePerp = referencePerpAnchor
            ?.let { anchorPosition(referenceRect, perpendicularAxis, it) }
            ?: axisCenter(referenceRect, perpendicularAxis)

        val primary = if (axis == Axis.X) SnapPoint(snapPos, targetPerp) else SnapPoint(targetPerp, snapPos)
        val secondary = if (axis == Axis.X) SnapPoint(snapPos, referencePerp) else SnapPoint(referencePerp, snapPos)

        return if (primary == secondary) listOf(primary) else listOf(primary, secondary)
    }

    // ---------------------------------------------------------------- scoring (snap_scoring.rs)

    private fun selectBestCandidate(
        candidates: List<Candidate>,
        targetRect: ScreenshotSnapping.DrawRect,
        snapDistance: Double
    ): Candidate? {
        var best: Candidate? = null
        var bestStrength = 0.0
        val distanceSlack = distanceSlack(snapDistance)

        for (candidate in candidates) {
            val candidateStrength = candidateStrength(candidate, targetRect, snapDistance)
            val shouldReplace = best == null || compareCandidates(
                candidate, best!!, candidateStrength, bestStrength, distanceSlack
            ) > 0

            if (shouldReplace) {
                best = candidate
                bestStrength = candidateStrength
            }
        }
        return best
    }

    internal fun distanceSlack(snapDistance: Double): Double =
        min(snapDistance * PRIORITY_DISTANCE_SLACK_FACTOR, PRIORITY_DISTANCE_SLACK_MAX)

    private fun candidateStrength(
        candidate: Candidate,
        targetRect: ScreenshotSnapping.DrawRect,
        snapDistance: Double
    ): Double {
        val distanceStrength = distanceStrength(candidate.distance, snapDistance)
        return when (candidate.kind) {
            Kind.POINT -> clamp01(
                distanceStrength * POINT_DISTANCE_WEIGHT +
                    perpendicularStrength(candidate, targetRect, snapDistance) * POINT_PERPENDICULAR_WEIGHT +
                    pointAlignmentStrength(candidate) * POINT_ANCHOR_WEIGHT
            )
            Kind.GAP_CENTER, Kind.GAP_SIDE -> clamp01(
                gapStrengthScore(
                    distanceStrength,
                    candidate.gapFrequency ?: 0,
                    candidate.kind == Kind.GAP_CENTER
                ) * GAP_STRENGTH_SCALE
            )
        }
    }

    private fun gapStrengthScore(distanceStrength: Double, gapFrequency: Int, isCenter: Boolean): Double {
        val frequencyStrength = gapFrequencyStrength(gapFrequency)
        val kindStrength = if (isCenter) 1.0 else 0.85
        return distanceStrength * GAP_DISTANCE_WEIGHT +
            frequencyStrength * GAP_FREQUENCY_WEIGHT +
            kindStrength * GAP_KIND_WEIGHT
    }

    private fun distanceStrength(distance: Double, snapDistance: Double): Double {
        if (snapDistance <= 0.0) return 0.0
        val ratio = (distance / snapDistance).coerceIn(0.0, 1.0)
        return 1.0 - ratio
    }

    private fun perpendicularStrength(
        candidate: Candidate,
        targetRect: ScreenshotSnapping.DrawRect,
        snapDistance: Double
    ): Double {
        val referenceRect = candidate.referenceRect ?: return 0.0
        val perpendicularDistance = candidate.perpendicularDistance ?: 0.0
        val range = perpendicularRange(targetRect, referenceRect, candidate.axis, snapDistance)
        if (range <= 0.0) return 0.0
        val ratio = min(perpendicularDistance / range, 1.0)
        return 1.0 - ratio
    }

    private fun perpendicularRange(
        targetRect: ScreenshotSnapping.DrawRect,
        referenceRect: ScreenshotSnapping.DrawRect,
        axis: Axis,
        snapDistance: Double
    ): Double {
        val perpendicularAxis = perpendicularOf(axis)
        val targetSize = axisSize(targetRect, perpendicularAxis)
        val referenceSize = axisSize(referenceRect, perpendicularAxis)
        val sizeRange = max(targetSize, referenceSize) * PERPENDICULAR_SIZE_RANGE_FACTOR
        val snapRange = snapDistance * PERPENDICULAR_SNAP_RANGE_FACTOR
        return max(max(sizeRange, snapRange), snapDistance)
    }

    private fun pointAlignmentStrength(candidate: Candidate): Double {
        val targetAnchor = candidate.targetAnchor ?: return 0.0
        val referenceAnchor = candidate.referenceAnchor ?: return 0.0
        val priority = anchorPriority(targetAnchor, referenceAnchor).toDouble()
        return 1.0 - (priority / MAX_ANCHOR_PRIORITY)
    }

    private fun gapFrequencyStrength(gapFrequency: Int): Double {
        if (gapFrequency <= 0) return 0.0
        return 1.0 - (1.0 / (gapFrequency + 1.0))
    }

    private fun clamp01(value: Double): Double =
        if (value <= 0.0) 0.0 else if (value >= 1.0) 1.0 else value

    private fun compareCandidates(
        left: Candidate,
        right: Candidate,
        leftStrength: Double,
        rightStrength: Double,
        distanceSlack: Double
    ): Int {
        val strengthDelta = leftStrength - rightStrength
        if (abs(strengthDelta) > STRENGTH_SLACK) {
            return if (strengthDelta > 0.0) 1 else -1
        }

        val leftExact = isExact(left.offset)
        val rightExact = isExact(right.offset)
        if (leftExact != rightExact) return if (leftExact) 1 else -1

        val distanceDelta = left.distance - right.distance
        if (abs(distanceDelta) > distanceSlack) {
            return if (distanceDelta < 0.0) 1 else -1
        }

        val leftKindPriority = snapKindPriority(left.kind)
        val rightKindPriority = snapKindPriority(right.kind)
        if (leftKindPriority != rightKindPriority) {
            return if (leftKindPriority < rightKindPriority) 1 else -1
        }

        val kindSpecific = compareKindSpecificTieBreakers(left, right)
        if (kindSpecific != 0) return kindSpecific

        if (abs(distanceDelta) > EPSILON) {
            return if (distanceDelta < 0.0) 1 else -1
        }

        return 0
    }

    private fun snapKindPriority(kind: Kind): Int = when (kind) {
        Kind.POINT -> 0
        Kind.GAP_CENTER, Kind.GAP_SIDE -> 1
    }

    private fun compareKindSpecificTieBreakers(left: Candidate, right: Candidate): Int {
        if (left.kind == Kind.POINT && right.kind == Kind.POINT) return comparePointCandidates(left, right)
        if (left.kind != Kind.POINT && right.kind != Kind.POINT) return compareGapCandidates(left, right)
        return 0
    }

    private fun comparePointCandidates(left: Candidate, right: Candidate): Int {
        val leftPointPriority = pointPriority(left)
        val rightPointPriority = pointPriority(right)
        if (leftPointPriority != rightPointPriority) {
            return if (leftPointPriority < rightPointPriority) 1 else -1
        }

        val leftPerpendicularDistance = left.perpendicularDistance ?: Double.POSITIVE_INFINITY
        val rightPerpendicularDistance = right.perpendicularDistance ?: Double.POSITIVE_INFINITY
        val perpendicularDelta = leftPerpendicularDistance - rightPerpendicularDistance
        if (abs(perpendicularDelta) <= EPSILON) return 0

        return if (perpendicularDelta < 0.0) 1 else -1
    }

    private fun compareGapCandidates(left: Candidate, right: Candidate): Int {
        val leftGapFrequency = left.gapFrequency ?: 0
        val rightGapFrequency = right.gapFrequency ?: 0
        if (leftGapFrequency != rightGapFrequency) {
            return if (leftGapFrequency > rightGapFrequency) 1 else -1
        }

        val leftGapKindPriority = gapKindPriority(left)
        val rightGapKindPriority = gapKindPriority(right)
        if (leftGapKindPriority == rightGapKindPriority) return 0

        return if (leftGapKindPriority < rightGapKindPriority) 1 else -1
    }

    private fun pointPriority(candidate: Candidate): Int =
        anchorPriority(candidate.targetAnchor!!, candidate.referenceAnchor!!)

    private fun gapKindPriority(candidate: Candidate): Int = when (candidate.kind) {
        Kind.GAP_CENTER -> 0
        Kind.POINT, Kind.GAP_SIDE -> 1
    }

    // ---------------------------------------------------------------- shared helpers (snap.rs impl)

    private fun overlapsPerpendicular(
        a: ScreenshotSnapping.DrawRect,
        b: ScreenshotSnapping.DrawRect,
        axis: Axis
    ): Boolean = if (axis == Axis.X) {
        a.maxY >= b.minY && a.minY <= b.maxY
    } else {
        a.maxX >= b.minX && a.minX <= b.maxX
    }

    internal fun anchorPosition(
        rect: ScreenshotSnapping.DrawRect,
        axis: Axis,
        anchor: Anchor
    ): Double = when (axis) {
        Axis.X -> when (anchor) {
            Anchor.START -> rect.minX
            Anchor.CENTER -> rect.centerX
            Anchor.END -> rect.maxX
        }
        Axis.Y -> when (anchor) {
            Anchor.START -> rect.minY
            Anchor.CENTER -> rect.centerY
            Anchor.END -> rect.maxY
        }
    }

    internal fun axisMin(rect: ScreenshotSnapping.DrawRect, axis: Axis): Double =
        if (axis == Axis.X) rect.minX else rect.minY

    internal fun axisMax(rect: ScreenshotSnapping.DrawRect, axis: Axis): Double =
        if (axis == Axis.X) rect.maxX else rect.maxY

    internal fun axisCenter(rect: ScreenshotSnapping.DrawRect, axis: Axis): Double =
        if (axis == Axis.X) rect.centerX else rect.centerY

    internal fun axisSize(rect: ScreenshotSnapping.DrawRect, axis: Axis): Double =
        if (axis == Axis.X) rect.width else rect.height

    private fun perpendicularOf(axis: Axis): Axis = if (axis == Axis.X) Axis.Y else Axis.X

    internal fun rectPerpendicularDistance(
        a: ScreenshotSnapping.DrawRect,
        b: ScreenshotSnapping.DrawRect,
        axis: Axis
    ): Double = if (axis == Axis.X) {
        when {
            a.maxY < b.minY -> b.minY - a.maxY
            b.maxY < a.minY -> a.minY - b.maxY
            else -> 0.0
        }
    } else {
        when {
            a.maxX < b.minX -> b.minX - a.maxX
            b.maxX < a.minX -> a.minX - b.maxX
            else -> 0.0
        }
    }

    internal fun anchorPriority(target: Anchor, reference: Anchor): Int = when {
        target == Anchor.CENTER && reference == Anchor.CENTER -> 0
        target == reference -> 1
        target == Anchor.CENTER || reference == Anchor.CENTER -> 2
        else -> 3
    }

    private fun isExact(offset: Double): Boolean = abs(offset) <= EPSILON

    private fun deduplicateAnchors(anchors: List<Anchor>): List<Anchor> {
        if (anchors.size < 2) return anchors
        val deduplicated = mutableListOf<Anchor>()
        for (anchor in anchors) {
            if (anchor !in deduplicated) deduplicated += anchor
        }
        return deduplicated
    }
}
