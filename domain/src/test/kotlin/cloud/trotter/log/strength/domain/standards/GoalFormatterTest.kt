package cloud.trotter.log.strength.domain.standards

import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalFormatterTest {

    // --- WEIGHTED: byte-identical to the pre-refactor number ------------------

    @Test
    fun `weighted label is the bare unit-converted number, no per-hand suffix`() {
        assertEquals("235", GoalFormatter.label(GoalTarget(235.0, perHand = false), WeightUnit.LB))
        // per-hand does not change the string — "/hand" stays a per-surface line.
        assertEquals("75", GoalFormatter.label(GoalTarget(75.0, perHand = true), WeightUnit.LB))
    }

    @Test
    fun `weighted label matches the old WeightStepper path for both units`() {
        for (lb in listOf(5.0, 60.0, 175.0, 235.0, 245.0)) {
            for (unit in WeightUnit.values()) {
                assertEquals(
                    WeightStepper.format(unit.fromLb(lb)),
                    GoalFormatter.label(GoalTarget(lb, perHand = false), unit),
                )
            }
        }
    }

}
