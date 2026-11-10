package cloud.trotter.log.strength.ui.preview

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.convertTestPlans
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.serialization.OpeningDto
import cloud.trotter.log.strength.data.serialization.OpeningJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.progression.RepTarget
import cloud.trotter.log.strength.domain.progression.Scheme
import cloud.trotter.log.strength.domain.progression.SlotRole
import cloud.trotter.log.strength.domain.progression.SlotRule
import cloud.trotter.log.strength.domain.progression.WorkingRows
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.sync.AppliedEditMarkers
import cloud.trotter.log.strength.sync.SetEditApplier
import cloud.trotter.log.strength.sync.WatchSnapshotBuilder
import cloud.trotter.log.strength.sync.WearSyncStore
import cloud.trotter.log.strength.ui.day.DaySelection
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreStartOpeningTest : OverviewFixture() {
    private val clock = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC)
    private val case13 = listOf(
        LoggedSet(155.0, 5, SetKind.RAMP), LoggedSet(210.0, 3, SetKind.RAMP),
        LoggedSet(260.0, 5, SetKind.TOP), LoggedSet(235.0, 5, SetKind.BACKOFF),
        LoggedSet(235.0, 5, SetKind.BACKOFF),
    )
    private lateinit var syncScope: CoroutineScope
    private lateinit var applier: SetEditApplier

    private class RecordingMarkers : AppliedEditMarkers {
        private val stamps = mutableMapOf<String, Long>()
        override suspend fun lastApplied(rowKey: String) = stamps[rowKey] ?: 0L
        override suspend fun markApplied(rowKey: String, editedAtMillis: Long) { stamps[rowKey] = editedAtMillis }
        override fun lastAppliedFlow(rowKey: String) = flowOf(stamps[rowKey] ?: 0L)
    }

    @Before fun openingSetUp() {
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings, clock)
        syncScope = CoroutineScope(dispatcher + Job())
        val syncStore = WearSyncStore(PreferenceDataStoreFactory.create(scope = syncScope) {
            File.createTempFile("pre-start-opening-acks", ".preferences_pb")
        })
        applier = SetEditApplier(repo, RecordingMarkers(), syncStore, plans = repo.plans, catalog = repo.catalog)
    }

    @After fun openingTearDown() { syncScope.cancel() }

    private fun squat() = listOf(
        LoggedSet(165.0, 5, SetKind.RAMP), LoggedSet(220.0, 3, SetKind.RAMP),
        LoggedSet(275.0, 5, SetKind.TOP), LoggedSet(250.0, 5, SetKind.BACKOFF),
        LoggedSet(250.0, 5, SetKind.BACKOFF),
    )

    private suspend fun storeSquat(plan: Long): Long {
        val s = db.programDao().exercisesForDay(plan, "A").single().id
        db.programDao().upsertLog(ExerciseLogEntity(plan, "A", s, Slot.MAIN, SetJson.encodeSets(squat()), 1000))
        db.programDao().setRule(s, SlotRuleJson.encode(
            SlotRule(SlotRole.MAIN, RepTarget.Fixed(5), 2, 0.025, 1, 0.05, Scheme.STRENGTH)))
        return s
    }

    private suspend fun rPlan(): Long {
        repo.replaceProgram(Program(listOf("A" to "bb_back_squat", "B" to "bb_bench", "C" to "bb_back_squat", "D" to "ohp")
            .map { (id, exercise) -> ProgramDay(id, "Day $id", "", listOf(ProgramExercise(exercise, targetSets = 3))) }))
        db.convertTestPlans(settings)
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        storeSquat(plan)
        val sixteen = listOf(0L, 1, 3, 5, 7, 9, 10, 12, 14, 16, 17, 19, 21, 23, 25, 27)
        for (offset in sixteen) {
            val time = clock.millis() - Duration.ofDays(16 + offset).toMillis()
            val session = db.sessionDao().insertSession(WorkoutSessionEntity(0, "A", "Day A", time, time, null, planId = plan))
            if (offset == 0L) db.sessionDao().insertSets(listOf(SessionSetEntity(0, session, "bb_back_squat", "bb_back_squat",
                Slot.MAIN, 0, "TOP", 275.0, 5, true)))
        }
        return plan
    }

    private suspend fun watch(plan: Long) = assertNotNull(WatchSnapshotBuilder.build(
        repo.observeDay(plan, "A").first(), WeightUnit.LB, revision = 0L)).day.exercises.single()

    private fun tick(plan: Long, squat: Long, next: String) = SetEditDelta(
        dayId = "A", programExerciseId = squat, slot = Slot.MAIN, setIndex = 0,
        weightLb = 165.0, reps = 5, done = true, editedAtMillis = 500,
        planId = plan, runId = next, exerciseId = "bb_back_squat",
    )

    @Test fun `case 13 shows 260 on the Day screen, the preview and the watch before Start and writes nothing`() = runTest(dispatcher) {
        val plan = rPlan()
        val s = db.programDao().exercisesForDay(plan, "A").single().id
        val beforeLog = db.programDao().logForSlot(plan, "A", s, Slot.MAIN)
        val beforeSlot = db.programDao().exercise(s)
        val workout = day(SavedStateHandle(mapOf("day_view_selection" to DaySelection(plan, "A"))))
        val preview = preview(plan, "A")
        advanceUntilIdle()

        val card = workout.uiState.value.exercises.single()
        assertEquals(listOf(155.0, 210.0, 260.0, 235.0, 235.0), card.rows.map { it.weightDisplay })
        assertEquals("TOP × 5 · 260 lb", card.workingHeading)
        assertNull(card.nextTime)
        assertEquals("TOP × 5 · 260 lb", preview.uiState.value.lifts.single().working)
        assertEquals(case13.map { it.weightLb to it.reps }, watch(plan).sets.map { it.weightLb to it.reps })
        assertNull(db.runDao().openRun())
        assertEquals(beforeLog, db.programDao().logForSlot(plan, "A", s, Slot.MAIN))
        assertEquals(beforeSlot, db.programDao().exercise(s))
    }

    @Test fun `a watch tick before Start starts the run at the opening and keeps the wrist's values`() = runTest(dispatcher) {
        val plan = rPlan()
        val s = db.programDao().exercisesForDay(plan, "A").single().id
        val next = db.programDao().day(plan, "A")!!.nextRunId
        val beforeLog = db.programDao().logForSlot(plan, "A", s, Slot.MAIN)

        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(tick(plan, s, next)))

        assertEquals(next, assertNotNull(db.runDao().openRun()).id)
        val track = db.runDao().tracks(next).single()
        assertEquals(case13, SetJson.decodeSets(track.plannedJson))
        assertEquals(OpeningDto("SCALED", 0.95), OpeningJson.decode(track.openingJson))
        val sets = SetJson.decodeSets(track.setsJson)
        assertEquals(LoggedSet(165.0, 5, SetKind.RAMP, done = true, watchEditedAt = 500, plannedIndex = 0), sets[0])
        assertEquals(case13.drop(1), WorkingRows.cleared(sets.drop(1)))
        assertEquals(beforeLog, db.programDao().logForSlot(plan, "A", s, Slot.MAIN))
    }

    @Test fun `a light superset ticked from the watch opens both halves light and fitted`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Day A", "", listOf(ProgramExercise("bb_back_squat", targetSets = 3))))))
        db.convertTestPlans(settings)
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val s = storeSquat(plan)
        repo.setSupersetPartner("A", s, "cable_row")
        db.programDao().upsertLog(ExerciseLogEntity(plan, "A", s, Slot.SS,
            SetJson.encodeSets(List(5) { LoggedSet(120.0, 10, SetKind.WORK) }), 1000))
        db.programDao().setPartnerRule(s, SlotRuleJson.encode(
            SlotRule(SlotRole.ACCESSORY, RepTarget.Fixed(10), 2, 0.0125, 1, 0.05, Scheme.STRENGTH)))
        db.planDao().updateLightCycle(plan, 2, 0)
        val before = watch(plan)
        assertEquals(listOf(165.0, 220.0, 250.0, 225.0), before.sets.map { it.weightLb })
        assertEquals(List(4) { 110.0 to 10 }, before.ssSets.map { it.weightLb to it.reps })
        val next = db.programDao().day(plan, "A")!!.nextRunId

        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(tick(plan, s, next)))

        val tracks = db.runDao().tracks(next)
        val main = tracks.single { it.slot == Slot.MAIN }
        val partner = tracks.single { it.slot == Slot.SS }
        assertEquals(listOf(
            LoggedSet(165.0, 5, SetKind.RAMP), LoggedSet(220.0, 3, SetKind.RAMP),
            LoggedSet(250.0, 5, SetKind.TOP), LoggedSet(225.0, 5, SetKind.BACKOFF),
        ), SetJson.decodeSets(main.plannedJson))
        assertEquals(List(4) { LoggedSet(110.0, 10, SetKind.WORK) }, SetJson.decodeSets(partner.plannedJson))
        tracks.forEach { assertEquals("""{"light":true}""", it.openingJson) }
        val first = SetJson.decodeSets(main.setsJson).first()
        assertEquals(165.0 to 5, first.weightLb to first.reps)
        assertTrue(first.done)
        assertTrue(SetJson.decodeSets(partner.setsJson).first().done)
    }
}
