package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.progression.CardioChange
import cloud.trotter.log.strength.domain.progression.CardioEffort
import cloud.trotter.log.strength.domain.progression.CardioHistory
import cloud.trotter.log.strength.domain.progression.CardioOutcome
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.Progression
import cloud.trotter.log.strength.domain.progression.RunRange
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.SprintContext
import cloud.trotter.log.strength.domain.progression.SprintDecision
import cloud.trotter.log.strength.domain.progression.SprintDose
import cloud.trotter.log.strength.domain.progression.SprintRecord
import cloud.trotter.log.strength.domain.progression.SprintRules
import cloud.trotter.log.strength.domain.progression.sprintBlock
import cloud.trotter.log.strength.domain.progression.sprintMaximalMetres
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SprintAllowanceTest {
    private fun created(event: SprintEvent = SprintEvent.M100, days: Int = 2, intro: Boolean = false) =
        Planner.plan(sprint(event, days, answers = SprintAnswers(event, sprintedRecently = !intro))).program
    private fun rule(program: Program, id: String) = requireNotNull(program.days.single { it.id == id }.cardioRule)
    private fun withRule(program: Program, id: String, next: CardioRule) = program.copy(days = program.days.map {
        if (it.id == id) it.copy(cardioRule = next) else it
    })
    private fun finished(block: CardioBlock) = block.sets.map {
        CardioSetResult(it, CardioSetStatus.FINISHED, (it.end as? Measure.Time)?.seconds ?: 0,
            (it.end as? Measure.Distance)?.metres, if (it.end is Measure.Distance) DistanceSource.MANUAL else null)
    }
    private fun reps(block: CardioBlock) = block.sets.filter { it.role == CardioRole.WORK }
        .map { (it.end as Measure.Distance).metres }
    private fun dropRep(block: CardioBlock) = block.copy(sets = block.sets.dropLast(2))
    private fun decide(rule: CardioRule, context: SprintContext) =
        SprintRules.decision(rule, requireNotNull(rule.session), context, CardioActivity.TRACK_RUN)
    private fun finish(rule: CardioRule, context: SprintContext, block: CardioBlock = decide(rule, context).block) =
        Progression.cardioAfterSession(block, rule, finished(block), CardioEffort.GOOD, CardioHistory(),
            Progression.CardioContext(0, null, 0, sprint = context))
    private fun ledger(program: Program, metres: Int, cycle: Int = 1, goal: PlanGoal? = PlanGoal.Sprint(100),
                       today: Long = 0, maximal: Set<String> = emptySet()) =
        SprintContext.of(program, "A", emptyMap(), cycle, goal, today, maximal,
            maximalMetresByEpochDay = mapOf(-1L to metres))

    private data class Run(val before: CardioRule, val context: SprintContext, val decision: SprintDecision,
                           val outcome: CardioOutcome, val block: CardioBlock)
    private inner class Driver(event: SprintEvent = SprintEvent.M100, days: Int = 2, intro: Boolean = false,
                               val trials: Boolean = true) {
        var program = created(event, days, intro)
        val goal = PlanGoal.Sprint(event.metres).takeIf { trials }
        val records = mutableListOf<SprintRecord>()
        fun context(id: String, date: Long, cycle: Int) =
            SprintContext.ofHistory(program, id, records, cycle, goal, date, careful = false)
        fun run(id: String, date: Long, cycle: Int, edit: (SprintDecision, CardioBlock) -> CardioBlock = { d, _ -> d.block }): Run {
            val old = rule(program, id)
            val context = context(id, date, cycle)
            val decision = decide(old, context)
            val ctx = Progression.CardioContext(date, null, 0, sprint = context)
            val stored = requireNotNull(program.days.single { it.id == id }.cardioBlock)
            assertEquals(decision.block, Progression.cardioAtStart(stored, old, CardioHistory(), goal, ctx))
            val block = edit(decision, sprintBlock(requireNotNull(old.session), old.steps))
            val results = finished(block)
            val out = Progression.cardioAfterSession(block, old, results, CardioEffort.GOOD, CardioHistory(), ctx)
            var next = assertNotNull(out.rule)
            if (sprintMaximalMetres(results) > 0) next = next.copy(maximalCycle = cycle)
            program = withRule(program, id, next)
            records += SprintRecord(id, date, results)
            return Run(old, context, decision, out, block)
        }
    }

    @Test fun `dense days run the tempo substitute once the week is full`() {
        val driver = Driver()
        val a0 = driver.run("A", 0, 0)
        assertEquals(SprintDose.NORMAL, a0.decision.dose)
        assertEquals(160, SprintRules.workMetres(a0.block))
        assertEquals(350, a0.context.weekLeft)
        assertEquals(30, SprintRules.workMetres(driver.run("C", 1, 0).block))
        val a2 = driver.run("A", 2, 1)
        assertEquals(160, SprintRules.workMetres(a2.block))
        assertEquals(13, a2.outcome.rule?.steps)
        val c3 = driver.run("C", 3, 1)
        assertEquals(20, c3.context.weekLeft)
        assertEquals(SprintDose.SUBSTITUTE, c3.decision.dose)
        assertEquals(List(6) { 100 }, reps(c3.block))
        assertEquals(c3.before, c3.outcome.rule)
        assertEquals(0, rule(driver.program, "C").maximalCycle)
    }

    @Test fun `a shortened block keeps the range minimum`() {
        val program = created()
        val old = rule(program, "A")
        val short = ledger(program, 265)
        assertEquals(85, short.weekLeft)
        assertEquals(SprintDose.SHORTENED, decide(old, short).dose)
        assertEquals(List(4) { 20 }, reps(decide(old, short).block))
        val substitute = ledger(program, 280)
        assertEquals(70, substitute.weekLeft)
        assertEquals(SprintDose.SUBSTITUTE, decide(old, substitute).dose)
        for (context in listOf(short, substitute)) {
            assertEquals(old, finish(old, context).rule)
            assertEquals(CardioChange.Repeat, finish(old, context).change)
        }
    }

    @Test fun `a restart is never shortened`() {
        for ((session, minimum) in listOf(RunSession.ACCELERATION to 4, RunSession.MAX_VELOCITY to 3,
            RunSession.SPRINT_ENDURANCE to 2)) {
            assertEquals(minimum, requireNotNull(RunRange.of(session)).rounds.first)
            assertEquals(minimum, reps(sprintBlock(session, 0)).size)
            val rule = CardioRule(null, session, steps = 1)
            val context = SprintContext(layoff = true, weekLeft = SprintRules.workMetres(sprintBlock(session, 0)) - 1)
            assertEquals(SprintDose.SUBSTITUTE, decide(rule, context).dose)
            assertEquals(rule, finish(rule, context).rule)
        }
    }

    @Test fun `an edited-down restart writes the restart state without counting`() {
        val old = CardioRule(null, RunSession.ACCELERATION, steps = 12, credits = 1)
        val low = sprintBlock(RunSession.ACCELERATION, 0)
        assertEquals(CardioOutcome(low, CardioRule(null, RunSession.ACCELERATION, intro = 3), CardioChange.Restart),
            finish(old, SprintContext(layoff = true), dropRep(low)))
    }

    @Test fun `an edited-down normal block earns no credit`() {
        val old = rule(created(), "A")
        for (context in listOf(SprintContext(false), ledger(created(), 0))) {
            assertEquals(old, finish(old, context, dropRep(sprintBlock(RunSession.ACCELERATION, 12))).rule)
            assertEquals(CardioChange.Repeat, finish(old, context, dropRep(sprintBlock(RunSession.ACCELERATION, 12))).change)
        }
    }

    @Test fun `a shortened block edited back up earns no credit`() {
        val program = created()
        val old = rule(program, "A")
        val context = ledger(program, 220)
        assertEquals(130, context.weekLeft)
        assertEquals(List(6) { 20 }, reps(decide(old, context).block))
        assertEquals(SprintDose.SHORTENED, decide(old, context).dose)
        assertEquals(old, finish(old, context, sprintBlock(RunSession.ACCELERATION, 12)).rule)
    }

    @Test fun `a trial declined by the cap leaves the rotation to the normal rules`() {
        val program = created()
        val context = ledger(program, 265, cycle = 4)
        assertNull(context.timeTrialMetres)
        assertEquals(SprintDose.SHORTENED, decide(rule(program, "A"), context).dose)
        assertNull(finish(rule(program, "A"), context).rule?.sprintTrialCycle)
        val next = SprintContext.of(program, "A", emptyMap(), 4, PlanGoal.Sprint(100), 1, setOf("A"),
            maximalMetresByEpochDay = emptyMap())
        assertNull(next.timeTrialMetres)
        assertEquals(SprintDose.NORMAL, decide(rule(program, "A"), next).dose)
        assertEquals(160, SprintRules.workMetres(decide(rule(program, "A"), next).block))
    }

    @Test fun `a trial declined into the substitute stays available in the rotation`() {
        val program = created()
        val context = ledger(program, 280, cycle = 4)
        assertNull(context.timeTrialMetres)
        assertEquals(SprintDose.SUBSTITUTE, decide(rule(program, "A"), context).dose)
        assertEquals(rule(program, "A"), finish(rule(program, "A"), context).rule)
        val next = ledger(program, 280, cycle = 4, today = 7)
        assertEquals(100, next.timeTrialMetres)
        assertEquals(SprintDose.TRIAL, decide(rule(program, "A"), next).dose)
    }

    @Test fun `a declined long trial never sends B to tempo`() {
        val program = created(SprintEvent.M200)
        val context = ledger(program, 500, cycle = 4, goal = PlanGoal.Sprint(200))
        assertEquals(130, context.weekLeft)
        assertNull(context.timeTrialMetres)
        assertEquals(SprintDose.NORMAL, decide(rule(program, "A"), context).dose)
        assertEquals(30, SprintRules.workMetres(decide(rule(program, "A"), context).block))
        val next = withRule(program, "A", assertNotNull(finish(rule(program, "A"), context).rule).copy(maximalCycle = 4))
        assertFalse(SprintContext.ofHistory(next, "C", listOf(SprintRecord("A", 0, finished(decide(rule(program, "A"), context).block))),
            4, PlanGoal.Sprint(200), 1, false).tempo)
    }

    @Test fun `the trial allowance stays in the cap while the trial is in the window`() {
        val driver = Driver(SprintEvent.YD40, days = 1)
        driver.program = withRule(driver.program, "A", CardioRule(null, RunSession.ACCELERATION, steps = 2))
        val normal = driver.run("A", 0, 3)
        assertEquals(120, normal.context.weekLeft)
        assertEquals(60, SprintRules.workMetres(normal.block))
        val trial = driver.run("A", 1, 4)
        assertEquals(SprintDose.TRIAL, trial.decision.dose)
        assertEquals(74, SprintRules.workMetres(trial.block))
        val next = driver.run("A", 2, 5)
        assertEquals(0, next.context.weekLeft)
        assertEquals(SprintDose.SUBSTITUTE, next.decision.dose)
    }

    @Test fun `the Finish anchor is the start date and excludes the session`() {
        val driver = Driver()
        driver.records += listOf(SprintRecord("A", -6, finished(sprintBlock(RunSession.ACCELERATION, 12))),
            SprintRecord("C", -4, finished(sprintBlock(RunSession.MAX_VELOCITY, 0))),
            SprintRecord("C", -2, finished(sprintBlock(RunSession.MAX_VELOCITY, 0))))
        val correct = driver.context("A", 0, 1)
        val old = rule(driver.program, "A")
        val block = decide(old, correct).block
        assertEquals(SprintDose.SHORTENED, decide(old, correct).dose)
        assertEquals(List(6) { 20 }, reps(block))
        driver.records += SprintRecord("A", 0, finished(block))
        val naive = driver.context("A", 1, 1)
        assertEquals(170, naive.weekLeft)
        assertEquals(SprintDose.NORMAL, decide(old, naive).dose)
        assertNotEquals(decide(old, correct), decide(old, naive))
        assertEquals(old, finish(old, naive, block).rule)
    }

    @Test fun `G-b exception after a restart`() {
        val driver = Driver(trials = false)
        assertEquals(160, SprintRules.workMetres(driver.run("A", 0, 0).block))
        val c18 = driver.run("C", 18, 1)
        assertEquals(SprintDose.RESTART, c18.decision.dose)
        assertEquals(2, c18.outcome.rule?.intro)
        assertEquals(1, driver.run("C", 20, 1).outcome.rule?.intro)
        assertEquals(0, driver.run("C", 22, 1).outcome.rule?.intro)
        val a23 = driver.run("A", 23, 1)
        assertEquals(260, a23.context.weekLeft)
        assertEquals(SprintDose.RESTART, a23.decision.dose)
        assertEquals(40, SprintRules.workMetres(a23.block))
        val c24 = driver.run("C", 24, 1)
        assertEquals(-20, c24.context.weekLeft)
        assertEquals(SprintDose.SUBSTITUTE, c24.decision.dose)
        assertEquals(c24.before, c24.outcome.rule)
    }

    @Test fun `G-b exception after a manual override`() {
        val driver = Driver(trials = false)
        val runs = (0..11).map { date -> driver.run(if (date % 2 == 0) "A" else "C", date.toLong(), date / 2) { d, full ->
            if (d.dose == SprintDose.SHORTENED) full else d.block
        } }
        assertEquals(listOf(160, 30, 160, 600, 600, 600, 600, 30, 170, 40, 170, 600),
            runs.map { SprintRules.workMetres(it.block) })
        assertEquals(listOf(SprintDose.NORMAL, SprintDose.NORMAL, SprintDose.NORMAL, SprintDose.SUBSTITUTE,
            SprintDose.SUBSTITUTE, SprintDose.SUBSTITUTE, SprintDose.SUBSTITUTE, SprintDose.NORMAL,
            SprintDose.NORMAL, SprintDose.NORMAL, SprintDose.SHORTENED, SprintDose.SUBSTITUTE), runs.map { it.decision.dose })
        assertEquals(13, runs[2].outcome.rule?.steps)
        assertEquals(1, runs[7].outcome.rule?.steps)
        assertEquals(SprintDose.SHORTENED, runs[10].decision.dose)
        assertEquals(130, SprintRules.workMetres(runs[10].decision.block))
        assertEquals(runs[10].before, runs[10].outcome.rule)
        assertEquals(-30, runs[11].context.weekLeft)
        assertEquals(SprintDose.SUBSTITUTE, runs[11].decision.dose)
    }

    @Test fun `ofHistory derives the ledger by shape`() {
        var program = created()
        program = withRule(program, "C", rule(program, "C").copy(maximalCycle = 4))
        fun work(metres: Int) = CardioSetSpec(CardioRole.WORK, Measure.Distance(metres), hard = true)
        val trial = finished(CardioBlock(CardioActivity.TRACK_RUN, listOf(work(100))))
        val gps = CardioSetResult(work(20), CardioSetStatus.FINISHED, 5, 23, DistanceSource.GPS)
        val skipped = gps.copy(status = CardioSetStatus.SKIPPED)
        assertEquals(20, sprintMaximalMetres(listOf(gps, skipped)))
        assertEquals(0, sprintMaximalMetres(finished(sprintBlock(RunSession.SPRINT_TEMPO, 0))))
        val records = listOf(SprintRecord("A", -4, trial), SprintRecord("A", -3, finished(CardioBlock(CardioActivity.TRACK_RUN, listOf(work(30))))),
            SprintRecord("C", -2, listOf(gps, skipped)), SprintRecord("C", -2, trial),
            SprintRecord("A", -1, finished(sprintBlock(RunSession.SPRINT_TEMPO, 0))), SprintRecord("A", 0, listOf(skipped)))
        val context = SprintContext.ofHistory(program, "A", records, 4, PlanGoal.Sprint(100), 0, false)
        // 160+30+160 minus (100+30+20+100); GPS and skipped reps add no extra meters.
        assertEquals(100, context.weekLeft)
        assertEquals(context, SprintContext.of(program, "A", mapOf("A" to listOf(-4L, -3L), "C" to listOf(-2L, -2L)),
            4, PlanGoal.Sprint(100), 0, setOf("C"), maximalMetresByEpochDay = mapOf(-4L to 100, -3L to 30, -2L to 120),
            trialEpochDays = setOf(-4L)))
        val oneDay = withRule(created(SprintEvent.YD40, days = 1), "A", CardioRule(null, RunSession.ACCELERATION, steps = 2))
        fun left(rows: List<SprintRecord>) = SprintContext.ofHistory(oneDay, "A", rows, 3, PlanGoal.Sprint(37), 0, false).weekLeft
        assertEquals(34, left(listOf(SprintRecord("A", -1, trial))))
        val shortWork = finished(CardioBlock(CardioActivity.TRACK_RUN, listOf(work(30))))
        assertEquals(90, left(listOf(SprintRecord("A", -1, shortWork))))
        assertEquals(120, left(listOf(SprintRecord("C", -1, trial), SprintRecord("A", -1, listOf(skipped)))))
        val stale = withRule(program, "A", rule(program, "A").copy(maximalCycle = 3))
        assertEquals(100, SprintContext.ofHistory(stale, "A", emptyList(), 4, PlanGoal.Sprint(100), 0, false).timeTrialMetres)
        val current = withRule(stale, "A", rule(stale, "A").copy(maximalCycle = 4))
        assertNull(SprintContext.ofHistory(current, "A", emptyList(), 4, PlanGoal.Sprint(100), 0, false).timeTrialMetres)
        val small = withRule(created(SprintEvent.YD40), "A", CardioRule(null, RunSession.ACCELERATION, steps = 2))
        assertEquals(50, SprintContext.ofHistory(small, "A", listOf(SprintRecord("C", -1, trial)),
            3, PlanGoal.Sprint(37), 0, false).weekLeft)
        assertEquals(64, SprintContext.ofHistory(small, "A", listOf(SprintRecord("A", -1, trial)),
            3, PlanGoal.Sprint(37), 0, false).weekLeft)
        val gap = listOf(SprintRecord("A", -20, trial), SprintRecord("A", -10, listOf(skipped)),
            SprintRecord("C", -5, finished(sprintBlock(RunSession.SPRINT_TEMPO, 0))))
        assertTrue(SprintContext.ofHistory(program, "A", gap, 4, PlanGoal.Sprint(100), 0, false).layoff)
        val old = rule(program, "A")
        assertEquals(160, sprintMaximalMetres(finished(sprintBlock(RunSession.ACCELERATION, 12))))
        assertEquals(old, Progression.cardioAfterSession(sprintBlock(RunSession.ACCELERATION, 12), old,
            finished(sprintBlock(RunSession.ACCELERATION, 12)), CardioEffort.GOOD, CardioHistory(),
            Progression.CardioContext(0, null, 0, sprint = SprintContext(false)), pain = true).rule)
    }

    private enum class Calendar { DRIFT, SLIP, EVERY_48_HOURS, DENSE, NINE, MONDAY_THURSDAY }
    private fun rows(calendar: Calendar, days: Int, cycle: Int): List<Pair<Int, Int>> = when (calendar) {
        Calendar.DRIFT -> listOf(0 to 0, 1 to if (cycle % 2 == 0) 5 else 3)
        Calendar.SLIP -> listOf(0 to listOf(0, 2, 1)[cycle % 3], 1 to listOf(3, 6, 5)[cycle % 3])
        Calendar.EVERY_48_HOURS, Calendar.DENSE -> {
            val entries: List<Int?> = when (days) {
                1 -> listOf(0)
                2 -> listOf(0, 1)
                3 -> listOf(0, null, 1)
                4 -> listOf(0, null, 1, null)
                5 -> listOf(0, null, 1, null, null)
                6 -> listOf(0, null, 1, null, null, null)
                else -> error("Unsupported day count")
            }
            val gap = if (calendar == Calendar.DENSE) 1 else 2
            entries.mapIndexedNotNull { index, entry -> entry?.let { it to index * gap } }
        }
        Calendar.NINE -> if (cycle == 0) (0..8).map { 0 to it } + if (days > 1) listOf(1 to 9) else emptyList()
            else listOf(0 to 0) + if (days > 1) listOf(1 to if (days == 2) 2 else 3) else emptyList()
        Calendar.MONDAY_THURSDAY -> listOf(0 to 0)
    }

    private fun checkCalendar(calendar: Calendar, event: SprintEvent, days: Int, intro: Boolean): Int? {
        val driver = Driver(event, days, intro)
        val ids = driver.program.days.filter { it.cardioRule?.session?.let(SprintRules::maximal) == true }.map { it.id }
        var base = 0L
        var firstTop: Int? = null
        repeat(52) { cycle ->
            val rows = rows(calendar, days, cycle)
            for ((index, offset) in rows) {
                val date = if (calendar == Calendar.MONDAY_THURSDAY) cycle / 2 * 7L + if (cycle % 2 == 0) 0 else 3 else base + offset
                val id = ids[index]
                val context = driver.context(id, date, cycle)
                val old = rule(driver.program, id)
                val session = requireNotNull(old.session)
                val decision = decide(old, context)
                val label = "$calendar $event days=$days intro=$intro cycle=$cycle id=$id"
                val normal = ids.map { rule(driver.program, it) }.map { SprintRules.workMetres(sprintBlock(requireNotNull(it.session), it.steps)) }
                val recent = driver.records.filter { it.epochDay in date - 6..date }.sumOf { sprintMaximalMetres(it.results) }
                val trial = SprintRules.workMetres(SprintRules.timeTrial(event.metres, CardioActivity.TRACK_RUN))
                val trialRep = requireNotNull(RunRange.of(requireNotNull(rule(driver.program, ids.first()).session))).rep.last
                val retained = driver.records.any { record -> record.dayId == ids.first() && record.epochDay in date - 6..date &&
                    sprintMaximalMetres(record.results) > 0 && record.results.any { it.spec.role == CardioRole.WORK &&
                        ((it.spec.end as? Measure.Distance)?.metres ?: 0) > trialRep } }
                assertEquals(normal.sum() + maxOf(normal.maxOrNull() ?: 0, if (retained) trial else 0) - recent,
                    context.weekLeft, label)
                val left = assertNotNull(context.weekLeft)
                assertTrue(left >= 0, label)
                val full = sprintBlock(session, old.steps)
                when (decision.dose) {
                    SprintDose.NORMAL, SprintDose.RESTART, SprintDose.SHORTENED ->
                        assertTrue(SprintRules.workMetres(decision.block) <= left, label)
                    SprintDose.TRIAL -> assertTrue(recent + trial <= normal.sum() + maxOf(normal.maxOrNull() ?: 0, trial), label)
                    SprintDose.TEMPO -> assertEquals(cycle, rule(driver.program, ids.first()).sprintTrialCycle, label)
                    SprintDose.SUBSTITUTE -> {
                        val prescribed = if (context.layoff) sprintBlock(session, 0) else full
                        val minimum = reps(prescribed).take(requireNotNull(RunRange.of(session)).rounds.first).sum()
                        assertTrue(minimum > left, label)
                    }
                }
                if (decision.dose == SprintDose.SHORTENED) assertTrue(SprintRules.workMetres(full) > left, label)
                if (calendar in listOf(Calendar.DRIFT, Calendar.SLIP, Calendar.MONDAY_THURSDAY)) {
                    assertFalse(decision.dose in listOf(SprintDose.SHORTENED, SprintDose.SUBSTITUTE), label)
                    val dates = driver.records.filter { sprintMaximalMetres(it.results) > 0 }.groupBy { it.dayId }
                        .mapValues { (_, records) -> records.map { it.epochDay } }
                    val maximal = ids.filter { rule(driver.program, it).maximalCycle == cycle }.toSet()
                    val unguarded = SprintContext.of(driver.program, id, dates, cycle, driver.goal, date, maximal)
                    assertEquals(unguarded.timeTrialMetres, context.timeTrialMetres, label)
                }
                driver.run(id, date, cycle)
                if (firstTop == null && rule(driver.program, ids.first()).steps ==
                    requireNotNull(RunRange.of(requireNotNull(rule(driver.program, ids.first()).session))).top) firstTop = cycle + 1
            }
            base += when (calendar) {
                Calendar.DRIFT, Calendar.SLIP -> 7
                Calendar.EVERY_48_HOURS -> days * 2
                Calendar.DENSE -> days
                Calendar.NINE -> maxOf(7, rows.maxOf { it.second } + 1)
                Calendar.MONDAY_THURSDAY -> 0
            }
        }
        return firstTop
    }

    @Test fun `ordinary drift and slip never cut a block or decline a trial`() {
        for (calendar in listOf(Calendar.DRIFT, Calendar.SLIP)) for (event in SprintEvent.entries)
            for (days in 2..6) for (intro in listOf(false, true)) checkCalendar(calendar, event, days, intro)
    }

    @Test fun `dense every 48 hours and nine session calendars obey the allowance`() {
        for (calendar in listOf(Calendar.DENSE, Calendar.EVERY_48_HOURS, Calendar.NINE)) for (event in SprintEvent.entries)
            for (days in 1..6) for (intro in listOf(false, true)) checkCalendar(calendar, event, days, intro)
    }

    @Test fun `a one-day plan run twice a week progresses twice as fast`() {
        for (event in SprintEvent.entries) {
            for (intro in listOf(false, true)) {
                val top = checkCalendar(Calendar.MONDAY_THURSDAY, event, 1, intro)
                if (!intro) assertEquals(if (event.metres <= 100) 19 else 14, top)
            }
        }
    }
}
