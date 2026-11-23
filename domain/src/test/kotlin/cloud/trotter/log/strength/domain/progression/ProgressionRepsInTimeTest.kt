package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProgressionRepsInTimeTest {
    private val ctx = Progression.Context(WeightUnit.LB, emptySet(), LoadSteps.defaults(WeightUnit.LB), 130)
    private val aimed = PrescribedTarget.RepsInTime(60, 20)
    private fun rule(aim: Int = 20, every: Int = 1) =
        SlotRule(SlotRole.ACCESSORY, RepTarget.Fixed(aim), 2, 0.0125, every, scheme = Scheme.STRENGTH)
    private fun rows(reps: Int) = List(3) { LoggedSet(0.0, reps, SetKind.WORK) }
    private fun after(
        rule: SlotRule,
        window: PrescribedTarget.RepsInTime,
        counts: List<Int>,
        rirs: List<Int?> = counts.map { null },
        planned: List<LoggedSet> = rows(window.repAim ?: 0),
    ) = Progression.afterSession(rule, planned, Progression.Exposure(planned,
        counts.mapIndexed { i, reps -> planned[i].copy(reps = reps, done = true, plannedIndex = i, rir = rirs[i]) }, 130),
        ctx, window)

    @Test fun `case 39 rows seed the rep aim`() {
        assertEquals(rows(20), SetSeeder.seedPrescribed(3, Prescription(aimed)))
        assertEquals(rows(0), SetSeeder.seedPrescribed(3, Prescription(PrescribedTarget.RepsInTime(60))))
    }

    @Test fun `case 39 every window at the aim raises the aim by one rep`() {
        val outcome = after(rule(), aimed, listOf(20, 20, 20))
        assertEquals(rows(21), outcome.rows)
        assertEquals(RepTarget.Fixed(21), outcome.rule.target)
        assertEquals(Change.RepsTo(21), outcome.change)
        assertEquals(Reason.AllMade, outcome.reason)
        assertEquals(1, outcome.rule.state.successStreak)
        assertEquals(1, outcome.rule.state.exposures)
    }

    @Test fun `case 39 a short window repeats the aim and never stalls`() {
        val first = after(rule(), aimed, listOf(20, 18, 20))
        assertEquals(rows(20), first.rows)
        assertEquals(Change.Hold, first.change)
        assertEquals(Reason.RepMiss, first.reason)
        assertEquals(RepTarget.Fixed(20), first.rule.target)
        val second = after(first.rule, aimed, listOf(20, 18, 20), planned = first.rows)
        assertEquals(rows(20), second.rows)
        assertEquals(Change.Hold, second.change)
        assertEquals(Reason.RepMiss, second.reason)
        assertEquals(RepTarget.Fixed(20), second.rule.target)
    }

    @Test fun `case 39 a window with no aim only measures`() {
        val window = PrescribedTarget.RepsInTime(60)
        val outcome = after(rule(), window, listOf(25, 24, 22))
        assertEquals(rows(0), outcome.rows)
        assertEquals(Change.Hold, outcome.change)
        assertEquals(Reason.Measured, outcome.reason)
        assertEquals(RepTarget.Fixed(20), outcome.rule.target)
        assertEquals(1, outcome.rule.state.exposures)
        assertEquals(rows(0), after(rule(), window, listOf(25, 24, 22), planned = rows(13)).rows)
    }

    @Test fun `case 39 the aim passes twenty reps with no weight offer or extra set`() {
        val outcome = after(rule(25), PrescribedTarget.RepsInTime(60, 25), listOf(25, 25, 25))
        assertEquals(rows(26), outcome.rows)
        assertEquals(Change.RepsTo(26), outcome.change)
        assertEquals(Reason.AllMade, outcome.reason)
    }

    @Test fun `case 39 the aim rises at the lift cadence`() {
        val first = after(rule(every = 2), aimed, listOf(20, 20, 20))
        assertEquals(rows(20), first.rows)
        assertEquals(Reason.Streak, first.reason)
        assertEquals(1, first.rule.state.successStreak)
        val second = after(first.rule, aimed, listOf(20, 20, 20), planned = first.rows)
        assertEquals(rows(21), second.rows)
        assertEquals(Change.RepsTo(21), second.change)
    }

    @Test fun `case 39 a max effort window repeats the aim`() {
        val outcome = after(rule(), aimed, listOf(20, 20, 20), rirs = listOf(2, 0, 2))
        assertEquals(rows(20), outcome.rows)
        assertEquals(Change.Hold, outcome.change)
        assertEquals(Reason.EffortMiss, outcome.reason)
        assertEquals(1, outcome.rule.state.effortMissStreak)
    }

    @Test fun `case 39 the aim follows the rows and falls back to the authored aim`() {
        assertEquals(21, Progression.repAim(aimed, rows(21)))
        assertEquals(20, Progression.repAim(aimed, rows(0)))
        assertEquals(20, Progression.repAim(aimed, null))
        assertNull(Progression.repAim(PrescribedTarget.RepsInTime(60), rows(21)))
    }
}
