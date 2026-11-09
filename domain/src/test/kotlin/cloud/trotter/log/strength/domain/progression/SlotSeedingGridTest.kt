package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlotSeedingGridTest {
    @Test fun `an untrusted history load rounds to the current unit's step`() {
        val slot = ProgramExercise("leg_press", SetStructure.STRAIGHT, 3)
        val rule = SlotRule(SlotRole.ACCESSORY, RepTarget.Fixed(10), 2, 0.025, 1, 0.05, Scheme.STRENGTH)
        for ((unit, history, expected) in listOf(
            Triple(WeightUnit.KG, 100.0, WeightUnit.KG.toLb(45.0)),
            Triple(WeightUnit.LB, 103.0, 100.0),
        )) {
            val seeded = SlotSeeding.rows(slot, rule,
                KnownLoads(mapOf("leg_press" to LoggedSet(history, 12, SetKind.WORK))), LoadSteps.defaults(unit), unit)
            assertEquals(3, seeded.rows.size)
            seeded.rows.forEach {
                assertEquals(expected, it.weightLb, 1e-9)
                assertEquals(LoggedSet(expected, 10, SetKind.WORK), it.copy(weightLb = expected))
            }
            assertTrue(seeded.rule.state.calibrating)
        }
    }
}
