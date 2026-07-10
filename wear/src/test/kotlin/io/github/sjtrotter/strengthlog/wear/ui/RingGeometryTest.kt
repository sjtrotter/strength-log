package io.github.sjtrotter.strengthlog.wear.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The progress-ring segment geometry (design digest appendix `ring()`), pulled out pure. */
class RingGeometryTest {

    @Test
    fun `one segment per round`() {
        assertEquals(6, ringSegments(6, List(6) { false }, currentIndex = 0).size)
    }

    @Test
    fun `empty exercise yields no segments`() {
        assertTrue(ringSegments(0, emptyList(), currentIndex = 0).isEmpty())
    }

    @Test
    fun `a done round is DONE regardless of the current index`() {
        val segments = ringSegments(3, listOf(true, false, false), currentIndex = 1)
        assertEquals(RingSegmentState.DONE, segments[0].state)
    }

    @Test
    fun `the current not-yet-done round is CURRENT`() {
        val segments = ringSegments(3, listOf(true, false, false), currentIndex = 1)
        assertEquals(RingSegmentState.CURRENT, segments[1].state)
    }

    @Test
    fun `every other round is TRACK`() {
        val segments = ringSegments(3, listOf(true, false, false), currentIndex = 1)
        assertEquals(RingSegmentState.TRACK, segments[2].state)
    }

    @Test
    fun `segments evenly divide the circle before the gap is subtracted`() {
        val segments = ringSegments(4, List(4) { false }, currentIndex = 0, gapDeg = 0f)
        assertEquals(90f, segments[0].sweepAngleDeg)
        assertEquals(90f, segments[1].startAngleDeg - segments[0].startAngleDeg)
    }

    @Test
    fun `the gap shrinks each segment's sweep without changing the spacing between segment starts`() {
        val noGap = ringSegments(6, List(6) { false }, currentIndex = 0, gapDeg = 0f)
        val withGap = ringSegments(6, List(6) { false }, currentIndex = 0, gapDeg = 4f)
        assertEquals(noGap[0].sweepAngleDeg - 4f, withGap[0].sweepAngleDeg)
        // Each segment still starts exactly 360/n apart — the gap centers within
        // that fixed slot by shrinking the sweep, not by moving the slots.
        assertEquals(
            noGap[1].startAngleDeg - noGap[0].startAngleDeg,
            withGap[1].startAngleDeg - withGap[0].startAngleDeg,
        )
    }

    @Test
    fun `the first segment starts at 12 o'clock`() {
        val segments = ringSegments(4, List(4) { false }, currentIndex = 0, gapDeg = 0f)
        assertEquals(-90f, segments[0].startAngleDeg)
    }
}
