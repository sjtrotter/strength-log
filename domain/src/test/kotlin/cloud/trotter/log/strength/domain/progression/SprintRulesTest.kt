package cloud.trotter.log.strength.domain.progression

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.SprintAnswers
import cloud.trotter.log.strength.domain.planner.sprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SprintRulesTest {
    private fun exposures(program: Program, ids: Set<String>, count: Int) = program.copy(days = program.days.map { day ->
        day.copy(exercises = day.exercises.map { slot ->
            val rule = requireNotNull(slot.rule)
            if (slot.exerciseId in ids) slot.copy(rule = rule.copy(state = rule.state.copy(exposures = count))) else slot
        })
    })

    @Test fun `spacing days are three in the first weeks or for the careful group and two otherwise`() {
        assertEquals(2, spacingDays(firstWeeks = false, careful = false))
        assertEquals(3, spacingDays(firstWeeks = true, careful = false))
        assertEquals(3, spacingDays(firstWeeks = false, careful = true))
        assertEquals(3, spacingDays(firstWeeks = true, careful = true))
    }

    @Test fun `days since reads null without a date and zero on the same day`() {
        assertNull(daysSince(null, 100))
        assertEquals(0L, daysSince(100, 100))
        assertEquals(3L, daysSince(97, 100))
    }

    @Test fun `the Nordic tier applies only to a Nordic slot under three exposures`() {
        val answers = sprint(answers = SprintAnswers(sprintedRecently = true))
            .copy(experience = LiftExperience.A_YEAR_OR_MORE)
        val program = Planner.plan(answers).program
        assertTrue(sprintFirstWeeks(program, newLifter = false))
        assertTrue(sprintFirstWeeks(exposures(program, setOf("nordic"), 2), newLifter = false))
        assertFalse(sprintFirstWeeks(exposures(program, setOf("nordic"), 3), newLifter = false))
        val fallback = Planner.plan(answers.copy(excluded = setOf("nordic"))).program
        assertTrue(fallback.days.flatMap { it.exercises }.any { it.exerciseId == "seated_curl" })
        assertFalse(sprintFirstWeeks(fallback, newLifter = false))
        val noKnee = program.copy(days = program.days.map { day ->
            day.copy(exercises = day.exercises.filterNot {
                ExerciseLibrary.get(it.exerciseId).pattern == MovementPattern.KNEE_FLEXION
            })
        })
        assertFalse(sprintFirstWeeks(noKnee, newLifter = false))
    }

    @Test fun `the intro and New lifter tiers apply in the first weeks`() {
        val intro = exposures(Planner.plan(sprint()).program, setOf("nordic"), 3)
        assertTrue(sprintFirstWeeks(intro, newLifter = false))
        val recent = exposures(Planner.plan(sprint(answers = SprintAnswers(sprintedRecently = true))).program,
            setOf("nordic"), 3)
        assertTrue(sprintFirstWeeks(recent, newLifter = true))
        assertFalse(sprintFirstWeeks(recent, newLifter = false))
        val lowerMature = exposures(recent, setOf("bb_back_squat", "trap_dl"), 3)
        assertFalse(sprintFirstWeeks(lowerMature, newLifter = true))
    }

    @Test fun `a careful restart writes the six session intro`() {
        val rule = CardioRule(null, RunSession.ACCELERATION, steps = 4, credits = 1)
        val block = sprintBlock(RunSession.ACCELERATION, 0)
        val results = block.sets.map { spec ->
            CardioSetResult(spec, CardioSetStatus.FINISHED, (spec.end as? Measure.Time)?.seconds ?: 0,
                (spec.end as? Measure.Distance)?.metres, if (spec.end is Measure.Distance) DistanceSource.MANUAL else null)
        }
        val context = SprintContext(layoff = true, careful = true)
        assertEquals(CardioOutcome(block, CardioRule(null, RunSession.ACCELERATION, intro = 5), CardioChange.Restart),
            SprintRules.afterSession(block, rule, RunSession.ACCELERATION, results, CardioEffort.HARD, false, context))
        assertEquals(CardioOutcome(block, CardioRule(null, RunSession.ACCELERATION, intro = 6), CardioChange.Restart),
            SprintRules.afterSession(block, rule, RunSession.ACCELERATION, results, CardioEffort.HARD, true, context))
        val program = Planner.plan(sprint()).program
        assertTrue(SprintContext.of(program, "A", emptyMap(), 0, null, 100, emptySet(), careful = true).careful)
    }

    @Test fun `cardio rules accept intro up to six and reject seven`() {
        assertEquals(6, CardioRule(null, RunSession.ACCELERATION, intro = 6).intro)
        assertFailsWith<IllegalArgumentException> { CardioRule(null, RunSession.ACCELERATION, intro = 7) }
    }
}
