package io.github.sjtrotter.strengthlog.wear.ui

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dial's layout math (brief §2/§4). Every radius is derived from the
 * measured face, so the same numbers have to hold on a 384px watch and a 454px
 * one — that proportionality is what the overlap bug can't survive.
 */
class DialGeometryTest {

    private val tolerance = 0.01f

    @Test
    fun `the cycle ring sits its inset plus half a stroke inside the face`() {
        val ring = DialGeometry.cycleRing(384f)
        assertEquals(172f, ring.radiusPx, tolerance) // 192 - 9 - 22/2
        assertEquals(22f, ring.strokePx, tolerance)
    }

    @Test
    fun `the exercise ring clears the cycle ring, which now carries type`() {
        val exercise = DialGeometry.exerciseRing(384f)
        assertEquals(145f, exercise.radiusPx, tolerance) // 192 - 40 - 14/2
        assertEquals(14f, exercise.strokePx, tolerance)
        val cycle = DialGeometry.cycleRing(384f)
        assertTrue(
            exercise.radiusPx + exercise.strokePx / 2f < cycle.radiusPx - cycle.strokePx / 2f,
            "the exercise ring runs into the cycle ring",
        )
    }

    @Test
    fun `today's progress rides the inner edge of the cycle ring`() {
        val progress = DialGeometry.cycleProgressRing(384f)
        val cycle = DialGeometry.cycleRing(384f)
        assertEquals(6f, progress.strokePx, tolerance)
        assertEquals(164f, progress.radiusPx, tolerance) // 172 - 11 + 3
        // Wholly inside the segment it reports on, top edge included.
        assertTrue(progress.radiusPx - progress.strokePx / 2f >= cycle.radiusPx - cycle.strokePx / 2f - tolerance)
        assertTrue(progress.radiusPx + progress.strokePx / 2f <= cycle.radiusPx + cycle.strokePx / 2f + tolerance)
    }

    @Test
    fun `a cycle label is centred in the ring it names`() {
        val band = DialGeometry.cycleLabelBand(384f)
        val ring = DialGeometry.cycleRing(384f)
        assertEquals(ring.radiusPx, band.radiusPx, tolerance)
        assertEquals(ring.strokePx, band.thicknessPx, tolerance)
        assertEquals(DialGeometry.CYCLE_RING_INSET, band.insetPx, tolerance)
    }

    @Test
    fun `ambient keeps v2's hairline arc where v2 drew it`() {
        val ambient = DialGeometry.ambientRing(384f)
        assertEquals(180.5f, ambient.radiusPx, tolerance) // 192 - 9 - 5/2
        assertEquals(5f, ambient.strokePx, tolerance)
    }

    @Test
    fun `the disc is 204px across at the reference size`() {
        assertEquals(102f, DialGeometry.discRadiusPx(384f), tolerance)
    }

    @Test
    fun `the clock ring sits on the disc's rim, inside the exercise ring`() {
        val clock = DialGeometry.clockRing(384f)
        assertEquals(7f, clock.strokePx, tolerance)
        assertEquals(102f - 3.5f, clock.radiusPx, tolerance)
        assertTrue(clock.radiusPx < DialGeometry.exerciseRing(384f).radiusPx)
        // Wholly inside the disc, so the undo fill and a rest share one rim.
        assertTrue(clock.radiusPx + clock.strokePx / 2f <= DialGeometry.discRadiusPx(384f) + tolerance)
    }

    @Test
    fun `every measurement scales with the measured face`() {
        val scale = 454f / 384f
        assertEquals(172f * scale, DialGeometry.cycleRing(454f).radiusPx, tolerance)
        assertEquals(22f * scale, DialGeometry.cycleRing(454f).strokePx, tolerance)
        assertEquals(164f * scale, DialGeometry.cycleProgressRing(454f).radiusPx, tolerance)
        assertEquals(145f * scale, DialGeometry.exerciseRing(454f).radiusPx, tolerance)
        assertEquals(14f * scale, DialGeometry.exerciseRing(454f).strokePx, tolerance)
        assertEquals(102f * scale, DialGeometry.discRadiusPx(454f), tolerance)
        assertEquals(7f * scale, DialGeometry.clockRing(454f).strokePx, tolerance)
    }

    @Test
    fun `a ring never touches the bezel it is inset from`() {
        listOf(324f, 384f, 454f).forEach { diameter ->
            val ring = DialGeometry.cycleRing(diameter)
            assertTrue(ring.radiusPx + ring.strokePx / 2f < diameter / 2f, "cycle ring escapes at $diameter")
        }
    }

    @Test
    fun `segments start at 12 o'clock and run clockwise`() {
        val segments = DialGeometry.segments(4, gapDeg = 0f)
        assertEquals(-90f, segments.first().startAngleDeg, tolerance)
        assertEquals(90f, segments[1].startAngleDeg - segments[0].startAngleDeg, tolerance)
    }

