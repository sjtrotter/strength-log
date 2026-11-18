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
        // PR 7d: the outcome names its dose
        assertEquals(CardioOutcome(block, CardioRule(null, RunSession.ACCELERATION, intro = 5), CardioChange.Restart, dose = SprintDose.RESTART),
            SprintRules.afterSession(block, rule, RunSession.ACCELERATION, results, CardioEffort.HARD, false, context))
        // PR 7d: the outcome names its dose
        assertEquals(CardioOutcome(block, CardioRule(null, RunSession.ACCELERATION, intro = 6), CardioChange.Restart, dose = SprintDose.RESTART),
            SprintRules.afterSession(block, rule, RunSession.ACCELERATION, results, CardioEffort.HARD, true, context))
        val program = Planner.plan(sprint()).program
        assertTrue(SprintContext.of(program, "A", emptyMap(), 0, null, 100, emptySet(), careful = true).careful)
    }

    @Test fun `cardio rules accept intro up to six and reject seven`() {
        assertEquals(6, CardioRule(null, RunSession.ACCELERATION, intro = 6).intro)
        assertFailsWith<IllegalArgumentException> { CardioRule(null, RunSession.ACCELERATION, intro = 7) }
    }
    @Test fun `the sprint decision is public and matches the opening`() {
        val rule = CardioRule(null, RunSession.ACCELERATION, steps = 12)
        val cases = listOf(
            SprintContext(false, weekLeft = 160) to SprintDose.NORMAL,
            SprintContext(true, weekLeft = 40) to SprintDose.RESTART,
            SprintContext(false, timeTrialMetres = 100, cyclesCompleted = 4, weekLeft = 100) to SprintDose.TRIAL,
            SprintContext(false, weekLeft = 80) to SprintDose.SHORTENED,
            SprintContext(false, weekLeft = 79) to SprintDose.SUBSTITUTE,
            SprintContext(false, tempo = true, weekLeft = 0) to SprintDose.TEMPO,
        )
        for ((context, dose) in cases) {
            val current = if (dose == SprintDose.TEMPO) CardioRule(null, RunSession.SPRINT_ENDURANCE, steps = 4) else rule
            val session = requireNotNull(current.session)
            val decision = sprintDecision(current, context, cloud.trotter.log.strength.domain.model.CardioActivity.TRACK_RUN)
            assertEquals(dose, decision.dose)
            assertEquals(SprintRules.opening(sprintBlock(session, current.steps), current, session, context), decision.block)
        }
    }

    @Test fun `rest before sprint wraps to the first day`() {
        val expected = mapOf(2 to listOf("B", "D"), 3 to listOf("C", "E"), 4 to listOf("C", "F"),
            5 to listOf("C"), 6 to listOf("C"))
        for ((days, ids) in expected) {
            val program = Planner.plan(sprint(days = days)).program
            assertEquals(ids, program.rotationDays.filter { restBeforeSprint(program, it.id) }.map { it.id })
            assertTrue(program.days.filter { it.kind != cloud.trotter.log.strength.domain.model.ProgramDayKind.REST }
                .none { restBeforeSprint(program, it.id) })
            assertFalse(restBeforeSprint(program, "missing"))
        }
    }

    @Test fun `last maximal date skips tempo and empty sessions`() {
        val program = Planner.plan(sprint(days = 5)).program
        val block = sprintBlock(RunSession.ACCELERATION, 0)
        val rows = block.sets.map { CardioSetResult(it, CardioSetStatus.FINISHED, 0) }
        val tempo = sprintBlock(RunSession.SPRINT_TEMPO, 0).sets.map { CardioSetResult(it, CardioSetStatus.FINISHED, 0) }
        val records = listOf(SprintRecord("A", 20, rows), SprintRecord("D", 21, rows),
            SprintRecord("F", 22, rows), SprintRecord("A", 23, tempo), SprintRecord("A", 24, emptyList()),
            SprintRecord("D", 25, rows.map { it.copy(status = CardioSetStatus.SKIPPED) }))
        assertEquals(21L, lastMaximalEpochDay(program, records))
        assertNull(lastMaximalEpochDay(program, records.drop(2)))
    }

    @Test fun `the planned block follows steps before trials and allowance`() {
        val rule = CardioRule(null, RunSession.ACCELERATION, steps = 2)
        val activity = cloud.trotter.log.strength.domain.model.CardioActivity.TRACK_RUN
        assertEquals(sprintBlock(RunSession.ACCELERATION, 2), sprintPlannedBlock(rule, activity))
        assertNull(sprintPlannedBlock(null, activity))
        assertNull(sprintPlannedBlock(CardioRule(null, RunSession.EASY), activity))
    }

}
