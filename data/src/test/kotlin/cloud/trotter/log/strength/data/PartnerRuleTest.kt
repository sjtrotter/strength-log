package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.serialization.OpeningDto
import cloud.trotter.log.strength.data.serialization.OpeningJson
import cloud.trotter.log.strength.data.serialization.ProgressionUndoJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.SlotState
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
class PartnerRuleTest : OpeningFixture() {
    private suspend fun partner(plan: Long, id: Long, rows: List<LoggedSet> = work(w = 120.0, r = 10),
                                rule: SlotRule = rule(SlotRole.ACCESSORY, 10), day: String = "A") {
        db.programDao().upsertLog(ExerciseLogEntity(plan, day, id, Slot.SS, SetJson.encodeSets(rows), 1000))
        db.programDao().setPartnerRule(id, SlotRuleJson.encode(rule))
    }
    private suspend fun ssRows(plan: Long, id: Long, day: String = "A") =
        SetJson.decodeSets(assertNotNull(db.programDao().logForSlot(plan, day, id, Slot.SS)).setsJson)
    private suspend fun partnerRule(id: Long) = SlotRuleJson.decode(db.programDao().exercise(id)!!.partnerRuleJson)
    private suspend fun ssOffer(plan: Long, id: Long, day: String = "A") =
        repo.history.slotSessionsById(plan, day).getValue(id to Slot.SS).offer
    private suspend fun finishBoth(plan: Long, id: Long, mainMiss: Boolean = false,
                                   partnerMiss: Boolean = false, day: String = "A"): Long {
        val run = db.runDao().openRun()?.takeIf { it.planId == plan && it.dayId == day }?.id ?: start(plan, day)
        repo.applyToRun(run, plan, day, listOf(Slot.MAIN to mainMiss, Slot.SS to partnerMiss).map { (name, miss) ->
            TrackEdit(id, name) { rows -> rows.mapIndexed { i, row ->
                row.copy(done = true, reps = row.reps - if (miss && i == rows.lastIndex) 1 else 0)
            } }
        })
        return assertNotNull(repo.finishRun(run))
    }

    @Test fun `a partner rule follows add, swap and remove of the partner and survives a main swap and Plan mode`() = runTest {
        val plan = authored()
        val b = add(plan, "bb_bench")
        lifter(plan, b)
        val mainRule = db.programDao().exercise(b)!!.ruleJson
        repo.setSupersetPartner("A", b, "cable_row")
        assertEquals(work(3, 20.0, 10), ssRows(plan, b))
        assertEquals(rule(SlotRole.ACCESSORY, 10, calibrating = true), partnerRule(b))
        assertEquals(mainRule, db.programDao().exercise(b)!!.ruleJson)
        val stored = db.programDao().exercise(b)!!.partnerRuleJson
        repo.swapExerciseById("A", b, "db_bench")
        assertEquals(stored, db.programDao().exercise(b)!!.partnerRuleJson)
        assertNull(db.programDao().logForSlot(plan, "A", b, Slot.SS))
        assertNotNull(db.programDao().exercise(b)!!.ruleJson)
        repo.keepToPlanFixture(db, b)
        assertEquals(stored, db.programDao().exercise(b)!!.partnerRuleJson)
        repo.setKeepToPlan(b, false)
        assertEquals(stored, db.programDao().exercise(b)!!.partnerRuleJson)
        repo.setSupersetPartner("A", b, "db_row")
        assertEquals(rule(SlotRole.ACCESSORY, 10, calibrating = true), partnerRule(b))
        assertEquals(work(3, 10.0, 10), ssRows(plan, b))
        repo.removeSupersetPartner("A", b)
        assertNull(db.programDao().exercise(b)!!.partnerRuleJson)
        assertNull(db.programDao().logForSlot(plan, "A", b, Slot.SS))
    }

