package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.serialization.OpeningDto
import cloud.trotter.log.strength.data.serialization.OpeningJson
import cloud.trotter.log.strength.data.serialization.ProgressionUndoJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.LiftGoal
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.SlotState
import cloud.trotter.log.strength.domain.progression.WorkingRows
import cloud.trotter.log.strength.domain.seeding.SetEdit
import cloud.trotter.log.strength.domain.seeding.SetEditor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DraftRunTest : OpeningFixture() {
    private val case13 = listOf(
        LoggedSet(155.0, 5, SetKind.RAMP), LoggedSet(210.0, 3, SetKind.RAMP),
        LoggedSet(260.0, 5, SetKind.TOP), LoggedSet(235.0, 5, SetKind.BACKOFF),
        LoggedSet(235.0, 5, SetKind.BACKOFF),
    )
    private val floorRamp = listOf(
        LoggedSet(45.0, 5, SetKind.RAMP), LoggedSet(45.0, 5, SetKind.RAMP),
        LoggedSet(45.0, 5, SetKind.RAMP), LoggedSet(45.0, 3, SetKind.RAMP),
        LoggedSet(45.0, 5, SetKind.TOP),
    )

    @Test fun `case 13 shows the opening before Start and writes nothing`() = runTest {
        val plan = rPlan()
        val s = id(plan, "bb_back_squat")
        history(plan, 16, sixteen)
        val beforeLog = log(plan, s)
        val beforeSlot = db.programDao().exercise(s)

        val main = repo.observeDay(plan, "A").first().slots.single().main

        assertEquals(case13, main.sets)
        assertEquals(case13, main.opening)
        assertEquals(squat(), main.baseRows)
        assertEquals(beforeLog, log(plan, s))
        assertEquals(beforeSlot, db.programDao().exercise(s))
        assertNull(db.runDao().openRun())
    }

    @Test fun `a value edit before Start opens a draft on the opened rows and leaves the plan alone`() = runTest {
        val plan = rPlan()
        val s = id(plan, "bb_back_squat")
        history(plan, 16, sixteen)
        val next = db.programDao().day(plan, "A")!!.nextRunId

        val beforeLog = log(plan, s)
        val beforeSlot = db.programDao().exercise(s)
        assertEquals(RunWriteResult.InRun(next, false), repo.applyToRun(next, plan, "A", listOf(
            TrackEdit(s, Slot.MAIN) { rows -> SetEditor.edit(rows, 4, SetEdit(reps = 4)) },
        )))

        val draft = assertNotNull(db.runDao().openRun())
        assertEquals(next, draft.id)
        assertTrue(draft.draft)
        assertEquals(1794225600000, draft.startedAt)
        assertEquals(beforeLog, log(plan, s))
        assertEquals(beforeSlot, db.programDao().exercise(s))
        assertEquals(16, db.sessionDao().allSessions().size)
        val track = db.runDao().tracks(next).single()
        assertEquals(case13, planned(track))
        assertEquals(OpeningDto("SCALED", 0.95), OpeningJson.decode(track.openingJson))
        val actual = SetJson.decodeSets(track.setsJson)
        assertEquals(case13.mapIndexed { i, row -> row.copy(plannedIndex = i, reps = if (i == 4) 4 else row.reps) }, actual)
        assertTrue(actual.none { it.done })
        assertEquals(squat(), rows(plan, s))
    }

    @Test fun `a value edit on a track without an opening opens a draft`() = runTest {
        val plan = rPlan()
        val s = id(plan, "bb_back_squat")
        history(plan, 4, sixteen)
        val next = db.programDao().day(plan, "A")!!.nextRunId

        assertEquals(RunWriteResult.InRun(next, false), repo.applyToRun(next, plan, "A", listOf(
            TrackEdit(s, Slot.MAIN) { rows -> SetEditor.edit(rows, 4, SetEdit(reps = 4)) },
        )))

        val track = db.runDao().tracks(next).single()
        assertEquals(WorkingRows.cleared(squat()), planned(track))
        assertNull(track.openingJson)
        assertEquals(squat().mapIndexed { i, row -> row.copy(plannedIndex = i, reps = if (i == 4) 4 else row.reps) },
            SetJson.decodeSets(track.setsJson))
        assertEquals(squat(), rows(plan, s))
        assertTrue(assertNotNull(db.runDao().openRun()).draft)
    }

    @Test fun `case 13 removing the first ramp before Start keeps identities through Finish`() = runTest {
        val plan = rPlan()
        val s = id(plan, "bb_back_squat")
        history(plan, 16, sixteen)
        val next = db.programDao().day(plan, "A")!!.nextRunId

        assertEquals(RunWriteResult.InRun(next, false), repo.applyToRun(next, plan, "A", listOf(
            TrackEdit(s, Slot.MAIN) { rows -> SetEditor.removeSet(rows, 0) },
        )))

        val track = db.runDao().tracks(next).single()
        assertEquals(case13, planned(track))
        val surviving = SetJson.decodeSets(track.setsJson)
        assertEquals(listOf(1, 2, 3, 4), surviving.map { it.plannedIndex })
        assertEquals(260.0, surviving.single { it.plannedIndex == 2 }.weightLb)
        assertEquals(RunStartResult.Started(next), repo.startRun(plan, "A"))
        asPlanned(track, plan)

        assertEquals(squat(), rows(plan, s))
        assertEquals(SlotState(exposures = 1), rule(s).state)
        assertNull(offer(plan, "bb_back_squat"))
    }

    @Test fun `a pre-start extra set has no planned identity`() = runTest {
        val plan = rPlan()
        val s = id(plan, "bb_back_squat")
        history(plan, 16, sixteen)
        val next = db.programDao().day(plan, "A")!!.nextRunId
        val add = TrackEdit(s, Slot.MAIN) { rows -> SetEditor.addSet(rows) }

        assertEquals(RunWriteResult.InRun(next, false), repo.applyToRun(next, plan, "A", listOf(add)))

        val track = db.runDao().tracks(next).single()
        assertEquals(case13, planned(track))
        assertEquals(listOf(0, 1, 2, 3, 4, null), SetJson.decodeSets(track.setsJson).map { it.plannedIndex })
        assertEquals(RunWriteResult.InRun(next, false), repo.applyToRun(next, plan, "A", listOf(add)))
        assertEquals(listOf(0, 1, 2, 3, 4, null, null),
            SetJson.decodeSets(db.runDao().tracks(next).single().setsJson).map { it.plannedIndex })
    }

    @Test fun `Start clears pending Finish Undo before writing a seed's calibrating flag`() = runTest {
        val plan = converted(day("bb_bench"))
        val b = id(plan, "bb_bench")
        lifter(plan, b, work(3, 120.0, 10), rule(SlotRole.MAIN, 10))
        val session = finish(plan, b, 120.0, listOf(10, 10, 10))
        val before = rule(b)
        val undo = assertNotNull(ProgressionUndoJson.decode(db.sessionDao().sessionById(session)!!.progressionUndoJson))
        assertEquals(db.programDao().exercise(b)!!.ruleJson, undo.rules.single().afterJson)
        assertFalse(before.state.calibrating)
        db.programDao().deleteLogForSlot(plan, "A", b, Slot.MAIN)

        start(plan)

        assertEquals(work(3, 120.0, 10), rows(plan, b))
        assertEquals(before.copy(state = before.state.copy(calibrating = true)), rule(b))
        assertNull(db.sessionDao().sessionById(session)!!.progressionUndoJson)
    }

    @Test fun `skipping a superset half before Start opens a draft through the layoff`() = runTest {
        val plan = rPlan()
        val s = id(plan, "bb_back_squat")
        repo.setSupersetPartner("A", s, "cable_row")
        history(plan, 16, sixteen)
        val next = db.programDao().day(plan, "A")!!.nextRunId

        assertEquals(RunWriteResult.InRun(next, false),
            repo.skipLift(plan, "A", s, Slot.SS, "bb_back_squat", "cable_row"))

        assertTrue(assertNotNull(db.runDao().openRun()).draft)
        val tracks = db.runDao().tracks(next)
        val main = tracks.single { it.slot == Slot.MAIN }
        assertEquals(case13, planned(main))
        assertEquals(OpeningDto("SCALED", 0.95), OpeningJson.decode(main.openingJson))
        val partner = tracks.single { it.slot == Slot.SS }
        assertTrue(partner.retired)
        assertTrue(partner.dropped)
        assertEquals(work(5, 20.0, 10), planned(partner))
        assertNull(partner.openingJson)
    }

    @Test fun `a never-opened floor seed is written with its calibrating rule at Start and calibrates at Finish`() = runTest {
        val plan = converted(day("bb_bench"))
        db.planDao().updatePlan(db.planDao().plan(plan)!!.copy(autoApply = true))
        val b = id(plan, "bb_bench")
        repo.setStructure(b, SetStructure.RAMP_TOP)
        assertNull(db.programDao().logForSlot(plan, "A", b, Slot.MAIN))
        val expected = SlotRule.defaults(SlotRole.MAIN, LiftGoal.STRENGTH, SetStructure.RAMP_TOP, 5, MovementPattern.H_PUSH)
        assertEquals(expected, rule(b))

        val run = start(plan)

        assertEquals(floorRamp, rows(plan, b))
        assertEquals(expected.copy(state = SlotState(calibrating = true)), rule(b))
        val track = db.runDao().tracks(run).single()
        assertEquals(floorRamp, planned(track))
        assertNull(track.openingJson)
        repo.applyToRun(run, plan, "A", listOf(TrackEdit(b, Slot.MAIN) { rows ->
            rows.map { if (it.kind == SetKind.TOP) it.copy(weightLb = 135.0, done = true, rir = 2) else it.copy(done = true) }
        }))
        assertNotNull(repo.finishRun(run))

        assertEquals(listOf(
            LoggedSet(75.0, 5, SetKind.RAMP), LoggedSet(95.0, 5, SetKind.RAMP),
            LoggedSet(110.0, 5, SetKind.RAMP), LoggedSet(120.0, 3, SetKind.RAMP),
            LoggedSet(135.0, 5, SetKind.TOP),
        ), rows(plan, b))
        assertEquals(SlotState(exposures = 1), rule(b).state)
        assertTrue(assertNotNull(offer(plan, "bb_bench")).calibration)
    }

    @Test fun `a calibrating fitted partner with a stored row only counts the exposure in Ask`() = runTest {
        heldPartner(false)
    }

    @Test fun `a calibrating fitted partner with a stored row only counts the exposure in Automatic`() = runTest {
        heldPartner(true)
    }

    @Test fun `a fitted partner with no done set keeps its rule`() = runTest {
        heldPartner(false, partnerDone = false)
    }

    private suspend fun heldPartner(automatic: Boolean, partnerDone: Boolean = true) {
        val plan = converted(day("bb_back_squat"))
        db.planDao().updatePlan(db.planDao().plan(plan)!!.copy(autoApply = automatic))
        val s = id(plan, "bb_back_squat")
        lifter(plan, s, squat(), squatRule())
        repo.setSupersetPartner("A", s, "cable_row")
        val beforeLog = assertNotNull(db.programDao().logForSlot(plan, "A", s, Slot.SS))
        assertEquals(work(5, 20.0, 10), SetJson.decodeSets(beforeLog.setsJson))
        val beforeRule = db.programDao().exercise(s)!!.partnerRuleJson
        assertEquals(rule(SlotRole.ACCESSORY, 10, calibrating = true), SlotRuleJson.decode(beforeRule))
        history(plan, 30, eight)
        val run = start(plan)
        val tracks = db.runDao().tracks(run)
        val main = tracks.single { it.slot == Slot.MAIN }
        assertEquals("""{"layoff":"SCALED","factor":0.85}""", main.openingJson)
        assertEquals(4, planned(main).size)
        val partner = tracks.single { it.slot == Slot.SS }
        assertEquals("""{"layoff":"HOLD"}""", partner.openingJson)
        assertEquals(work(4, 20.0, 10), planned(partner))
        repo.applyToRun(run, plan, "A", tracks.filter { it.slot == Slot.MAIN || partnerDone }.map { track ->
            TrackEdit(s, track.slot) { rows -> rows.map { it.copy(done = true) } }
        })
        val session = assertNotNull(repo.finishRun(run))

        assertEquals(beforeLog, db.programDao().logForSlot(plan, "A", s, Slot.SS))
        assertEquals(squat(), rows(plan, s))
        assertNull(repo.history.slotSessionsById(plan, "A").getValue(s to Slot.SS).offer)
        if (partnerDone) {
            assertEquals(rule(SlotRole.ACCESSORY, 10).copy(state = SlotState(calibrating = true, exposures = 1)),
                SlotRuleJson.decode(db.programDao().exercise(s)!!.partnerRuleJson))
        } else {
            assertEquals(beforeRule, db.programDao().exercise(s)!!.partnerRuleJson)
            val undo = assertNotNull(ProgressionUndoJson.decode(db.sessionDao().sessionById(session)!!.progressionUndoJson))
            assertFalse(undo.rules.any { it.programExerciseId == s && it.slot == Slot.SS })
        }
    }

    private suspend fun editDraft(plan: Long, s: Long): String {
        val next = assertNotNull(db.programDao().day(plan, "A")).nextRunId
        assertEquals(RunWriteResult.InRun(next, false), repo.applyToRun(next, plan, "A", listOf(
            TrackEdit(s, Slot.MAIN) { SetEditor.edit(it, 4, SetEdit(reps = 4)) },
        )))
        return next
    }

    @Test fun `Start promotes the draft without reopening it`() = runTest {
        val plan = rPlan()
        val s = id(plan, "bb_back_squat")
        history(plan, 16, sixteen)
        val beforeLog = log(plan, s)
        val beforeSlot = db.programDao().exercise(s)
        val next = editDraft(plan, s)
        val tracks = db.runDao().tracks(next)
        advanceDays(1)
        assertEquals(RunStartResult.Started(next), repo.startRun(plan, "A"))
        val run = assertNotNull(db.runDao().openRun())
        assertFalse(run.draft)
        assertEquals(1794312000000, run.startedAt)
        assertEquals(tracks, db.runDao().tracks(next))
        assertEquals(beforeLog, log(plan, s))
        assertEquals(beforeSlot, db.programDao().exercise(s))
    }

    @Test fun `a tick on a draft starts it in the same write`() = runTest {
        val plan = rPlan()
        val s = id(plan, "bb_back_squat")
        history(plan, 16, sixteen)
        val next = editDraft(plan, s)
        assertEquals(RunWriteResult.InRun(next, true), repo.applyToRun(next, plan, "A", listOf(
            TrackEdit(s, Slot.MAIN) { SetEditor.edit(it, 0, SetEdit(done = true)) },
        )))
        assertFalse(assertNotNull(db.runDao().openRun()).draft)
        val sets = SetJson.decodeSets(db.runDao().tracks(next).single().setsJson)
        assertTrue(sets[0].done)
        assertEquals(4, sets[4].reps)
    }

    @Test fun `an edit that changes nothing opens no draft`() = runTest {
        val plan = rPlan()
        val s = id(plan, "bb_back_squat")
        history(plan, 16, sixteen)
        val next = assertNotNull(db.programDao().day(plan, "A")).nextRunId
        val before = db.programDao().allLogs()
        for (edit in listOf(TrackEdit(s, Slot.MAIN) { it }, TrackEdit(s, Slot.MAIN) { null })) {
            assertEquals(RunWriteResult.Unchanged, repo.applyToRun(next, plan, "A", listOf(edit)))
            assertNull(db.runDao().openRun())
            assertEquals(before, db.programDao().allLogs())
        }
    }

    @Test fun `a draft refuses Finish and the old watch path, and Discard drops it`() = runTest {
        val plan = rPlan()
        val s = id(plan, "bb_back_squat")
        history(plan, 16, sixteen)
        val beforeLog = log(plan, s)
        val beforeSlot = db.programDao().exercise(s)
        val next = editDraft(plan, s)
        assertNull(repo.finishRunCapturing(next))
        assertTrue(assertNotNull(db.runDao().openRun()).draft)
        val tick = listOf(TrackEdit(s, Slot.MAIN) { SetEditor.edit(it, 0, SetEdit(done = true)) })
        assertEquals(RunWriteResult.Rejected, repo.applyToOpenRun(plan, "A", 2000, tick))
        assertTrue(repo.discardRun(next))
        assertNull(db.runDao().openRun())
        assertNotEquals(next, db.programDao().day(plan, "A")!!.nextRunId)
        assertEquals(beforeLog, log(plan, s))
        assertEquals(beforeSlot, db.programDao().exercise(s))
        assertEquals(RunWriteResult.Rejected, repo.applyToRun(next, plan, "A", tick))
    }

    @Test fun `a draft blocks edits and starts on another day`() = runTest {
        val plan = rPlan()
        val s = id(plan, "bb_back_squat")
        history(plan, 16, sixteen)
        val next = editDraft(plan, s)
        val b = id(plan, "bb_bench", "B")
        val before = db.programDao().logsForDay(plan, "B")
        assertEquals(RunWriteResult.Rejected, repo.applyToRun(db.programDao().day(plan, "B")!!.nextRunId, plan, "B", listOf(
            TrackEdit(b, Slot.MAIN, createsMissing = true) { SetEditor.edit(it, 0, SetEdit(reps = 4)) },
        )))
        assertEquals(RunStartResult.OtherRunOpen(next, plan, "A"), repo.startRun(plan, "B"))
        assertEquals(before, db.programDao().logsForDay(plan, "B"))
    }

    private suspend fun finishedBench(): Triple<Long, Long, Long> {
        val plan = converted(day("bb_bench"))
        db.planDao().updatePlan(db.planDao().plan(plan)!!.copy(autoApply = true))
        val b = id(plan, "bb_bench")
        lifter(plan, b, work(3, 120.0, 10), rule(SlotRole.MAIN, 10))
        return Triple(plan, b, finish(plan, b, 120.0, listOf(10, 10, 10)))
    }

    private suspend fun benchDraft(plan: Long, b: Long): String {
        val next = db.programDao().day(plan, "A")!!.nextRunId
        assertEquals(RunWriteResult.InRun(next, false), repo.applyToRun(next, plan, "A", listOf(
            TrackEdit(b, Slot.MAIN) { SetEditor.edit(it, 0, SetEdit(reps = 9)) },
        )))
        return next
    }

    @Test fun `a same-day draft refuses the last Finish's Undo until it is discarded`() = runTest {
        val beforeRows = work(3, 120.0, 10)
        val beforeRule = rule(SlotRole.MAIN, 10)
        val (plan, b, session) = finishedBench()
        val afterRows = rows(plan, b)
        val afterRule = rule(b)
        assertNotEquals(beforeRows, afterRows)
        assertNotEquals(beforeRule, afterRule)
        val next = benchDraft(plan, b)
        assertFalse(repo.undoProgression(session))
        assertEquals(afterRows, rows(plan, b))
        assertEquals(afterRule, rule(b))
        assertNotNull(db.sessionDao().sessionById(session)!!.progressionUndoJson)
        assertTrue(repo.discardRun(next))
        assertTrue(repo.undoProgression(session))
        assertEquals(beforeRows, rows(plan, b))
        assertEquals(beforeRule, rule(b))
    }

    @Test fun `Start clears the last Finish's Undo`() = runTest {
        val (plan, b, session) = finishedBench()
        val next = benchDraft(plan, b)
        assertEquals(RunStartResult.Started(next), repo.startRun(plan, "A"))
        assertNull(db.sessionDao().sessionById(session)!!.progressionUndoJson)
        assertFalse(repo.undoProgression(session))
    }

    @Test fun `a watch swap before Start opens a draft and a refused swap leaves none`() = runTest {
        val plan = rPlan()
        val s = id(plan, "bb_back_squat")
        val logs = db.programDao().allLogs()
        val next = db.programDao().day(plan, "A")!!.nextRunId
        assertEquals(ExerciseSwapResult.CHANGED, repo.applyExerciseSwap(plan, "A", s, "hack_squat", next, allowChange = true))
        assertTrue(assertNotNull(db.runDao().openRun()).draft)
        assertEquals(logs, db.programDao().allLogs())
        repo.discardRun(next)
        val n2 = db.programDao().day(plan, "A")!!.nextRunId
        assertFalse(repo.swapInRun(n2, s, Slot.MAIN, "hack_squat", expectedExerciseId = "nope"))
        assertNull(db.runDao().openRun())
    }
}
