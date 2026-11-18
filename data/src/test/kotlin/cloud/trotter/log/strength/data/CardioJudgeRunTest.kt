package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioProgress
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.DistanceSource
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.planner.PlanBranch
import cloud.trotter.log.strength.domain.planner.RunAnswers
import cloud.trotter.log.strength.domain.planner.RunExperience
import cloud.trotter.log.strength.domain.planner.RunGoal
import cloud.trotter.log.strength.domain.planner.SetupAnswers
import cloud.trotter.log.strength.domain.progression.CardioChange
import cloud.trotter.log.strength.domain.progression.CardioRule
import cloud.trotter.log.strength.domain.progression.RunSession
import cloud.trotter.log.strength.domain.progression.RunWalkLadder
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test

class CardioJudgeRunTest {
    private val session = CardioSessionEntity(id = 1, planId = 7, dayId = "A", mode = "OUTDOOR_RUN", hard = false,
        label = "Run", startedAt = 86_400_000_000L, completedAt = 86_401_000_000L, seconds = 1000,
        stepsCompleted = 1, activity = CardioActivity.ROAD_RUN.name, effort = "EASY")
    private fun day(block: CardioBlock, rule: CardioRule?) = ProgramDay("A", "Run", "", emptyList<ProgramExercise>(),
        kind = ProgramDayKind.CARDIO, cardioBlock = block, cardioRule = rule)
    private fun plan(day: ProgramDay, setup: SetupAnswers? = null) = CardioPlan(Program(listOf(day)), 0, null, false, ZoneOffset.UTC, setup)
    private fun vo2(rounds: Int) = CardioBlock(CardioActivity.ROAD_RUN, buildList<CardioSetSpec> {
        repeat(rounds) { index ->
            if (index > 0) add(CardioSetSpec(CardioRole.RECOVERY, Measure.Time(180)))
            add(CardioSetSpec(CardioRole.WORK, Measure.Time(180), hard = true))
        }
    })
    private fun results(block: CardioBlock) = block.sets.map { spec ->
        CardioSetResult(spec, CardioSetStatus.FINISHED, (spec.end as? Measure.Time)?.seconds ?: 240,
            (spec.end as? Measure.Distance)?.metres, if (spec.end is Measure.Distance) DistanceSource.MANUAL else null)
    }
    private fun judge(day: ProgramDay, inputs: CardioPlan? = null, cap: Boolean = false) =
        CardioJudge.judge(day, session, listOf(session), mapOf(session.id to results(requireNotNull(day.cardioBlock))), cap, inputs)

    @Test fun `a ruled run day is judged only with plan inputs`() {
        // ladder 2026-11-17: a level-up now needs the first Easy credit before this finish.
        val day = day(RunWalkLadder.block(4, CardioActivity.ROAD_RUN), CardioRule(4, credits = 1))
        assertNull(judge(day))
        assertEquals(CardioChange.LevelTo(5), assertNotNull(judge(day, plan(day))).outcome.change)
        // ladder 2026-11-17: Start is UTC day 1000, so production records its counted and raised date.
        assertEquals(CardioRule(5, ladderCounted = listOf(1000), ladderRaised = 1000),
            assertNotNull(judge(day, plan(day))).outcome.rule)
    }

