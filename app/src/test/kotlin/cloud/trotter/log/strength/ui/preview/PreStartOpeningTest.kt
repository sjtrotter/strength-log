package cloud.trotter.log.strength.ui.preview

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.storeTestRows
import cloud.trotter.log.strength.data.convertTestPlans
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.serialization.OpeningDto
import cloud.trotter.log.strength.data.serialization.OpeningJson
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.SlotRuleJson
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioBlock
import cloud.trotter.log.strength.domain.model.CardioRole
import cloud.trotter.log.strength.domain.model.CardioSetResult
import cloud.trotter.log.strength.domain.model.CardioSetSpec
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.domain.model.Measure
import cloud.trotter.log.strength.domain.model.PlanGoal
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
import cloud.trotter.log.strength.domain.sync.toWatch
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.sync.AppliedEditMarkers
import cloud.trotter.log.strength.sync.SetEditApplier
import cloud.trotter.log.strength.sync.TodaySnapshotSource
import cloud.trotter.log.strength.sync.WatchSnapshotBuilder
import cloud.trotter.log.strength.sync.WearSyncStore
import cloud.trotter.log.strength.ui.day.WorkoutFocus
import cloud.trotter.log.strength.ui.day.LiftMarker
import cloud.trotter.log.strength.ui.day.DaySelection
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
    private lateinit var syncStore: WearSyncStore

    private class RecordingMarkers : AppliedEditMarkers {
        private val stamps = mutableMapOf<String, Long>()
        override suspend fun lastApplied(rowKey: String) = stamps[rowKey] ?: 0L
        override suspend fun markApplied(rowKey: String, editedAtMillis: Long) { stamps[rowKey] = editedAtMillis }
        override fun lastAppliedFlow(rowKey: String) = flowOf(stamps[rowKey] ?: 0L)
    }

    @Before fun openingSetUp() {
        repo = TrackerRepository(db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings, clock)
        syncScope = CoroutineScope(dispatcher + Job())
        syncStore = WearSyncStore(PreferenceDataStoreFactory.create(scope = syncScope) {
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

    @Test fun `case 17 shows the capped run on the preview and the watch before Start and writes nothing`() = runTest(dispatcher) {
        val stored = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(3600))))
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Run", "", emptyList(), cardioBlock = stored))))
        val plan = requireNotNull(repo.plans.selectedPlanIdFlow.first())
        val completedAt = clock.millis() - 86_400_000
        repo.logCardioSession(CardioSessionEntity(planId = null, dayId = null,
            mode = "OUTDOOR_RUN", hard = false, label = "Run", startedAt = completedAt - 2_900_000,
            completedAt = completedAt, seconds = 2900, stepsCompleted = 1, activity = CardioActivity.ROAD_RUN.name),
            listOf(CardioSetResult(CardioSetSpec(CardioRole.WORK, Measure.Time(2900)), CardioSetStatus.FINISHED, 2900)))
        val preview = preview(plan, "A", clock)
        advanceUntilIdle()
        val snapshot = TodaySnapshotSource(repo, MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore,
            settings = settings, plans = repo.plans, historyRepository = repo.history, clock = clock)
            .snapshots.filterNotNull().first()

        assertEquals(PreviewCardio("Road run", "53:00", CardioActivity.ROAD_RUN, null), preview.uiState.value.cardio)
        assertEquals(CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(3180)))).toWatch(),
            snapshot.cardioBlock)
        assertNull(db.programDao().day(plan, "A")!!.cardioWorkingJson)
        assertNull(db.runDao().openRun())
    }

    @Test fun `case 19 shows the tapered run on the preview and the watch`() = runTest(dispatcher) {
        val stored = CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(3600))))
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Run", "", emptyList(), cardioBlock = stored))))
        val plan = requireNotNull(repo.plans.selectedPlanIdFlow.first())
        repo.plans.setGoal(plan, PlanGoal.Timed(CardioActivity.ROAD_RUN, 21_097, 7200,
            byEpochDay = LocalDate.of(2026, 11, 21).toEpochDay()))
        val preview = preview(plan, "A", clock)
        advanceUntilIdle()
        val snapshot = TodaySnapshotSource(repo, MutableStateFlow(LocalDate.now(clock)), syncStore, syncStore,
            settings = settings, plans = repo.plans, historyRepository = repo.history, clock = clock)
            .snapshots.filterNotNull().first()

        assertEquals("42:00", preview.uiState.value.cardio?.time)
        assertEquals(CardioBlock(CardioActivity.ROAD_RUN, listOf(CardioSetSpec(CardioRole.WORK, Measure.Time(2520)))).toWatch(),
            snapshot.cardioBlock)
    }

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
        assertEquals(LiftMarker.AFTER_BREAK, card.marker)
        assertEquals(LiftMarker.AFTER_BREAK, preview.uiState.value.lifts.single().marker)
        assertEquals("TOP × 5 · 260 lb", card.workingHeading)
        assertNull(card.nextTime)
        assertEquals("TOP × 5 · 260 lb", preview.uiState.value.lifts.single().working)
        assertEquals(case13.map { it.weightLb to it.reps }, watch(plan).sets.map { it.weightLb to it.reps })
        assertNull(db.runDao().openRun())
        assertEquals(beforeLog, db.programDao().logForSlot(plan, "A", s, Slot.MAIN))
        assertEquals(beforeSlot, db.programDao().exercise(s))
    }

    @Test fun `case 13 decrement before Start uses the focused TOP on the opening`() = runTest(dispatcher) {
        val plan = rPlan()
        val s = db.programDao().exercisesForDay(plan, "A").single().id
        // Omit ramps to focus TOP without starting; the opening clears legacy completion flags.
        db.storeTestRows("A", s, Slot.MAIN, squat().filter { it.kind != SetKind.RAMP }.map {
            it.copy(done = it.kind == SetKind.TOP)
        })
        val beforeLog = db.programDao().logForSlot(plan, "A", s, Slot.MAIN)
        val workout = day(SavedStateHandle(mapOf("day_view_selection" to DaySelection(plan, "A"))))
        advanceUntilIdle()
        val top = workout.uiState.value.exercises.single().rows.first()
        assertEquals(WorkoutFocus(s, Slot.MAIN, 0), workout.uiState.value.workout?.focus)
        assertEquals(SetKind.TOP, top.kind)
        assertEquals(260.0, top.weightDisplay)
        assertEquals(case13.single { it.kind == SetKind.TOP }.reps, top.reps)
        assertTrue(!top.done)
        assertNull(db.runDao().openRun())

        workout.decrementFocusedReps()
        advanceUntilIdle()

        val draft = assertNotNull(db.runDao().openRun())
        assertTrue(draft.draft)
        assertEquals(LiftMarker.AFTER_BREAK, workout.uiState.value.exercises.single().marker)
        val track = db.runDao().tracks(draft.id).single()
        assertEquals(OpeningDto("SCALED", 0.95), OpeningJson.decode(track.openingJson))
        val rows = SetJson.decodeSets(track.setsJson)
        assertEquals(SetKind.TOP, rows.first().kind)
        assertEquals(top.reps - 1, rows.first().reps)
        assertEquals(4, workout.uiState.value.exercises.single().rows.first().reps)
        assertEquals(case13.filter { it.kind == SetKind.BACKOFF }.mapIndexed { i, row -> row.copy(plannedIndex = i + 1) },
            rows.drop(1))
        assertEquals(beforeLog, db.programDao().logForSlot(plan, "A", s, Slot.MAIN))
    }

    @Test fun `a watch tick before Start starts the run at the opening and keeps the wrist's values`() = runTest(dispatcher) {
        val plan = rPlan()
        val s = db.programDao().exercisesForDay(plan, "A").single().id
        val next = db.programDao().day(plan, "A")!!.nextRunId
        val beforeLog = db.programDao().logForSlot(plan, "A", s, Slot.MAIN)

        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(tick(plan, s, next)))

        assertEquals(next, assertNotNull(db.runDao().openRun()).id)
        assertTrue(!assertNotNull(db.runDao().openRun()).draft)
        val track = db.runDao().tracks(next).single()
        assertEquals(case13, SetJson.decodeSets(track.plannedJson))
        assertEquals(OpeningDto("SCALED", 0.95), OpeningJson.decode(track.openingJson))
        val sets = SetJson.decodeSets(track.setsJson)
        assertEquals(LoggedSet(165.0, 5, SetKind.RAMP, done = true, watchEditedAt = 500, plannedIndex = 0), sets[0])
        assertEquals(case13.drop(1), WorkingRows.cleared(sets.drop(1)))
        assertEquals(beforeLog, db.programDao().logForSlot(plan, "A", s, Slot.MAIN))
    }

    @Test fun `case 13 Remove and Undo restore the opened TOP with its identity and watch stamp`() = runTest(dispatcher) {
        val plan = rPlan()
        val s = db.programDao().exercisesForDay(plan, "A").single().id
        val stored = assertNotNull(db.programDao().logForSlot(plan, "A", s, Slot.MAIN))
        db.programDao().upsertLog(stored.copy(setsJson = SetJson.encodeSets(squat().mapIndexed { i, row ->
            if (i == 2) row.copy(watchEditedAt = 700) else row
        })))
        val workout = day(SavedStateHandle(mapOf("day_view_selection" to DaySelection(plan, "A"))))
        advanceUntilIdle()
        assertNull(db.runDao().openRun())
        val expected = case13.mapIndexed { i, row -> row.copy(plannedIndex = i, watchEditedAt = if (i == 2) 700L else null) }

        workout.removeSet(s, 2, isSuperset = false)
        advanceTimeBy(1_000)
        runCurrent()

        val run = assertNotNull(db.runDao().openRun()).id
        assertTrue(assertNotNull(db.runDao().openRun()).draft)
        assertEquals(expected[2], workout.removedSets.value.single().main)
        assertEquals(listOf(0, 1, 3, 4), SetJson.decodeSets(db.runDao().tracks(run).single().setsJson).map { it.plannedIndex })
        workout.undoRemoveSet()
        advanceTimeBy(1_000)
        runCurrent()

        val restored = SetJson.decodeSets(db.runDao().tracks(run).single().setsJson)
        assertEquals(expected[2], restored[2])
        assertEquals(260.0, restored[2].weightLb)
        assertEquals(listOf(0, 1, 2, 3, 4), restored.map { it.plannedIndex })
        assertTrue(workout.removedSets.value.isEmpty())
    }

    @Test fun `case 13 Remove and Undo restore a padded partner round aligned with MAIN`() = runTest(dispatcher) {
        val plan = rPlan()
        val s = db.programDao().exercisesForDay(plan, "A").single().id
        repo.setSupersetPartner("A", s, "cable_row")
        val partner = listOf(
            LoggedSet(120.0, 10, SetKind.WORK, watchEditedAt = 100),
            LoggedSet(120.0, 10, SetKind.WORK, watchEditedAt = 200),
        )
        db.programDao().upsertLog(ExerciseLogEntity(plan, "A", s, Slot.SS, SetJson.encodeSets(partner), 1000))
        val workout = day(SavedStateHandle(mapOf("day_view_selection" to DaySelection(plan, "A"))))
        advanceUntilIdle()
        assertNull(db.runDao().openRun())
        val expectedMain = case13.mapIndexed { i, row -> row.copy(plannedIndex = i) }
        val expectedPartner = List(5) { i -> partner[minOf(i, 1)].copy(plannedIndex = i) }

        workout.removeSet(s, 4, isSuperset = true)
        advanceTimeBy(1_000)
        runCurrent()

        val run = assertNotNull(db.runDao().openRun()).id
        assertTrue(assertNotNull(db.runDao().openRun()).draft)
        assertEquals(expectedMain[4], workout.removedSets.value.single().main)
        assertEquals(expectedPartner[4], workout.removedSets.value.single().partner)
        assertTrue(db.runDao().tracks(run).all { SetJson.decodeSets(it.setsJson).size == 4 })
        workout.undoRemoveSet()
        advanceTimeBy(1_000)
        runCurrent()

        val tracks = db.runDao().tracks(run)
        assertEquals(expectedMain, SetJson.decodeSets(tracks.single { it.slot == Slot.MAIN }.setsJson))
        assertEquals(expectedPartner, SetJson.decodeSets(tracks.single { it.slot == Slot.SS }.setsJson))
        assertTrue(workout.removedSets.value.isEmpty())
    }

    @Test fun `Remove and Undo keep both tracks index stamp floors against a replayed watch edit`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Day A", "", listOf(ProgramExercise("bb_back_squat", targetSets = 3))))))
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val s = db.programDao().exercisesForDay(plan, "A").single().id
        repo.setSupersetPartner("A", s, "cable_row")
        for ((slot, weight) in listOf(Slot.MAIN to 100.0, Slot.SS to 40.0)) {
            val rows = List(3) { i -> LoggedSet(weight + i * 10, 5 + i, SetKind.WORK) }
            db.programDao().upsertLog(ExerciseLogEntity(plan, "A", s, slot, SetJson.encodeSets(rows), 1000))
        }
        repo.startRun(plan, "A")
        val run = assertNotNull(db.runDao().openRun()).id
        val before = db.runDao().tracks(run).associate { it.slot to SetJson.decodeSets(it.setsJson) }
        assertEquals(setOf(Slot.MAIN, Slot.SS), before.keys)
        val workout = day(SavedStateHandle(mapOf("day_view_selection" to DaySelection(plan, "A"))))
        advanceUntilIdle()

        workout.removeSet(s, 0, isSuperset = true)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(db.runDao().tracks(run).all { SetJson.decodeSets(it.setsJson).map { row -> row.plannedIndex } == listOf(1, 2) })
        val deltas = listOf(Slot.MAIN to "bb_back_squat", Slot.SS to "cable_row").map { (slot, exercise) ->
            SetEditDelta(dayId = "A", programExerciseId = s, slot = slot, setIndex = 1,
                reps = 9, editedAtMillis = 200, planId = plan, runId = run, exerciseId = exercise)
        }
        for (delta in deltas) assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(delta))

        workout.undoRemoveSet()
        advanceTimeBy(1_000)
        runCurrent()

        val restored = db.runDao().tracks(run).associate { it.slot to SetJson.decodeSets(it.setsJson) }
        assertEquals(before.keys, restored.keys)
        for ((slot, rows) in restored) {
            val original = before.getValue(slot)
            assertEquals(listOf(
                original[0], original[1].copy(watchEditedAt = 200), original[2].copy(reps = 9, watchEditedAt = 200),
            ), rows)
        }
        for (delta in deltas) assertEquals(SetEditApplier.Outcome.STALE, applier.apply(delta))
        assertEquals(restored, db.runDao().tracks(run).associate { it.slot to SetJson.decodeSets(it.setsJson) })
    }

    @Test fun `a newer pre-start watch edit survives a light opening and rejects an older delta`() = runTest(dispatcher) {
        repo.replaceProgram(Program(listOf(ProgramDay("A", "Day A", "", listOf(ProgramExercise("bb_back_squat", targetSets = 3))))))
        db.convertTestPlans(settings)
        val plan = repo.plans.selectedPlanIdFlow.first()!!
        val s = storeSquat(plan)
        val next = db.programDao().day(plan, "A")!!.nextRunId
        val newer = tick(plan, s, next).copy(done = null, weightLb = 170.0, reps = 7, editedAtMillis = 2_000)
        db.storeTestRows("A", s, Slot.MAIN, squat().mapIndexed { i, row ->
            if (i == 0) row.copy(weightLb = 170.0, reps = 7, watchEditedAt = 2_000) else row
        })
        assertNull(db.runDao().openRun())
        val stored = db.programDao().logForSlot(plan, "A", s, Slot.MAIN)
        db.planDao().updateLightCycle(plan, 2, 0)

        assertEquals(SetEditApplier.Outcome.STALE,
            applier.apply(newer.copy(weightLb = 150.0, reps = 4, editedAtMillis = 1_000)))

        assertNull(db.runDao().openRun())
        assertEquals(stored, db.programDao().logForSlot(plan, "A", s, Slot.MAIN))
        val shown = repo.observeDay(plan, "A").first().slots.single().main.sets.first()
        assertEquals(170.0 to 7, shown.weightLb to shown.reps)
        repo.startRun(plan, "A")
        val opened = SetJson.decodeSets(db.runDao().tracks(next).single().setsJson).first()
        assertEquals(2_000L, opened.watchEditedAt)
        assertEquals(170.0 to 7, opened.weightLb to opened.reps)
    }

    @Test fun `light fitting keeps the higher stored index stamp when it pads a partner`() = runTest(dispatcher) {
        val plan = rPlan()
        val s = db.programDao().exercisesForDay(plan, "A").single().id
        repo.setSupersetPartner("A", s, "cable_row")
        val stamps = listOf(100L, 200L, 900L, 1_000L, 700L)
        db.storeTestRows("A", s, Slot.SS,
            stamps.map { LoggedSet(120.0, 10, SetKind.WORK, watchEditedAt = it) })
        db.programDao().setPartnerRule(s, SlotRuleJson.encode(
            SlotRule(SlotRole.ACCESSORY, RepTarget.Fixed(10), 2, 0.0125, 1, 0.05, Scheme.STRENGTH)))
        db.planDao().updateLightCycle(plan, 2, 0)
        val next = db.programDao().day(plan, "A")!!.nextRunId
        val older = SetEditDelta(dayId = "A", programExerciseId = s, slot = Slot.SS, setIndex = 3,
            weightLb = 80.0, reps = 8, editedAtMillis = 950,
            planId = plan, runId = next, exerciseId = "cable_row")

        assertEquals(SetEditApplier.Outcome.STALE, applier.apply(older))

        assertNull(db.runDao().openRun())
        repo.startRun(plan, "A")
        val partner = db.runDao().tracks(next).single { it.slot == Slot.SS }
        val rows = SetJson.decodeSets(partner.setsJson)
        assertEquals(listOf(100L, 200L, 900L, 1_000L), rows.map { it.watchEditedAt })
        assertEquals(List(4) { 110.0 to 10 }, rows.map { it.weightLb to it.reps })
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
