package io.github.sjtrotter.strengthlog.wear.ui

import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cumulative weight stepping for the rotary crown (MED-2): N detents must move
 * N steps, not one — the old call site sent N identical absolute deltas off an
 * unchanging captured weight and advanced a single step.
 */
class WeightScrollTest {

    @Test
    fun `one detent up steps once`() {
        assertEquals(205.0, scrolledWeightLb(200.0, WeightUnit.LB, detents = 1))
    }

    @Test
    fun `three detents up step three times, not one`() {
        // 200 -> 205 -> 210 -> 215. The bug produced 205 (a single step).
        assertEquals(215.0, scrolledWeightLb(200.0, WeightUnit.LB, detents = 3))
    }

    @Test
    fun `negative detents step down cumulatively`() {
        // 200 -> 195 -> 190.
        assertEquals(190.0, scrolledWeightLb(200.0, WeightUnit.LB, detents = -2))
    }

    @Test
    fun `zero detents is a no-op`() {
        assertEquals(200.0, scrolledWeightLb(200.0, WeightUnit.LB, detents = 0))
    }

    @Test
    fun `each step re-evaluates the unit increment as the running value moves`() {
        // kg increment is 2.5 above the light-isolation threshold; 3 detents up
        // from 100 kg = 100 -> 102.5 -> 105 -> 107.5, carried back to canonical lb.
        val target = scrolledWeightLb(WeightUnit.KG.toLb(100.0), WeightUnit.KG, detents = 3)
        assertEquals(WeightUnit.KG.toLb(107.5), target, 1e-6)
    }
}
