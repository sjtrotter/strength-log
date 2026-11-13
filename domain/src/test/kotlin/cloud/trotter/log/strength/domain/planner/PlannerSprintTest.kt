package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.RunRange
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.SlotRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun sprint(
    event: SprintEvent = SprintEvent.M100,
    days: Int = 3,
    minutes: Int = 60,
    answers: SprintAnswers = SprintAnswers(event),
) = SetupAnswers(branch = PlanBranch.SPRINT, days = days, minutes = minutes, sprint = answers)

class PlannerSprintTest {
    private fun day(draft: PlanDraft, id: String) = draft.program.days.single { it.id == id }
    private fun ids(draft: PlanDraft, id: String) = day(draft, id).exercises.map { it.exerciseId }
    private fun roles(draft: PlanDraft, id: String) = day(draft, id).exercises.map { requireNotNull(it.rule).role }
    private fun seconds(draft: PlanDraft) = draft.program.days.map { SessionBudget.seconds(it, LiftGoal.STRENGTH) }
    private fun intervals(rounds: Int, metres: Int, recovery: Int, warmup: Boolean = true, hard: Boolean = true) =
        CardioBlock(CardioActivity.TRACK_RUN, buildList<CardioSetSpec> {
            if (warmup) add(CardioSetSpec(CardioRole.WARMUP, Measure.Time(600)))
            repeat(rounds) { index ->
                if (index > 0) add(CardioSetSpec(CardioRole.RECOVERY, Measure.Time(recovery)))
                add(CardioSetSpec(CardioRole.WORK, Measure.Distance(metres), hard = hard))
            }
        })
    private val acc0 = intervals(4, 10, 60)
    private val acc5 = intervals(8, 20, 120)
    private val maxv0 = intervals(3, 10, 240)
    private val se0 = intervals(3, 100, 480)
    private val tempo0 = intervals(6, 100, 60, warmup = false, hard = false)

    @Test fun `a 100 m plan at two days matches the table`() {
        val draft = Planner.plan(sprint(days = 2))
        assertEquals(listOf("A", "B", "C"), draft.program.days.map { it.id })
        assertEquals(listOf(ProgramDayKind.CARDIO, ProgramDayKind.REST, ProgramDayKind.CARDIO), draft.program.days.map { it.kind })
        assertEquals(mapOf("A" to RunSession.ACCELERATION, "C" to RunSession.MAX_VELOCITY), draft.sessions)
        assertEquals(acc0, day(draft, "A").cardioBlock)
        assertEquals(maxv0, day(draft, "C").cardioBlock)
        assertEquals(CardioRule(null, RunSession.ACCELERATION), day(draft, "A").cardioRule)
        assertEquals(CardioRule(null, RunSession.MAX_VELOCITY), day(draft, "C").cardioRule)
        assertEquals(listOf("pogo_hop", "a_skip", "bb_back_squat", "rdl", "bss"), ids(draft, "A"))
        assertEquals(listOf(SlotRole.PLYO, SlotRole.PLYO, SlotRole.MAIN, SlotRole.ACCESSORY, SlotRole.ACCESSORY), roles(draft, "A"))
        assertEquals(listOf("bb_bench", "ohp", "bb_row", "nordic"), ids(draft, "C"))
        assertEquals(List(3) { SlotRole.MAIN } + SlotRole.ISOLATION, roles(draft, "C"))
        assertTrue(draft.reasons.isEmpty())
        assertEquals(PlanGoal.Sprint(100), draft.goal)
        assertNull(draft.phase)
        assertEquals(listOf(2699, 0, 3326), seconds(draft))
    }

    @Test fun `a 100 m plan at three days matches the table`() {
        val draft = Planner.plan(sprint())
        assertEquals(listOf("A", "B", "C", "D", "E"), draft.program.days.map { it.id })
        assertEquals(listOf(ProgramDayKind.CARDIO, ProgramDayKind.REST, ProgramDayKind.STRENGTH,
            ProgramDayKind.CARDIO, ProgramDayKind.REST), draft.program.days.map { it.kind })
        assertEquals("Lower", day(draft, "C").title)
        assertEquals(listOf("bb_back_squat", "rdl", "bss", "nordic"), ids(draft, "C"))
        assertEquals(listOf("pogo_hop", "a_skip"), ids(draft, "A"))
        assertTrue(day(draft, "D").exercises.isEmpty())
        assertEquals(mapOf("A" to RunSession.ACCELERATION, "D" to RunSession.MAX_VELOCITY), draft.sessions)
        assertEquals(acc0, day(draft, "A").cardioBlock)
        assertEquals(maxv0, day(draft, "D").cardioBlock)
        assertEquals(listOf(1214, 0, 1995, 1091, 0), seconds(draft))
        assertTrue(draft.reasons.isEmpty())
    }

