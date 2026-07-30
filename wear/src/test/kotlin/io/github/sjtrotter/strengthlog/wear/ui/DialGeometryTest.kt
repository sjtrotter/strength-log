package io.github.sjtrotter.strengthlog.wear.ui

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dial's layout math (brief §2/§4). Every radius is derived from the
 * measured face, so the same numbers have to hold on a 384px watch and a 454px
 * one — that proportionality is what the overlap bug can't survive.
 */
class DialGeometryTest {

    private val tolerance = 0.01f

    @Test
    fun `the day ring sits its inset plus half a stroke inside the face`() {
        val ring = DialGeometry.dayRing(384f)
        assertEquals(180.5f, ring.radiusPx, tolerance) // 192 - 9 - 5/2
        assertEquals(5f, ring.strokePx, tolerance)
    }

    @Test
    fun `the exercise ring sits inside the day ring`() {
        val exercise = DialGeometry.exerciseRing(384f)
        assertEquals(159f, exercise.radiusPx, tolerance) // 192 - 26 - 14/2
        assertEquals(14f, exercise.strokePx, tolerance)
        assertTrue(exercise.radiusPx < DialGeometry.dayRing(384f).radiusPx)
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
        assertEquals(180.5f * scale, DialGeometry.dayRing(454f).radiusPx, tolerance)
        assertEquals(5f * scale, DialGeometry.dayRing(454f).strokePx, tolerance)
        assertEquals(159f * scale, DialGeometry.exerciseRing(454f).radiusPx, tolerance)
        assertEquals(14f * scale, DialGeometry.exerciseRing(454f).strokePx, tolerance)
        assertEquals(102f * scale, DialGeometry.discRadiusPx(454f), tolerance)
        assertEquals(7f * scale, DialGeometry.clockRing(454f).strokePx, tolerance)
    }

    @Test
    fun `a ring never touches the bezel it is inset from`() {
        listOf(324f, 384f, 454f).forEach { diameter ->
            val ring = DialGeometry.dayRing(diameter)
            assertTrue(ring.radiusPx + ring.strokePx / 2f < diameter / 2f, "day ring escapes at $diameter")
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
        // Exercise ring inner edge 159 - 7 = 152; disc rim 102.
        assertEquals(50f, band.thicknessPx, tolerance)
        assertEquals(127f, band.radiusPx, tolerance) // centred in it
        assertEquals(40f, band.insetPx, tolerance) // 192 - 152
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
        assertEquals(127f * scale, band.radiusPx, tolerance)
        assertEquals(50f * scale, band.thicknessPx, tolerance)
        assertEquals(40f * scale, band.insetPx, tolerance)
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
