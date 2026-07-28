package io.github.sjtrotter.strengthlog.wear.ui

/**
 * The dial's layout, as pure math (redesign brief §2). Every number below is a
 * logical px on the brief's 384px reference face; nothing is ever drawn at a
 * hardcoded size — the composable measures the real face and everything scales
 * from that one measurement, which is what makes the layout hold on a 41mm and
 * a 45mm watch alike.
 *
 * There are only four legal zones — day ring, exercise ring, label bands, centre
 * disc — and an element that doesn't belong to one of them doesn't go on screen.
 * Keeping the arithmetic here (rather than in `Modifier.padding` calls) is the
 * structural half of that rule: a radius is *derived*, never nudged.
 */

/** One item of the inner ring — a round of the current exercise, or (on the
 *  "today" screen) a whole exercise. `PEEKED` is the crown-scrub marker: white,
 *  "you are looking here", distinct from the accent "you are here" (§4). */
enum class RoundState { DONE, CURRENT, UPCOMING, PEEKED }

/** An arc in Compose `drawArc` terms: 0° is 3 o'clock, sweeping clockwise. */
data class DialArc(val startAngleDeg: Float, val sweepAngleDeg: Float)

/** A ring's centreline radius and stroke width, in real px for the measured face. */
data class DialRing(val radiusPx: Float, val strokePx: Float)

object DialGeometry {

    /** The brief's design canvas; every constant here is px at this diameter. */
    const val REFERENCE_DIAMETER = 384f

    const val DAY_RING_INSET = 9f
    const val DAY_RING_STROKE = 5f
    const val EXERCISE_RING_INSET = 26f
    const val EXERCISE_RING_STROKE = 14f
    const val DISC_DIAMETER = 176f
    const val BAND_SIDE_INSET = 56f
    const val TOP_BAND_INSET = 52f
    const val BOTTOM_BAND_INSET = 48f

    /** The rest-over halo's width (§8). */
    const val BLOOM_WIDTH = 10f

    /** The undo hold's progress ring, drawn on the disc's own edge (§6). */
    const val HOLD_RING_STROKE = 4f

    /** ~4° between segments (§4). */
    const val SEGMENT_GAP_DEG = 4f

    /** 12 o'clock, where every ring starts (§4). */
    const val TOP_ANGLE_DEG = -90f

    /** How much bigger (or smaller) the real face is than the reference canvas. */
    fun scale(diameterPx: Float): Float = diameterPx / REFERENCE_DIAMETER

    /** A reference-canvas measurement in real px for the measured face. */
    fun px(referenceValue: Float, diameterPx: Float): Float = referenceValue * scale(diameterPx)

    /** Outer ring: the whole day. */
    fun dayRing(diameterPx: Float): DialRing =
        ring(diameterPx, DAY_RING_INSET, DAY_RING_STROKE)

    /** Inner ring: the current exercise's rounds. */
    fun exerciseRing(diameterPx: Float): DialRing =
        ring(diameterPx, EXERCISE_RING_INSET, EXERCISE_RING_STROKE)

    /**
     * An inset is measured from the face edge to the ring's *outer* edge, so the
     * centreline the arc is stroked along sits half a stroke further in.
     */
    private fun ring(diameterPx: Float, inset: Float, stroke: Float): DialRing {
        val strokePx = px(stroke, diameterPx)
        return DialRing(
            radiusPx = diameterPx / 2f - px(inset, diameterPx) - strokePx / 2f,
            strokePx = strokePx,
        )
    }

    fun discRadiusPx(diameterPx: Float): Float = px(DISC_DIAMETER, diameterPx) / 2f

    /**
     * [count] evenly spaced segments starting at 12 o'clock, each shrunk by
     * [gapDeg] so the gap centres inside a fixed slot — the slots themselves
     * never move, which is what keeps "one segment per round" readable as a
     * count when a gap animates open or shut.
     */
    fun segments(count: Int, gapDeg: Float = SEGMENT_GAP_DEG): List<DialArc> {
        if (count <= 0) return emptyList()
        val slotDeg = 360f / count
        val sweep = (slotDeg - gapDeg).coerceAtLeast(0f)
        return (0 until count).map { i ->
            DialArc(startAngleDeg = TOP_ANGLE_DEG + i * slotDeg + gapDeg / 2f, sweepAngleDeg = sweep)
        }
    }

    /** A continuous proportion arc — day progress, a draining rest, a filling hold. */
    fun proportionArc(fraction: Float): DialArc =
        DialArc(TOP_ANGLE_DEG, 360f * fraction.coerceIn(0f, 1f))

    /**
     * How much of [arc] falls inside the leading [fraction] of the circle — used
     * to trim the segment ring down to a draining/filling arc without swapping
     * shapes (the melt in §8 is one code path, not a cross-fade).
     */
    fun trimToFraction(arc: DialArc, fraction: Float): DialArc {
        val visibleDeg = 360f * fraction.coerceIn(0f, 1f)
        val offsetDeg = arc.startAngleDeg - TOP_ANGLE_DEG
        return arc.copy(sweepAngleDeg = (visibleDeg - offsetDeg).coerceIn(0f, arc.sweepAngleDeg))
    }

    /**
     * The ring's reading of a set of items: done ones are green, the one you're
     * on is the single accent segment, the rest are track. [currentIndex] earns
     * the accent only if it isn't already done, so "exactly one accent segment,
     * ever" (§4) holds even when everything is finished.
     *
     * [peekedIndex] is the crown's white scrub marker, and it is checked *after*
     * the accent: where the lifter is looking may coincide with where they are,
     * and in that case the accent stays — the peek marker is a second reading of
     * the ring, never a replacement for the first.
     */
    fun roundStates(
        doneFlags: List<Boolean>,
        currentIndex: Int,
        peekedIndex: Int? = null,
    ): List<RoundState> =
        doneFlags.mapIndexed { i, done ->
            when {
                i == currentIndex && !done -> RoundState.CURRENT
                i == peekedIndex -> RoundState.PEEKED
                done -> RoundState.DONE
                else -> RoundState.UPCOMING
            }
        }
}