    @Test fun `a 100 m plan at four days matches the table`() {
        val draft = Planner.plan(sprint(days = 4))
        assertEquals(listOf("A", "B", "C", "D", "E"), draft.program.days.map { it.id })
        assertEquals(listOf(ProgramDayKind.CARDIO, ProgramDayKind.STRENGTH, ProgramDayKind.REST,
            ProgramDayKind.CARDIO, ProgramDayKind.STRENGTH), draft.program.days.map { it.kind })
        assertEquals(listOf("bb_back_squat", "rdl", "bss"), ids(draft, "B"))
        assertEquals(listOf("trap_dl", "rdl", "bss", "nordic"), ids(draft, "E"))
        assertEquals(listOf(1214, 1785, 0, 1091, 1995), seconds(draft))
        assertTrue(draft.reasons.isEmpty())
    }

    @Test fun `the other events keep the table`() {
        for (event in SprintEvent.entries) for (days in 2..4) {
            val reference = Planner.plan(sprint(days = days, minutes = 75))
            val draft = Planner.plan(sprint(event, days, minutes = 75))
            assertTrue(draft.reasons.isEmpty(), "$event $days reasons")
            assertTrue(seconds(draft).all { it <= 4500 }, "$event $days budget")
            assertEquals(reference.program.days.map { it.kind }, draft.program.days.map { it.kind }, "$event $days kinds")
            assertEquals(reference.program.days.map { it.exercises.map { slot -> slot.exerciseId } },
                draft.program.days.map { it.exercises.map { slot -> slot.exerciseId } }, "$event $days lifts")
            assertEquals(PlanGoal.Sprint(event.metres), draft.goal)
            if (event in listOf(SprintEvent.M200, SprintEvent.M400)) {
                val cardio = draft.program.cardioDays
                assertEquals(maxv0, cardio[0].cardioBlock)
                assertEquals(CardioRule(null, RunSession.MAX_VELOCITY), cardio[0].cardioRule)
                assertEquals(se0, cardio[1].cardioBlock)
                assertEquals(CardioRule(null, RunSession.SPRINT_ENDURANCE), cardio[1].cardioRule)
                assertEquals(listOf(RunSession.MAX_VELOCITY, RunSession.SPRINT_ENDURANCE), draft.sessions.values.toList())
            } else {
                assertEquals(reference.program.days.map { it.cardioBlock }, draft.program.days.map { it.cardioBlock })
                assertEquals(reference.sessions, draft.sessions)
            }
        }
        assertEquals(37, SprintEvent.YD40.metres)
    }

    @Test fun `200 and 400 m at two days fit sixty minutes at ladder step three`() {
        for (event in listOf(SprintEvent.M200, SprintEvent.M400)) {
            val reference = Planner.plan(sprint(event, days = 2, minutes = 75))
            val draft = Planner.plan(sprint(event, days = 2, minutes = 60))
            assertTrue(reference.reasons.isEmpty(), "$event reference reasons")
            assertEquals(listOf(2996, 0, 3903), seconds(reference), "$event reference seconds")
            assertEquals(listOf(PlanReason.ShortSession("C", 3)), draft.reasons, "$event reasons")
            assertEquals(listOf(2996, 0, 3018), seconds(draft), "$event seconds")
            for (id in listOf("A", "B")) {
                assertEquals(day(reference, id), day(draft, id), "$event $id unchanged")
            }
            assertEquals(listOf("bb_bench", "ohp", "bb_row"), ids(draft, "C"), "$event C lifts")
            assertEquals(List(3) { SlotRole.MAIN }, roles(draft, "C"), "$event C roles")
            assertEquals(listOf(2, 2, 2), day(draft, "C").exercises.map { it.targetSets }, "$event C sets")
            assertEquals(day(reference, "C").cardioBlock, day(draft, "C").cardioBlock, "$event C block")
            assertEquals(day(reference, "C").cardioRule, day(draft, "C").cardioRule, "$event C rule")
            assertEquals(reference.sessions, draft.sessions, "$event sessions")
        }
    }

