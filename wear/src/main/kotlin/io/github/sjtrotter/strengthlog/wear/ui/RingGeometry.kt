package io.github.sjtrotter.strengthlog.wear.ui

/** One ring segment's visual state — the composable maps this to a color token. */
enum class RingSegmentState { DONE, CURRENT, TRACK }

data class RingSegment(val startAngleDeg: Float, val sweepAngleDeg: Float, val state: RingSegmentState)

/**
 * Pure port of the design digest's `ring()` (appendix) — one segment per round,
 * round caps, gaps between segments — translated from the mock's SVG
 * `stroke-dasharray`/circumference math into start/sweep angles for a Compose
 * `Canvas.drawArc`. The digest's gap is 6px of *arc length* at `r=104` on a
 * 224 viewBox; [GAP_DEG] is that same physical gap expressed as an angle
 * (`6 / (2πr) * 360°`) so the ring keeps the source's proportions regardless
 * of the screen's actual pixel size. Segments start at 12 o'clock (-90°) and
 * proceed clockwise, matching the mock.
 */
private const val RING_R = 104.0
private const val RING_GAP_PX = 6.0
val RING_GAP_DEG: Float = (RING_GAP_PX / (2 * Math.PI * RING_R) * 360.0).toFloat()

fun ringSegments(
    roundCount: Int,
    doneFlags: List<Boolean>,
    currentIndex: Int,
    gapDeg: Float = RING_GAP_DEG,
): List<RingSegment> {
    if (roundCount <= 0) return emptyList()
    val segmentDeg = 360f / roundCount
    val sweep = (segmentDeg - gapDeg).coerceAtLeast(0f)
    return (0 until roundCount).map { i ->
        val state = when {
            doneFlags.getOrElse(i) { false } -> RingSegmentState.DONE
            i == currentIndex -> RingSegmentState.CURRENT
            else -> RingSegmentState.TRACK
        }
        RingSegment(
            startAngleDeg = -90f + i * segmentDeg + gapDeg / 2f,
            sweepAngleDeg = sweep,
            state = state,
        )
    }
}
