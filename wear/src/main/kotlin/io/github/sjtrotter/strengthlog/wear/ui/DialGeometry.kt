package io.github.sjtrotter.strengthlog.wear.ui

import kotlin.math.sqrt

/**
 * The dial's layout, as pure math (redesign brief §2, amended by dial v2 §2).
 * Every number below is a logical px on the brief's 384px reference face; nothing
 * is ever drawn at a hardcoded size — the composable measures the real face and
 * everything scales from that one measurement, which is what makes the layout
 * hold on a 41mm and a 45mm watch alike.
 *
 * There are only five legal zones — day ring, exercise ring, clock ring, label
 * bands, centre disc — and an element that doesn't belong to one of them doesn't
 * go on screen. Keeping the arithmetic here (rather than in `Modifier.padding`
 * calls) is the structural half of that rule: a radius is *derived*, never nudged.
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
    const val DISC_DIAMETER = 204f
    const val TOP_BAND_INSET = 48f
    const val BOTTOM_BAND_INSET = 44f

    /** The rest-over halo's width (§8). */
    const val BLOOM_WIDTH = 10f

    /**
     * The clock ring — a draining rest, a filling hold, a filling undo. One
     * stroke for all three because they are one meaning: the ring touching the
     * disc is the clock running right now (v2 §3).
     */
    const val CLOCK_RING_STROKE = 7f

    /** Keeps a band row's far corners clear of the curve (v2 §2). */
    private const val BAND_SAFETY = 4f

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

    /** Innermost ring: the clock, stroked just inside the disc's own rim (v2 §3). */
    fun clockRing(diameterPx: Float): DialRing {
        val strokePx = px(CLOCK_RING_STROKE, diameterPx)
        return DialRing(radiusPx = discRadiusPx(diameterPx) - strokePx / 2f, strokePx = strokePx)
    }

    /**
     * The widest a horizontal row may be, given [rowFarEdgeYPx] — the distance
     * from the nearer pole to the row's outer edge, which is the narrowest chord
     * the row touches. A fixed side inset cannot express this: at a band's height
     * the visible screen *is* a chord, so an inset wide enough to clear the curve
     * there throws away the middle of the face everywhere else (v2 §2).
     */
    fun bandMaxWidthPx(diameterPx: Float, rowFarEdgeYPx: Float): Float {
        val radius = diameterPx / 2f
        val depth = rowFarEdgeYPx.coerceIn(0f, diameterPx)
        val halfChord = sqrt((radius * radius - (radius - depth) * (radius - depth)).coerceAtLeast(0f))
        return (2f * halfChord - 2f * px(BAND_SAFETY, diameterPx)).coerceAtLeast(0f)
    }

    /**
     * [count] evenly spaced segments starting at 12 o'clock, each shrunk by
     * [gapDeg] so the gap centres inside a fixed slot — a slot's position depends
     * on nothing but the count, so a segment never shifts under the lifter's eye
     * while they are reading the ring as a count.
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