    @Test fun `five and six days append tempo then upper lifts`() {
        val four = Planner.plan(sprint(days = 4))
        val five = Planner.plan(sprint(days = 5))
        val six = Planner.plan(sprint(days = 6))
        assertEquals(four.program.days, five.program.days.take(5))
        assertEquals(five.program.days, six.program.days.take(6))
        val tempo = day(five, "F")
        assertEquals(ProgramDayKind.CARDIO, tempo.kind)
        assertEquals(tempo0, tempo.cardioBlock)
        assertEquals(CardioRule(null, RunSession.SPRINT_TEMPO), tempo.cardioRule)
        assertTrue(tempo.exercises.isEmpty())
        assertEquals(816, SessionBudget.seconds(tempo, LiftGoal.STRENGTH))
        val upper = day(six, "G")
        assertEquals(ProgramDayKind.STRENGTH, upper.kind)
        assertEquals("Upper", upper.title)
        assertEquals(listOf("bb_bench", "ohp", "bb_row"), ids(six, "G"))
        assertEquals(List(3) { SlotRole.MAIN }, roles(six, "G"))
        assertEquals(2325, SessionBudget.seconds(upper, LiftGoal.STRENGTH))
        assertTrue(five.reasons.isEmpty())
        assertTrue(six.reasons.isEmpty())
    }

    @Test fun `the intro opens every range at its low end`() {
        val recent = SprintAnswers(SprintEvent.M100, sprintedRecently = true)
        val draft = Planner.plan(sprint(answers = recent))
        val a = day(draft, "A")
        assertEquals(acc5, a.cardioBlock)
        assertEquals(CardioRule(null, RunSession.ACCELERATION, steps = 5), a.cardioRule)
        assertEquals(160, requireNotNull(a.cardioBlock).sets.filter { it.role == CardioRole.WORK }
            .sumOf { (it.end as Measure.Distance).metres })
        assertEquals(1918, SessionBudget.seconds(a, LiftGoal.STRENGTH))
        assertEquals(maxv0, day(draft, "D").cardioBlock)
        assertEquals(CardioRule(null, RunSession.MAX_VELOCITY), day(draft, "D").cardioRule)
        val strain = Planner.plan(sprint(answers = recent.copy(hamstringStrain = true)))
        assertEquals(acc0, day(strain, "A").cardioBlock)
        assertEquals(CardioRule(null, RunSession.ACCELERATION), day(strain, "A").cardioRule)
        val longEvent = Planner.plan(sprint(answers = recent.copy(event = SprintEvent.M200)))
        assertEquals(maxv0, day(longEvent, "A").cardioBlock)
        assertEquals(CardioRule(null, RunSession.MAX_VELOCITY), day(longEvent, "A").cardioRule)
        assertTrue(SprintAnswers().intro)
        assertFalse(recent.intro)
    }

    @Test fun `plyometrics and Nordic open at the owner doses`() {
        val draft = Planner.plan(sprint())
        val plyos = day(draft, "A").exercises
        assertEquals(listOf(3, 1), plyos.map { it.targetSets })
        assertEquals(listOf(RepTarget.Fixed(15), RepTarget.Fixed(20)), plyos.map { it.rule!!.target })
        for (slot in plyos) {
            assertEquals(SlotRole.PLYO, slot.rule!!.role)
            assertEquals(4, slot.rule!!.rir)
            assertEquals(60, slot.rule!!.restSeconds)
        }
        assertEquals(65, plyos.sumOf { it.targetSets * it.rule!!.target.reps })
        val nordic = day(draft, "C").exercises.last()
        assertEquals("nordic", nordic.exerciseId)
        assertEquals(2, nordic.targetSets)
        assertEquals(RepTarget.Range(4, 6, 4), nordic.rule!!.target)
        for (day in draft.program.days) for ((index, slot) in day.exercises.withIndex()) {
            assertEquals(slot.targetSets, draft.working.getValue(SlotKey(day.id, index)).size)
        }
        val excluded = Planner.plan(sprint().copy(excluded = setOf("nordic")))
        val fallback = day(excluded, "C").exercises.last()
        assertEquals("seated_curl", fallback.exerciseId)
        assertEquals(2, fallback.targetSets)
        assertEquals(RepTarget.Range(8, 12, 8), fallback.rule!!.target)
    }

    @Test fun `thirty minutes fits through the ladder or reports the excess`() {
        val one = Planner.plan(sprint(days = 1, minutes = 30))
        assertEquals(listOf(PlanReason.ShortSession("A", 1)), one.reasons)
        assertEquals(listOf("bb_back_squat"), ids(one, "A"))
        assertEquals(1469, SessionBudget.seconds(day(one, "A"), LiftGoal.STRENGTH))
        val recent = Planner.plan(sprint(minutes = 30, answers = SprintAnswers(sprintedRecently = true)))
        assertEquals(listOf(PlanReason.ShortSession("A", 6), PlanReason.OverBudget("A", 118),
            PlanReason.ShortSession("C", 1)), recent.reasons)
        assertEquals(listOf("pogo_hop", "a_skip"), ids(recent, "A"))
    }

