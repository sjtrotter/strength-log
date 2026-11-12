package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressionBodyweightCapTest {
    @Test fun `bodyweight at or above the cap reports AddWeight without reducing reps`() {
        for (target in listOf(RepTarget.Fixed(20), RepTarget.Fixed(30), RepTarget.Range(20, 30, 30))) {
            val rows = List(3) { LoggedSet(0.0, target.reps, SetKind.WORK) }
            val rule = SlotRule(SlotRole.ACCESSORY, target, 2, 0.0125, 1, scheme = Scheme.STRENGTH)
            val outcome = Progression.afterSession(rule, rows, Progression.Exposure(rows,
                rows.mapIndexed { index, row -> row.copy(done = true, plannedIndex = index) }, 130),
                Progression.Context(WeightUnit.LB, emptySet(), LoadSteps.defaults(WeightUnit.LB), 130))
            assertEquals(Reason.AddWeight, outcome.reason)
            assertEquals(Change.Hold, outcome.change)
            assertEquals(target, outcome.rule.target)
            assertEquals(rows, outcome.rows)
        }
    }
}
