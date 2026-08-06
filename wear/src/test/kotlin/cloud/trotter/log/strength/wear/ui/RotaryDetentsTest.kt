package cloud.trotter.log.strength.wear.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Crown pixels into whole notches (brief §6) — the dial only moves in steps. */
class RotaryDetentsTest {

    private val step = RotaryDetents.DETENT_PIXELS

    @Test
    fun `a nudge short of a detent moves nothing and is carried`() {
        val turn = RotaryDetents.accumulate(carryPixels = 0f, scrollPixels = step / 2f)
        assertEquals(0, turn.detents)
        assertEquals(step / 2f, turn.carryPixels)
    }

    @Test
    fun `a slow turn adds up across events`() {
        val first = RotaryDetents.accumulate(0f, step * 0.4f)
        val second = RotaryDetents.accumulate(first.carryPixels, step * 0.4f)
        assertEquals(0, second.detents)
        val third = RotaryDetents.accumulate(second.carryPixels, step * 0.4f)
        assertEquals(1, third.detents)
        // The overshoot is kept, not thrown away: 1.2 detents leaves 0.2 behind.
        assertEquals(step * 0.2f, third.carryPixels, absoluteTolerance = 0.01f)
    }

    @Test
    fun `a flick emits every detent it crossed, in one go`() {
        val turn = RotaryDetents.accumulate(0f, step * 3.5f)
        assertEquals(3, turn.detents)
        assertEquals(step * 0.5f, turn.carryPixels, absoluteTolerance = 0.01f)
    }

    @Test
    fun `turning back moves back`() {
        val turn = RotaryDetents.accumulate(0f, -step * 2f)
        assertEquals(-2, turn.detents)
        assertEquals(0f, turn.carryPixels, absoluteTolerance = 0.01f)
    }

    @Test
    fun `a reversal drops the carry rather than eating the correction`() {
        val forward = RotaryDetents.accumulate(0f, step * 0.9f)
        val back = RotaryDetents.accumulate(forward.carryPixels, -step)
        assertEquals(-1, back.detents)
        assertEquals(0f, back.carryPixels, absoluteTolerance = 0.01f)
    }

    @Test
    fun `a zero-length event changes nothing`() {
        val turn = RotaryDetents.accumulate(carryPixels = step / 3f, scrollPixels = 0f)
        assertEquals(0, turn.detents)
        assertEquals(step / 3f, turn.carryPixels)
    }
}
