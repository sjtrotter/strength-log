package cloud.trotter.log.strength.domain.units

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlateMathTest {

    @Test
    fun `bar weight is 45 lb or 20 kg`() {
        assertEquals(45.0, PlateMath.barWeight(WeightUnit.LB))
        assertEquals(20.0, PlateMath.barWeight(WeightUnit.KG))
    }

    @Test
    fun `lb 235 loads 45 45 5 a side`() {
        assertEquals(listOf(45.0, 45.0, 5.0), PlateMath.perSide(235.0, WeightUnit.LB))
    }

    @Test
    fun `lb 245 loads 45 45 10 a side`() {
        assertEquals(listOf(45.0, 45.0, 10.0), PlateMath.perSide(245.0, WeightUnit.LB))
    }

    @Test
    fun `lb 130 loads 25 10 5 2point5 a side`() {
        assertEquals(listOf(25.0, 10.0, 5.0, 2.5), PlateMath.perSide(130.0, WeightUnit.LB))
    }

    @Test
    fun `lb 165 loads 45 10 5 a side`() {
        assertEquals(listOf(45.0, 10.0, 5.0), PlateMath.perSide(165.0, WeightUnit.LB))
    }

    @Test
    fun `lb 190 loads 45 25 2point5 a side`() {
        assertEquals(listOf(45.0, 25.0, 2.5), PlateMath.perSide(190.0, WeightUnit.LB))
    }

    @Test
    fun `lb 210 loads 45 25 10 2point5 a side`() {
        assertEquals(listOf(45.0, 25.0, 10.0, 2.5), PlateMath.perSide(210.0, WeightUnit.LB))
    }

    @Test
    fun `lb 135 loads a single 45 a side`() {
        assertEquals(listOf(45.0), PlateMath.perSide(135.0, WeightUnit.LB))
    }

    @Test
    fun `lb 45 is the empty bar`() {
        assertEquals(emptyList(), PlateMath.perSide(45.0, WeightUnit.LB))
    }

    @Test
    fun `lb 40 is below the bar and returns null`() {
        assertNull(PlateMath.perSide(40.0, WeightUnit.LB))
    }

    @Test
    fun `lb 137 leaves an uncoverable remainder and returns null`() {
        assertNull(PlateMath.perSide(137.0, WeightUnit.LB))
    }

    @Test
    fun `kg 60 loads a single 20 a side`() {
        assertEquals(listOf(20.0), PlateMath.perSide(60.0, WeightUnit.KG))
    }

    @Test
    fun `kg 20 is the empty bar`() {
        assertEquals(emptyList(), PlateMath.perSide(20.0, WeightUnit.KG))
    }

    @Test
    fun `kg 102point5 loads 25 15 1point25 a side`() {
        assertEquals(listOf(25.0, 15.0, 1.25), PlateMath.perSide(102.5, WeightUnit.KG))
    }

    @Test
    fun `kg 19 is below the bar and returns null`() {
        assertNull(PlateMath.perSide(19.0, WeightUnit.KG))
    }
}
