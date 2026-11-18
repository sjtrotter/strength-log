package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.planner.LiftExperience
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.planner.SprintAnswers
import cloud.trotter.log.strength.domain.progression.CardioChange
import cloud.trotter.log.strength.domain.progression.SprintContext
import cloud.trotter.log.strength.domain.progression.SprintDose
import cloud.trotter.log.strength.domain.progression.sprintDecision
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardioJudgeSprintStartTest {
    private val zone = ZoneId.of("America/Chicago")
    private val today = LocalDate.of(2026, 11, 17)
    private val now = today.atTime(21, 30).atZone(zone).toInstant().toEpochMilli()
    private val plan = PlanEntity(7, "Sprint", "AUTHORED", 0, 0)
    private fun inputs(answers: SetupAnswers = SetupAnswers(branch = PlanBranch.SPRINT, days = 2,
        experience = LiftExperience.A_YEAR_OR_MORE)) = CardioPlan(Planner.plan(answers).program, 0,
        PlanGoal.Sprint(100), answers.careful, zone, answers)
    private fun session(id: Long, gap: Long, dayId: String = "A") = CardioSessionEntity(id = id, planId = plan.id,
        dayId = dayId, mode = "OUTDOOR_RUN", hard = true, label = "Sprint",
        startedAt = now - gap * 86_400_000 - 3_000_000, completedAt = now - gap * 86_400_000 - 600_000,
        seconds = 2400, stepsCompleted = 1, activity = CardioActivity.TRACK_RUN.name)
    private fun rows(inputs: CardioPlan) = requireNotNull(inputs.program.days.first().cardioBlock).sets.map {
        CardioSetResult(it, CardioSetStatus.FINISHED, (it.end as? Measure.Time)?.seconds ?: 5)
    }
    private fun start(inputs: CardioPlan, gap: Long? = 1): SprintStart {
        val history = gap?.let { listOf(session(1, it)) }.orEmpty()
        return assertNotNull(CardioJudge.sprintStart(inputs.program.days.first(), plan, history,
            history.associate { it.id to rows(inputs) }, now, today.toEpochDay(), inputs))
    }

    @Test fun `the Start note follows the gap and the tier`() {
        val first = inputs()
        assertEquals(1L, start(first).daysSince)
        assertEquals(3, start(first).spacingDays)
        assertEquals(SpacingReason.STARTING_OUT, start(first).reason)
        for (gap in listOf(0L, 1L, 3L)) assertEquals(gap, start(first, gap).daysSince)
        val setup = assertNotNull(first.setup)
        val forty = inputs(setup.copy(sprint = SprintAnswers(fortyOrOlder = true, hamstringStrain = true)))
        assertEquals(SpacingReason.FORTY_OR_OLDER, start(forty).reason)
        val older = inputs(setup.copy(olderAdult = true))
        assertEquals(SpacingReason.FORTY_OR_OLDER, start(older).reason)
        val strain = inputs(setup.copy(sprint = SprintAnswers(hamstringStrain = true)))
        assertEquals(SpacingReason.HAMSTRING_STRAIN, start(strain).reason)
        assertEquals(3, start(strain).spacingDays)
        val mature = inputs(setup.copy(sprint = SprintAnswers(sprintedRecently = true))).let {
            it.copy(program = it.program.copy(days = it.program.days.map { day -> day.copy(exercises = emptyList<ProgramExercise>()) }))
        }
        assertEquals(2, start(mature).spacingDays)
        assertEquals(SpacingReason.FOR_NOW, start(mature).reason)
        assertNull(start(first, gap = null).daysSince)
    }

    @Test fun `nine tenths shows only for the careful group in the intro`() {
        val ordinary = inputs()
        val setup = assertNotNull(ordinary.setup)
        assertFalse(start(ordinary).nineTenths)
        val careful = inputs(setup.copy(sprint = SprintAnswers(fortyOrOlder = true)))
        assertTrue(start(careful).nineTenths)
        val mature = careful.copy(program = careful.program.copy(days = careful.program.days.map { day ->
            day.copy(cardioRule = day.cardioRule?.copy(intro = 0))
        }))
        assertFalse(start(mature).nineTenths)
        val tempo = inputs(setup.copy(days = 5, sprint = SprintAnswers(fortyOrOlder = true)))
        val day = tempo.program.days.single { it.id == "F" }
        assertFalse(assertNotNull(CardioJudge.sprintStart(day, plan, emptyList(), emptyMap(), now,
            today.toEpochDay(), tempo)).nineTenths)
    }

    @Test fun `a careful intro substituted by the allowance hides nine tenths`() {
        val inputs = inputs(SetupAnswers(branch = PlanBranch.SPRINT, days = 2,
            experience = LiftExperience.A_YEAR_OR_MORE, sprint = SprintAnswers(fortyOrOlder = true)))
        val day = inputs.program.days.first()
        assertTrue(assertNotNull(day.cardioRule).intro > 0)
        val history = (1L..3L).map { session(it, it) }
        val start = assertNotNull(CardioJudge.sprintStart(day, plan, history,
            history.associate { it.id to rows(inputs) }, now, today.toEpochDay(), inputs))
        assertEquals(SprintDose.SUBSTITUTE, start.dose)
        assertFalse(start.nineTenths)
    }

    @Test fun `a careful restart shows nine tenths after the stored intro ends`() {
        val careful = inputs(SetupAnswers(branch = PlanBranch.SPRINT, days = 2,
            experience = LiftExperience.A_YEAR_OR_MORE, sprint = SprintAnswers(fortyOrOlder = true)))
        val mature = careful.copy(program = careful.program.copy(days = careful.program.days.map { day ->
            day.copy(cardioRule = day.cardioRule?.copy(intro = 0))
        }))
        val restart = start(mature, gap = 15)
        assertEquals(SprintDose.RESTART, restart.dose)
        assertTrue(restart.nineTenths)
        val ordinary = start(mature.copy(careful = false), gap = 15)
        assertEquals(SprintDose.RESTART, ordinary.dose)
        assertFalse(ordinary.nineTenths)
    }

    @Test fun `the opening on a sprint day is the decided block`() {
        val inputs = inputs()
        val day = inputs.program.days.first()
        val start = start(inputs, gap = null)
        assertEquals(sprintDecision(assertNotNull(day.cardioRule), SprintContext(false, weekLeft = 110),
            CardioActivity.TRACK_RUN).block, start.block)
        assertEquals(start.block, CardioJudge.opening(day, plan, emptyList(), emptyMap(), now, today.toEpochDay(), inputs))
        assertNull(CardioJudge.opening(day, plan, emptyList(), emptyMap(), now, today.toEpochDay()))
    }

    @Test fun `Start and Finish agree on the local date across midnight`() {
        val inputs = inputs()
        val day = inputs.program.days.first()
        val prior = session(1, 15)
        val current = session(2, 0).copy(startedAt = now, completedAt = now + 2_400_000)
        val sets = mapOf(prior.id to rows(inputs), current.id to rows(inputs))
        val start = assertNotNull(CardioJudge.sprintStart(day, plan, listOf(prior), sets, now, today.toEpochDay(), inputs))
        assertEquals(15L, start.daysSince)
        assertEquals(SprintDose.RESTART, start.dose)
        val finish = assertNotNull(CardioJudge.judge(day, current, listOf(prior, current), sets, true, inputs))
        assertEquals(start.block, finish.opening)
        assertEquals(CardioChange.Restart, finish.outcome.change)
        assertEquals(start.dose, finish.outcome.dose)
    }

    @Test fun `the plan builder reads the rotation and zone from its inputs`() {
        val inputs = inputs()
        val stored = plan.copy(cyclesCompleted = 4)
        val built = CardioPlan.of(stored, inputs.program, WeightUnit.LB, zone)
        assertEquals(inputs.program, built.program)
        assertEquals(4, built.cyclesCompleted)
        assertEquals(zone, built.zone)
        assertNull(built.setup)
        assertFalse(built.careful)
    }
}
