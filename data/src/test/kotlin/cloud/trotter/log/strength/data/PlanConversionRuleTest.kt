package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.mapping.toDomain
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlanConversionRuleTest {
    private val slot = ProgramExerciseEntity(1, 1, "A", 0, "custom_gone", "STRAIGHT", 3, null, "")

    @Test fun `firstWorkingReps prefers TOP over an earlier WORK`() {
        assertEquals(5, PlanConversion.firstWorkingReps(listOf(LoggedSet(1.0, 8, SetKind.WORK),
            LoggedSet(2.0, 5, SetKind.TOP))))
    }
    @Test fun `firstWorkingReps skips RAMP rows`() {
        assertEquals(8, PlanConversion.firstWorkingReps(listOf(LoggedSet(1.0, 5, SetKind.RAMP),
            LoggedSet(2.0, 8, SetKind.WORK))))
    }
    @Test fun `firstWorkingReps returns null for a timed row`() {
        assertNull(PlanConversion.firstWorkingReps(listOf(LoggedSet(0.0, 0, SetKind.WORK, seconds = 45))))
    }
    @Test fun `firstWorkingReps returns null for no rows`() {
        assertNull(PlanConversion.firstWorkingReps(emptyList()))
    }
    @Test fun `a missing entry uses ACCESSORY and the upper-body increment`() {
        val rule = PlanConversion.rule(slot.toDomain(), null, null)
        assertEquals(SlotRole.ACCESSORY, rule.role)
        assertEquals(0.0125, rule.incrementPct)
    }
    @Test fun `an unknown structure reads STRAIGHT`() {
        assertEquals(Scheme.STRENGTH, PlanConversion.rule(slot.copy(structure = "UNKNOWN").toDomain(), null, null).scheme)
    }
    @Test fun `reps above 999 count as none`() {
        assertEquals(RepTarget.Fixed(10), PlanConversion.rule(slot.toDomain(), listOf(LoggedSet(50.0, 1000, SetKind.WORK)), null).target)
    }
}
