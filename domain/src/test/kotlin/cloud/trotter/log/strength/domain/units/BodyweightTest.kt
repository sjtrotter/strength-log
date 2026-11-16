package cloud.trotter.log.strength.domain.units

import kotlin.test.Test
import kotlin.test.assertEquals

class BodyweightTest {
    @Test fun `metric and pound inputs round trip at tenths precision`() {
        assertEquals(95.5, Bodyweight.display(Bodyweight.fromDisplay(95.5, WeightUnit.KG), WeightUnit.KG))
        assertEquals(210.5, Bodyweight.display(Bodyweight.fromDisplay(210.5, WeightUnit.LB), WeightUnit.LB))
    }

}