    @Test
    fun `the gap shrinks each segment without moving the slots`() {
        val gapless = DialGeometry.segments(6, gapDeg = 0f)
        val gapped = DialGeometry.segments(6)
        assertEquals(gapless[0].sweepAngleDeg - DialGeometry.SEGMENT_GAP_DEG, gapped[0].sweepAngleDeg, tolerance)
        assertEquals(
            gapless[1].startAngleDeg - gapless[0].startAngleDeg,
            gapped[1].startAngleDeg - gapped[0].startAngleDeg,
            tolerance,
        )
    }

    @Test
    fun `one segment per round, and none for an empty exercise`() {
        assertEquals(6, DialGeometry.segments(6).size)
        assertTrue(DialGeometry.segments(0).isEmpty())
    }

    @Test
    fun `a proportion arc is that fraction of the circle, clamped`() {
        assertEquals(180f, DialGeometry.proportionArc(0.5f).sweepAngleDeg, tolerance)
        assertEquals(360f, DialGeometry.proportionArc(1.4f).sweepAngleDeg, tolerance)
        assertEquals(0f, DialGeometry.proportionArc(-1f).sweepAngleDeg, tolerance)
    }

    // --- bands are arcs through their annulus (curved-bands §1) -------------------

    @Test
    fun `the band annulus is the free ring between the exercise ring and the disc`() {
        val band = DialGeometry.bandArc(384f)
        // Exercise ring inner edge 145 - 7 = 138; disc rim 102. The wider cycle ring
        // took 14px off this annulus and the bands simply followed it in (v3 §1).
        assertEquals(36f, band.thicknessPx, tolerance)
        assertEquals(120f, band.radiusPx, tolerance) // centred in it
        assertEquals(54f, band.insetPx, tolerance) // 192 - 138
    }

    @Test
    fun `a band row cannot reach either neighbour, let alone the bezel`() {
        listOf(324f, 384f, 454f).forEach { diameter ->
            val band = DialGeometry.bandArc(diameter)
            val ring = DialGeometry.exerciseRing(diameter)
            assertTrue(
                band.radiusPx + band.thicknessPx / 2f <= ring.radiusPx - ring.strokePx / 2f + tolerance,
                "band overlaps the exercise ring at $diameter",
            )
            assertTrue(
                band.radiusPx - band.thicknessPx / 2f >= DialGeometry.discRadiusPx(diameter) - tolerance,
                "band overlaps the disc at $diameter",
            )
        }
    }

    @Test
    fun `the band annulus scales with the measured face`() {
        val scale = 454f / 384f
        val band = DialGeometry.bandArc(454f)
        assertEquals(120f * scale, band.radiusPx, tolerance)
        assertEquals(36f * scale, band.thicknessPx, tolerance)
        assertEquals(54f * scale, band.insetPx, tolerance)
    }

    @Test
    fun `an arc as long as its circle sweeps the whole circle`() {
        val radius = 127f
        assertEquals(360f, DialGeometry.bandSweepDeg(2f * PI.toFloat() * radius, radius), tolerance)
        assertEquals(180f, DialGeometry.bandSweepDeg(PI.toFloat() * radius, radius), tolerance)
        assertEquals(0f, DialGeometry.bandSweepDeg(0f, radius), tolerance)
    }

    @Test
    fun `a wider face turns the same arc length into a smaller sweep`() {
        val small = DialGeometry.bandSweepDeg(100f, DialGeometry.bandArc(384f).radiusPx)
        val large = DialGeometry.bandSweepDeg(100f, DialGeometry.bandArc(454f).radiusPx)
        assertTrue(large < small)
    }

    @Test
    fun `the two bands cannot meet at the equator`() {
        // Each runs half its sweep either side of its pole; 90° apart is the most
        // either may travel before they touch.
        assertTrue(DialGeometry.BAND_MAX_SWEEP_DEG / 2f < 90f)
    }

    @Test
    fun `the dot's slot leaves the text most of the band`() {
        val band = DialGeometry.bandArc(384f)
        val slot = DialGeometry.bandSweepDeg(DialGeometry.px(DialGeometry.BAND_DOT_SLOT, 384f), band.radiusPx)
        assertTrue(slot > 0f)
        assertTrue(DialGeometry.BAND_MAX_SWEEP_DEG - slot > 100f, "the dot ate ${slot}° of the band")
    }

    // --- the cycle ring (v3 §1) ---------------------------------------------------

    @Test
    fun `a day's segment is its slot less the gap, whatever the program's length`() {
        listOf(3, 5, 7).forEach { days ->
            val segments = DialGeometry.segments(days)
            assertEquals(days, segments.size)
            assertEquals(360f / days - DialGeometry.SEGMENT_GAP_DEG, segments[0].sweepAngleDeg, tolerance)
            assertEquals(-90f, segments.first().startAngleDeg - DialGeometry.SEGMENT_GAP_DEG / 2f, tolerance)
        }
        // The whole rim, less one gap, when the phone published no cycle at all.
        assertEquals(356f, DialGeometry.segments(1).single().sweepAngleDeg, tolerance)
    }

