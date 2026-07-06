package io.github.sjtrotter.strengthlog.domain.units

import kotlin.test.Test
import kotlin.test.assertEquals

class WeightUnitTest {

    @Test
    fun `lb passes through unchanged in both directions`() {
        assertEquals(135.0, WeightUnit.LB.fromLb(135.0))
        assertEquals(135.0, WeightUnit.LB.toLb(135.0))
    }

    @Test
    fun `kg conversion matches the pinned constant`() {
        assertEquals(1.0, WeightUnit.KG.fromLb(WeightUnit.LB_PER_KG), 1e-9)
        assertEquals(WeightUnit.LB_PER_KG, WeightUnit.KG.toLb(1.0), 1e-9)
    }

    @Test
    fun `round trips lb through kg and back`() {
        val originalLb = 225.0
        val displayKg = WeightUnit.KG.fromLb(originalLb)
        val backToLb = WeightUnit.KG.toLb(displayKg)
        assertEquals(originalLb, backToLb, 1e-9)
    }

    @Test
    fun `round trips kg through lb and back`() {
        val originalKg = 100.0
        val lb = WeightUnit.KG.toLb(originalKg)
        val backToKg = WeightUnit.KG.fromLb(lb)
        assertEquals(originalKg, backToKg, 1e-9)
    }
}
