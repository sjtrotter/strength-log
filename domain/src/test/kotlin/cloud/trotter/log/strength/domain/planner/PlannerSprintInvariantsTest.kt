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
    private val lowerPatterns = setOf(MovementPattern.SQUAT_BILATERAL, MovementPattern.HINGE, MovementPattern.SINGLE_LEG,
        MovementPattern.KNEE_FLEXION, MovementPattern.KNEE_EXTENSION, MovementPattern.CALF_GASTROC, MovementPattern.CALF_SOLEUS)
    private val liftRoles = setOf(SlotRole.MAIN, SlotRole.SECONDARY, SlotRole.ACCESSORY, SlotRole.ISOLATION)

    private fun check(answers: SetupAnswers, label: String) {
        val goal = if (answers.branch == PlanBranch.SPRINT) LiftGoal.STRENGTH else answers.goal
        val draft = Planner.plan(answers)
        val cardio = ProgramDayKind.CARDIO
        val strength = ProgramDayKind.STRENGTH
        val rest = ProgramDayKind.REST
        val short = answers.sprint.event in listOf(SprintEvent.YD40, SprintEvent.M60, SprintEvent.M100)
        val a = if (short) RunSession.ACCELERATION else RunSession.MAX_VELOCITY
        val b = if (short) RunSession.MAX_VELOCITY else RunSession.SPRINT_ENDURANCE
        val sessions = when (answers.days) {
            // sprint week design §2.2: one high + one rest = two entries.
            1 -> listOf(a, null)
            // sprint week design §2.2: two highs + two rests = four entries.
            2 -> listOf(a, null, b, null)
            3 -> listOf(a, null, null, b, null)
            // sprint week design §2.2: two highs + two U + two rests = six entries.
            4 -> listOf(a, null, null, b, null, null)
            5 -> listOf(a, null, null, b, null, RunSession.SPRINT_TEMPO)
            else -> listOf(a, null, null, b, null, RunSession.SPRINT_TEMPO, null)
        }
        val mains = resolveMains(answers.copy(goal = goal))
        val upper = if (mains.any { mainRow(it.pattern) in 2..4 }) strength else rest
        // sprint week design §2.2: U sits at B from 3 days, at E from 4, and at G at 6 (HA, U, R, HB, R at 3).
        val upperEntries = when (answers.days) { 1, 2 -> emptyList(); 3 -> listOf(1); 4, 5 -> listOf(1, 4); else -> listOf(1, 4, 6) }
        // sprint week design §2.2 and §2.4: U has upper mains or becomes Rest; high and rest counts follow the rotation.
        val kinds = sessions.mapIndexed { index, session ->
            when {
                session != null -> cardio
                index in upperEntries -> upper
                else -> rest
            }
        }
        // sprint week design §2.2: entry counts = training days + rests, [2,4,5,6,6,7].
        assertEquals(listOf(2, 4, 5, 6, 6, 7)[answers.days - 1], draft.program.days.size, "$label size")
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
        // sprint week design §3.1: G1 checks both neighbors cyclically, including the one-day wrap through Rest.
        for (index in maximal) {
            assertTrue((index + 1) % sessions.size !in maximal, "$label next maximal")
            assertTrue((index + sessions.size - 1) % sessions.size !in maximal, "$label previous maximal")
        }
        val workingKeys = mutableSetOf<SlotKey>()
        for ((dayIndex, day) in draft.program.days.withIndex()) {
            val session = sessions[dayIndex]
            val block = day.cardioBlock
            // sprint week design §3.1: G2 includes the wrap; zero lower lifts or maximal blocks before each high.
            if (dayIndex in maximal) {
                val previousIndex = (dayIndex + sessions.size - 1) % sessions.size
                val previous = draft.program.days[previousIndex]
                assertTrue(previousIndex !in maximal, "$label no maximal block before maximal")
                assertTrue(previous.exercises.none {
                    ExerciseLibrary.get(it.exerciseId).pattern in lowerPatterns && it.rule?.role in liftRoles
                }, "$label no lower lift before maximal")
            }
            val ranks = day.exercises.map { slot ->
                val pattern = ExerciseLibrary.get(slot.exerciseId).pattern
                when {
                    slot.rule?.role == SlotRole.BALANCE -> 0
                    slot.rule?.role == SlotRole.PLYO -> 1
                    slot.rule?.role == SlotRole.MAIN && mainRow(pattern) in 0..1 -> 2
                    pattern == MovementPattern.KNEE_FLEXION -> 5
                    slot.rule?.role == SlotRole.MAIN && mainRow(pattern) in 2..4 -> 4
                    else -> 3
                }
            }
            assertTrue(ranks.zipWithNext().all { (before, after) -> before <= after }, "$label slot order")
            assertTrue(day.exercises.count { it.rule?.role == SlotRole.MAIN &&
                mainRow(ExerciseLibrary.get(it.exerciseId).pattern) in 0..1 } <= 1, "$label one lower main")
            val knee = day.exercises.filter { ExerciseLibrary.get(it.exerciseId).pattern == MovementPattern.KNEE_FLEXION }
            if (knee.isNotEmpty()) {
                assertTrue(answers.days >= 2, "$label no one-day knee flexion")
                assertEquals(RunSession.MAX_VELOCITY, session, "$label knee flexion on max velocity")
                assertEquals(listOf(day.exercises.last()), knee, "$label knee flexion last")
            }
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
                if (session == RunSession.SPRINT_ENDURANCE) {
                    assertTrue(planned.sets.filter { it.role == CardioRole.RECOVERY }.all {
                        it.end == Measure.Time(600)
                    }, "$label sprint-endurance recovery")
                    // sprint week design §5: careful opens at step 0, 2*100=200 m; otherwise recent opens at 3*100=300 m.
                    assertEquals(List(if (answers.sprintIntro) 2 else 3) { Measure.Distance(100) },
                        work.map { it.end }, "$label sprint-endurance opening")
                }
                // sprint week design §5: sprintIntro includes 65+; careful opens at step 0 instead of acceleration 12 or endurance 5.
                val steps = when {
                    answers.sprintIntro -> 0
                    session == RunSession.ACCELERATION -> 12
                    session == RunSession.SPRINT_ENDURANCE -> 5
                    else -> 0
                }
                // sprint week design §5: careful intro=6, other intro=3, recent non-careful=0.
                val intro = if (answers.sprintIntro) (if (answers.careful) 6 else 3) else 0
                assertEquals(CardioRule(null, session, steps = steps, intro = intro), day.cardioRule, "$label rule")
            }
            if (day.kind != rest) {
                val excess = SessionBudget.seconds(day, goal) - answers.minutes * 60
                if (excess > 0) assertTrue(PlanReason.OverBudget(day.id, excess) in draft.reasons, "$label budget")
                else assertTrue(draft.reasons.none { it is PlanReason.OverBudget && it.dayId == day.id }, "$label excess reason")
            }
            val plyos = day.exercises.filter { it.rule?.role == SlotRole.PLYO }
            assertTrue(plyos.sumOf { it.targetSets * requireNotNull(it.rule).target.reps } <= 80, "$label contacts")
            // sprint week design §2.1: plyos on either maximal day, up to 65 and 50 opening contacts.
            assertTrue(plyos.isEmpty() || dayIndex in maximal, "$label plyo placement")
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
                    // sprint week design §2.2: upper rows split 1+2 on two-day highs; otherwise only U entries.
                    val row = mainRow(exercise.pattern)
                    if (row in 2..4) {
                        assertTrue(if (answers.days == 2) {
                            (dayIndex == 0 && row == 2) || (dayIndex == 2 && row in 3..4)
                        } else answers.days >= 3 && dayIndex in listOf(1, 4, 6), "$label upper main placement")
                    }
                }
            }
        }
        if (answers.branch == PlanBranch.BOTH && answers.days >= 2) {
            assertTrue(draft.program.days.count { day -> day.exercises.any { it.rule?.role == SlotRole.MAIN } } >= 2,
                "$label Both floor")
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
