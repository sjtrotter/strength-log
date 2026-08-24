package cloud.trotter.log.strength.ui.components

import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.domain.units.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepperCommitTest {

    @Test
    fun weightLb_roundsThenClamps() {
        assertEquals(135.0, resolveStepperCommit("137", 0.0, 500.0) {
            WeightStepper.round(it, WeightUnit.LB)
        })
        assertEquals(500.0, resolveStepperCommit("999", 0.0, 500.0) {
            WeightStepper.round(it, WeightUnit.LB)
        })
    }

    @Test
    fun weightKg_usesTheKgGrid() {
        assertEquals(8.75, resolveStepperCommit("8.4", 0.0, 500.0) {
            WeightStepper.round(it, WeightUnit.KG)
        })
    }

    @Test
    fun reps_roundToWholeNumbersAndClamp() {
        assertEquals(8.0, resolveStepperCommit("7.6", 1.0, 100.0) { Math.round(it).toDouble() })
        assertEquals(1.0, resolveStepperCommit("0", 1.0, 100.0) { Math.round(it).toDouble() })
    }

    @Test
    fun seconds_snapToFiveSecondDetents() {
        assertEquals(45.0, resolveStepperCommit("43", 0.0, 3_600.0) {
            Math.round(it / 5.0) * 5.0
        })
    }

    @Test
    fun junkDoesNotCommit() {
        assertNull(resolveStepperCommit("not a number", 0.0, 100.0) { it })
        assertNull(resolveStepperCommit("NaN", 0.0, 100.0) { it })
    }
}
