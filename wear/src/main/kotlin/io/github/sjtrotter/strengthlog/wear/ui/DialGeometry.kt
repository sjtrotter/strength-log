package io.github.sjtrotter.strengthlog.wear.ui

/**
 * The dial's layout, as pure math (redesign brief §2, amended by dial v2 §2 and
 * v3 §1). Every number below is a logical px on the brief's 384px reference face;
 * nothing is ever drawn at a hardcoded size — the composable measures the real
 * face and everything scales from that one measurement, which is what makes the
 * layout hold on a 41mm and a 45mm watch alike.
 *
 * There are only five legal zones — cycle ring, exercise ring, clock ring, label
 * bands, centre disc — and an element that doesn't belong to one of them doesn't
 * go on screen. Keeping the arithmetic here (rather than in `Modifier.padding`
 * calls) is the structural half of that rule: a radius is *derived*, never nudged.
 */

/** One item of the inner ring — a round of the current exercise, or (on the
 *  "today" screen) a whole exercise. `PEEKED` is the crown-scrub marker: white,
 *  "you are looking here", distinct from the accent "you are here" (§4). */
enum class RoundState { DONE, CURRENT, UPCOMING, PEEKED }

/** How much of a day's name its cycle segment can hold — "DAY C", "C", or nothing. */
enum class CycleLabelFit { FULL, SHORT, NONE }

/** An arc in Compose `drawArc` terms: 0° is 3 o'clock, sweeping clockwise. */
data class DialArc(val startAngleDeg: Float, val sweepAngleDeg: Float)

/** A ring's centreline radius and stroke width, in real px for the measured face. */
data class DialRing(val radiusPx: Float, val strokePx: Float)

/**
 * The annulus a label band is laid along, in real px: the free ring between the
 * exercise ring's inner edge and the disc's rim. The glyph row is centred in it,
 * so [radiusPx] is both the annulus's mid-radius and the row's centreline.
 *
 * [insetPx] is what a `CurvedLayout` is padded by — it lays its children against
 * its own outer radius, so shrinking the layout is how the band is placed.
 */
data class DialBand(val radiusPx: Float, val thicknessPx: Float, val insetPx: Float)

object DialGeometry {

    /** The brief's design canvas; every constant here is px at this diameter. */
    const val REFERENCE_DIAMETER = 384f

    /** The outer ring — the program cycle. Wide enough to carry a label (v3 §1). */
    const val CYCLE_RING_INSET = 9f
    const val CYCLE_RING_STROKE = 22f

    /** Today's progress, riding the inner edge of today's own cycle segment (v3 §1). */
    const val CYCLE_PROGRESS_STROKE = 6f

    /** Ambient keeps v2's hairline outer arc at v2's radius: a 22px stroke lit for
     *  hours is exactly the burn-in the ambient rules exist to prevent (v3 §1). */
    const val AMBIENT_RING_STROKE = 5f

    const val EXERCISE_RING_INSET = 40f
    const val EXERCISE_RING_STROKE = 14f
    const val DISC_DIAMETER = 204f

    /** The rest-over halo's width (§8). */
    const val BLOOM_WIDTH = 10f

    /**
     * The clock ring — a draining rest, a filling hold, a filling undo. One
     * stroke for all three because they are one meaning: the ring touching the
     * disc is the clock running right now (v2 §3).
     */
    const val CLOCK_RING_STROKE = 7f

    /**
     * How far a band may run either side of its pole, together: ±60°, i.e. from
     * 10 o'clock to 2 o'clock. Past that a glyph is tilted more than 60° off
     * upright and stops reading as part of a word, and the two bands would start
     * closing on each other at the equator. Text longer than this ellipsizes.
     */
    const val BAND_MAX_SWEEP_DEG = 120f

    /** The pulsing status dot and the angular slot it rides in; the slack is the
     *  gap to the text, half of it either side (curved rows have no "start"). */
    const val BAND_DOT_DIAMETER = 10f
    const val BAND_DOT_SLOT = 24f

    /** ~4° between segments (§4). */
    const val SEGMENT_GAP_DEG = 4f

    /** Breathing room a cycle label needs either side of itself before it may claim
     *  its segment — without it a 5-day ring reads as one unbroken word (v3 §1). */
    const val CYCLE_LABEL_MARGIN_DEG = 8f

    /** 12 o'clock, where every ring starts (§4). */
    const val TOP_ANGLE_DEG = -90f

    private const val PI = kotlin.math.PI.toFloat()

    /** How much bigger (or smaller) the real face is than the reference canvas. */
    fun scale(diameterPx: Float): Float = diameterPx / REFERENCE_DIAMETER