    @Test fun `conversion rules partners from their stored rows and writes no rows`() = runTest {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Day A", "", listOf(
            ProgramExercise("bb_bench", targetSets = 3, superset = SupersetPartner("cable_row")),
            ProgramExercise("ohp", targetSets = 3, superset = SupersetPartner("pushup")),
            ProgramExercise("ez_curl", targetSets = 3, superset = SupersetPartner("custom_gone")),
        )))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val b = id(plan, "bb_bench")
        val p = id(plan, "ohp")
        val c = id(plan, "ez_curl")
        val logs = listOf(ExerciseLogEntity(plan, "A", b, Slot.SS, SetJson.encodeSets(work(3, 120.0, 12)), 1000),
            ExerciseLogEntity(plan, "A", c, Slot.SS, SetJson.encodeSets(work(3, 30.0, 12)), 1000))
        logs.forEach { db.programDao().upsertLog(it) }
        repo.plans.convertPlans()
        assertEquals(rule(SlotRole.ACCESSORY, 12), partnerRule(b))
        assertEquals(rule(SlotRole.ACCESSORY, 15), partnerRule(p))
        assertEquals(rule(SlotRole.ACCESSORY, 12), partnerRule(c))
        assertNull(db.programDao().logForSlot(plan, "A", p, Slot.SS))
        logs.forEach { assertEquals(it, db.programDao().logForSlot(plan, "A", it.programExerciseId, Slot.SS)) }
        val before = db.programDao().exercisesForPlan(plan).map { it.partnerRuleJson }
        repo.plans.convertPlans()
        assertEquals(before, db.programDao().exercisesForPlan(plan).map { it.partnerRuleJson })
        assertTrue(db.programDao().unruledPartners().isEmpty())
    }

    @Test fun `an Ask partner holds its rows and offers 120 to 130`() = runTest {
        val plan = converted(day("bb_bench"))
        val b = id(plan, "bb_bench")
        lifter(plan, b)
        repo.setSupersetPartner("A", b, "cable_row")
        partner(plan, b)
        finishBoth(plan, b)
        assertEquals(work(3, 120.0, 10), ssRows(plan, b))
        assertEquals(SlotState(successStreak = 1, exposures = 1), partnerRule(b)!!.state)
        assertEquals(NextOffer(120.0, 130.0), ssOffer(plan, b))
        assertEquals(NextOffer(185.0, 190.0), offer(plan, "bb_bench"))
        val next = db.programDao().day(plan, "A")!!.nextRunId
        assertEquals(RunWriteResult.WorkingValues, repo.setWorkingWeight(next, plan, "A", b, Slot.SS, 130.0))
        assertEquals(work(3, 130.0, 10), ssRows(plan, b))
        assertEquals(RepTarget.Fixed(10), partnerRule(b)!!.target)
    }

    @Test fun `accepting an equal-weight Ask partner calibration changes only the partner rule`() = runTest {
        val plan = converted(day("bb_bench"))
        val b = id(plan, "bb_bench")
        lifter(plan, b)
        repo.setSupersetPartner("A", b, "cable_row")
        partner(plan, b, rule = rule(SlotRole.ACCESSORY, 10, calibrating = true))
        finishBoth(plan, b)
        assertEquals(NextOffer(120.0, 120.0, calibration = true), ssOffer(plan, b))
        assertTrue(partnerRule(b)!!.state.calibrating)
        val mainRule = db.programDao().exercise(b)!!.ruleJson
        val next = db.programDao().day(plan, "A")!!.nextRunId
        assertEquals(RunWriteResult.WorkingValues, repo.setWorkingWeight(next, plan, "A", b, Slot.SS, 120.0))
        assertFalse(partnerRule(b)!!.state.calibrating)
        assertEquals(mainRule, db.programDao().exercise(b)!!.ruleJson)
    }

    @Test fun `an Automatic partner applies the increase and opens at 130 next time`() = runTest {
        val plan = authored()
        val b = add(plan, "bb_bench")
        lifter(plan, b)
        repo.setSupersetPartner("A", b, "cable_row")
        partner(plan, b)
        finishBoth(plan, b)
        assertEquals(work(3, 130.0, 10), ssRows(plan, b))
        assertEquals(NextOffer(120.0, 130.0), ssOffer(plan, b))
        val track = db.runDao().tracks(start(plan)).single { it.slot == Slot.SS }
        assertEquals(work(3, 130.0, 10), planned(track))
        assertNull(track.openingJson)
    }

    @Test fun `an unreadable partner rule carries forward and stays`() = runTest {
        val plan = converted(day("bb_bench"))
        val b = id(plan, "bb_bench")
        lifter(plan, b)
        repo.setSupersetPartner("A", b, "cable_row")
        partner(plan, b)
        db.programDao().setPartnerRule(b, "junk")
        finishBoth(plan, b)
        assertEquals(work(3, 120.0, 10), ssRows(plan, b))
        assertEquals("junk", db.programDao().exercise(b)!!.partnerRuleJson)
        assertNull(ssOffer(plan, b))
    }

    @Test fun `credit crosses between a partner and a main slot of the same exercise and rep target`() = runTest {
        val plan = converted(day("bb_bench"), day("cable_row", id = "B"), day("cable_row", id = "C"))
        val a = id(plan, "bb_bench")
        val b = id(plan, "cable_row", "B")
        val c = id(plan, "cable_row", "C")
        lifter(plan, a)
        repo.setSupersetPartner("A", a, "cable_row")
        partner(plan, a)
        lifter(plan, b, work(w = 120.0, r = 10), rule(SlotRole.ACCESSORY, 10).copy(everyExposures = 2), "B")
        lifter(plan, c, work(w = 120.0, r = 12), rule(SlotRole.ACCESSORY, 12), "C")
        val cRule = db.programDao().exercise(c)!!.ruleJson
        finishBoth(plan, a)
        assertEquals(1, rule(b).state.successStreak)
        assertFalse(rule(b).state.pendingIncrease)
        assertEquals(work(3, 120.0, 10), rows(plan, b, "B"))
        assertEquals(cRule, db.programDao().exercise(c)!!.ruleJson)
        finish(plan, b, 120.0, listOf(10, 10, 10), "B")
        assertEquals(NextOffer(120.0, 130.0), offer(plan, "cable_row", "B"))
        assertEquals(2, partnerRule(a)!!.state.successStreak)
    }

    @Test fun `undo restores a partner rule and its rows after a restart`() = runTest {
        val plan = authored()
        val b = add(plan, "bb_bench")
        lifter(plan, b)
        repo.setSupersetPartner("A", b, "cable_row")
        partner(plan, b)
        val before = db.programDao().exercise(b)
        val main = log(plan, b)
        val ss = db.programDao().logForSlot(plan, "A", b, Slot.SS)
        val session = finishBoth(plan, b)
        assertEquals(work(3, 190.0, 5), rows(plan, b))
        assertEquals(work(3, 130.0, 10), ssRows(plan, b))
        assertTrue("\"slot\":\"ss\"" in db.sessionDao().sessionById(session)!!.progressionUndoJson!!)
        restart()
        assertEquals(UndoResult.Undone, repo.undoFinish(session, null))
        assertEquals(before, db.programDao().exercise(b))
        assertEquals(main, log(plan, b))
        assertEquals(ss, db.programDao().logForSlot(plan, "A", b, Slot.SS))
        assertEquals(UndoResult.Nothing, repo.undoFinish(session, null))
    }

    @Test fun `a superset opens both tracks light with equal row counts`() = runTest {
        val plan = converted(day("bb_back_squat"))
        val s = id(plan, "bb_back_squat")
        lifter(plan, s, squat(), squatRule())
        repo.setSupersetPartner("A", s, "cable_row")
        partner(plan, s, work(5, 120.0, 10))
        db.planDao().updateLightCycle(plan, 2, 0)
        val run = start(plan)
        val tracks = db.runDao().tracks(run)
        assertEquals(listOf(LoggedSet(165.0, 5, SetKind.RAMP), LoggedSet(220.0, 3, SetKind.RAMP),
            LoggedSet(250.0, 5, SetKind.TOP), LoggedSet(225.0, 5, SetKind.BACKOFF)),
            planned(tracks.single { it.slot == Slot.MAIN }))
        assertEquals(work(4, 110.0, 10), planned(tracks.single { it.slot == Slot.SS }))
        for (track in tracks) {
            assertEquals("""{"light":true}""", track.openingJson)
            assertEquals((0..3).toList(), SetJson.decodeSets(track.setsJson).map { it.plannedIndex })
        }
        finishBoth(plan, s)
        assertEquals(squat(), rows(plan, s))
        assertEquals(work(5, 120.0, 10), ssRows(plan, s))
        assertNull(offer(plan, "bb_back_squat"))
        assertNull(ssOffer(plan, s))
    }

    @Test fun `a partner whose stored row is deleted mid-run records exposure without storing rows`() = runTest {
        val plan = converted(ProgramDay("A", "Day A", "", listOf(
            ProgramExercise("bb_back_squat", targetSets = 3, superset = SupersetPartner("cable_row")),
        )))
        val s = id(plan, "bb_back_squat")
        lifter(plan, s, squat(), squatRule())
        assertNull(db.programDao().logForSlot(plan, "A", s, Slot.SS))
        history(plan, 30, eight)
        val tracks = db.runDao().tracks(start(plan))
        db.programDao().deleteLogForSlot(plan, "A", s, Slot.SS)
        val beforeRule = db.programDao().exercise(s)!!.partnerRuleJson
        val exposures = partnerRule(s)!!.state.exposures
        val main = tracks.single { it.slot == Slot.MAIN }
        val ss = tracks.single { it.slot == Slot.SS }
        assertEquals(4, planned(main).size)
        assertEquals("""{"layoff":"SCALED","factor":0.85}""", main.openingJson)
        assertEquals(planned(main).size, planned(ss).size)
        assertEquals("""{"layoff":"HOLD"}""", ss.openingJson)
        val session = finishBoth(plan, s)
        assertNull(db.programDao().logForSlot(plan, "A", s, Slot.SS))
        assertEquals(exposures + 1, partnerRule(s)!!.state.exposures)
        val undo = assertNotNull(ProgressionUndoJson.decode(db.sessionDao().sessionById(session)!!.progressionUndoJson))
        assertTrue(undo.logs.none { it.programExerciseId == s && it.slot == Slot.SS })
        assertEquals(beforeRule, undo.rules.single { it.programExerciseId == s && it.slot == Slot.SS }.beforeJson)
    }

    @Test fun `a never-stored calibrating partner fitted to its main's layoff only counts exposure in Automatic`() = runTest {
        fittedLazyCalibration(true)
    }

    @Test fun `a never-stored calibrating partner fitted to its main's layoff only counts exposure in Ask`() = runTest {
        fittedLazyCalibration(false)
    }

    private suspend fun fittedLazyCalibration(automatic: Boolean) {
        val plan = converted(ProgramDay("A", "Day A", "", listOf(
            ProgramExercise("bb_back_squat", targetSets = 3, superset = SupersetPartner("cable_row")),
        )))
        db.planDao().updatePlan(db.planDao().plan(plan)!!.copy(autoApply = automatic))
        val s = id(plan, "bb_back_squat")
        val mainRule = squatRule()
        lifter(plan, s, squat(), mainRule)
        val storedRule = rule(SlotRole.ACCESSORY, 10).copy(
            state = SlotState(successStreak = 1, exposures = 3, calibrating = true))
        val beforeRule = SlotRuleJson.encode(storedRule)
        db.programDao().setPartnerRule(s, beforeRule)
        assertNull(db.programDao().logForSlot(plan, "A", s, Slot.SS))
        history(plan, 30, eight)
        val run = start(plan)
        db.programDao().deleteLogForSlot(plan, "A", s, Slot.SS)
        val tracks = db.runDao().tracks(run)
        val main = tracks.single { it.slot == Slot.MAIN }
        val ss = tracks.single { it.slot == Slot.SS }
        assertEquals(4, planned(main).size)
        assertEquals("""{"layoff":"SCALED","factor":0.85}""", main.openingJson)
        assertEquals(planned(main).size, planned(ss).size)
        assertEquals("""{"layoff":"HOLD"}""", ss.openingJson)
        // Pin the run's planned and actual loads without creating a stored SS row.
        val partnerRows = work(planned(ss).size, 120.0, 10)
        db.runDao().updateTrack(ss.copy(plannedJson = SetJson.encodeSets(partnerRows),
            setsJson = SetJson.encodeSets(partnerRows.mapIndexed { i, row -> row.copy(plannedIndex = i) })))
        val session = finishBoth(plan, s)
        assertNull(db.programDao().logForSlot(plan, "A", s, Slot.SS))
        assertEquals(storedRule.copy(state = storedRule.state.copy(exposures = storedRule.state.exposures + 1)), partnerRule(s))
        assertNull(ssOffer(plan, s))
        assertEquals(squat(), rows(plan, s))
        assertEquals(mainRule.copy(state = mainRule.state.copy(exposures = mainRule.state.exposures + 1)), rule(s))
        assertNull(offer(plan, "bb_back_squat"))
        val undo = assertNotNull(ProgressionUndoJson.decode(db.sessionDao().sessionById(session)!!.progressionUndoJson))
        assertTrue(undo.logs.none { it.programExerciseId == s && it.slot == Slot.SS })
        val undoRule = undo.rules.single { it.programExerciseId == s && it.slot == Slot.SS }
        assertEquals(beforeRule, undoRule.beforeJson)
        assertEquals(db.programDao().exercise(s)!!.partnerRuleJson, undoRule.afterJson)
    }

    @Test fun `reseed after Finish preserves fitted lazy partner and main rules for Undo in Ask`() = runTest {
        reseedForUndo(false)
    }

    @Test fun `reseed after Finish preserves fitted lazy partner and main rules for Undo in Automatic`() = runTest {
        reseedForUndo(true)
    }

    private suspend fun reseedForUndo(automatic: Boolean) {
        val plan = converted(ProgramDay("A", "Day A", "", listOf(
            ProgramExercise("bb_back_squat", targetSets = 3, superset = SupersetPartner("cable_row")),
        )))
        db.planDao().updatePlan(db.planDao().plan(plan)!!.copy(autoApply = automatic))
        val s = id(plan, "bb_back_squat")
        lifter(plan, s, squat(), squatRule())
        val storedRule = rule(SlotRole.ACCESSORY, 8).copy(
            state = SlotState(successStreak = 1, exposures = 3, calibrating = true))
        db.programDao().setPartnerRule(s, SlotRuleJson.encode(storedRule))
        val before = assertNotNull(db.programDao().exercise(s))
        assertNull(db.programDao().logForSlot(plan, "A", s, Slot.SS))
        history(plan, 30, eight)
        val run = start(plan)
        db.programDao().deleteLogForSlot(plan, "A", s, Slot.SS)
        val tracks = db.runDao().tracks(run)
        val main = tracks.single { it.slot == Slot.MAIN }
        val ss = tracks.single { it.slot == Slot.SS }
        assertEquals(4, planned(main).size)
        assertEquals("""{"layoff":"SCALED","factor":0.85}""", main.openingJson)
        assertEquals(planned(main).size, planned(ss).size)
        assertEquals("""{"layoff":"HOLD"}""", ss.openingJson)
        val partnerRows = work(planned(ss).size, 120.0, 8)
        db.runDao().updateTrack(ss.copy(plannedJson = SetJson.encodeSets(partnerRows),
            setsJson = SetJson.encodeSets(partnerRows.mapIndexed { i, row -> row.copy(plannedIndex = i) })))

        val session = finishBoth(plan, s)
        assertNull(db.programDao().logForSlot(plan, "A", s, Slot.SS))
        assertEquals(storedRule.copy(state = storedRule.state.copy(exposures = 4)), partnerRule(s))
        val after = assertNotNull(db.programDao().exercise(s))
        val undo = assertNotNull(ProgressionUndoJson.decode(db.sessionDao().sessionById(session)!!.progressionUndoJson))
        assertTrue(undo.logs.none { it.programExerciseId == s && it.slot == Slot.SS })

        repo.ensureSeeded(plan, "A")

        val seeded = assertNotNull(db.programDao().logForSlot(plan, "A", s, Slot.SS))
        assertEquals(work(squat().size, 120.0, 8), SetJson.decodeSets(seeded.setsJson))
        assertEquals(after.partnerRuleJson, db.programDao().exercise(s)!!.partnerRuleJson)
        assertEquals(after.ruleJson, db.programDao().exercise(s)!!.ruleJson)
        assertEquals(UndoResult.Undone, repo.undoFinish(session, null))
        assertEquals(before.partnerRuleJson, db.programDao().exercise(s)!!.partnerRuleJson)
        assertEquals(before.ruleJson, db.programDao().exercise(s)!!.ruleJson)
        assertEquals(seeded, db.programDao().logForSlot(plan, "A", s, Slot.SS))
    }

    @Test fun `reseed after a structure edit replaces the default rule despite pending Finish Undo`() = runTest {
        val plan = converted(day("bb_bench"))
        val b = id(plan, "bb_bench")
        lifter(plan, b, work(3, 120.0, 10), rule(SlotRole.MAIN, 10))
        val run = start(plan)
        repo.applyToRun(run, plan, "A", listOf(TrackEdit(b, Slot.MAIN) { rows ->
            rows.map { it.copy(done = true, rir = 2) }
        }))
        val session = assertNotNull(repo.finishRun(run))
        val undo = assertNotNull(ProgressionUndoJson.decode(db.sessionDao().sessionById(session)!!.progressionUndoJson))
        val undoRule = undo.rules.single { it.programExerciseId == b && it.slot == Slot.MAIN }
        assertEquals(undoRule.afterJson, db.programDao().exercise(b)!!.ruleJson)

        repo.setStructure(b, SetStructure.RAMP_TOP)

        val editedRule = rule(b)
        assertFalse(editedRule.state.calibrating)
        assertTrue(db.programDao().exercise(b)!!.ruleJson != undoRule.afterJson)
        assertNull(db.programDao().logForSlot(plan, "A", b, Slot.MAIN))

        repo.ensureSeeded(plan, "A")

        assertTrue(rule(b).state.calibrating)
        assertEquals(editedRule.copy(state = editedRule.state.copy(calibrating = true)), rule(b))
        assertEquals(listOf(
            LoggedSet(65.0, 5, SetKind.RAMP), LoggedSet(85.0, 5, SetKind.RAMP),
            LoggedSet(95.0, 5, SetKind.RAMP), LoggedSet(110.0, 3, SetKind.RAMP),
            LoggedSet(120.0, 5, SetKind.TOP),
        ), rows(plan, b))
        val seededRule = db.programDao().exercise(b)!!.ruleJson
        val seeded = log(plan, b)
        assertEquals(UndoResult.Undone, repo.undoFinish(session, null))
        assertEquals(seededRule, db.programDao().exercise(b)!!.ruleJson)
        assertEquals(seeded, log(plan, b))
    }

    @Test fun `a partner fitted to its main's layoff keeps its stored row count`() = runTest {
        val plan = authored()
        val s = add(plan, "bb_back_squat")
        lifter(plan, s, squat(), squatRule())
        repo.setSupersetPartner("A", s, "cable_row")
        partner(plan, s, work(5, 120.0, 10))
        val streak = partnerRule(s)!!.state.successStreak
        history(plan, 30, eight)
        val tracks = db.runDao().tracks(start(plan))
        val main = tracks.single { it.slot == Slot.MAIN }
        assertEquals("""{"layoff":"SCALED","factor":0.85}""", main.openingJson)
        assertEquals(4, planned(main).size)
        assertEquals(235.0, top(main))
        val ss = tracks.single { it.slot == Slot.SS }
        assertEquals(OpeningDto(OpeningDto.HOLD), OpeningJson.decode(ss.openingJson))
        assertEquals(work(4, 120.0, 10), planned(ss))
        finishBoth(plan, s)
        assertEquals(squat(), rows(plan, s))
        assertEquals(work(5, 120.0, 10), ssRows(plan, s))
        assertEquals(streak, partnerRule(s)!!.state.successStreak)
        assertNull(ssOffer(plan, s))
        assertTrue(repo.history.slotSessionsById(plan, "A").getValue(s to Slot.SS).opened)
    }

    @Test fun `a partner stall counts toward the reactive light cycle`() = runTest {
        val plan = authored()
        val b = add(plan, "bb_bench")
        lifter(plan, b)
        repo.setSupersetPartner("A", b, "cable_row")
        partner(plan, b)
        finishBoth(plan, b, mainMiss = true, partnerMiss = true)
        advanceDays(1)
        finishBoth(plan, b, mainMiss = true, partnerMiss = true)
        assertEquals(work(3, 175.0, 5), rows(plan, b))
        assertEquals(work(3, 110.0, 10), ssRows(plan, b))
        assertEquals(listOf(LocalDate.of(2026, 11, 10).toEpochDay()), partnerRule(b)!!.state.resetEpochDays)
        assertEquals(1, db.planDao().plan(plan)!!.lightCycleRemaining)
    }
}
