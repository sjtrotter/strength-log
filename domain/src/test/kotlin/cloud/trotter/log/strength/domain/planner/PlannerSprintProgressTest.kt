package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
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
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
            assertEquals(CardioRule(null, session, steps = if (id == "A") 12 else 0), rule(recent, id))
        }
    }

    @Test fun `SprintContext reads only maximal session dates and includes trial exposure`() {
        val program = Planner.plan(sprint(days = 2, answers = SprintAnswers(sprintedRecently = true))).program
        val dates = mapOf("A" to 970L, "B" to 999L, "C" to 993L)
        val goal = PlanGoal.Sprint(100)
        assertEquals(SprintContext(993, 30, 100, 190), SprintContext.of(program, "A", dates, 4, goal, 190))
        assertEquals(SprintContext(993, 100, null, 190), SprintContext.of(program, "C", dates, 4, goal, 190))
        assertNull(SprintContext.of(program, "A", dates, 3, goal, 190).timeTrialMetres)
        assertEquals(100, SprintContext.of(program, "A", dates, 9, goal, 190).timeTrialMetres)
        assertNull(SprintContext.of(program, "A", dates, 4, null, 190).timeTrialMetres)
        val five = Planner.plan(sprint(days = 5)).program
        assertEquals(993L, SprintContext.of(five, "A", mapOf("D" to 993L, "F" to 999L), 4, goal, 70).lastEpochDay)
        assertEquals(40, SprintContext.of(five, "D", emptyMap(), 4, goal, 70).otherMetres)
        val endurance = Planner.plan(sprint(SprintEvent.M400, days = 2)).program
        assertEquals(993L, SprintContext.of(endurance, "A", mapOf("A" to 970L, "C" to 993L),
            3, PlanGoal.Sprint(400), 330).lastEpochDay)
    }

    @Test fun `skipping A does not reset while B finished within 14 days`() {
        val program = Planner.plan(sprint(days = 2, answers = SprintAnswers(sprintedRecently = true))).program
        val block = requireNotNull(day(program, "A").cardioBlock)
        val context = Progression.CardioContext(1_000, null, 0,
            sprint = SprintContext.of(program, "A", mapOf("A" to 970L, "C" to 987L), 3, PlanGoal.Sprint(100), 190))
        assertEquals(block, Progression.cardioAtStart(block, rule(program, "A"), history, PlanGoal.Sprint(100), context))
        assertEquals(CardioChange.Repeat, Progression.cardioAfterSession(block, rule(program, "A"), finished(block),
            CardioEffort.HARD, history, context).change)
    }

    @Test fun `a 400 m fifth rotation keeps its 30 meter flying block`() {
        val program = Planner.plan(sprint(SprintEvent.M400, days = 1,
            answers = SprintAnswers(SprintEvent.M400, sprintedRecently = true))).program
        val block = requireNotNull(day(program, "A").cardioBlock)
        val context = Progression.CardioContext(1_000, null, 0,
            sprint = SprintContext.of(program, "A", mapOf("A" to 993L), 4, PlanGoal.Sprint(400), 30))
        assertEquals(30, SprintRules.workMetres(block))
        assertEquals(block, Progression.cardioAtStart(block, rule(program, "A"), history, PlanGoal.Sprint(400), context))
        assertEquals(CardioOutcome(block, rule(program, "A").copy(credits = 1), CardioChange.Repeat),
            Progression.cardioAfterSession(block, rule(program, "A"), finished(block), CardioEffort.HARD, history, context))
    }

    private fun openingSequence(recent: Boolean): List<Pair<List<Int>, List<Int>>> {
        var program = Planner.plan(sprint(days = 2, answers = SprintAnswers(sprintedRecently = recent))).program
        val goal = PlanGoal.Sprint(100)
        val dates = mutableMapOf<String, Long>()
        var previous = program.days.mapNotNull { it.cardioBlock }.sumOf(SprintRules::workMetres)
        return (1..16).map { week ->
            val blocks = listOf("A", "C").map { id ->
                val today = 1_000L + 7 * (week - 1) + if (id == "A") 0 else 3
                val context = Progression.CardioContext(today, null, 0,
                    sprint = SprintContext.of(program, id, dates, week - 1, goal, previous))
                val block = Progression.cardioAtStart(requireNotNull(day(program, id).cardioBlock),
                    rule(program, id), history, goal, context)
                val out = Progression.cardioAfterSession(block, rule(program, id), finished(block),
                    CardioEffort.HARD, history, context)
                program = program.copy(days = program.days.map {
                    if (it.id == id) it.copy(cardioBlock = out.block, cardioRule = out.rule) else it
                })
                dates[id] = today
                block.sets.filter { it.role == CardioRole.WORK }.map { (it.end as Measure.Distance).metres }
            }
            val total = blocks.sumOf { it.sum() }
            assertTrue(total <= previous * 1.15 + 1e-9, "week $week recent=$recent: $previous -> $total")
            previous = total
            blocks[0] to blocks[1]
        }
    }

    @Test fun `a two-day 100 m intro pins 16 weekly openings under 15 percent`() {
        val a40 = List(4) { 10 }
        val a50 = List(5) { 10 }
        val a60 = List(6) { 10 }
        val a70 = List(7) { 10 }
        val a80 = List(8) { 10 }
        val a90 = listOf(20) + List(7) { 10 }
        val b30 = List(3) { 10 }
        assertEquals(listOf(a40, a40, a40, a40, a40, a50, a50, a60, a60, a70, a70, a80, a80, a90,
            listOf(100), a90).map { it to b30 }, openingSequence(recent = false))
    }

    @Test fun `a two-day 100 m non-intro pins 16 weekly openings under 15 percent`() {
        fun a(long: Int) = List(long) { 20 } + List(8 - long) { 10 }
        fun b(long: Int) = List(long) { 20 } + List(5 - long) { 10 }
        assertEquals(listOf(
            List(8) { 20 } to List(3) { 10 },
            List(8) { 20 } to List(3) { 10 },
            (listOf(30) + List(7) { 20 }) to List(4) { 10 },
            (listOf(30) + List(7) { 20 }) to List(4) { 10 },
            listOf(100) to b(0),
            a(4) to b(0),
            a(4) to b(0),
            a(5) to b(1),
            a(5) to b(1),
            listOf(100) to b(2),
            a(4) to b(2),
            a(4) to b(2),
            a(5) to b(3),
            a(5) to b(3),
            listOf(100) to b(3),
            a(2) to b(5),
        ), openingSequence(recent = true))
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
