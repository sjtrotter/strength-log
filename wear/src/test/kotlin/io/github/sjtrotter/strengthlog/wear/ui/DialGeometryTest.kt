package io.github.sjtrotter.strengthlog.wear.ui

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
    fun `the disc is 176px across at the reference size`() {
        assertEquals(88f, DialGeometry.discRadiusPx(384f), tolerance)
    }

    @Test
    fun `every measurement scales with the measured face`() {
        val scale = 454f / 384f
        assertEquals(180.5f * scale, DialGeometry.dayRing(454f).radiusPx, tolerance)
        assertEquals(5f * scale, DialGeometry.dayRing(454f).strokePx, tolerance)
        assertEquals(159f * scale, DialGeometry.exerciseRing(454f).radiusPx, tolerance)
        assertEquals(14f * scale, DialGeometry.exerciseRing(454f).strokePx, tolerance)
        assertEquals(88f * scale, DialGeometry.discRadiusPx(454f), tolerance)
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

    @Test
    fun `trimming keeps whole segments inside the fraction and drops the ones past it`() {
        val segments = DialGeometry.segments(4, gapDeg = 0f)
        // Half the circle: the first two segments survive, the last two vanish.
        val trimmed = segments.map { DialGeometry.trimToFraction(it, 0.5f) }
        assertEquals(90f, trimmed[0].sweepAngleDeg, tolerance)
        assertEquals(90f, trimmed[1].sweepAngleDeg, tolerance)
        assertEquals(0f, trimmed[2].sweepAngleDeg, tolerance)
        assertEquals(0f, trimmed[3].sweepAngleDeg, tolerance)
    }

    @Test
    fun `trimming cuts the segment the fraction lands inside`() {
        val segments = DialGeometry.segments(4, gapDeg = 0f)
        assertEquals(45f, DialGeometry.trimToFraction(segments[1], 0.375f).sweepAngleDeg, tolerance)
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