    @Test fun `easy run context goes only to cardio days with setup answers`() {
        val block = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(960))))
        val day = day(block, CardioRule(null, RunSession.EASY)).copy(exercises = listOf(ProgramExercise("bb_back_squat")))
        val setup = SetupAnswers(branch = PlanBranch.RUN, days = 3, minutes = 60,
            run = RunAnswers(RunGoal.CARDIO_FITNESS, RunExperience.SOME, longest = Measure.Time(900)))
        val outcome = assertNotNull(judge(day, plan(day, setup), cap = true)).outcome
        assertEquals(CardioChange.Increase(ProgressMeasure.TIME, false), outcome.change)
        assertEquals(Measure.Time(1008), outcome.block.sets.single().end)
        val lift = day.copy(kind = ProgramDayKind.STRENGTH)
        assertEquals(CardioChange.Repeat, assertNotNull(judge(lift, plan(lift, setup), cap = true)).outcome.change)
        assertEquals(CardioChange.Repeat, assertNotNull(judge(day, plan(day), cap = true)).outcome.change)
    }

    @Test fun `the held guard applies only to rule less days`() {
        val work = CardioSetSpec(CardioRole.WORK, Measure.Distance(1000))
        val recovery = CardioSetSpec(CardioRole.RECOVERY, Measure.Distance(200))
        val opening = CardioBlock(CardioActivity.ROAD_RUN, listOf(work, recovery, work, recovery, work))
        val unruled = day(opening, null).copy(cardioProgress = CardioProgress(increasePercent = 25))
        // A16 cannot add rounds; the cap can remove one, which must still be held on a rule-less day.
        assertEquals(CardioChange.Repeat, assertNotNull(judge(unruled, cap = true)).outcome.change)
        val ruled = day(vo2(5),
            CardioRule(null, RunSession.VO2MAX, credits = 1))
        val next = assertNotNull(judge(ruled, plan(ruled))).outcome
        assertEquals(CardioChange.Step, next.change)
        assertEquals(6, next.block.sets.count { it.role == CardioRole.WORK })
    }

    @Test fun `quality steps use the budget left after lifts`() {
        val block = vo2(5)
        val lift = ProgramExercise("bb_back_squat", targetSets = 4,
            rule = SlotRule.defaults(SlotRole.SECONDARY, LiftGoal.STRENGTH, SetStructure.STRAIGHT, 5, MovementPattern.SQUAT_BILATERAL))
        val day = day(block, CardioRule(null, RunSession.VO2MAX, credits = 1)).copy(exercises = listOf(lift))
        val setup = SetupAnswers(branch = PlanBranch.RUN, minutes = 45)
        // 2700 - 300 warm-up - 4 * (15 + 5 * 3 + 120 rest) = 1800; the sixth round takes 1980.
        assertEquals(CardioChange.Repeat, assertNotNull(judge(day, plan(day, setup))).outcome.change)
        val solo = day.copy(exercises = emptyList<ProgramExercise>())
        assertEquals(CardioChange.Step, assertNotNull(judge(solo, plan(solo, setup))).outcome.change)
    }

    @Test fun `a ladder day always gets a ladder context`() {
        val d = 20_000L
        val zone = ZoneOffset.ofHours(-6)
        fun start(day: Long) = LocalDate.ofEpochDay(day).atTime(23, 30).toInstant(zone).toEpochMilli()
        val current = session.copy(id = 10, startedAt = start(d), completedAt = start(d) + 40 * 60_000)
        val day = day(RunWalkLadder.block(4, CardioActivity.ROAD_RUN), CardioRule(4, credits = 1))
        val inputs = plan(day).copy(zone = zone)
        val rows = results(requireNotNull(day.cardioBlock))
        val next = assertNotNull(CardioJudge.judge(day, current, listOf(current), mapOf(current.id to rows), false, inputs)).outcome
        assertEquals(CardioChange.LevelTo(5), next.change)
        assertEquals(CardioRule(5, ladderCounted = listOf(d), ladderRaised = d), next.rule)
        val busyRule = CardioRule(4, credits = 1, ladderCounted = listOf(d - 3), ladderRaised = d - 3)
        val busyDay = day.copy(cardioRule = busyRule)
        val prior = (d - 13..d - 10).mapIndexed { index, date ->
            session.copy(id = index + 1L, planId = null, startedAt = start(date), completedAt = start(date) + 40 * 60_000,
                effort = if (index == 0) null else "GOOD", stepsCompleted = if (index == 1) 0 else rows.size)
        }
        val sets = prior.filterIndexed { index, _ -> index != 1 }.associate { it.id to rows } + (current.id to rows)
        val held = assertNotNull(CardioJudge.judge(busyDay, current, prior + current, sets, false, inputs)).outcome
        assertEquals(CardioChange.Repeat, held.change)
        assertEquals(busyRule, held.rule)
        val quiet = assertNotNull(CardioJudge.judge(busyDay, current, listOf(current), sets, false, inputs)).outcome
        assertEquals(CardioChange.LevelTo(5), quiet.change)
        assertEquals(CardioRule(5, ladderCounted = listOf(d - 3, d), ladderRaised = d), quiet.rule)
    }
}