    /** A reference-canvas measurement in real px for the measured face. */
    fun px(referenceValue: Float, diameterPx: Float): Float = referenceValue * scale(diameterPx)

    /** Outer ring: the program cycle, one segment per day. */
    fun cycleRing(diameterPx: Float): DialRing =
        ring(diameterPx, CYCLE_RING_INSET, CYCLE_RING_STROKE)

    /** Today's progress arc, on the inner edge of the cycle ring's own annulus. */
    fun cycleProgressRing(diameterPx: Float): DialRing {
        val ring = cycleRing(diameterPx)
        val strokePx = px(CYCLE_PROGRESS_STROKE, diameterPx)
        return DialRing(
            radiusPx = ring.radiusPx - ring.strokePx / 2f + strokePx / 2f,
            strokePx = strokePx,
        )
    }

    /** The ambient dial's outer arc: v2's hairline, at v2's radius. */
    fun ambientRing(diameterPx: Float): DialRing =
        ring(diameterPx, CYCLE_RING_INSET, AMBIENT_RING_STROKE)

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
     * Where a label band lives: the free annulus between the exercise ring's
     * inner edge and the disc's rim. On a round face that space is an arc, so the
     * band is an arc — there is no width to bound and no chord to clear, and the
     * row can no longer reach the bezel at any length (curved-bands brief §1).
     */
    fun bandArc(diameterPx: Float): DialBand {
        val ring = exerciseRing(diameterPx)
        val outer = ring.radiusPx - ring.strokePx / 2f
        val inner = discRadiusPx(diameterPx)
        return DialBand(
            radiusPx = (outer + inner) / 2f,
            thicknessPx = outer - inner,
            insetPx = diameterPx / 2f - outer,
        )
    }

    /**
     * Where a cycle label lives: the cycle ring's own annulus, so the label rides
     * the colour it belongs to. The row is centred on the ring's centreline — at
     * BAND_SECONDARY the line box is a shade taller than the stroke, but the caps
     * a label is written in are not, and caps are all this row ever draws.
     */
    fun cycleLabelBand(diameterPx: Float): DialBand {
        val ring = cycleRing(diameterPx)
        return DialBand(
            radiusPx = ring.radiusPx,
            thicknessPx = ring.strokePx,
            insetPx = diameterPx / 2f - (ring.radiusPx + ring.strokePx / 2f),
        )
    }

    /** The angle an arc of [arcLengthPx] subtends on a circle of [radiusPx] — how
     *  a length budget becomes a sweep budget, which is all a curved row bounds. */
    fun bandSweepDeg(arcLengthPx: Float, radiusPx: Float): Float =
        if (radiusPx <= 0f) 0f else (arcLengthPx / radiusPx) * 180f / PI

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

    /** A continuous proportion arc — a draining rest, a filling hold, a full rim. */
    fun proportionArc(fraction: Float): DialArc =
        DialArc(TOP_ANGLE_DEG, 360f * fraction.coerceIn(0f, 1f))

    /** [fraction] of one segment, from its own start — today's progress inside
     *  today's slice of the cycle, so "where am I" and "how far" are one glance. */
    fun progressWithin(segment: DialArc, fraction: Float): DialArc =
        DialArc(segment.startAngleDeg, segment.sweepAngleDeg * fraction.coerceIn(0f, 1f))

    /** The angle a segment's label is anchored at: the middle of the segment. */
    fun midAngleDeg(segment: DialArc): Float = segment.startAngleDeg + segment.sweepAngleDeg / 2f

    /**
     * True where a clockwise curved row would hang upside down — the bottom half of
     * the face, which is why the bottom band runs the other way (curved-bands §1).
     */
    fun isBottomHalf(angleDeg: Float): Boolean {
        val normalized = ((angleDeg % 360f) + 360f) % 360f
        return normalized > 0f && normalized < 180f
    }

    /**
     * Which form of a day's label its segment can hold, given the sweep each form
     * measures to on this face: the long one, the letter alone, or nothing at all.
     * Derived from measurement rather than from the day count — a 5-day ring on a
     * 45mm watch is not the same budget as a 5-day ring on a 41mm one.
     */
    fun cycleLabelFit(
        segmentSweepDeg: Float,
        fullSweepDeg: Float,
        shortSweepDeg: Float,
    ): CycleLabelFit = when {
        segmentSweepDeg >= fullSweepDeg + CYCLE_LABEL_MARGIN_DEG -> CycleLabelFit.FULL
        segmentSweepDeg >= shortSweepDeg + CYCLE_LABEL_MARGIN_DEG -> CycleLabelFit.SHORT
        else -> CycleLabelFit.NONE
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
