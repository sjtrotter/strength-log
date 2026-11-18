package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.progression.plannedSeconds
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.availableWith
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.RecentMaxima
import cloud.trotter.log.strength.domain.progression.WeekLoad
import cloud.trotter.log.strength.domain.progression.withoutLastRound
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.RunCap
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.RunWalkLadder
import cloud.trotter.log.strength.domain.progression.SlotRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerRunInvariantsTest {
    private fun variants() = RunGoal.entries.flatMap { goal ->
        when (goal) {
            RunGoal.FITNESS_TEST -> FitnessTest.entries.map { RunAnswers(goal, test = it) }
            RunGoal.CARDIO_FITNESS -> listOf(RunAnswers(goal))
            else -> goal.events.map { RunAnswers(goal, event = it) }
        }
    }

    private fun check(answers: SetupAnswers, known: KnownLoads, label: String) {
        val draft = Planner.plan(answers, known, TODAY)
        val run = answers.run
        val anchor = known.longestRecent[CardioFamily.of(run.activity)] ?: run.seed()
        val goal = if (answers.branch == PlanBranch.RUN) LiftGoal.STRENGTH else answers.goal
        val cardio = draft.program.days.filter { it.cardioBlock != null }
        assertEquals(cardio.map { it.id }.toSet(), draft.sessions.keys, "$label session keys")
        val quality = draft.sessions.values.count { it.quality }
        assertTrue(quality <= if (cardio.size <= 3 || run.experience == RunExperience.NEW) 1 else 2, "$label quality")
        val long = cardio.firstOrNull { draft.sessions[it.id] == RunSession.LONG }?.cardioBlock
        val workingKeys = mutableSetOf<SlotKey>()
        for (day in draft.program.days) {
            val session = draft.sessions[day.id]
            val block = day.cardioBlock
            if (block != null) {
                assertEquals(run.activity, block.activity, "$label activity")
                if (session == RunSession.LADDER) {
                    val level = requireNotNull(day.cardioRule?.ladderLevel) { "$label ladder level" }
                    assertEquals(RunWalkLadder.block(level, run.activity), block, "$label ladder block")
                } else {
                    assertEquals(block, RunCap.capped(block, anchor, null), "$label ${day.id} cap")
                    val counted = block.sets.filter { it.role == CardioRole.WORK || it.role == CardioRole.RECOVERY }
                    if (anchor?.metres != null && counted.all { it.end is Measure.Distance }) {
                        assertTrue(counted.sumOf { (it.end as Measure.Distance).metres } <= RunCap.limit(anchor.metres, 100),
                            "$label ${day.id} distance total")
                    }
                    if (anchor?.seconds != null && counted.all { it.end is Measure.Time }) {
                        assertTrue(counted.sumOf { (it.end as Measure.Time).seconds } <= RunCap.limit(anchor.seconds, 60),
                            "$label ${day.id} time total")
                    }
                }
                if (day.kind == ProgramDayKind.CARDIO && session == RunSession.EASY) {
                    assertTrue(plannedSeconds(block) <= easyRunSeconds(answers.minutes, day.exercises.isNotEmpty(), long),
                        "$label ${day.id} easy session stop")
                }
                if (session == RunSession.EASY && long != null) {
                    assertTrue(plannedSeconds(block) <= maxOf(60, (3 * plannedSeconds(long) / 4) / 60 * 60), "$label easy limit")
                }
                if (day.kind == ProgramDayKind.CARDIO && day.exercises.isNotEmpty()) {
                    assertTrue(session != RunSession.LONG || answers.days == 2, "$label long strength")
                }
            }
            if (day.kind != ProgramDayKind.REST && session != RunSession.LONG) {
                val excess = SessionBudget.seconds(day, goal) - answers.minutes * 60
                if (excess > 0) assertTrue(PlanReason.OverBudget(day.id, excess) in draft.reasons, "$label budget")
                else assertTrue(draft.reasons.none { it is PlanReason.OverBudget && it.dayId == day.id }, "$label excess reason")
            }
            for ((index, slot) in day.exercises.withIndex()) {
                val key = SlotKey(day.id, index)
                workingKeys += key
                assertTrue(draft.working.getValue(key).isNotEmpty(), "$label rows")
                val entries = listOf(slot.exerciseId to slot.rule) + listOfNotNull(slot.superset?.let { it.exerciseId to it.rule })
                for ((id, rule) in entries) {
                    assertTrue(ExerciseLibrary.get(id).availableWith(answers.equipment, answers.excluded), "$label $id available")
                    assertTrue(requireNotNull(rule).rir >= 2, "$label $id RIR")
                }
            }
        }
        assertEquals(workingKeys, draft.working.keys, "$label working keys")
        assertTrue(draft.program.cardioDays.count { it.exercises.isNotEmpty() } <= run.strengthBlocks, "$label strength blocks")
        // audit 2026-11-13, row 14: lift-first at two days keeps 2 strength days instead of 1 + 1.
        if (answers.branch == PlanBranch.BOTH) {
            if (!answers.both.cardioFirst && answers.minutes >= 45 &&
                answers.together() == BothAnswers.Together.SAME_VISIT) {
                // Run ladder design §8: a ladder phase rides on at most 3 spread lift days; other phases on every lift day.
                val withRun = draft.program.strengthDays.count { it.cardioBlock != null }
                val expected = if (draft.phase == RunPhase.LADDER) minOf(draft.program.strengthDays.size, 3)
                    else draft.program.strengthDays.size
                assertEquals(expected, withRun, "$label reserve")
            }
            if (answers.together() == BothAnswers.Together.SEPARATE_DAYS && draft.program.strengthDays.isNotEmpty()) {
                for (main in Planner.defaultMains(answers.equipment, answers.excluded)) {
                    assertTrue(draft.program.strengthDays.any { day ->
                        day.exercises.any { it.exerciseId == main && it.rule?.role == SlotRole.MAIN }
                    }, "$label $main main")
                }
            }
        }
    }

    @Test fun `run cap quality budget and strength invariants hold over all 5130 run plans`() {
        var plans = 0
        for (variant in variants()) for (days in 1..6) for (minutes in listOf(30, 45, 60, 75, 90)) {
            for (experience in RunExperience.entries) for (anchor in 0..2) {
                val runAnswers = variant.copy(experience = experience, longest = if (anchor == 1) Measure.Time(1200) else null)
                val answers = run(runAnswers.goal, days, minutes, experience, runAnswers)
                check(answers, if (anchor == 2) known(32_000, 12_000) else KnownLoads.NONE,
                    "${runAnswers.goal} ${runAnswers.resolvedEvent ?: runAnswers.test} $days days $minutes min $experience anchor=$anchor")
                plans++
            }
        }
        assertEquals(5130, plans)
    }

    @Test fun `run invariants hold over all 8208 Both plans and all four layouts`() {
        var plans = 0
        for (variant in variants()) for (days in 1..6) for (minutes in listOf(30, 60, 90)) {
            for (experience in RunExperience.entries) for (history in listOf(false, true)) {
                for (first in listOf(false, true)) for (together in BothAnswers.Together.entries) {
                    val runAnswers = variant.copy(experience = experience)
                    val answers = run(runAnswers.goal, days, minutes, experience, runAnswers).copy(branch = PlanBranch.BOTH,
                        goal = LiftGoal.GENERAL, both = BothAnswers(first, together))
                    check(answers, if (history) known(32_000, 12_000) else KnownLoads.NONE,
                        "${runAnswers.goal} ${runAnswers.resolvedEvent ?: runAnswers.test} $days days $minutes min $experience history=$history first=$first $together")
                    plans++
                }
            }
        }
        assertEquals(8208, plans)
    }

    @Test fun `re-plans keep slots and the run cap across the grid`() {
        val layouts = listOf<BothAnswers?>(null) + listOf(false, true).flatMap { first ->
            BothAnswers.Together.entries.map { BothAnswers(first, it) }
        }
        val gates = mapOf(RunPhase.LADDER to RecentMaxima(null, 1800), RunPhase.BASE to RecentMaxima(10_000, 3600),
            RunPhase.HALF_BUILD to RecentMaxima(21_000, 7200), RunPhase.MARATHON_BUILD to RecentMaxima(30_000, 10_800))
        fun planned(program: Program) = program.rotationDays.sumOf { it.cardioToRun?.let { b -> plannedSeconds(b) } ?: 0 }
        fun end(day: ProgramDay) = day.cardioToRun?.sets?.firstOrNull { it.role == CardioRole.WORK }?.end
        fun withEnd(day: ProgramDay, value: Measure) = day.copy(cardioBlock = requireNotNull(day.cardioBlock).let { b ->
            b.copy(sets = b.sets.map { if (it.role == CardioRole.WORK) it.copy(end = value) else it })
        })
        for (layout in layouts) for (goal in listOf(RunGoal.FIVE_TO_TEN_K, RunGoal.HALF_MARATHON, RunGoal.MARATHON, RunGoal.ULTRA)) {
            for (experience in RunExperience.entries) for (days in 1..6) for (minutes in listOf(30, 60, 90)) {
                val answers = run(goal, days, minutes, experience).let {
                    if (layout == null) it else it.copy(branch = PlanBranch.BOTH, both = layout)
                }
                for ((phase, nextPhase) in goal.phases.zipWithNext()) {
                    val seed = when (phase) {
                        RunPhase.LADDER -> known(null, 600)
                        RunPhase.BASE -> known(null, 1800)
                        RunPhase.HALF_BUILD -> known(10_000, 3600)
                        RunPhase.MARATHON_BUILD -> known(21_000, 7200)
                        RunPhase.ULTRA_BUILD -> error("No later phase")
                    }
                    val draft = Planner.plan(answers, seed, TODAY, phase)
                    // A Both layout without any running has no phase to advance.
                    if (draft.phase == null) { assertTrue(draft.sessions.isEmpty()); continue }
                    val stored = draft.program.let { p -> p.copy(days = p.days.map { day ->
                        if (day.cardioRule?.ladderLevel == null) day else day.copy(cardioRule = CardioRule(null),
                            cardioBlock = RunWalkLadder.block(12, answers.run.activity))
                    }) }
                    val anchor = gates.getValue(phase)
                    val history = KnownLoads.NONE.copy(longestRecent = mapOf(CardioFamily.RUN to anchor))
                    val total = planned(stored)
                    val longest = stored.days.maxOf { it.cardioToRun?.let { b -> plannedSeconds(b) } ?: 0 }
                    val week = WeekLoad(RecentMaxima(null, total), RecentMaxima(null, longest))
                    val label = "$goal $experience $days days $minutes min $layout $phase"
                    val result = RunReplan.next(stored, answers, phase, history, TODAY, week, null)
                    val allowed = total.toLong() * 120 / 100
                    if (result == null) {
                        val candidate = Planner.plan(answers, history, TODAY, nextPhase)
                        val floorDays = candidate.program.days.map { day ->
                            val old = stored.days.firstOrNull { it.id == day.id }
                            var floorDay = day.copy(exercises = old?.exercises.orEmpty())
                            val block = floorDay.cardioBlock
                            if (floorDay.cardioRule?.session == RunSession.EASY && end(floorDay) is Measure.Time) {
                                floorDay = withEnd(floorDay, Measure.Time(minOf((end(floorDay) as Measure.Time).seconds,
                                    RunReplan.EASY_FLOOR_SECONDS)))
                            } else if (block != null && block.sets.count { it.role == CardioRole.WORK } > 1) {
                                var one = block
                                while (one.sets.count { it.role == CardioRole.WORK } > 1) one = one.withoutLastRound()
                                floorDay = floorDay.copy(cardioBlock = one)
                            } else if (floorDay.cardioRule?.session == RunSession.LONG) {
                                val oldLong = stored.days.firstOrNull { it.cardioRule?.session == RunSession.LONG }?.cardioToRun
                                val seconds = maxOf(oldLong?.let { plannedSeconds(it) } ?: 0, longest)
                                floorDay = when (val value = end(floorDay)) {
                                    is Measure.Time -> withEnd(floorDay, Measure.Time(minOf(value.seconds, seconds)))
                                    is Measure.Distance -> withEnd(floorDay, Measure.Distance(minOf(value.metres,
                                        kotlin.math.ceil(seconds / 0.36).toInt())))
                                    null -> floorDay
                                }
                            }
                            floorDay
                        }
                        assertTrue(planned(Program(floorDays)) > allowed, "$label only floors can defer")
                        continue
                    }
                    assertEquals(nextPhase, result.phase, label)
                    val next = result.program
                    assertEquals(stored.days.map { it.id }, next.days.take(stored.days.size).map { it.id }, "$label prefix")
                    assertEquals(stored.days.count { it.exercises.isNotEmpty() }, next.days.count { it.exercises.isNotEmpty() }, label)
                    assertTrue(planned(next) <= allowed, "$label weekly guard")
                    for (day in next.days) {
                        val old = stored.days.firstOrNull { it.id == day.id }
                        assertEquals(old?.exercises.orEmpty(), day.exercises, "$label ${day.id} slots")
                        if (old?.exercises?.isNotEmpty() == true) assertTrue(day.kind != ProgramDayKind.REST, label)
                        if (old == null) assertTrue(day.kind != ProgramDayKind.STRENGTH, label)
                        day.cardioBlock?.let { block -> assertEquals(block, RunCap.capped(block, anchor, null), "$label cap") }
                        if (day.cardioRule?.session == RunSession.EASY) {
                            val carry = old?.takeIf { it.cardioRule?.session == RunSession.EASY }?.cardioToRun?.let { plannedSeconds(it) }
                                ?: stored.days.filter { it.cardioRule?.session == RunSession.EASY }
                                    .mapNotNull { it.cardioToRun?.let { b -> plannedSeconds(b) } }.maxOrNull()
                            if (carry != null) assertTrue(plannedSeconds(requireNotNull(day.cardioToRun)) <= carry, "$label carry")
                        }
                    }
                }
            }
        }
    }

    @Test fun `D15 reserve also holds at forty five and seventy five minutes`() {
        for (minutes in listOf(45, 75)) for (days in 1..6) for (goal in RunGoal.entries) {
            val answers = run(goal, days, minutes).copy(branch = PlanBranch.BOTH,
                both = BothAnswers(together = BothAnswers.Together.SAME_VISIT))
            check(answers, KnownLoads.NONE, "$goal $days days $minutes min reserve")
        }
    }
}