    @Test fun `sprint sessions skip the run cap and ignore the lift goal answer`() {
        val answers = sprint(days = 4)
        val draft = Planner.plan(answers)
        assertEquals(draft, Planner.plan(answers, known(100, 30), TODAY))
        assertFalse(draft.reasons.any { it is PlanReason.RunCapped })
        assertEquals(draft, Planner.plan(answers.copy(goal = LiftGoal.MUSCLE)))
    }

    @Test fun `Both with a sprint sub-goal builds the sprint layout with the lift rules`() {
        val answers = sprint(days = 4).copy(branch = PlanBranch.BOTH, goal = LiftGoal.STRENGTH,
            both = BothAnswers(sprint = true))
        val strength = Planner.plan(sprint(days = 4))
        assertEquals(strength, Planner.plan(answers))
        val muscleAnswers = answers.copy(goal = LiftGoal.MUSCLE, mains = listOf("bb_back_squat", "conv_dl", "bb_bench"))
        val muscle = Planner.plan(muscleAnswers)
        assertEquals(strength.program.days.map { it.kind }, muscle.program.days.map { it.kind })
        assertEquals(strength.sessions, muscle.sessions)
        val squat = day(muscle, "B").exercises.first()
        assertEquals("bb_back_squat", squat.exerciseId)
        assertEquals(3, squat.targetSets)
        assertEquals(RepTarget.Range(8, 12, 8), squat.rule!!.target)
        assertEquals(3, squat.rule!!.rir)
        assertEquals("conv_dl", day(muscle, "E").exercises.first().exerciseId)
        assertTrue(muscle.program.days.flatMap { it.exercises }.none { it.exerciseId == "bb_bench" })
        val six = Planner.plan(muscleAnswers.copy(days = 6))
        assertEquals(listOf("bb_bench"), ids(six, "G"))
        assertEquals(listOf(SlotRole.MAIN), roles(six, "G"))
        assertFailsWith<IllegalArgumentException> { BothAnswers(cardioFirst = true, sprint = true) }
        assertFailsWith<IllegalArgumentException> { BothAnswers(together = BothAnswers.Together.SAME_VISIT, sprint = true) }
    }

    @Test fun `Reset day reproduces each sprint day`() {
        for (answers in listOf(sprint(days = 4), sprint(days = 2, minutes = 30))) {
            val full = Planner.plan(answers)
            for (day in full.program.days) {
                val draft = requireNotNull(Planner.day(answers, day.id))
                assertEquals(day, draft.day)
                assertEquals(full.working.filterKeys { it.dayId == day.id }.mapKeys { it.key.index }, draft.working)
                assertEquals(full.reasons.filter { when (it) {
                    is PlanReason.ShortSession -> it.dayId == day.id
                    is PlanReason.OverBudget -> it.dayId == day.id
                    is PlanReason.RunCapped -> it.dayId == day.id
                    PlanReason.DateTooClose, is PlanReason.GoalCheck -> false
                } }, draft.reasons)
            }
            assertNull(Planner.day(answers, "Z"))
        }
    }

    @Test fun `sprint ranges and blocks`() {
        val sessions = listOf(RunSession.ACCELERATION, RunSession.MAX_VELOCITY, RunSession.SPRINT_ENDURANCE, RunSession.SPRINT_TEMPO)
        assertEquals(listOf(6, 3, 6, 2), sessions.map { requireNotNull(RunRange.of(it)).top })
        assertEquals(listOf(false, false, false, true), sessions.map { requireNotNull(RunRange.of(it)).goodBand })
        assertEquals(intervals(8, 30, 180), sprintBlock(RunSession.ACCELERATION, 6))
        assertEquals(intervals(6, 300, 60, warmup = false, hard = false), sprintBlock(RunSession.SPRINT_TEMPO, 2))
        assertEquals(listOf(acc0, acc5, maxv0, se0, tempo0), listOf(
            sprintBlock(RunSession.ACCELERATION, 0), sprintBlock(RunSession.ACCELERATION, 5),
            sprintBlock(RunSession.MAX_VELOCITY, 0), sprintBlock(RunSession.SPRINT_ENDURANCE, 0),
            sprintBlock(RunSession.SPRINT_TEMPO, 0)))
        assertEquals(listOf(794, 1498, 1091, 1668, 516), listOf(acc0, acc5, maxv0, se0, tempo0).map(::plannedSeconds))
        assertEquals(6, CardioRule(null, RunSession.ACCELERATION, steps = 6).steps)
        assertFailsWith<IllegalArgumentException> { CardioRule(null, RunSession.ACCELERATION, steps = 7) }
    }
}
