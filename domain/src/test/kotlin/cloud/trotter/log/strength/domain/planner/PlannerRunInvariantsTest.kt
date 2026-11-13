package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.availableWith
import cloud.trotter.log.strength.domain.model.CardioFamily
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.RunCap
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
        if (answers.branch == PlanBranch.BOTH) {
            if (!answers.both.cardioFirst && answers.minutes >= 45 &&
                answers.both.together(answers.days) == BothAnswers.Together.SAME_VISIT) {
                assertTrue(draft.program.strengthDays.all { it.cardioBlock != null }, "$label reserve")
            }
            if (answers.both.together(answers.days) == BothAnswers.Together.SEPARATE_DAYS && draft.program.strengthDays.isNotEmpty()) {
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

    @Test fun `D15 reserve also holds at forty five and seventy five minutes`() {
        for (minutes in listOf(45, 75)) for (days in 1..6) for (goal in RunGoal.entries) {
            val answers = run(goal, days, minutes).copy(branch = PlanBranch.BOTH,
                both = BothAnswers(together = BothAnswers.Together.SAME_VISIT))
            check(answers, KnownLoads.NONE, "$goal $days days $minutes min reserve")
        }
    }
}
