package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.serialization.CardioUndoJson
import cloud.trotter.log.strength.data.serialization.OpeningDto
import cloud.trotter.log.strength.data.serialization.OpeningJson
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.ProgressMeasure
import cloud.trotter.log.strength.domain.progression.CardioEffort
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReceiptProgressionTest : FinishProgressionFixture() {
    override val fileBacked = true

    @Test fun `Finish archives a literal reason and legacy planned JSON defaults both additions`() = runTest {
        val plan = authored()
        val bench = add(plan, "bb_bench")
        lifter(plan, bench)
        val session = finish(plan, bench)
        assertEquals("ALL_MADE", SessionPlanJson.decode(db.sessionDao().sessionById(session)!!.plannedJson!!).single().reason)
        val old = """[{"position":0,"exerciseId":"bb_bench","slot":"main","sets":[]}]"""
        val decoded = SessionPlanJson.decode(old).single()
        assertNull(decoded.reason)
        assertFalse(decoded.dropped)
    }

    @Test fun `a fresh repository restores strength rows and rules`() = runTest {
        val plan = authored()
        val bench = add(plan, "bb_bench")
        lifter(plan, bench)
        val beforeRule = db.programDao().exercise(bench)!!.ruleJson
        val session = finish(plan, bench)
        assertEquals(work(w = 190.0), rows(plan, bench))
        restart()
        assertTrue(repo.finishUndoState(session, null).restorable)
        assertEquals(UndoResult.Undone, repo.undoFinish(session, null))
        assertEquals(work(), rows(plan, bench))
        assertEquals(beforeRule, db.programDao().exercise(bench)!!.ruleJson)
        assertNull(db.sessionDao().sessionById(session)!!.progressionUndoJson)
    }

    @Test fun `an Ask plan with one Automatic lift has restorable applied changes`() = runTest {
        val plan = converted(day("bb_bench", "ohp"))
        val bench = id(plan, "bb_bench")
        val press = id(plan, "ohp")
        lifter(plan, bench, rule = rule().copy(nextTime = cloud.trotter.log.strength.domain.progression.NextTimeMode.AUTO))
        lifter(plan, press, work(w = 95.0))
        val run = start(plan)
        perform(run, plan, bench, 185.0)
        perform(run, plan, press, 95.0)
        val session = assertNotNull(repo.finishRun(run))
        assertEquals(work(w = 190.0), rows(plan, bench))
        assertEquals(work(w = 95.0), rows(plan, press))
        assertTrue(repo.finishUndoState(session, null).restorable)
    }

    @Test fun `rule counters alone on an Ask plan leave Undo hidden`() = runTest {
        val plan = converted(day("bb_bench"))
        val bench = id(plan, "bb_bench")
        lifter(plan, bench, rule = rule().copy(everyExposures = 3))
        // Avoid a rotation wrap, which also writes plan counters.
        repo.addDay(plan, "Day B")
        val session = finish(plan, bench)
        assertFalse(repo.finishUndoState(session, null).restorable)
        assertTrue(repo.finishUndoState(session, null).hasStrengthRecord)
    }

    @Test fun `a same day draft refuses Undo without changing rows`() = runTest {
        val plan = authored()
        val bench = add(plan, "bb_bench")
        lifter(plan, bench)
        val session = finish(plan, bench)
        val next = db.programDao().day(plan, "A")!!.nextRunId
        repo.setWorkingWeight(next, plan, "A", bench, Slot.MAIN, 190.0)
        // Editing actuals creates a draft; editing stored working values alone does not.
        repo.applyToRun(next, plan, "A", listOf(TrackEdit(bench, Slot.MAIN) { it.map { set -> set.copy(reps = 4) } }))
        assertEquals(UndoResult.DayOpen, repo.undoFinish(session, null))
        assertEquals(work(w = 190.0), rows(plan, bench))
    }

    @Test fun `Rough day swaps rows and rules but leaves Finish counters alone`() = runTest {
        val plan = authored()
        val bench = add(plan, "bb_bench")
        lifter(plan, bench)
        val before = db.programDao().exercise(bench)!!.ruleJson
        val session = finish(plan, bench)
        val after = db.programDao().exercise(bench)!!.ruleJson
        val counters = db.planDao().plan(plan)!!.let { it.lightCycleRemaining to it.cyclesCompleted }
        assertEquals(UndoResult.Undone, repo.setRoughDay(session, true))
        assertEquals(work(), rows(plan, bench))
        assertEquals(before, db.programDao().exercise(bench)!!.ruleJson)
        assertTrue(db.sessionDao().sessionById(session)!!.roughDay)
        assertEquals(counters, db.planDao().plan(plan)!!.let { it.lightCycleRemaining to it.cyclesCompleted })
        assertEquals(UndoResult.Nothing, repo.undoFinish(session, null))
        assertEquals(UndoResult.Undone, repo.setRoughDay(session, false))
        assertEquals(work(w = 190.0), rows(plan, bench))
        assertEquals(after, db.programDao().exercise(bench)!!.ruleJson)
        assertFalse(repo.history.slotSessionsById(plan, "A").getValue(bench to Slot.MAIN).rough)
    }

    @Test fun `Rough day preserves break restart rows and reverts other lifts`() = runTest {
        val plan = authored()
        val squat = add(plan, "bb_back_squat")
        val bench = add(plan, "bb_bench")
        lifter(plan, squat, work(w = 230.0))
        lifter(plan, bench)
        val run = start(plan)
        val track = db.runDao().tracks(run).single { it.programExerciseId == squat }
        val planned = work(w = 195.0)
        db.runDao().updateTrack(track.copy(plannedJson = SetJson.encodeSets(planned),
            setsJson = SetJson.encodeSets(planned.mapIndexed { i, set -> set.copy(done = true, plannedIndex = i, reps = if (i == 2) 4 else 5) }),
            openingJson = OpeningJson.encode(OpeningDto(OpeningDto.SCALED, 0.85))))
        perform(run, plan, bench, 185.0)
        val session = assertNotNull(repo.finishRun(run))
        assertEquals(work(w = 195.0), rows(plan, squat))
        repo.setRoughDay(session, true)
        assertEquals(work(w = 195.0), rows(plan, squat))
        assertEquals(work(), rows(plan, bench))
    }

    @Test fun `two rough strength days start a rotation of light days only on Automatic`() = runTest { roughPair(true) }
    @Test fun `two rough strength days on Ask leave the light counter alone`() = runTest { roughPair(false) }

    private suspend fun roughPair(automatic: Boolean) {
        val plan = converted(day("bb_bench"), day("ohp", id = "B"), day("bb_back_squat", id = "C"))
        db.planDao().updatePlan(db.planDao().plan(plan)!!.copy(autoApply = automatic))
        val bench = id(plan, "bb_bench")
        val press = id(plan, "ohp", "B")
        lifter(plan, bench)
        lifter(plan, press, day = "B")
        repo.setRoughDay(finish(plan, bench), true)
        val session = finish(plan, press, day = "B")
        repo.setRoughDay(session, true)
        assertEquals(if (automatic) 3 else 0, db.planDao().plan(plan)!!.lightCycleRemaining)
        repo.setRoughDay(session, false)
        assertEquals(0, db.planDao().plan(plan)!!.lightCycleRemaining)
    }

    private fun block(meters: Int = 5000) = CardioBlock(CardioActivity.TRACK_RUN,
        listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(1500), Measure.Distance(meters))))
    private suspend fun cardioPlan(combined: Boolean = false): Long {
        val plan = converted(if (combined) day("bb_bench").copy(cardioBlock = block()) else
            ProgramDay("A", "Run", "", emptyList(), kind = ProgramDayKind.CARDIO, cardioBlock = block()))
        db.planDao().updatePlan(db.planDao().plan(plan)!!.copy(autoApply = true))
        repo.setCardioProgression(plan, "A", ProgressMeasure.DISTANCE, 5)
        return plan
    }
    private suspend fun cardio(plan: Long, meters: Int? = 5000, finishDay: Boolean = true, current: CardioBlock = block()): Long =
        repo.logCardioSession(CardioSessionEntity(dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Run",
            startedAt = clock.millis(), completedAt = clock.millis() + 1500000, seconds = 1500, stepsCompleted = 1,
            planId = plan, activity = current.activity.name),
            listOf(CardioSetResult(current.sets.single(), CardioSetStatus.FINISHED, 1500, meters)), finishDay)

    @Test fun `cardio journal restores block and counters after reopening and never reapplies`() = runTest {
        val plan = cardioPlan()
        db.planDao().updateLightCycle(plan, 2, 0)
        val id = cardio(plan)
        assertEquals(1, db.planDao().plan(plan)!!.lightCycleRemaining)
        assertNotNull(CardioUndoJson.decode(db.cardioSessionDao().byId(id)!!.progressionUndoJson)?.block)
        restart()
        assertEquals(UndoResult.Undone, repo.undoFinish(null, id))
        assertNull(db.programDao().day(plan, "A")!!.cardioWorkingJson)
        assertEquals(2, db.planDao().plan(plan)!!.lightCycleRemaining)
        assertTrue(db.cardioSessionDao().byId(id)!!.increaseApplied)
        assertNull(db.cardioSessionDao().byId(id)!!.progressionUndoJson)
        repo.amendCardioSetDistance(id, 0, 5000)
        assertNull(db.programDao().day(plan, "A")!!.cardioWorkingJson)
    }

    @Test fun `an amendment adds a block snapshot to a counter only journal preserving the current block`() = runTest {
        val plan = cardioPlan()
        repo.setCardioWorking(plan, "A", block(5250))
        val before = db.programDao().day(plan, "A")!!.cardioWorkingJson
        db.planDao().updateLightCycle(plan, 2, 0)
        val id = cardio(plan, meters = null, current = block(5250))
        assertNull(CardioUndoJson.decode(db.cardioSessionDao().byId(id)!!.progressionUndoJson)?.block)
        repo.amendCardioSetDistance(id, 0, 5250)
        val journal = assertNotNull(CardioUndoJson.decode(db.cardioSessionDao().byId(id)!!.progressionUndoJson))
        assertEquals(before, assertNotNull(journal.block).before)
        assertTrue(repo.finishUndoState(null, id).restorable)
        repo.undoFinish(null, id)
        assertEquals(before, db.programDao().day(plan, "A")!!.cardioWorkingJson)
        assertEquals(2, db.planDao().plan(plan)!!.lightCycleRemaining)
    }

    @Test fun `combined Finish preserves cardio logged during its run and Undo restores both`() = runTest {
        val plan = cardioPlan(combined = true)
        val bench = id(plan, "bb_bench")
        lifter(plan, bench)
        val run = start(plan)
        val cardio = cardio(plan, finishDay = false)
        perform(run, plan, bench, 185.0)
        val strength = assertNotNull(repo.finishRun(run))
        assertNotNull(db.cardioSessionDao().byId(cardio)!!.progressionUndoJson)
        assertEquals(UndoResult.Undone, repo.undoFinish(strength, cardio))
        assertEquals(work(), rows(plan, bench))
        assertNull(db.programDao().day(plan, "A")!!.cardioWorkingJson)
    }

    @Test fun `Start clears earlier cardio Undo and a later plan Finish clears both journals`() = runTest {
        val plan = cardioPlan(combined = true)
        val bench = id(plan, "bb_bench")
        lifter(plan, bench)
        val early = cardio(plan, finishDay = false)
        assertTrue(repo.finishUndoState(null, early).restorable)
        val blockAfter = db.programDao().day(plan, "A")!!.cardioWorkingJson
        val run = start(plan)
        assertNull(db.cardioSessionDao().byId(early)!!.progressionUndoJson)
        perform(run, plan, bench, 185.0)
        val strength = assertNotNull(repo.finishRun(run))
        repo.undoFinish(strength, null)
        assertEquals(blockAfter, db.programDao().day(plan, "A")!!.cardioWorkingJson)
        advanceDays(1)
        val other = repo.addDay(plan, "Day B")!!
        val press = add(plan, "ohp", other)
        lifter(plan, press, day = other)
        finish(plan, press, day = other)
        assertNull(db.sessionDao().sessionById(strength)!!.progressionUndoJson)
        assertNull(db.cardioSessionDao().byId(early)!!.progressionUndoJson)
    }

    @Test fun `kept to plan cardio records counter only Undo and effort can be selected and cleared`() = runTest {
        val plan = cardioPlan()
        repo.setCardioKeepToPlan(plan, "A", true)
        db.planDao().updateLightCycle(plan, 2, 0)
        val id = cardio(plan)
        val undo = assertNotNull(CardioUndoJson.decode(db.cardioSessionDao().byId(id)!!.progressionUndoJson))
        assertNull(undo.block)
        assertNotNull(undo.plan)
        assertTrue(repo.finishUndoState(null, id).restorable)
        repo.setCardioEffort(id, CardioEffort.GOOD)
        assertEquals("GOOD", db.cardioSessionDao().byId(id)!!.effort)
        repo.setCardioEffort(id, null)
        assertNull(db.cardioSessionDao().byId(id)!!.effort)
        repo.undoFinish(null, id)
        assertEquals(2, db.planDao().plan(plan)!!.lightCycleRemaining)
    }
}
