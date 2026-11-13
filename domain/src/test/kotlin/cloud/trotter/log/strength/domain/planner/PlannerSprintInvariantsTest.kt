package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.library.availableWith
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.SlotRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannerSprintInvariantsTest {
    private fun check(answers: SetupAnswers, label: String) {
        val goal = if (answers.branch == PlanBranch.SPRINT) LiftGoal.STRENGTH else answers.goal
        val draft = Planner.plan(answers)
        val cardio = ProgramDayKind.CARDIO
        val strength = ProgramDayKind.STRENGTH
        val rest = ProgramDayKind.REST
        val kinds = when (answers.days) {
            1 -> listOf(cardio)
            2 -> listOf(cardio, rest, cardio)
            3 -> listOf(cardio, rest, strength, cardio, rest)
            4 -> listOf(cardio, strength, rest, cardio, strength)
            5 -> listOf(cardio, strength, rest, cardio, strength, cardio)
            else -> listOf(cardio, strength, rest, cardio, strength, cardio, strength)
        }
        val short = answers.sprint.event in listOf(SprintEvent.YD40, SprintEvent.M60, SprintEvent.M100)
        val a = if (short) RunSession.ACCELERATION else RunSession.MAX_VELOCITY
        val b = if (short) RunSession.MAX_VELOCITY else RunSession.SPRINT_ENDURANCE
        val sessions = when (answers.days) {
            1 -> listOf(a)
            2 -> listOf(a, null, b)
            3 -> listOf(a, null, null, b, null)
            4 -> listOf(a, null, null, b, null)
            5 -> listOf(a, null, null, b, null, RunSession.SPRINT_TEMPO)
            else -> listOf(a, null, null, b, null, RunSession.SPRINT_TEMPO, null)
        }
        assertEquals(listOf(1, 3, 5, 5, 6, 7)[answers.days - 1], draft.program.days.size, "$label size")
        assertEquals(kinds, draft.program.days.map { it.kind }, "$label kinds")
        assertEquals(kinds.indices.map { ('A' + it).toString() }, draft.program.days.map { it.id }, "$label ids")
        assertEquals(sessions, draft.program.days.map { draft.sessions[it.id] }, "$label sessions")
        assertEquals(PlanGoal.Sprint(answers.sprint.event.metres), draft.goal, "$label goal")
        assertNull(draft.phase, "$label phase")
        assertEquals(if (answers.experience == LiftExperience.A_YEAR_OR_MORE) 6 else null,
            draft.deloadEveryCycles, "$label deload")
        assertEquals(draft.program.days.filter { it.cardioBlock != null }.map { it.id }.toSet(), draft.sessions.keys,
            "$label session keys")
        val maximal = draft.program.days.indices.filter { sessions[it] != null && sessions[it] != RunSession.SPRINT_TEMPO }
        if (maximal.size == 2) {
            assertTrue((maximal[0] + 1 until maximal[1]).any { kinds[it] == rest }, "$label sprint rest")
        }
        val mains = resolveMains(answers.copy(goal = goal))
        val workingKeys = mutableSetOf<SlotKey>()
        for ((dayIndex, day) in draft.program.days.withIndex()) {
            val session = sessions[dayIndex]
            val block = day.cardioBlock
            if (session == null) {
                assertNull(block, "$label ${day.id} no block")
                assertNull(day.cardioRule, "$label ${day.id} no cardio rule")
            } else {
                val planned = requireNotNull(block)
                assertEquals(CardioActivity.TRACK_RUN, planned.activity, "$label activity")
                val work = planned.sets.filter { it.role == CardioRole.WORK }
                assertTrue(work.isNotEmpty(), "$label work")
                assertTrue(work.all { it.end is Measure.Distance && it.hard == (session != RunSession.SPRINT_TEMPO) && it.aim == null },
                    "$label work ends")
                if (session != RunSession.SPRINT_TEMPO) {
                    assertEquals(CardioSetSpec(CardioRole.WARMUP, Measure.Time(600)), planned.sets.first(), "$label build-up")
                } else assertTrue(planned.sets.none { it.role == CardioRole.WARMUP }, "$label tempo warm-up")
                val steps = if (session == RunSession.ACCELERATION && !answers.sprint.intro) 5 else 0
                assertEquals(CardioRule(null, session, steps = steps), day.cardioRule, "$label rule")
            }
            if (day.kind != rest) {
                val excess = SessionBudget.seconds(day, goal) - answers.minutes * 60
                if (excess > 0) assertTrue(PlanReason.OverBudget(day.id, excess) in draft.reasons, "$label budget")
                else assertTrue(draft.reasons.none { it is PlanReason.OverBudget && it.dayId == day.id }, "$label excess reason")
            }
            val plyos = day.exercises.filter { it.rule?.role == SlotRole.PLYO }
            assertTrue(plyos.sumOf { it.targetSets * requireNotNull(it.rule).target.reps } <= 80, "$label contacts")
            assertTrue(plyos.isEmpty() || dayIndex == 0, "$label plyo placement")
            assertTrue(plyos.all { requireNotNull(it.rule).rir >= 4 }, "$label plyo effort")
            for ((index, slot) in day.exercises.withIndex()) {
                val key = SlotKey(day.id, index)
                workingKeys += key
                assertEquals(slot.targetSets, draft.working.getValue(key).size, "$label rows")
                val exercise = ExerciseLibrary.get(slot.exerciseId)
                assertTrue(exercise.availableWith(answers.equipment, answers.excluded), "$label ${exercise.id} available")
                val rule = requireNotNull(slot.rule)
                if (answers.experience == LiftExperience.NEW) assertTrue(rule.rir >= 2, "$label New effort")
                if (answers.olderAdult) assertTrue(rule.rir >= 3, "$label older effort")
                if (rule.role == SlotRole.MAIN) {
                    assertTrue(exercise in mains, "$label chosen main")
                    if (answers.experience == LiftExperience.NEW) assertTrue(rule.rir >= 3, "$label New main effort")
                    if (day.title == "Lower" || (answers.days <= 2 && dayIndex == 0)) {
                        assertTrue(mainRow(exercise.pattern) in 0..1, "$label lower main")
                    }
                }
            }
            if ((answers.days == 2 && day.id == "C") || (answers.days == 6 && day.id == "G")) {
                assertEquals(mains.filter { mainRow(it.pattern) in 2..4 }.map { it.id },
                    day.exercises.filter { it.rule?.role == SlotRole.MAIN }.map { it.exerciseId }, "$label upper mains")
            }
        }
        assertEquals(workingKeys, draft.working.keys, "$label working keys")
        assertTrue(draft.program.days.count { day -> day.exercises.any {
            ExerciseLibrary.get(it.exerciseId).pattern == MovementPattern.KNEE_FLEXION
        } } <= 1, "$label Nordic placement")
        assertTrue(SetCount.weekly(draft.program).values.all { it <= 20 }, "$label weekly cap")
    }

    @Test fun `sprint invariants hold over 1800 sprint plans`() {
        var plans = 0
        for (event in SprintEvent.entries) for (days in 1..6) for (minutes in listOf(30, 45, 60, 75, 90)) {
            for (experience in LiftExperience.entries) for (recent in listOf(false, true)) for (older in listOf(false, true)) {
                val answers = sprint(event, days, minutes, SprintAnswers(event, sprintedRecently = recent))
                    .copy(experience = experience, olderAdult = older)
                check(answers, "$event $days days $minutes min $experience recent=$recent older=$older")
                plans++
            }
        }
        assertEquals(1800, plans)
    }

    @Test fun `sprint invariants hold over every equipment preset`() {
        var plans = 0
        for (preset in EquipmentPreset.entries) for (days in 1..6) for (minutes in listOf(30, 45, 60, 75, 90)) {
            for (excluded in listOf(emptySet<String>(), setOf("pogo_hop", "a_skip", "nordic"))) {
                check(sprint(days = days, minutes = minutes).copy(equipment = preset.equipment, excluded = excluded),
                    "$preset $days days $minutes min excluded=$excluded")
                plans++
            }
        }
        assertEquals(360, plans)
    }

    @Test fun `sprint invariants hold over 648 Both plans`() {
        var plans = 0
        for (goal in LiftGoal.entries) for (experience in LiftExperience.entries) for (days in 1..6) {
            for (minutes in listOf(30, 60, 90)) for (older in listOf(false, true)) {
                for (event in listOf(SprintEvent.M100, SprintEvent.M400)) {
                    val answers = sprint(event, days, minutes).copy(branch = PlanBranch.BOTH, goal = goal,
                        experience = experience, olderAdult = older, both = BothAnswers(sprint = true))
                    check(answers, "Both $goal $experience $days days $minutes min older=$older $event")
                    plans++
                }
            }
        }
        assertEquals(648, plans)
    }
}
