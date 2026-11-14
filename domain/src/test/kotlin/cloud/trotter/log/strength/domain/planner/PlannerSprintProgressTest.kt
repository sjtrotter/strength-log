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
import cloud.trotter.log.strength.domain.progression.sprintBlock
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.math.round
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

    @Test fun `SprintContext reads only maximal session dates and uses stored plan meters`() {
        val program = Planner.plan(sprint(days = 2, answers = SprintAnswers(sprintedRecently = true))).program
        val dates = mapOf("A" to 970L, "B" to 999L, "C" to 993L)
        val goal = PlanGoal.Sprint(100)
        assertEquals(SprintContext(993, 30, 100, 190, cyclesCompleted = 4),
            SprintContext.of(program, "A", dates, 4, goal))
        assertEquals(SprintContext(993, 160, null, 190, cyclesCompleted = 4),
            SprintContext.of(program, "C", dates, 4, goal))
        assertNull(SprintContext.of(program, "A", dates, 3, goal).timeTrialMetres)
        assertEquals(100, SprintContext.of(program, "A", dates, 9, goal).timeTrialMetres)
        assertNull(SprintContext.of(program, "A", dates, 4, null).timeTrialMetres)
        val five = Planner.plan(sprint(days = 5)).program
        assertEquals(993L, SprintContext.of(five, "A", mapOf("D" to 993L, "F" to 999L), 4, goal).lastEpochDay)
        assertEquals(40, SprintContext.of(five, "D", emptyMap(), 4, goal).otherMetres)
        assertEquals(70, SprintContext.of(five, "D", emptyMap(), 4, goal).plannedWeekMetres)
        val endurance = Planner.plan(sprint(SprintEvent.M400, days = 2)).program
        assertEquals(993L, SprintContext.of(endurance, "A", mapOf("A" to 970L, "C" to 993L),
            3, PlanGoal.Sprint(400)).lastEpochDay)
    }

    private fun withRule(program: Program, id: String, next: CardioRule): Program = program.copy(days = program.days.map {
        if (it.id == id) it.copy(cardioRule = next, cardioBlock = sprintBlock(requireNotNull(next.session), next.steps)) else it
    })

    @Test fun `B only last week leaves A stored block intact four days later`() {
        val created = Planner.plan(sprint(days = 2, answers = SprintAnswers(sprintedRecently = true))).program
        for ((steps, meters) in listOf(0 to 40, 12 to 160)) {
            val program = withRule(created, "A", rule(created, "A").copy(steps = steps))
            val block = requireNotNull(day(program, "A").cardioBlock)
            val context = Progression.CardioContext(1_000, null, 0,
                sprint = SprintContext.of(program, "A", mapOf("A" to 982L, "C" to 996L), 3, PlanGoal.Sprint(100)))
            assertEquals(meters + 30, requireNotNull(context.sprint).plannedWeekMetres)
            assertEquals(block, Progression.cardioAtStart(block, rule(program, "A"), history, PlanGoal.Sprint(100), context))
            assertEquals(CardioOutcome(block, rule(program, "A").copy(credits = 1), CardioChange.Repeat),
                Progression.cardioAfterSession(block, rule(program, "A"), finished(block), CardioEffort.HARD, history, context))
            assertEquals(meters, SprintRules.workMetres(block))
        }
    }

    @Test fun `a W5 trial preserves A stored step in W6`() {
        val created = Planner.plan(sprint(days = 2, answers = SprintAnswers(sprintedRecently = true))).program
        val program = withRule(created, "A", rule(created, "A").copy(steps = 14))
        val block = requireNotNull(day(program, "A").cardioBlock)
        val goal = PlanGoal.Sprint(100)
        val context = Progression.CardioContext(1_000, null, 0,
            sprint = SprintContext.of(program, "A", mapOf("C" to 996L), 4, goal))
        val trial = Progression.cardioAtStart(block, rule(program, "A"), history, goal, context)
        assertEquals(180, SprintRules.workMetres(block))
        assertEquals(100, SprintRules.workMetres(trial))
        val out = Progression.cardioAfterSession(trial, rule(program, "A"), finished(trial), CardioEffort.HARD, history, context)
        assertEquals(CardioOutcome(block, rule(program, "A"), CardioChange.Repeat), out)
        val next = withRule(program, "A", requireNotNull(out.rule))
        val week6 = Progression.CardioContext(1_007, null, 0,
            sprint = SprintContext.of(next, "A", mapOf("A" to 1_000L, "C" to 1_003L), 5, goal))
        assertEquals(14, rule(next, "A").steps)
        assertEquals(block, Progression.cardioAtStart(out.block, out.rule, history, goal, week6))
        val finish = Progression.cardioAfterSession(block, out.rule, finished(block), CardioEffort.HARD, history, week6)
        assertEquals(CardioOutcome(block, rule(next, "A").copy(credits = 1), CardioChange.Repeat), finish)
    }

    @Test fun `two-day 200 and 400 m trials at 350 planned meters make B tempo without changing its rule`() {
        for (event in listOf(SprintEvent.M200, SprintEvent.M400)) {
            val created = Planner.plan(sprint(event, days = 2,
                answers = SprintAnswers(event, sprintedRecently = true))).program
            var program = withRule(created, "A", rule(created, "A").copy(steps = 2, credits = 1, sprintWait = 1))
            program = withRule(program, "C", rule(program, "C").copy(credits = 1, sprintWait = 2))
            val goal = PlanGoal.Sprint(event.metres)
            for (id in listOf("A", "C")) {
                val stored = requireNotNull(day(program, id).cardioBlock)
                val context = Progression.CardioContext(1_000, null, 0,
                    sprint = SprintContext.of(program, id, mapOf("A" to 993L), 4, goal))
                assertEquals(350, requireNotNull(context.sprint).plannedWeekMetres)
                val opening = Progression.cardioAtStart(stored, rule(program, id), history, goal, context)
                assertEquals(if (id == "A") SprintRules.timeTrial(event.metres, stored.activity)
                    else sprintBlock(RunSession.SPRINT_TEMPO, 0), opening)
                for (pain in listOf(false, true)) {
                    assertEquals(CardioOutcome(stored, rule(program, id), CardioChange.Repeat),
                        Progression.cardioAfterSession(opening, rule(program, id), finished(opening),
                            CardioEffort.GOOD, history, context, pain))
                }
            }
        }
    }

    @Test fun `an over-cap 400 m rotation keeps both normal blocks`() {
        val program = Planner.plan(sprint(SprintEvent.M400, days = 2,
            answers = SprintAnswers(SprintEvent.M400, sprintedRecently = true))).program
        val goal = PlanGoal.Sprint(400)
        for (id in listOf("A", "C")) {
            val block = requireNotNull(day(program, id).cardioBlock)
            val context = Progression.CardioContext(1_000, null, 0,
                sprint = SprintContext.of(program, id, mapOf("A" to 993L), 4, goal))
            assertEquals(330, requireNotNull(context.sprint).plannedWeekMetres)
            assertEquals(block, Progression.cardioAtStart(block, rule(program, id), history, goal, context))
        }
    }

    @Test fun `one-day 200 and 400 m plans never admit a trial`() {
        for (event in listOf(SprintEvent.M200, SprintEvent.M400)) {
            val created = Planner.plan(sprint(event, days = 1,
                answers = SprintAnswers(event, sprintedRecently = true))).program
            for (steps in listOf(0, 7)) {
                val program = withRule(created, "A", rule(created, "A").copy(steps = steps))
                val block = requireNotNull(day(program, "A").cardioBlock)
                val goal = PlanGoal.Sprint(event.metres)
                val context = Progression.CardioContext(1_000, null, 0,
                    sprint = SprintContext.of(program, "A", mapOf("A" to 993L), 4, goal))
                assertNull(requireNotNull(context.sprint).timeTrialMetres)
                assertEquals(block, Progression.cardioAtStart(block, rule(program, "A"), history, goal, context))
            }
        }
    }

    @Test fun `short trial admission counts both reps and leaves B maximal`() {
        val created = Planner.plan(sprint(days = 2, answers = SprintAnswers(sprintedRecently = true))).program
        for ((event, steps, admitted) in listOf(Triple(37, 0, false), Triple(60, 4, false),
            Triple(37, 4, true), Triple(60, 12, true), Triple(100, 12, true))) {
            val program = withRule(created, "A", rule(created, "A").copy(steps = steps))
            val goal = PlanGoal.Sprint(event)
            val context = SprintContext.of(program, "A", emptyMap(), 4, goal)
            assertEquals(event.takeIf { admitted }, context.timeTrialMetres)
            val b = requireNotNull(day(program, "C").cardioBlock)
            val bContext = Progression.CardioContext(1_000, null, 0,
                sprint = SprintContext.of(program, "C", emptyMap(), 4, goal))
            assertEquals(b, Progression.cardioAtStart(b, rule(program, "C"), history, goal, bContext))
        }
    }

    @Test fun `a rotation shares its planned allowance across both Finish decisions`() {
        var program = Planner.plan(sprint(days = 2, answers = SprintAnswers(sprintedRecently = true))).program
        program = withRule(program, "A", rule(program, "A").copy(steps = 0, credits = 1))
        program = withRule(program, "C", rule(program, "C").copy(credits = 1))
        for (id in listOf("A", "C")) {
            val block = requireNotNull(day(program, id).cardioBlock)
            val sprint = SprintContext.of(program, id, emptyMap(), 0, PlanGoal.Sprint(100))
            assertEquals(70, sprint.rotationStartMetres)
            val context = Progression.CardioContext(1_000, null, 0, sprint = sprint)
            val out = Progression.cardioAfterSession(block, rule(program, id), finished(block), CardioEffort.HARD, history, context)
            assertEquals(if (id == "A") CardioChange.Step else CardioChange.Repeat, out.change)
            program = withRule(program, id, requireNotNull(out.rule))
        }
        assertEquals(1, rule(program, "C").sprintWait)
        assertEquals(80, SprintContext.of(program, "A", emptyMap(), 1, null).plannedWeekMetres)
        assertEquals(80, SprintContext.of(program, "A", emptyMap(), 1, null).rotationStartMetres)
    }

    @Test fun `a ready sprint day yields to the longer credited wait`() {
        var program = Planner.plan(sprint(days = 2, answers = SprintAnswers(sprintedRecently = true))).program
        program = withRule(program, "A", rule(program, "A").copy(credits = 1))
        program = withRule(program, "C", rule(program, "C").copy(credits = 1, sprintWait = 2))
        for (id in listOf("A", "C")) {
            val block = requireNotNull(day(program, id).cardioBlock)
            val context = Progression.CardioContext(1_000, null, 0,
                sprint = SprintContext.of(program, id, emptyMap(), 0, null))
            val out = Progression.cardioAfterSession(block, rule(program, id), finished(block), CardioEffort.HARD, history, context)
            assertEquals(if (id == "A") CardioChange.Repeat else CardioChange.Step, out.change)
            program = withRule(program, id, requireNotNull(out.rule))
        }
        assertEquals(12, rule(program, "A").steps)
        assertEquals(1, rule(program, "A").sprintWait)
        assertEquals(1, rule(program, "C").steps)
        assertEquals(0, rule(program, "C").sprintWait)
    }

    private data class SprintWeek(val blocks: Pair<List<Int>, List<Int>>, val planned: Int, val risePercent: Double)

    private fun openingSequence(recent: Boolean): List<SprintWeek> {
        var program = Planner.plan(sprint(days = 2, answers = SprintAnswers(sprintedRecently = recent))).program
        val goal = PlanGoal.Sprint(100)
        val dates = mutableMapOf<String, Long>()
        var previousPlan = SprintContext.of(program, "A", dates, 0, goal).plannedWeekMetres
        val weeks = (1..16).map { week ->
            val planned = SprintContext.of(program, "A", dates, week - 1, goal).plannedWeekMetres
            assertTrue(planned <= previousPlan * 1.15 + 1e-9, "week $week recent=$recent: $previousPlan -> $planned")
            val risePercent = round((planned - previousPlan) * 10_000.0 / previousPlan) / 100.0
            val blocks = listOf("A", "C").map { id ->
                val today = 1_000L + 7 * (week - 1) + if (id == "A") 0 else 3
                val context = Progression.CardioContext(today, null, 0,
                    sprint = SprintContext.of(program, id, dates, week - 1, goal))
                val block = Progression.cardioAtStart(requireNotNull(day(program, id).cardioBlock),
                    rule(program, id), history, goal, context)
                val out = Progression.cardioAfterSession(block, rule(program, id), finished(block),
                    CardioEffort.HARD, history, context)
                program = withRule(program, id, requireNotNull(out.rule))
                dates[id] = today
                block.sets.filter { it.role == CardioRole.WORK }.map { (it.end as Measure.Distance).metres }
            }
            val nextPlan = SprintContext.of(program, "A", dates, week - 1, goal).plannedWeekMetres
            assertTrue(nextPlan <= planned * 1.15 + 1e-9, "week $week Finish recent=$recent: $planned -> $nextPlan")
            previousPlan = planned
            SprintWeek(blocks[0] to blocks[1], planned, risePercent)
        }
        assertTrue(weeks.last().planned > weeks.first().planned)
        return weeks
    }

    @Test fun `a two-day 100 m intro pins 16 weekly openings under 15 percent`() {
        fun a(metres: Int) = if (metres <= 80) List(metres / 10) { 10 } else listOf(20) + List(7) { 10 }
        fun b(metres: Int) = if (metres <= 50) List(metres / 10) { 10 }
            else List((metres - 50) / 10) { 20 } + List((100 - metres) / 10) { 10 }
        val openings = listOf(40 to 30, 40 to 30, 40 to 30, 40 to 30, 40 to 30, 50 to 30, 50 to 40,
            60 to 40, 60 to 50, 70 to 50, 70 to 60, 80 to 60, 80 to 70, 90 to 70, 100 to 80, 90 to 80)
        val planned = listOf(70, 70, 70, 70, 70, 80, 90, 100, 110, 120, 130, 140, 150, 160, 170, 170)
        val percentages = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 14.29, 12.5, 11.11, 10.0, 9.09,
            8.33, 7.69, 7.14, 6.67, 6.25, 0.0)
        val expected = openings.indices.map { index ->
            val (am, bm) = openings[index]
            SprintWeek((if (index == 14) listOf(100) else a(am)) to b(bm), planned[index], percentages[index])
        }
        assertEquals(expected, openingSequence(recent = false))
    }

    @Test fun `a two-day 100 m non-intro pins 16 weekly openings under 15 percent`() {
        fun a(metres: Int) = List((metres - 160) / 10) { 30 } + List((240 - metres) / 10) { 20 }
        fun b(metres: Int) = if (metres <= 50) List(metres / 10) { 10 }
            else List((metres - 50) / 10) { 20 } + List((100 - metres) / 10) { 10 }
        val openings = listOf(160 to 30, 160 to 30, 170 to 40, 170 to 40, 100 to 50, 180 to 50,
            180 to 60, 190 to 60, 190 to 70, 100 to 70, 200 to 100, 200 to 100, 240 to 100,
            240 to 100, 100 to 100, 240 to 100)
        val planned = listOf(190, 190, 210, 210, 230, 230, 240, 250, 260, 270, 300, 300, 340, 340, 340, 340)
        val percentages = listOf(0.0, 0.0, 10.53, 0.0, 9.52, 0.0, 4.35, 4.17, 4.0, 3.85,
            11.11, 0.0, 13.33, 0.0, 0.0, 0.0)
        val expected = openings.indices.map { index ->
            val (am, bm) = openings[index]
            SprintWeek((if ((index + 1) % 5 == 0) listOf(100) else a(am)) to b(bm), planned[index], percentages[index])
        }
        assertEquals(expected, openingSequence(recent = true))
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
