package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
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
import cloud.trotter.log.strength.domain.progression.RunRange
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.SprintContext
import cloud.trotter.log.strength.domain.progression.SprintRules
import cloud.trotter.log.strength.domain.progression.sprintBlock
import cloud.trotter.log.strength.domain.units.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannerSprintProgressTest {
    private val history = CardioHistory()
    private fun day(program: Program, id: String) = program.days.single { it.id == id }
    private fun rule(program: Program, id: String) = requireNotNull(day(program, id).cardioRule)
    private fun result(spec: CardioSetSpec, status: CardioSetStatus = CardioSetStatus.FINISHED) = CardioSetResult(
        spec, status, (spec.end as? Measure.Time)?.seconds ?: 0,
        (spec.end as? Measure.Distance)?.metres,
        if (spec.end is Measure.Distance) DistanceSource.MANUAL else null,
    )
    private fun finished(block: CardioBlock) = block.sets.map { result(it) }
    private fun actual(planned: List<LoggedSet>) = planned.mapIndexed { index, row ->
        row.copy(plannedIndex = index, done = true, rir = null)
    }
    private fun withRule(program: Program, id: String, next: CardioRule): Program = program.copy(days = program.days.map {
        if (it.id == id) it.copy(cardioRule = next, cardioBlock = sprintBlock(requireNotNull(next.session), next.steps)) else it
    })
    private fun planned(program: Program) = program.days.mapNotNull { it.cardioRule }.filter {
        it.session?.let(SprintRules::maximal) == true
    }.sumOf { SprintRules.workMetres(sprintBlock(requireNotNull(it.session), it.steps)) }
    private fun created(event: SprintEvent = SprintEvent.M100, days: Int = 2, intro: Boolean = false) =
        Planner.plan(sprint(event, days, answers = SprintAnswers(event, sprintedRecently = !intro))).program

    private data class Opened(val id: String, val kind: String, val metres: Int)
    private data class Rotation(val planned: Int, val steps: List<Int>, val blocks: List<Opened>)
    private data class Simulation(val program: Program, val rotations: List<Rotation>, val trials: Int, val restarts: Int)

    private fun simulate(
        event: SprintEvent = SprintEvent.M100,
        intro: Boolean = false,
        days: Int = 2,
        rotations: Int = 52,
        trials: Boolean = true,
        schedule: (Int) -> Pair<Int, List<Pair<String, Int>>> = { 0 to listOf("A" to 0, "C" to 3) },
    ): Simulation {
        var program = created(event, days, intro)
        val goal = PlanGoal.Sprint(event.metres)
        val dates = mutableMapOf<String, List<Long>>()
        val runs = mutableListOf<Rotation>()
        var trialCount = 0
        var restarts = 0
        var start = 1_000L
        repeat(rotations) { cycle ->
            val (extra, rows) = schedule(cycle)
            start += extra
            val before = planned(program)
            val steps = program.days.mapNotNull { it.cardioRule?.steps }
            val maximalThisCycle = mutableSetOf<String>()
            val opened = rows.map { (id, offset) ->
                val today = start + offset
                val sprint = SprintContext.of(program, id, dates, cycle, goal.takeIf { trials }, today, maximalThisCycle)
                val context = Progression.CardioContext(today, null, 0, sprint = sprint)
                val old = rule(program, id)
                val block = Progression.cardioAtStart(requireNotNull(day(program, id).cardioBlock), old, history, goal, context)
                val kind = when {
                    sprint.layoff -> "reset"
                    sprint.tempo -> "tempo"
                    sprint.timeTrialMetres != null -> "trial"
                    else -> "normal"
                }
                val out = Progression.cardioAfterSession(block, old, finished(block), CardioEffort.GOOD, history, context)
                val next = requireNotNull(out.rule)
                if (kind == "trial") trialCount++
                if (out.change == CardioChange.Restart) restarts++ else assertTrue(next.steps >= old.steps)
                program = withRule(program, id, next)
                if (SprintRules.maximal(requireNotNull(old.session)) && kind != "tempo") {
                    dates[id] = dates[id].orEmpty() + today
                    maximalThisCycle += id
                }
                Opened(id, kind, SprintRules.workMetres(block))
            }
            assertTrue(SprintRules.withinRise(before, planned(program)), "$event intro=$intro cycle=$cycle")
            runs += Rotation(before, steps, opened)
            start += maxOf(7, rows.maxOfOrNull { it.second + 1 } ?: 0)
        }
        return Simulation(program, runs, trialCount, restarts)
    }

    @Test fun `the planner writes the intro on every sprint day`() {
        val intro = created(days = 5, intro = true)
        val recent = created(days = 5)
        for ((id, session) in listOf("A" to RunSession.ACCELERATION, "D" to RunSession.MAX_VELOCITY, "F" to RunSession.SPRINT_TEMPO)) {
            assertEquals(CardioRule(null, session, intro = 3), rule(intro, id))
            assertEquals(CardioRule(null, session, steps = if (id == "A") 12 else 0), rule(recent, id))
        }
    }

    @Test fun `every reachable range combination obeys the planned rotation bound in every layout`() {
        for (event in SprintEvent.entries) for (days in 1..6) {
            val rules = created(event, days).days.mapNotNull { it.cardioRule }
            fun enumerate(index: Int, before: Int, after: Int) {
                if (index == rules.size) {
                    assertTrue(SprintRules.withinRise(before, after), "$event $days days: $before -> $after")
                    return
                }
                val session = requireNotNull(rules[index].session)
                val range = requireNotNull(RunRange.of(session))
                for (steps in 0..range.top) {
                    val current = if (SprintRules.maximal(session)) SprintRules.workMetres(sprintBlock(session, steps)) else 0
                    val next = if (SprintRules.maximal(session)) SprintRules.workMetres(sprintBlock(session, minOf(steps + 1, range.top))) else 0
                    enumerate(index + 1, before + current, after + next)
                }
            }
            enumerate(0, 0, 0)
        }
    }

    @Test fun `a two-day 100 m intro pins 16 rotation openings to the simulator`() {
        val run = simulate(intro = true, rotations = 16)
        assertEquals(listOf(40, 40, 40, 40, 40, 50, 50, 60, 60, 70, 70, 80, 80, 90, 100, 90),
            run.rotations.map { it.blocks[0].metres })
        assertEquals(listOf(30, 30, 30, 30, 30, 40, 40, 50, 50, 60, 60, 70, 70, 80, 80, 90),
            run.rotations.map { it.blocks[1].metres })
        assertEquals(listOf(70, 70, 70, 70, 70, 90, 90, 110, 110, 130, 130, 150, 150, 170, 170, 180),
            run.rotations.map { it.planned })
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 5), run.rotations.map { it.steps[0] })
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6), run.rotations.map { it.steps[1] })
        assertEquals(listOf(14), run.rotations.indices.filter { run.rotations[it].blocks[0].kind == "trial" })
    }

    @Test fun `a two-day 100 m non-intro pins 16 rotation openings to the simulator`() {
        val run = simulate(rotations = 16)
        assertEquals(listOf(160, 160, 170, 170, 100, 180, 180, 190, 190, 100, 200, 200, 210, 210, 100, 220),
            run.rotations.map { it.blocks[0].metres })
        assertEquals(listOf(30, 30, 40, 40, 50, 50, 60, 60, 70, 70, 80, 80, 90, 90, 100, 100),
            run.rotations.map { it.blocks[1].metres })
        assertEquals(listOf(190, 190, 210, 210, 230, 230, 240, 250, 260, 270, 280, 280, 300, 300, 320, 320),
            run.rotations.map { it.planned })
        assertEquals(listOf(12, 12, 13, 13, 14, 14, 14, 15, 15, 16, 16, 16, 17, 17, 18, 18), run.rotations.map { it.steps[0] })
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7), run.rotations.map { it.steps[1] })
        assertEquals(listOf(4, 9, 14), run.rotations.indices.filter { run.rotations[it].blocks[0].kind == "trial" })
    }

    @Test fun `a two-day 400 m plan pins 16 rotation openings to the simulator`() {
        val run = simulate(SprintEvent.M400, rotations = 16)
        assertEquals(listOf(30, 30, 40, 40, 400, 50, 50, 60, 60, 400, 70, 70, 80, 80, 400, 90),
            run.rotations.map { it.blocks[0].metres })
        assertEquals(listOf(300, 300, 320, 320, 600, 340, 340, 360, 360, 600, 380, 380, 400, 400, 600, 420),
            run.rotations.map { it.blocks[1].metres })
        assertEquals(listOf(330, 330, 360, 360, 390, 390, 390, 420, 420, 450, 450, 450, 480, 480, 510, 510),
            run.rotations.map { it.planned })
        assertEquals(List(2) { listOf(0, 0, 1, 1, 2, 2, 2, 3, 3, 4, 4, 4, 5, 5, 6, 6) },
            listOf(run.rotations.map { it.steps[0] }, run.rotations.map { it.steps[1] }))
    }

    @Test fun `two-day 200 and 400 m plans reach both tops with no deadlock`() {
        for (event in listOf(SprintEvent.M200, SprintEvent.M400)) for (intro in listOf(true, false)) {
            val run = simulate(event, intro, trials = false)
            assertEquals(7, rule(run.program, "A").steps)
            assertEquals(15, rule(run.program, "C").steps)
            assertEquals(if (intro) 18 else 15, run.rotations.indexOfFirst { it.blocks[0].metres == 100 } + 1)
            assertEquals(if (intro) 34 else 31, run.rotations.indexOfFirst { it.blocks[1].metres == 600 } + 1)
            val trialRun = simulate(event, intro)
            assertEquals(7, rule(trialRun.program, "A").steps)
            assertEquals(15, rule(trialRun.program, "C").steps)
        }
    }

    @Test fun `100 and 400 m two-day trial counts match the 52 rotation simulator rows`() {
        for ((event, intro, count) in listOf(Triple(SprintEvent.M100, true, 8), Triple(SprintEvent.M100, false, 10),
            Triple(SprintEvent.M400, true, 9), Triple(SprintEvent.M400, false, 10))) {
            val run = simulate(event, intro)
            assertEquals(count, run.trials, "$event intro=$intro")
            for (rotation in run.rotations.filter { it.blocks.any { block -> block.kind == "trial" } }) {
                val maximal = rotation.blocks.filter { it.kind != "tempo" }.sumOf { it.metres }
                assertTrue(SprintRules.withinRise(rotation.planned, maximal))
            }
        }
    }

    @Test fun `nine A sessions before B keep credit and allow only one step per rotation`() {
        val run = simulate(intro = true, rotations = 2) { cycle ->
            0 to if (cycle == 0) (0..8).map { "A" to it } + ("C" to 9) else listOf("A" to 0, "C" to 3)
        }
        assertEquals(listOf(40, 40, 40, 40, 40, 50, 50, 50, 50, 30), run.rotations[0].blocks.map { it.metres })
        assertEquals(1, rule(run.program, "A").stepCycle)
        assertEquals(2, rule(run.program, "A").steps)
    }

    @Test fun `out-of-order sessions keep the bound and B before A prevents long trials`() {
        for (event in listOf(SprintEvent.M200, SprintEvent.M400)) {
            val run = simulate(event) { cycle ->
                val rows = if (cycle % 2 == 1) listOf("C" to 0, "A" to 3) else listOf("A" to 0, "C" to 3)
                0 to if (cycle % 3 == 2) rows + ("A" to 6) else rows
            }
            for (cycle in listOf(9, 19, 29, 39, 49)) {
                assertTrue(run.rotations[cycle].blocks.all { it.kind == "normal" })
            }
            assertEquals(7, rule(run.program, "A").steps)
            assertEquals(15, rule(run.program, "C").steps)
        }
    }

    @Test fun `B before A or off-suggestion blocks a 200 or 400 m trial even with no step credit`() {
        for (event in listOf(SprintEvent.M200, SprintEvent.M400)) {
            val program = withRule(created(event), "A", CardioRule(null, RunSession.MAX_VELOCITY, steps = 2))
            val goal = PlanGoal.Sprint(event.metres)
            assertEquals(event.metres, SprintContext.of(program, "A", emptyMap(), 4, goal, 1_000, emptySet()).timeTrialMetres)
            assertNull(SprintContext.of(program, "A", mapOf("C" to listOf(999L)), 4, goal, 1_000, setOf("C")).timeTrialMetres)
            val b = SprintContext.of(program, "C", emptyMap(), 4, goal, 1_000, emptySet())
            assertFalse(b.tempo)
        }
    }

    @Test fun `14 days keep and 15 days restart a one-day sprinter`() {
        val program = created(days = 1)
        for (gap in listOf(14, 15)) {
            val sprint = SprintContext.of(program, "A", mapOf("A" to listOf(1_000L)), 3, PlanGoal.Sprint(100), 1_000L + gap, emptySet())
            assertEquals(gap == 15, sprint.layoff)
            val context = Progression.CardioContext(1_000L + gap, null, 0, sprint = sprint)
            val block = Progression.cardioAtStart(requireNotNull(day(program, "A").cardioBlock), rule(program, "A"), history, null, context)
            assertEquals(if (gap == 15) 40 else 160, SprintRules.workMetres(block))
            val out = Progression.cardioAfterSession(block, rule(program, "A"), finished(block), CardioEffort.HARD, history, context)
            assertEquals(if (gap == 15) CardioChange.Restart else CardioChange.Repeat, out.change)
            assertEquals(if (gap == 15) CardioRule(null, RunSession.ACCELERATION, intro = 2)
                else rule(program, "A").copy(credits = 1), out.rule)
        }
    }

    @Test fun `plan-wide scan keeps 14-day gaps and restarts both days after 15-day gaps`() {
        val program = created(SprintEvent.M400)
        for (gap in listOf(14, 15)) {
            val dates = mapOf("A" to listOf(990L, 1_000L + gap), "C" to listOf(1_000L))
            val a = SprintContext.of(program, "A", mapOf("A" to listOf(990L), "C" to listOf(1_000L)),
                3, null, 1_000L + gap, emptySet())
            val b = SprintContext.of(program, "C", dates, 3, null, 1_003L + gap, setOf("A"))
            assertEquals(gap == 15, a.layoff)
            assertEquals(gap == 15, b.layoff)
            val context = Progression.CardioContext(1_003L + gap, null, 0, sprint = b)
            val block = Progression.cardioAtStart(requireNotNull(day(program, "C").cardioBlock), rule(program, "C"), history, null, context)
            val out = Progression.cardioAfterSession(block, rule(program, "C"), finished(block), CardioEffort.HARD, history, context)
            assertEquals(if (gap == 15) CardioChange.Restart else CardioChange.Repeat, out.change)
            assertEquals(if (gap == 15) 2 else 0, requireNotNull(out.rule).intro)
            val neverRun = SprintContext.of(program, "C", mapOf("A" to listOf(1_000L, 1_000L + gap)),
                3, null, 1_003L + gap, setOf("A"))
            assertEquals(gap == 15, neverRun.layoff)
        }
        val ongoing = mapOf("A" to listOf(982L), "C" to listOf(996L))
        assertFalse(SprintContext.of(created(), "A", ongoing, 3, null, 1_000, emptySet()).layoff)
    }

    @Test fun `tempo and non-sprint dates do not interrupt the plan-wide layoff`() {
        val program = created(days = 5)
        val dates = mapOf("A" to listOf(970L), "D" to listOf(993L), "F" to listOf(999L), "B" to listOf(995L))
        assertTrue(SprintContext.of(program, "A", dates, 3, null, 1_000, emptySet()).layoff)
        assertFalse(SprintContext.of(program, "F", dates, 3, null, 1_000, emptySet()).layoff)
        assertTrue(SprintContext.of(program, "D", mapOf("A" to listOf(970L), "F" to listOf(999L)),
            3, null, 1_000, emptySet()).layoff)
    }

    @Test fun `intro ending on trial rotation five keeps B maximal`() {
        val run = simulate(SprintEvent.M200, intro = true, rotations = 5) { cycle ->
            0 to if (cycle in 1..2) listOf("C" to 3) else listOf("A" to 0, "C" to 3)
        }
        assertEquals(listOf(Opened("A", "normal", 30), Opened("C", "normal", 300)), run.rotations[4].blocks)
        assertEquals(0, rule(run.program, "A").intro)
    }

    @Test fun `skipping A on trial rotation ten keeps B normal at 380 meters`() {
        val run = simulate(SprintEvent.M200, rotations = 10) { cycle ->
            0 to if (cycle == 9) listOf("C" to 3) else listOf("A" to 0, "C" to 3)
        }
        assertEquals(listOf(Opened("C", "normal", 380)), run.rotations[9].blocks)
    }

    @Test fun `return on trial rotation twenty restarts A and B without a trial or tempo`() {
        val run = simulate(SprintEvent.M400, rotations = 20) { cycle ->
            (if (cycle == 19) 21 else 0) to listOf("A" to 0, "C" to 3)
        }
        assertEquals(listOf(Opened("A", "reset", 30), Opened("C", "reset", 300)), run.rotations[19].blocks)
        assertEquals(2, run.restarts)
        assertEquals(2, rule(run.program, "A").intro)
        assertEquals(2, rule(run.program, "C").intro)
    }

    @Test fun `200 m trial waits while B restart is pending after A finishes restart intro`() {
        val run = simulate(SprintEvent.M200, rotations = 5) { cycle ->
            (if (cycle == 1) 21 else 0) to
                if (cycle in 1..3) listOf("A" to 0) else listOf("A" to 0, "C" to 3)
        }
        assertEquals(listOf(Opened("A", "normal", 30), Opened("C", "normal", 300)), run.rotations[0].blocks)
        assertEquals(listOf(Opened("A", "reset", 30)), run.rotations[1].blocks)
        for (cycle in 2..3) assertEquals(listOf(Opened("A", "normal", 30)), run.rotations[cycle].blocks)
        val trialRotation = run.rotations[4]
        assertEquals(330, trialRotation.planned)
        assertEquals(listOf(Opened("A", "normal", 30), Opened("C", "reset", 300)), trialRotation.blocks)
        assertTrue(SprintRules.withinRise(trialRotation.planned, trialRotation.blocks.sumOf { it.metres }))
        assertEquals(0, run.trials)
        assertEquals(2, run.restarts)
        assertEquals(0, rule(run.program, "A").intro)
        assertEquals(2, rule(run.program, "C").intro)
    }

    @Test fun `A repeated after its trial runs normal at 70 meters and B runs tempo`() {
        val run = simulate(SprintEvent.M400, rotations = 5) { 0 to listOf("A" to 0, "A" to 1, "C" to 3) }
        assertEquals(listOf(Opened("A", "trial", 400), Opened("A", "normal", 70), Opened("C", "tempo", 600)),
            run.rotations[4].blocks)
        assertEquals(1, run.trials)
        assertEquals(4, rule(run.program, "A").sprintTrialCycle)
    }

    @Test fun `trial preserves A stored step and credit for the next normal session`() {
        val program = withRule(created(), "A", CardioRule(null, RunSession.ACCELERATION, steps = 14, credits = 1))
        val goal = PlanGoal.Sprint(100)
        val sprint = SprintContext.of(program, "A", emptyMap(), 4, goal, 1_000, emptySet())
        val context = Progression.CardioContext(1_000, null, 0, sprint = sprint)
        val stored = requireNotNull(day(program, "A").cardioBlock)
        val block = Progression.cardioAtStart(stored, rule(program, "A"), history, goal, context)
        val out = Progression.cardioAfterSession(block, rule(program, "A"), finished(block), CardioEffort.HARD, history, context)
        assertEquals(CardioOutcome(stored, rule(program, "A").copy(sprintTrialCycle = 4), CardioChange.Repeat), out)
        val next = withRule(program, "A", requireNotNull(out.rule))
        assertNull(SprintContext.of(next, "A", mapOf("A" to listOf(1_000L)), 4, goal, 1_001, setOf("A")).timeTrialMetres)
        val after = SprintContext.of(next, "A", mapOf("A" to listOf(1_000L)), 5, goal, 1_007, emptySet())
        assertEquals(stored, Progression.cardioAtStart(stored, out.rule, history, goal, context.copy(sprint = after)))
    }

    @Test fun `long trials admit at 350 planned meters and reject 400 at 340 meters`() {
        for (event in listOf(SprintEvent.M200, SprintEvent.M400)) {
            for (steps in listOf(1, 2)) {
                var program = withRule(created(event), "A", CardioRule(null, RunSession.MAX_VELOCITY, steps = steps, credits = 1))
                val goal = PlanGoal.Sprint(event.metres)
                assertEquals(330 + steps * 10, planned(program))
                val context = SprintContext.of(program, "A", emptyMap(), 4, goal, 1_000, emptySet())
                val admitted = event == SprintEvent.M200 || steps == 2
                assertEquals(event.metres.takeIf { admitted }, context.timeTrialMetres)
                assertFalse(SprintContext.of(program, "C", emptyMap(), 4, goal, 1_000, emptySet()).tempo)
                if (admitted) {
                    program = withRule(program, "A", rule(program, "A").copy(sprintTrialCycle = 4))
                    assertTrue(SprintContext.of(program, "C", mapOf("A" to listOf(1_000L)), 4, goal, 1_003, setOf("A")).tempo)
                }
            }
        }
    }

    @Test fun `short trial admission counts both reps and leaves B maximal`() {
        for ((event, steps, admitted) in listOf(Triple(37, 0, false), Triple(60, 4, false),
            Triple(37, 4, true), Triple(60, 12, true), Triple(100, 12, true))) {
            val program = withRule(created(), "A", CardioRule(null, RunSession.ACCELERATION, steps = steps))
            val context = SprintContext.of(program, "A", emptyMap(), 4, PlanGoal.Sprint(event), 1_000, emptySet())
            assertEquals(event.takeIf { admitted }, context.timeTrialMetres)
            assertFalse(SprintContext.of(program, "C", emptyMap(), 4, PlanGoal.Sprint(event), 1_003, emptySet()).tempo)
        }
    }

    @Test fun `one-day 200 and 400 m plans never admit a trial`() {
        for (event in listOf(SprintEvent.M200, SprintEvent.M400)) for (steps in 0..7) {
            val program = withRule(created(event, days = 1), "A", CardioRule(null, RunSession.MAX_VELOCITY, steps = steps))
            assertNull(SprintContext.of(program, "A", emptyMap(), 4, PlanGoal.Sprint(event.metres), 1_000, emptySet()).timeTrialMetres)
        }
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
