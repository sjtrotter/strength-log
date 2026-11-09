package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.serialization.OpeningDto
import cloud.trotter.log.strength.data.serialization.OpeningJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.LightCycle
import cloud.trotter.log.strength.domain.progression.SlotState
import cloud.trotter.log.strength.domain.progression.WorkingRows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LightCycleFinishTest : OpeningFixture() {
    private suspend fun counters(plan: Long): LightCycle = db.planDao().plan(plan)!!.let {
        LightCycle(it.lightCycleRemaining, it.cyclesCompleted)
    }
    private suspend fun plannedCycle(): Long {
        val plan = converted(day("bb_back_squat"), day("bb_bench", id = "B"))
        lifter(plan, id(plan, "bb_back_squat"), squat(), squatRule())
        lifter(plan, id(plan, "bb_bench", "B"), day = "B")
        db.planDao().updatePlan(db.planDao().plan(plan)!!.copy(deloadEveryCycles = 6, cyclesCompleted = 5))
        return plan
    }

    @Test fun `case 35 a planned light cycle fires at the sixth wrap in Ask and opens light rows without a load or an offer`() = runTest {
        val plan = plannedCycle()
        asPlanned(opened(plan), plan)
        assertEquals(LightCycle(0, 5), counters(plan))
        asPlanned(opened(plan, "B"), plan, "B")
        assertEquals(LightCycle(2, 6), counters(plan))
        val squatTrack = opened(plan)
        assertEquals(listOf(LoggedSet(165.0, 5, SetKind.RAMP), LoggedSet(220.0, 3, SetKind.RAMP),
            LoggedSet(250.0, 5, SetKind.TOP), LoggedSet(225.0, 5, SetKind.BACKOFF)), planned(squatTrack))
        assertEquals("""{"light":true}""", squatTrack.openingJson)
        asPlanned(squatTrack, plan)
        assertEquals(squat(), rows(plan, id(plan, "bb_back_squat")))
        assertNull(offer(plan, "bb_back_squat"))
        assertEquals(LightCycle(1, 6), counters(plan))
        val bench = opened(plan, "B")
        assertTrue(OpeningJson.decode(bench.openingJson)!!.light)
        asPlanned(bench, plan, "B")
        assertEquals(LightCycle(0, 7), counters(plan))
        assertNull(offer(plan, "bb_bench", "B"))
        val normal = opened(plan)
        assertEquals(WorkingRows.cleared(squat()), planned(normal))
        assertNull(normal.openingJson)
    }

    @Test fun `case 14 a fired light cycle lasts four finished days on R's plan`() = runTest {
        val plan = converted(day("bb_back_squat"), day("bb_bench", id = "B"), day("bb_back_squat", id = "C"),
            ProgramDay("R", "Rest", "", emptyList(), kind = ProgramDayKind.REST), day("ohp", id = "D"))
        for ((day, exercise) in listOf("A" to "bb_back_squat", "B" to "bb_bench", "C" to "bb_back_squat", "D" to "ohp")) {
            lifter(plan, id(plan, exercise, day), if (day == "A") squat() else work(),
                if (day == "A") squatRule() else rule(), day)
        }
        db.planDao().updatePlan(db.planDao().plan(plan)!!.copy(deloadEveryCycles = 6, cyclesCompleted = 5, suggestedDayId = "D"))
        asPlanned(opened(plan, "D"), plan, "D")
        assertEquals(LightCycle(4, 6), counters(plan))
        for ((index, day) in listOf("A", "B", "C", "D").withIndex()) {
            val track = opened(plan, day)
            assertTrue(OpeningJson.decode(track.openingJson)!!.light)
            asPlanned(track, plan, day)
            assertEquals(LightCycle(3 - index, if (day == "D") 7 else 6), counters(plan))
        }
    }

    @Test fun `setting a weight during an Automatic light cycle keeps the light view`() = runTest {
        val plan = authored()
        val id = add(plan, "bb_back_squat")
        lifter(plan, id, work(w = 275.0), squatRule())
        history(plan, 14, sixteen)
        db.planDao().updateLightCycle(plan, 2, 0)
        val track = opened(plan)
        assertEquals(OpeningDto(OpeningDto.SCALED, 0.95, light = true), OpeningJson.decode(track.openingJson))
        val before = log(plan, id).setsJson
        repo.setWorkingWeight(track.runId, plan, "A", id, Slot.MAIN, 275.0)
        val edited = db.runDao().tracks(track.runId).single()
        assertEquals(OpeningDto(light = true), OpeningJson.decode(edited.openingJson))
        asPlanned(edited, plan)
        assertEquals(before, log(plan, id).setsJson)
        assertNull(offer(plan, "bb_back_squat"))
        assertEquals(LightCycle(1, 1), counters(plan))
    }

    @Test fun `case 31 undo after a restart restores the light counters a Finish fired`() = runTest {
        val plan = plannedCycle()
        asPlanned(opened(plan), plan)
        val beforeRows = rows(plan, id(plan, "bb_bench", "B"), "B")
        val beforeRule = rule(id(plan, "bb_bench", "B"))
        val session = asPlanned(opened(plan, "B"), plan, "B")
        assertEquals(LightCycle(2, 6), counters(plan))
        restart()
        assertTrue(repo.undoProgression(session))
        assertEquals(LightCycle(0, 5), counters(plan))
        val normal = opened(plan)
        assertNull(normal.openingJson)
        repo.discardRun(normal.runId)
        db.planDao().updateSuggestedDay(plan, "B")
        val second = asPlanned(opened(plan, "B"), plan, "B")
        assertEquals(LightCycle(2, 6), counters(plan))
        db.planDao().updateLightCycle(plan, 1, 6)
        assertTrue(repo.undoProgression(second))
        assertEquals(LightCycle(1, 6), counters(plan))
        assertEquals(beforeRows, rows(plan, id(plan, "bb_bench", "B"), "B"))
        assertEquals(beforeRule, rule(id(plan, "bb_bench", "B")))
    }

    private suspend fun stalls(automatic: Boolean): Long {
        val plan = if (automatic) authored() else converted(day("bb_back_squat"), day("bb_bench", id = "B"))
        if (automatic) {
            repo.addDay(plan, "Day B")
            add(plan, "bb_back_squat")
            add(plan, "bb_bench", "B")
        }
        lifter(plan, id(plan, "bb_back_squat"))
        lifter(plan, id(plan, "bb_bench", "B"), day = "B")
        return plan
    }

    // One plan per test: converted() reuses the selected plan, so an Ask pass after the Automatic one would keep autoApply.
    @Test fun `two stalled lifts within one rotation fire a light cycle on an Automatic plan`() = runTest { twoStalls(true) }

    @Test fun `two stalled lifts within one rotation do not fire a light cycle on an Ask plan`() = runTest { twoStalls(false) }

    private suspend fun twoStalls(automatic: Boolean) {
        val plan = stalls(automatic)
        val a = id(plan, "bb_back_squat")
        val b = id(plan, "bb_bench", "B")
        finish(plan, a, reps = listOf(5, 5, 3))
        assertEquals(0, counters(plan).remaining)
        advanceDays(1)
        finish(plan, b, reps = listOf(5, 5, 3), day = "B")
        assertEquals(0, counters(plan).remaining)
        advanceDays(1)
        finish(plan, a, reps = listOf(5, 4, 4))
        assertEquals(0, counters(plan).remaining)
        advanceDays(1)
        val last = finish(plan, b, reps = listOf(5, 4, 4), day = "B")
        assertEquals(if (automatic) 2 else 0, counters(plan).remaining)
        assertTrue(repo.undoProgression(last))
        assertEquals(0, counters(plan).remaining)
    }

    @Test fun `a stall outside the rotation window does not fire`() = runTest {
        val plan = stalls(true)
        val a = id(plan, "bb_back_squat")
        val b = id(plan, "bb_bench", "B")
        val sequence = listOf(Triple(a, 185.0, listOf(5, 5, 3)), Triple(b, 185.0, listOf(5, 5, 5)),
            Triple(a, 185.0, listOf(5, 4, 4)), Triple(b, 190.0, listOf(5, 5, 3)),
            Triple(a, 175.0, listOf(5, 5, 5)), Triple(b, 190.0, listOf(5, 4, 4)))
        for ((id, weight, reps) in sequence) {
            finish(plan, id, weight, reps, if (id == a) "A" else "B")
            assertEquals(0, counters(plan).remaining)
            advanceDays(1)
        }
    }

    @Test fun `cardio entries do not widen the strength stall window`() = runTest {
        val plan = converted(day("bb_back_squat"), ProgramDay("B", "Cardio", "", emptyList(), kind = ProgramDayKind.CARDIO),
            day("bb_bench", id = "C"), ProgramDay("D", "Cardio", "", emptyList(), kind = ProgramDayKind.CARDIO))
        db.planDao().updatePlan(db.planDao().plan(plan)!!.copy(autoApply = true))
        val today = clock.instant().atZone(clock.zone).toLocalDate().toEpochDay()
        lifter(plan, id(plan, "bb_back_squat"), rule = rule().copy(state = SlotState(resetEpochDays = listOf(today - 2))))
        val bench = id(plan, "bb_bench", "C")
        lifter(plan, bench, rule = rule().copy(state = SlotState(repMissStreak = 1)), day = "C")
        history(plan, 1, listOf(0L, 1, 2), exercise = "bb_bench", day = "C")
        finish(plan, bench, reps = listOf(5, 5, 4), day = "C")
        assertEquals(listOf(today), rule(bench).state.resetEpochDays)
        assertEquals(0, counters(plan).remaining)
    }

    @Test fun `a cardio day finish counts down and wraps`() = runTest {
        val spec = CardioSetSpec(CardioRole.WORK, Measure.Time(900))
        val block = CardioBlock(CardioActivity.TRACK_RUN, listOf(spec))
        val plan = converted(day("bb_back_squat"), ProgramDay("B", "Run", "", emptyList(),
            kind = ProgramDayKind.CARDIO, cardioBlock = block))
        db.planDao().updateSuggestedDay(plan, "B")
        db.planDao().updateLightCycle(plan, 2, 0)
        val session = CardioSessionEntity(dayId = "B", mode = "OUTDOOR_RUN", hard = false, label = "Track",
            startedAt = clock.millis(), completedAt = clock.millis() + 900000, seconds = 900, stepsCompleted = 1,
            planId = plan, activity = "TRACK_RUN")
        repo.logCardioSession(session, listOf(CardioSetResult(spec, CardioSetStatus.FINISHED, 900)), finishDay = true)
        assertEquals(LightCycle(1, 1), counters(plan))
        assertEquals("A", db.planDao().plan(plan)!!.suggestedDayId)
    }
}
