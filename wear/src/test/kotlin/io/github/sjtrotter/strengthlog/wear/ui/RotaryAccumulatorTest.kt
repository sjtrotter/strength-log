package io.github.sjtrotter.strengthlog.wear.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Rotary-crown scroll-pixel -> detent thresholding, pulled out pure for [ExerciseStreamScreen]. */
class RotaryAccumulatorTest {

    @Test
    fun `small scrolls below the threshold fire no detent`() {
        val accumulator = RotaryAccumulator(thresholdPx = 36f)
        assertEquals(0, accumulator.onScroll(10f))
        assertEquals(0, accumulator.onScroll(10f))
    }

    @Test
    fun `crossing the threshold fires exactly one detent and keeps the remainder`() {
        val accumulator = RotaryAccumulator(thresholdPx = 36f)
        assertEquals(0, accumulator.onScroll(30f))
        assertEquals(1, accumulator.onScroll(10f)) // 40px total: one detent, 4px carried over
        assertEquals(0, accumulator.onScroll(30f)) // 34px accumulated, still below threshold
        assertEquals(1, accumulator.onScroll(2f)) // crosses again
    }

    @Test
    fun `a large scroll can fire multiple detents at once`() {
        val accumulator = RotaryAccumulator(thresholdPx = 36f)
        assertEquals(3, accumulator.onScroll(110f))
    }

    @Test
    fun `negative scrolls fire negative detents`() {
        val accumulator = RotaryAccumulator(thresholdPx = 36f)
        assertEquals(-1, accumulator.onScroll(-40f))
        assertEquals(-2, accumulator.onScroll(-80f))
    }
}
