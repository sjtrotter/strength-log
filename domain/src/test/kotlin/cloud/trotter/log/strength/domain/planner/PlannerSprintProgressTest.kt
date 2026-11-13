package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.CardioChange
import cloud.trotter.log.strength.domain.progression.CardioEffort
import cloud.trotter.log.strength.domain.progression.CardioHistory
import cloud.trotter.log.strength.domain.progression.CardioOutcome
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.Change
import cloud.trotter.log.strength.domain.progression.LoadSteps
import cloud.trotter.log.strength.domain.progression.Progression
import cloud.trotter.log.strength.domain.progression.Reason
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.SprintContext
import cloud.trotter.log.strength.domain.progression.SprintRules
import cloud.trotter.log.strength.domain.progression.sprintBlock
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class PlannerSprintProgressTest {
    private val history = CardioHistory()
    private fun day(program: Program, id: String) = program.days.single { it.id == id }
    private fun rule(program: Program, id: String) = requireNotNull(day(program, id).cardioRule)
    private fun result(spec: CardioSetSpec, status: CardioSetStatus = CardioSetStatus.FINISHED) = CardioSetResult(
        spec, status, ((spec.end as? Measure.Time) ?: (spec.aim as? Measure.Time))?.seconds ?: 0,
        (spec.end as? Measure.Distance)?.metres,
        if (spec.end is Measure.Distance) DistanceSource.MANUAL else null,
    )
    private fun finished(block: CardioBlock) = block.sets.map { result(it) }
    private fun actual(planned: List<LoggedSet>) = planned.mapIndexed { index, row ->
        row.copy(plannedIndex = index, done = true, rir = null)
    }

    @Test fun `the planner writes the intro on every sprint day`() {
        val intro = Planner.plan(sprint(days = 5)).program
        val recent = Planner.plan(sprint(days = 5, answers = SprintAnswers(sprintedRecently = true))).program
        for ((id, session) in listOf("A" to RunSession.ACCELERATION, "D" to RunSession.MAX_VELOCITY, "F" to RunSession.SPRINT_TEMPO)) {
            assertEquals(CardioRule(null, session, intro = 3), rule(intro, id))
            assertEquals(CardioRule(null, session, steps = if (id == "A") 5 else 0), rule(recent, id))
        }
    }

    @Test fun `SprintContext reads the plan`() {
        val program = Planner.plan(sprint(days = 2)).program
        val goal = PlanGoal.Sprint(100)
        assertEquals(SprintContext(993, 30, 100), SprintContext.of(program, "A", 993, 4, goal))
        assertEquals(SprintContext(null, 40, null), SprintContext.of(program, "C", null, 4, goal))
        assertNull(SprintContext.of(program, "A", null, 3, goal).timeTrialMetres)
        assertEquals(100, SprintContext.of(program, "A", null, 9, goal).timeTrialMetres)
        assertNull(SprintContext.of(program, "A", null, 4, null).timeTrialMetres)
        val five = Planner.plan(sprint(days = 5)).program
        assertEquals(SprintContext(null, 30, 100), SprintContext.of(five, "A", null, 4, goal))
        assertEquals(SprintContext(null, 70, null), SprintContext.of(five, "F", null, 4, goal))
        val recent = Planner.plan(sprint(days = 2, answers = SprintAnswers(sprintedRecently = true))).program
        assertEquals(160, SprintContext.of(recent, "C", null, 4, goal).otherMetres)
        val longEvent = Planner.plan(sprint(SprintEvent.M200, days = 2)).program
        assertEquals(SprintContext(null, 300, 200), SprintContext.of(longEvent, "A", null, 4, PlanGoal.Sprint(200)))
    }

    @Test fun `a 100 m plan's first twelve weeks`() {
        var program = Planner.plan(sprint(days = 2)).program
        val goal = PlanGoal.Sprint(100)
        fun context(program: Program, id: String, week: Int, last: Long?): Progression.CardioContext {
            val today = 1_000L + 7 * (week - 1) + if (id == "A") 0 else 3
            return Progression.CardioContext(today, null, 0, sprint = SprintContext.of(program, id, last, week - 1, goal))
        }
        fun opening(program: Program, id: String, ctx: Progression.CardioContext) =
            Progression.cardioAtStart(requireNotNull(day(program, id).cardioBlock), rule(program, id), history, goal, ctx)
        fun finish(program: Program, id: String, ctx: Progression.CardioContext): CardioOutcome {
            val block = opening(program, id, ctx)
            return Progression.cardioAfterSession(block, rule(program, id), finished(block), CardioEffort.HARD, history, ctx)
        }
        fun write(program: Program, id: String, out: CardioOutcome) = program.copy(days = program.days.map {
            if (it.id == id) it.copy(cardioBlock = out.block, cardioRule = out.rule) else it
        })
        for (week in 1..12) for (id in listOf("A", "C")) {
            val today = 1_000L + 7 * (week - 1) + if (id == "A") 0 else 3
            val ctx = context(program, id, week, if (week == 1) null else today - 7)
            val block = opening(program, id, ctx)
            if (id == "A" && week in listOf(5, 10)) {
                assertEquals(SprintRules.timeTrial(100, CardioActivity.TRACK_RUN), block, "week $week A trial")
            } else assertEquals(day(program, id).cardioBlock, block, "week $week $id opening")
            val out = finish(program, id, ctx)
            val steps = if (id == "A") listOf(6, 8, 11) else listOf(5, 7, 11)
            assertEquals(if (week in steps) CardioChange.Step else CardioChange.Repeat, out.change, "week $week $id change")
            program = write(program, id, out)
            if (week == 10 && id == "C") assertEquals(2, rule(program, id).held)
        }
        assertEquals(CardioRule(null, RunSession.ACCELERATION, steps = 3, credits = 1), rule(program, "A"))
        assertEquals(sprintBlock(RunSession.ACCELERATION, 3), day(program, "A").cardioBlock)
        assertEquals(CardioRule(null, RunSession.MAX_VELOCITY, steps = 3), rule(program, "C"))
        assertEquals(sprintBlock(RunSession.MAX_VELOCITY, 3), day(program, "C").cardioBlock)
        val week14 = context(program, "A", 14, 1_077)
        assertEquals(sprintBlock(RunSession.ACCELERATION, 3), opening(program, "A", week14))
        assertNotEquals(CardioChange.Restart, finish(program, "A", week14).change)
        for (week in 13..14) {
            val today = 1_003L + 7 * (week - 1)
            program = write(program, "C", finish(program, "C", context(program, "C", week, today - 7)))
        }
        val week15 = context(program, "A", 15, 1_077)
        assertEquals(1_098L, week15.todayEpochDay)
        assertEquals(sprintBlock(RunSession.ACCELERATION, 0), opening(program, "A", week15))
        assertEquals(CardioOutcome(sprintBlock(RunSession.ACCELERATION, 0),
            CardioRule(null, RunSession.ACCELERATION, intro = 2), CardioChange.Restart), finish(program, "A", week15))
    }

    @Test fun `plyometric contacts grow to the 80 cap through the lift rules`() {
        val draft = Planner.plan(sprint())
        val slots = day(draft.program, "A").exercises.take(2)
        var rules = slots.map { requireNotNull(it.rule) }
        var rows = slots.indices.map { draft.working.getValue(SlotKey("A", it)) }
        assertEquals(listOf("pogo_hop", "a_skip"), slots.map { it.exerciseId })
        assertEquals(listOf(List(3) { LoggedSet(0.0, 15, SetKind.WORK) }, listOf(LoggedSet(0.0, 20, SetKind.WORK))), rows)
        val contacts = mutableListOf<Int>()
        for (session in 0..6) {
            contacts += rows.sumOf { sets -> sets.sumOf { it.reps } }
            val next = slots.indices.map { index ->
                val working = rows[index]
                Progression.afterSession(rules[index], working, Progression.Exposure(working, actual(working), 130),
                    Progression.Context(WeightUnit.LB, ExerciseLibrary.get(slots[index].exerciseId).equipment.toSet(),
                        LoadSteps.defaults(WeightUnit.LB), 130))
            }
            assertEquals(if (session < 5) Change.RepsTo(16 + session) else Change.Hold, next[0].change)
            if (session >= 4) assertEquals(Reason.AddWeight, next[0].reason)
            assertEquals(Change.Hold, next[1].change)
            assertEquals(Reason.AddWeight, next[1].reason)
            rules = next.map { it.rule }
            rows = next.map { it.rows }
        }
        assertEquals(listOf(65, 68, 71, 74, 77, 80, 80), contacts)
    }
}
