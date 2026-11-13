package cloud.trotter.log.strength.domain.planner

import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.KnownLoads
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.RunWalkLadder
import cloud.trotter.log.strength.domain.progression.SlotRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannerBothTest {
    private fun answers(days: Int = 3, minutes: Int = 60, cardioFirst: Boolean = false,
                        together: BothAnswers.Together = BothAnswers.Together.SAME_VISIT) =
        SetupAnswers(branch = PlanBranch.BOTH, days = days, minutes = minutes, goal = LiftGoal.GENERAL,
            run = RunAnswers(RunGoal.FIVE_TO_TEN_K, RunExperience.SOME), both = BothAnswers(cardioFirst, together))
    private fun plan(answers: SetupAnswers, history: KnownLoads = known(8050, 2900)) = Planner.plan(answers, history, TODAY)
    private fun block(seconds: Int, hard: Boolean = false) = CardioBlock(CardioActivity.ROAD_RUN,
        listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(seconds), hard = hard)))

    @Test fun `lift first separate alternates full body and run days with remapped rows`() {
        val answers = answers(days = 4, together = BothAnswers.Together.SEPARATE_DAYS)
        val draft = plan(answers)
        assertEquals(listOf("A", "B", "C", "D"), draft.program.days.map { it.id })
        assertEquals(listOf(ProgramDayKind.STRENGTH, ProgramDayKind.CARDIO, ProgramDayKind.STRENGTH, ProgramDayKind.CARDIO),
            draft.program.days.map { it.kind })
        assertEquals(mapOf("B" to RunSession.EASY, "D" to RunSession.LONG), draft.sessions)
        assertTrue(draft.program.cardioDays.all { it.exercises.isEmpty() })
        val lift = plan(answers.copy(branch = PlanBranch.LIFT, days = 2))
        for ((index, day) in draft.program.strengthDays.withIndex()) {
            assertEquals(lift.program.days[index].copy(id = day.id), day)
            for (slot in day.exercises.indices) assertEquals(lift.working[SlotKey(lift.program.days[index].id, slot)], draft.working[SlotKey(day.id, slot)])
        }
        assertEquals(listOf(PlanReason.RunCapped("D")), draft.reasons.filterIsInstance<PlanReason.RunCapped>())
    }

    @Test fun `cardio first separate keeps rest after its long run before the remaining lift`() {
        val draft = plan(answers(days = 6, cardioFirst = true, together = BothAnswers.Together.SEPARATE_DAYS))
        assertEquals(listOf("Easy", "Full Body", "Threshold", "Full Body", "Long", "Rest", "Full Body"), draft.program.days.map { it.title })
        assertEquals(setOf("A", "C", "E"), draft.sessions.keys)
        assertEquals(ProgramDayKind.REST, draft.program.days[5].kind)
        assertTrue(draft.program.cardioDays.all { it.exercises.isEmpty() })
    }

    @Test fun `cardio first same visit uses the lift subgoal for strength blocks`() {
        val answers = answers(cardioFirst = true).copy(goal = LiftGoal.MUSCLE)
        val draft = plan(answers)
        val run = runDraft(answers.copy(branch = PlanBranch.RUN), known(8050, 2900), TODAY, LiftGoal.MUSCLE)
        assertEquals(run, draft)
        assertEquals(listOf("A", "B"), draft.program.days.filter { it.exercises.isNotEmpty() }.map { it.id })
        assertEquals(listOf(RunSession.EASY, RunSession.THRESHOLD, RunSession.LONG), draft.sessions.values.toList())
        assertEquals(RepTarget.Range(8, 12, 8), draft.program.days.first().exercises.single { it.rule!!.role == SlotRole.SECONDARY }.rule!!.target)
    }

    @Test fun `D15 lift first reserves twenty minutes and replaces a long run that cannot fit`() {
        val answers = answers()
        val draft = plan(answers)
        val lift = liftDraft(answers.copy(branch = PlanBranch.LIFT), known(8050, 2900), 2400)
        assertEquals(listOf("Full Body", "Full Body", "Full Body"), draft.program.days.map { it.title })
        assertEquals(listOf(RunSession.EASY, RunSession.THRESHOLD, RunSession.EASY), draft.sessions.values.toList())
        for ((index, day) in draft.program.strengthDays.withIndex()) {
            assertEquals(lift.program.days[index], day.copy(cardioBlock = null))
            assertEquals(block(1200, hard = index == 1), day.cardioBlock)
            val estimate = SessionBudget.seconds(day, answers.goal)
            val over = draft.reasons.filterIsInstance<PlanReason.OverBudget>().singleOrNull { it.dayId == day.id }
            if (estimate > 3600) assertEquals(estimate - 3600, over?.seconds) else assertNull(over)
            val liftSeconds = SessionBudget.seconds(day.copy(cardioBlock = null), answers.goal)
            assertTrue(liftSeconds <= 2400 || over != null)
        }
        assertFalse(RunSession.LONG in draft.sessions.values)
        assertFalse(draft.reasons.any { it is PlanReason.RunCapped })
    }

    @Test fun `D15 ladder reserves its own duration and sets every lift days ladder rule`() {
        val answers = answers(days = 6).copy(run = RunAnswers(RunGoal.MARATHON, RunExperience.NEW))
        val draft = plan(answers, KnownLoads.NONE)
        val ladder = RunWalkLadder.block(1, CardioActivity.ROAD_RUN)
        val lift = liftDraft(answers, KnownLoads.NONE, 2160)
        assertEquals(RunPhase.LADDER, draft.phase)
        assertEquals(6, draft.sessions.size)
        for ((index, day) in draft.program.strengthDays.withIndex()) {
            assertEquals(ladder, day.cardioBlock)
            assertEquals(CardioRule(1), day.cardioRule)
            assertEquals(lift.program.strengthDays[index], day.copy(cardioBlock = null, cardioRule = null))
            assertTrue(SessionBudget.seconds(day.copy(cardioBlock = null), answers.goal) <= 2160 ||
                draft.reasons.any { it is PlanReason.OverBudget && it.dayId == day.id })
        }
    }

    @Test fun `D15 thirty minutes uses the full lift budget and only attaches runs that fit`() {
        val answers = answers(days = 1, minutes = 30).copy(mains = emptyList(),
            excluded = ExerciseLibrary.entries.filter { it.id != "plank" }.map { it.id }.toSet())
        val draft = plan(answers)
        val lift = plan(answers.copy(branch = PlanBranch.LIFT))
        assertTrue(lift.program.days.any { SessionBudget.seconds(it, answers.goal) <= 600 })
        assertTrue(draft.program.days.any { it.cardioBlock != null })
        for ((index, day) in draft.program.days.withIndex()) {
            assertEquals(lift.program.days[index], day.copy(cardioBlock = null, cardioRule = null))
            if (day.cardioBlock != null) {
                assertTrue(SessionBudget.seconds(day, answers.goal) <= 1800)
                if (draft.sessions[day.id] == RunSession.EASY) {
                    val seconds = (day.cardioBlock.sets.single().end as Measure.Time).seconds
                    assertTrue(seconds >= 1200 && seconds % 60 == 0)
                }
            }
        }
    }

    @Test fun `D15 recomputes the full budget excess after adding a run`() {
        val answers = answers(days = 2, minutes = 45).copy(goal = LiftGoal.STRENGTH, olderAdult = true)
        val draft = plan(answers)
        val lift = liftDraft(answers, known(8050, 2900), 1500)
        assertTrue(lift.reasons.any { it is PlanReason.OverBudget })
        for (day in draft.program.strengthDays) {
            assertTrue(day.cardioBlock != null)
            val excess = SessionBudget.seconds(day, answers.goal) - 2700
            val over = draft.reasons.filterIsInstance<PlanReason.OverBudget>().singleOrNull { it.dayId == day.id }
            if (excess > 0) assertEquals(excess, over?.seconds) else assertNull(over)
        }
    }

    @Test fun `defaults choose separate from four days and one day same visit includes a run`() {
        assertEquals(BothAnswers.Together.SAME_VISIT, BothAnswers().together(1))
        assertEquals(BothAnswers.Together.SAME_VISIT, BothAnswers().together(3))
        assertEquals(BothAnswers.Together.SEPARATE_DAYS, BothAnswers().together(4))
        val answers = answers(days = 1).copy(both = BothAnswers())
        val draft = plan(answers)
        val lift = liftDraft(answers, known(8050, 2900), 2400)
        assertEquals(lift.program.days.single(), draft.program.days.single().copy(cardioBlock = null))
        assertEquals(block(1200), draft.program.days.single().cardioBlock)
        assertEquals(mapOf("A" to RunSession.EASY), draft.sessions)
        assertEquals(plan(answers.copy(branch = PlanBranch.LIFT)),
            plan(answers.copy(both = BothAnswers(together = BothAnswers.Together.SEPARATE_DAYS))))
        val cardio = plan(answers.copy(both = BothAnswers(cardioFirst = true)))
        assertEquals(runDraft(answers.copy(branch = PlanBranch.RUN), known(8050, 2900), TODAY, answers.goal), cardio)
        assertEquals(ProgramDayKind.CARDIO, cardio.program.days.single().kind)
        assertEquals(RunSession.EASY, cardio.sessions["A"])
        assertTrue(cardio.program.days.single().exercises.isNotEmpty())
    }

    @Test fun `every Both layout takes the run goal even when only lifting fits`() {
        for (days in listOf(1, 4)) for (first in listOf(false, true)) for (together in BothAnswers.Together.entries) {
            val answers = answers(days = days, cardioFirst = first, together = together).let {
                it.copy(run = it.run.copy(event = RunEvent.K10, goalSeconds = 3000))
            }
            assertEquals(PlanGoal.Timed(CardioActivity.ROAD_RUN, 10_000, 3000), plan(answers).goal)
        }
    }

    @Test fun `Both keeps date and goal reasons and day filtering after relabeling`() {
        for (first in listOf(false, true)) for (together in BothAnswers.Together.entries) {
            val answers = answers(days = 6, cardioFirst = first, together = together).copy(
                run = RunAnswers(RunGoal.MARATHON, RunExperience.SOME, dateEpochDay = TODAY + 1,
                    goalSeconds = 10_000, recent = RecentRace(21_097, 6600)))
            val draft = plan(answers)
            assertTrue(PlanReason.DateTooClose in draft.reasons)
            assertTrue(PlanReason.GoalCheck(14_449) in draft.reasons)
            for (day in draft.program.days) {
                val reasons = Planner.day(answers, day.id, known(8050, 2900), TODAY)!!.reasons
                assertFalse(reasons.any { it == PlanReason.DateTooClose || it is PlanReason.GoalCheck })
            }
        }
    }
}
