package cloud.trotter.log.strength.data

import cloud.trotter.log.strength.data.db.entity.RunTrackEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.serialization.OpeningDto
import cloud.trotter.log.strength.data.serialization.OpeningJson
import cloud.trotter.log.strength.data.serialization.SessionPlanJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.model.Equipment
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramDayKind
import cloud.trotter.log.strength.domain.model.SetStructure
import cloud.trotter.log.strength.domain.progression.LoadSteps
import cloud.trotter.log.strength.domain.progression.Progression
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotState
import cloud.trotter.log.strength.domain.progression.WorkingRows
import cloud.trotter.log.strength.domain.seeding.SetSeeder
import cloud.trotter.log.strength.domain.units.WeightUnit
import java.time.Duration
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

abstract class OpeningFixture : FinishProgressionFixture() {
    override val fileBacked = true
    protected val sixteen = listOf(0L, 1, 3, 5, 7, 9, 10, 12, 14, 16, 17, 19, 21, 23, 25, 27)
    protected val eight = listOf(0L, 3, 7, 10, 14, 17, 21, 25)
    protected fun squat() = SetSeeder.shape(SetStructure.RAMP_TOP_BACKOFF, 275.0, 5, 1, WeightUnit.LB,
        listOf(Equipment.BARBELL, Equipment.RACK), Scheme.STRENGTH)
    protected fun squatRule() = rule(inc = 0.025, scheme = Scheme.STRENGTH)
    protected suspend fun rPlan() = converted(day("bb_back_squat"), day("bb_bench", id = "B"),
        day("bb_back_squat", id = "C"), day("ohp", id = "D")).also {
        lifter(it, id(it, "bb_back_squat"), squat(), squatRule())
    }
    protected suspend fun history(plan: Long, lastDaysAgo: Long, offsets: List<Long>,
                                  exercise: String = "bb_back_squat", day: String = "A") {
        for (offset in offsets) {
            val time = clock.millis() - Duration.ofDays(lastDaysAgo + offset).toMillis()
            val session = db.sessionDao().insertSession(WorkoutSessionEntity(0, day, "Day $day", time, time, null, planId = plan))
            if (offset == 0L) db.sessionDao().insertSets(listOf(SessionSetEntity(0, session, exercise, exercise,
                Slot.MAIN, 0, "TOP", 275.0, 5, true)))
        }
    }
    protected suspend fun opened(plan: Long, day: String = "A"): RunTrackEntity {
        val run = start(plan, day)
        return db.runDao().tracks(run).first { it.slot == Slot.MAIN && !it.retired }
    }
    protected suspend fun reopen(track: RunTrackEntity, plan: Long, days: Long): RunTrackEntity {
        repo.discardRun(track.runId)
        advanceDays(days)
        return opened(plan)
    }
    protected suspend fun asPlanned(track: RunTrackEntity, plan: Long, day: String = "A", miss: Boolean = false): Long {
        repo.applyToRun(track.runId, plan, day, listOf(TrackEdit(assertNotNull(track.programExerciseId), Slot.MAIN) { rows ->
            rows.mapIndexed { i, row -> row.copy(done = true, reps = if (miss && i == rows.lastIndex) 4 else row.reps) }
        }))
        return assertNotNull(repo.finishRun(track.runId))
    }
    protected fun planned(track: RunTrackEntity) = SetJson.decodeSets(track.plannedJson)
    protected fun top(track: RunTrackEntity) = WorkingRows.workingWeight(planned(track))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RunOpeningTest : OpeningFixture() {
    @Test fun `case 13 a sixteen day gap opens the squat at ninety five percent`() = runTest {
        val plan = rPlan()
        history(plan, 16, sixteen)
        val before = log(plan, id(plan, "bb_back_squat")).setsJson
        val track = opened(plan)
        val today = clock.instant().atZone(clock.zone).toLocalDate().toEpochDay()
        val ctx = Progression.Context(WeightUnit.LB, repo.catalog.snapshot().find("bb_back_squat")!!.equipment.toSet(),
            LoadSteps.defaults(WeightUnit.LB), today)
        assertEquals(260.0, top(track))
        assertEquals(Progression.atStart(squatRule(), WorkingRows.cleared(squat()), today - 16, 4, ctx).rows, planned(track))
        assertEquals(OpeningDto("SCALED", 0.95), OpeningJson.decode(track.openingJson))
        assertEquals(before, log(plan, id(plan, "bb_back_squat")).setsJson)
    }

    @Test fun `case 13 a normal rotation gap opens as stored and a nine day gap holds`() = runTest {
        val plan = rPlan()
        history(plan, 4, sixteen)
        var track = opened(plan)
        assertEquals(WorkingRows.cleared(squat()), planned(track))
        assertNull(track.openingJson)
        track = reopen(track, plan, 3)
        assertEquals(WorkingRows.cleared(squat()), planned(track))
        assertNull(track.openingJson)
        track = reopen(track, plan, 2)
        assertEquals(WorkingRows.cleared(squat()), planned(track))
        assertEquals("""{"layoff":"HOLD"}""", track.openingJson)
    }

    @Test fun `a nine day layoff Hold archives an opened session without an offer`() = runTest {
        val plan = rPlan()
        history(plan, 9, sixteen)
        val track = opened(plan)
        assertEquals(275.0, top(track))
        assertEquals(OpeningDto(OpeningDto.HOLD), OpeningJson.decode(track.openingJson))
        asPlanned(track, plan)
        val session = repo.history.slotSessionsById(plan, "A").getValue(id(plan, "bb_back_squat") to Slot.MAIN)
        assertTrue(session.opened)
        assertNull(session.offer)
    }

    // One plan per test: converted() reuses the selected plan, so a second pass would see the first pass's session.
    @Test fun `a recalibration opening archives a calibration offer in Automatic`() = runTest { recalibrationOffer(true) }

    @Test fun `a recalibration opening archives a calibration offer in Ask`() = runTest { recalibrationOffer(false) }

    private suspend fun recalibrationOffer(automatic: Boolean) {
        val plan = converted(day("bb_back_squat"))
        db.planDao().updatePlan(db.planDao().plan(plan)!!.copy(autoApply = automatic))
        val id = id(plan, "bb_back_squat")
        lifter(plan, id, squat(), squatRule())
        val beforeRuleJson = db.programDao().exercise(id)!!.ruleJson
        assertFalse(rule(id).state.calibrating)
        history(plan, 60, sixteen)
        val track = opened(plan)
        assertEquals(OpeningDto(OpeningDto.RECALIBRATE), OpeningJson.decode(track.openingJson))
        val session = asPlanned(track, plan)
        assertTrue(assertNotNull(offer(plan, "bb_back_squat")).calibration)
        val archived = SessionPlanJson.decode(db.sessionDao().sessionById(session)!!.plannedJson!!).single()
        assertEquals(beforeRuleJson, assertNotNull(archived.offer).beforeRuleJson)
    }

    @Test fun `sessions outside the 28 days before the last session do not count`() = runTest {
        val plan = rPlan()
        history(plan, 9, eight + (28L..35L))
        assertNull(opened(plan).openingJson)
    }

    @Test fun `one session counts as one day a week`() = runTest {
        val plan = rPlan()
        history(plan, 16, listOf(0))
        assertNull(opened(plan).openingJson)
    }

    @Test fun `cardio entries do not lengthen strength recurrence`() = runTest {
        val plan = converted(day("bb_back_squat"), ProgramDay("B", "Cardio", "", emptyList(), kind = ProgramDayKind.CARDIO),
            day("bb_bench", id = "C"), ProgramDay("D", "Cardio", "", emptyList(), kind = ProgramDayKind.CARDIO))
        lifter(plan, id(plan, "bb_back_squat"), squat(), squatRule())
        history(plan, 10, eight)
        assertEquals(7, Progression.expectedRecurrenceDays(2, 2, 1))
        var track = opened(plan)
        assertNull(track.openingJson)
        assertEquals(275.0, top(track))
        track = reopen(track, plan, 5)
        assertEquals(OpeningDto(OpeningDto.SCALED, 0.95), OpeningJson.decode(track.openingJson))
        assertEquals(260.0, top(track))
    }

    @Test fun `ten sessions round to three days a week`() = runTest {
        val plan = rPlan()
        history(plan, 9, eight + listOf(26L, 27L))
        assertEquals("""{"layoff":"HOLD"}""", opened(plan).openingJson)
    }

    @Test fun `an older lifter recalibrates only past fourteen days`() = runTest {
        val plan = converted(day("bb_back_squat"), day("bb_bench", id = "B"))
        lifter(plan, id(plan, "bb_back_squat"), squat(), squatRule())
        history(plan, 21, eight)
        settings.setGlobalLifter(GlobalLifter(age = 66))
        var track = opened(plan)
        assertEquals(OpeningDto("SCALED", 0.95), OpeningJson.decode(track.openingJson))
        assertEquals(260.0, top(track))
        track = reopen(track, plan, 1)
        assertEquals(OpeningDto("RECALIBRATE"), OpeningJson.decode(track.openingJson))
        assertEquals(205.0, top(track))
        settings.setGlobalLifter(GlobalLifter(age = 40))
        track = reopen(track, plan, 0)
        assertEquals(OpeningDto("SCALED", 0.85), OpeningJson.decode(track.openingJson))
        assertEquals(235.0, top(track))
        assertEquals(2, WorkingRows.workSetIndices(planned(track)).size)
    }

    private suspend fun scaled(automatic: Boolean): Pair<Long, RunTrackEntity> {
        val plan = if (automatic) authored() else converted(day("bb_back_squat"))
        val id = if (automatic) add(plan, "bb_back_squat") else id(plan, "bb_back_squat")
        lifter(plan, id, squat(), squatRule().copy(state = SlotState(successStreak = 1, exposures = 3)))
        history(plan, 14, sixteen)
        return plan to opened(plan)
    }

    @Test fun `an Automatic scaled success returns to the working load after a restart`() = runTest {
        val (plan, track) = scaled(true)
        restart()
        val session = asPlanned(track, plan)
        assertEquals(squat(), rows(plan, id(plan, "bb_back_squat")))
        assertEquals(SlotState(successStreak = 1, exposures = 4), rule(id(plan, "bb_back_squat")).state)
        assertNull(offer(plan, "bb_back_squat"))
        assertEquals(OpeningDto("SCALED", 0.95), SessionPlanJson.decode(db.sessionDao().sessionById(session)!!.plannedJson!!).single().opening)
    }

    @Test fun `a scaled rep miss restarts at the scaled load in Automatic`() = runTest {
        val (plan, track) = scaled(true)
        asPlanned(track, plan, miss = true)
        assertEquals(260.0, WorkingRows.workingWeight(rows(plan, id(plan, "bb_back_squat"))))
        assertEquals(SlotState(exposures = 4), rule(id(plan, "bb_back_squat")).state)
        assertEquals(NextOffer(275.0, 260.0), offer(plan, "bb_back_squat"))
    }

    @Test fun `a scaled rep miss is offered in Ask`() = runTest {
        val (plan, track) = scaled(false)
        asPlanned(track, plan, miss = true)
        assertEquals(squat(), rows(plan, id(plan, "bb_back_squat")))
        assertEquals(SlotState(exposures = 4), rule(id(plan, "bb_back_squat")).state)
        assertEquals(NextOffer(275.0, 260.0), offer(plan, "bb_back_squat"))
    }

    @Test fun `an opened track with an unreadable rule keeps the stored load`() = runTest {
        val (plan, track) = scaled(true)
        val id = id(plan, "bb_back_squat")
        db.programDao().setRule(id, "junk")
        asPlanned(track, plan)
        assertEquals(squat(), rows(plan, id))
        assertEquals("junk", db.programDao().exercise(id)!!.ruleJson)
        assertNull(offer(plan, "bb_back_squat"))
    }

    @Test fun `an opened track with an unencodable outcome keeps the stored load`() = runTest {
        val (plan, track) = scaled(true)
        val id = id(plan, "bb_back_squat")
        val original = rule(id).copy(state = SlotState(exposures = 9999))
        db.programDao().setRule(id, SlotRuleJson.encode(original))
        asPlanned(track, plan)
        assertEquals(squat(), rows(plan, id))
        assertEquals(original, rule(id))
        assertNull(offer(plan, "bb_back_squat"))
    }

    @Test fun `a tick start opens by the layoff, Plan mode opens as stored, and each superset half opens by its own history`() = runTest {
        val plan = rPlan()
        val id = id(plan, "bb_back_squat")
        history(plan, 16, sixteen)
        val today = clock.instant().atZone(clock.zone).toLocalDate().toEpochDay()
        val ctx = Progression.Context(WeightUnit.LB, repo.catalog.snapshot().find("bb_back_squat")!!.equipment.toSet(),
            LoadSteps.defaults(WeightUnit.LB), today)
        val next = db.programDao().day(plan, "A")!!.nextRunId
        repo.applyToRun(next, plan, "A", listOf(TrackEdit(id, Slot.MAIN) { rows ->
            rows.mapIndexed { i, row -> row.copy(done = i == 0) }
        }))
        val tick = db.runDao().tracks(next).single()
        assertEquals(Progression.atStart(squatRule(), WorkingRows.cleared(squat()), today - 16, 4, ctx).rows, planned(tick))
        assertEquals(OpeningDto("SCALED", 0.95), OpeningJson.decode(tick.openingJson))
        assertEquals(listOf(true, false, false, false, false), SetJson.decodeSets(tick.setsJson).map { it.done })
        repo.discardRun(next)
        repo.setSupersetPartner("A", id, "cable_row")
        val paired = opened(plan)
        val tracks = db.runDao().tracks(paired.runId)
        assertEquals(2, tracks.size)
        val main = tracks.first { it.slot == Slot.MAIN }
        assertEquals(260.0, top(main))
        assertEquals(Progression.atStart(squatRule(), WorkingRows.cleared(squat()), today - 16, 4, ctx).rows, planned(main))
        assertEquals(OpeningDto("SCALED", 0.95), OpeningJson.decode(main.openingJson))
        val partner = tracks.first { it.slot == Slot.SS }
        assertEquals(work(5, 20.0, 10), planned(partner))
        assertNull(partner.openingJson)
        for (track in tracks) {
            assertEquals(planned(track), WorkingRows.cleared(SetJson.decodeSets(track.setsJson)))
        }
        repo.discardRun(paired.runId)
        repo.removeSupersetPartner("A", id)
        repo.setKeepToPlan(id, true)
        assertNull(opened(plan).openingJson)
    }

    @Test fun `a reopened skipped lift keeps its opening rows`() = runTest {
        val plan = rPlan()
        history(plan, 16, sixteen)
        val track = opened(plan)
        val id = assertNotNull(track.programExerciseId)
        repo.skipLift(plan, "A", id, Slot.MAIN, "bb_back_squat", null)
        repo.restoreSkippedLift(track.runId, id)
        val restored = db.runDao().tracks(track.runId).single { !it.retired }
        assertEquals(track.plannedJson, restored.plannedJson)
        assertEquals(track.openingJson, restored.openingJson)
        assertEquals(planned(track), WorkingRows.cleared(SetJson.decodeSets(restored.setsJson)))
        assertEquals(260.0, top(restored))
    }
}
