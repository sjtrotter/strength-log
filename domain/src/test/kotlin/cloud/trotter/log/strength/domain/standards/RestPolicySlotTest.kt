package cloud.trotter.log.strength.domain.standards

import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import kotlin.test.Test
import kotlin.test.assertEquals

class RestPolicySlotTest {
    @Test fun `rest follows the slot rule and the plan goal`() {
        val squat = SlotRule(SlotRole.MAIN, RepTarget.Fixed(5), 2, 0.025, 1, scheme = Scheme.STRENGTH)
        fun rest(rule: SlotRule?, goal: LiftGoal = LiftGoal.STRENGTH, kind: SetKind = SetKind.WORK,
                 overrides: Map<RestCategory, Int> = emptyMap(), tracking: TrackingType = TrackingType.WEIGHTED) =
            RestPolicy.effectiveRestSeconds(kind, tracking, overrides, rule, goal)
        assertEquals(180, rest(squat))
        assertEquals(120, rest(squat, LiftGoal.MUSCLE))
        assertEquals(90, rest(squat.copy(role = SlotRole.ACCESSORY)))
        assertEquals(60, rest(squat.copy(role = SlotRole.ISOLATION)))
        assertEquals(150, rest(squat, overrides = mapOf(RestCategory.TOP to 150)))
        assertEquals(150, rest(squat.copy(restSeconds = 150), LiftGoal.MUSCLE, overrides = mapOf(RestCategory.TOP to 100)))
        for (goal in LiftGoal.entries) {
            assertEquals(90, rest(squat, goal, SetKind.RAMP))
            assertEquals(120, rest(squat, goal, SetKind.BACKOFF))
        }
        assertEquals(90, rest(null))
        assertEquals(60, rest(null, tracking = TrackingType.REPS))
    }
}