    @Test
    fun `progress inside a segment starts where the segment does and cannot leave it`() {
        val segment = DialGeometry.segments(5)[2]
        val half = DialGeometry.progressWithin(segment, 0.5f)
        assertEquals(segment.startAngleDeg, half.startAngleDeg, tolerance)
        assertEquals(segment.sweepAngleDeg / 2f, half.sweepAngleDeg, tolerance)
        assertEquals(segment.sweepAngleDeg, DialGeometry.progressWithin(segment, 1.4f).sweepAngleDeg, tolerance)
        assertEquals(0f, DialGeometry.progressWithin(segment, -1f).sweepAngleDeg, tolerance)
    }

    @Test
    fun `a label is anchored at the middle of its own segment`() {
        // Segments start *at* 12 and run clockwise, so the first one's middle is
        // halfway to 3 o'clock — a label sits over its own slice, not over the top.
        val segments = DialGeometry.segments(4)
        assertEquals(-45f, DialGeometry.midAngleDeg(segments[0]), tolerance)
        assertEquals(45f, DialGeometry.midAngleDeg(segments[1]), tolerance)
        assertEquals(135f, DialGeometry.midAngleDeg(segments[2]), tolerance)
        assertEquals(225f, DialGeometry.midAngleDeg(segments[3]), tolerance)
        // Which is what decides the two that have to run counter-clockwise.
        assertEquals(
            listOf(false, true, true, false),
            segments.map { DialGeometry.isBottomHalf(DialGeometry.midAngleDeg(it)) },
        )
    }

    @Test
    fun `only the bottom half needs turning around`() {
        assertTrue(DialGeometry.isBottomHalf(90f)) // 6 o'clock
        assertTrue(DialGeometry.isBottomHalf(170f))
        assertFalse(DialGeometry.isBottomHalf(-90f)) // 12 o'clock
        assertFalse(DialGeometry.isBottomHalf(0f)) // 3 o'clock — upright either way
        assertFalse(DialGeometry.isBottomHalf(180f)) // 9 o'clock
        assertTrue(DialGeometry.isBottomHalf(450f)) // normalises past a full turn
    }

    @Test
    fun `a segment takes the longest label its own sweep can hold`() {
        // Measured sweeps stand in for the real face's text: "DAY A" is the long
        // form, "A" the short one. The margin is what keeps a ring of labels from
        // reading as one word.
        val full = 24f
        val short = 6f
        assertEquals(CycleLabelFit.FULL, DialGeometry.cycleLabelFit(40f, full, short))
        assertEquals(CycleLabelFit.FULL, DialGeometry.cycleLabelFit(32f, full, short))
        assertEquals(CycleLabelFit.SHORT, DialGeometry.cycleLabelFit(31f, full, short))
        assertEquals(CycleLabelFit.SHORT, DialGeometry.cycleLabelFit(14f, full, short))
        assertEquals(CycleLabelFit.NONE, DialGeometry.cycleLabelFit(13f, full, short))
    }

    @Test
    fun `a 3, 5 and 7 day ring all keep the long label on a 384px face`() {
        // "DAY A" at BAND_SECONDARY measures about 70px on the reference face, which
        // is 23° of the 172px label arc; the tightest ring here is a 7-day one at
        // 47°. The rule is measured, not assumed — this pins the headroom.
        val labelSweep = DialGeometry.bandSweepDeg(70f, DialGeometry.cycleLabelBand(384f).radiusPx)
        listOf(3, 5, 7).forEach { days ->
            assertEquals(
                CycleLabelFit.FULL,
                DialGeometry.cycleLabelFit(DialGeometry.segments(days)[0].sweepAngleDeg, labelSweep, labelSweep / 4f),
                "a $days-day ring lost its labels",
            )
        }
        // Past that the ring runs out of room and says it in letters, then in colour.
        assertEquals(
            CycleLabelFit.SHORT,
            DialGeometry.cycleLabelFit(DialGeometry.segments(12)[0].sweepAngleDeg, labelSweep, labelSweep / 4f),
        )
    }

    // --- exactly one accent segment, ever (§4) -----------------------------------

    @Test
    fun `the current undone round is the one accent segment`() {
        val states = DialGeometry.roundStates(listOf(true, false, false), currentIndex = 1)
        assertEquals(listOf(RoundState.DONE, RoundState.CURRENT, RoundState.UPCOMING), states)
        assertEquals(1, states.count { it == RoundState.CURRENT })
    }

    @Test
    fun `a done round stays done even when it is the current index`() {
        val states = DialGeometry.roundStates(listOf(true, true, false), currentIndex = 0)
        assertEquals(RoundState.DONE, states[0])
        assertEquals(0, states.count { it == RoundState.CURRENT })
    }

    @Test
    fun `there is never more than one accent segment, whatever the index`() {
        val flags = listOf(true, false, false, false)
        (-2..6).forEach { index ->
            val accents = DialGeometry.roundStates(flags, index).count { it == RoundState.CURRENT }
            assertTrue(accents <= 1, "index $index produced $accents accent segments")
        }
    }
}
