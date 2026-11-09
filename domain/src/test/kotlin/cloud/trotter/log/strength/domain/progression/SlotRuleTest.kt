package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.SetStructure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SlotRuleTest {
    private fun defaults(role: SlotRole, goal: LiftGoal, reps: Int? = null, pattern: MovementPattern = MovementPattern.BICEPS) =
        SlotRule.defaults(role, goal, SetStructure.STRAIGHT, reps, pattern)

    @Test
    fun `case 38 rules for a hand added accessory and ramped main`() {
        val rule = defaults(SlotRole.ACCESSORY, LiftGoal.STRENGTH, 8)
        assertEquals(SlotRole.ACCESSORY, rule.role)
        assertEquals(RepTarget.Fixed(8), rule.target)
        assertEquals(2, rule.rir)
        assertEquals(0.0125, rule.incrementPct, 1e-9)
        assertEquals(1, rule.everyExposures)
        assertEquals(0.05, rule.dropPct, 1e-9)
        assertNull(rule.restSeconds)
        assertNull(rule.nextTime)
        assertFalse(rule.state.calibrating)
        val main = SlotRule.defaults(SlotRole.MAIN, LiftGoal.STRENGTH, SetStructure.RAMP_TOP_BACKOFF, 5, MovementPattern.H_PUSH)
        assertEquals(SlotRole.MAIN, main.role)
        assertEquals(RepTarget.Fixed(5), main.target)
        assertEquals(Scheme.PROTOTYPE, main.scheme)
    }

    @Test
    fun `case 28 conversion keeps ramped rules and supplies never opened accessory defaults`() {
        val main = SlotRule.defaults(SlotRole.MAIN, LiftGoal.STRENGTH, SetStructure.RAMP_TOP_BACKOFF, 5, MovementPattern.SQUAT_BILATERAL)
        assertEquals(RepTarget.Fixed(5), main.target)
        assertEquals(2, main.rir)
        assertEquals(0.025, main.incrementPct, 1e-9)
        assertEquals(Scheme.PROTOTYPE, main.scheme)
        val accessory = defaults(SlotRole.ACCESSORY, LiftGoal.STRENGTH, pattern = MovementPattern.SQUAT_BILATERAL)
        assertEquals(RepTarget.Fixed(10), accessory.target)
        assertEquals(2, accessory.rir)
        assertEquals(0.025, accessory.incrementPct, 1e-9)
        assertEquals(Scheme.STRENGTH, accessory.scheme)
    }

    @Test
    fun `Strength rir and default reps by role`() {
        for (role in SlotRole.entries) {
            val rule = defaults(role, LiftGoal.STRENGTH)
            assertEquals(if (role == SlotRole.SECONDARY) 3 else 2, rule.rir)
            assertEquals(RepTarget.Fixed(if (role in listOf(SlotRole.MAIN, SlotRole.SECONDARY)) 5 else 10), rule.target)
        }
    }

    @Test
    fun `Muscle rir and default reps by role`() {
        for (role in SlotRole.entries) {
            val rule = defaults(role, LiftGoal.MUSCLE)
            assertEquals(if (role == SlotRole.ISOLATION) 1 else 2, rule.rir)
            assertEquals(RepTarget.Fixed(if (role in listOf(SlotRole.MAIN, SlotRole.SECONDARY)) 8 else 12), rule.target)
        }
    }

    @Test
    fun `General rir and default reps by role`() {
        for (role in SlotRole.entries) {
            val rule = defaults(role, LiftGoal.GENERAL)
            assertEquals(if (role == SlotRole.MAIN) 3 else 2, rule.rir)
            assertEquals(RepTarget.Fixed(if (role == SlotRole.MAIN) 10 else 12), rule.target)
        }
    }

    @Test
    fun `increments follow the body half for every pattern`() {
        val lower = setOf(MovementPattern.SQUAT_BILATERAL, MovementPattern.SINGLE_LEG, MovementPattern.HINGE,
            MovementPattern.KNEE_FLEXION, MovementPattern.KNEE_EXTENSION, MovementPattern.CALF_GASTROC, MovementPattern.CALF_SOLEUS)
        for (pattern in MovementPattern.entries) {
            assertEquals(if (pattern in lower) 0.025 else 0.0125,
                defaults(SlotRole.ACCESSORY, LiftGoal.STRENGTH, pattern = pattern).incrementPct, 1e-9)
        }
    }

    @Test
    fun `rep range requires a positive ordered range containing current`() {
        for ((lo, hi, current) in listOf(Triple(0, 5, 3), Triple(5, 4, 5), Triple(5, 8, 9))) {
            assertFailsWith<IllegalArgumentException> { RepTarget.Range(lo, hi, current) }
        }
    }
}
