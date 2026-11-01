package cloud.trotter.log.strength.ui.day

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.prefs.WorkoutRecord
import cloud.trotter.log.strength.domain.generator.CardioBlocks
import cloud.trotter.log.strength.domain.model.CardioActivity
import cloud.trotter.log.strength.domain.model.CardioSuggestion
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.domain.standards.PhoneRest
import cloud.trotter.log.strength.domain.sync.SetEditDelta
import cloud.trotter.log.strength.rest.RestRuntime
import cloud.trotter.log.strength.sync.SetEditApplier
import cloud.trotter.log.strength.sync.WearSyncStore
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayViewModelWorkoutTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = WorkoutClock()
    private val runtime = RecordingRestRuntime()
    private val vms = mutableListOf<DayViewModel>()
    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var share: ShareCardService
    private lateinit var storeScope: CoroutineScope
    private lateinit var syncStore: WearSyncStore
    private lateinit var preferences: DataStore<Preferences>

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        storeScope = CoroutineScope(dispatcher + Job())
        preferences = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("workout-vm", ".preferences_pb")
        }
        val settings = SettingsStore(preferences)
        repo = TrackerRepository(
            db, db.programDao(), db.sessionDao(), db.customExerciseDao(), settings, clock,
        )
        syncStore = WearSyncStore(PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("workout-watch", ".preferences_pb")
        })
        share = ShareCardService(context, repo)
    }

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        storeScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun bench(sets: Int = 3, superset: Boolean = false) = ProgramExercise(
        "db_bench", targetSets = sets,
        prescription = Prescription(PrescribedTarget.LoadReps(80.0, 10)),
        superset = if (superset) SupersetPartner("cable_row") else null,
    )

    private suspend fun prepare(
        exercises: List<ProgramExercise> = listOf(bench()),
        restEnabled: Boolean = false,
        cardio: Boolean = false,
    ) {
        repo.replaceProgram(Program(listOf(ProgramDay(
            "A", "Workout", "", exercises,
            cardioBlock = if (cardio) CardioBlocks.fromLegacy(
                CardioSuggestion("Easy cardio", "Run easy.", hard = false), true, CardioActivity.ROAD_RUN,
            ) else null,
        ))))
        repo.setPhoneRestTimerEnabled(restEnabled)
    }

    private fun TestScope.readyVm(flags: WorkoutFlags = WorkoutFlags(true)): DayViewModel {
        val vm = DayViewModel(
            repo, SessionPublisher.NoOp, share, SavedStateHandle(), clock, InertAlarm, runtime,
            clock = clock, flags = flags,
        ).also { vms += it }
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    private fun TestScope.at(millis: Long) {
        clock.now = millis
        advanceTimeBy(250)
        runCurrent()
    }

    private suspend fun track(vm: DayViewModel, id: Long, slot: String = Slot.MAIN): List<LoggedSet> =
        repo.dayTracksFlow(requireNotNull(vm.uiState.value.planId), "A").first().tracks
            .first { it.programExerciseId == id && it.slot == slot }.sets

    private suspend fun TestScope.roundTwo(vm: DayViewModel): Long {
        val id = vm.uiState.value.exercises.single().programExerciseId
        val main = track(vm, id).mapIndexed { index, set -> set.copy(done = index == 0, weightLb = 80.0, reps = 10) }
        val partner = track(vm, id, Slot.SS).mapIndexed { index, set ->
            set.copy(done = index == 0, weightLb = 120.0, reps = 10)
        }
        repo.updateSetsPaired(requireNotNull(vm.uiState.value.runId), "A", id, main, partner)
        runCurrent()
        return id
    }

    @Test
    fun `★ superset logs one half cancels mid-round rest and labels the next round`() = test {
        prepare(listOf(bench(superset = true)), restEnabled = true)
        val vm = readyVm()
        val id = roundTwo(vm)
        repo.setPhoneRest(PhoneRest(190_000, 90, "Old rest"))
        runCurrent()
        val cancellations = runtime.cancellations
        vm.logFocusedSet()
        runCurrent()
        assertTrue(track(vm, id)[1].done)
        assertFalse(track(vm, id, Slot.SS)[1].done)
        assertNull(repo.phoneRestFlow.first())
        assertEquals(cancellations + 1, runtime.cancellations)
        assertTrue(runtime.armed.isEmpty())
        assertEquals(WorkoutFocus(id, Slot.SS, 1), vm.uiState.value.workout!!.focus)
        vm.logFocusedSet()
        runCurrent()
        assertTrue(track(vm, id, Slot.SS)[1].done)
        assertEquals("Round 3, DB Bench Press 80 × 10", runtime.armed.single().nextSetLabel)
        assertEquals(runtime.armed.single(), repo.phoneRestFlow.first())
    }

    @Test
    fun `★ phone completion stamps once and sheet untick clears both stamps`() = test {
        prepare()
        val vm = readyVm()
        val id = vm.uiState.value.exercises.single().programExerciseId
        vm.logFocusedSet()
        runCurrent()
        assertEquals(100_000L, track(vm, id)[0].completedAtMillis)
        assertEquals(100_000L, vm.uiState.value.workout!!.lastCompletionMillis)
        at(100_500)
        vm.toggleDone(id, 0, true, Slot.MAIN)
        runCurrent()
        assertEquals(100_000L, track(vm, id)[0].completedAtMillis)
        val stored = track(vm, id)
        repo.updateSets(requireNotNull(vm.uiState.value.runId), "A", id, Slot.MAIN,
            stored.mapIndexed { index, set -> if (index == 0) set.copy(startedAtMillis = 99_000) else set })
        vm.toggleDone(id, 0, false, Slot.MAIN)
        runCurrent()
        val undone = track(vm, id)[0]
        assertFalse(undone.done)
        assertNull(undone.startedAtMillis)
        assertNull(undone.completedAtMillis)
        assertNull(vm.uiState.value.workout!!.undo)
        vm.toggleDone(id, 0, true, Slot.MAIN)
        runCurrent()
        assertEquals(100_500L, track(vm, id)[0].completedAtMillis)
    }

    @Test
    fun `★ timed-rep count undo restores the reps and seconds before entry`() = test {
        prepare(listOf(ProgramExercise("pushup", targetSets = 2,
            prescription = Prescription(PrescribedTarget.RepsInTime(120, 40)))))
        val vm = readyVm()
        val id = vm.uiState.value.exercises.single().programExerciseId
        repo.updateSets(requireNotNull(vm.uiState.value.runId), "A", id, Slot.MAIN,
            track(vm, id).mapIndexed { index, set -> if (index == 0) set.copy(reps = 7, seconds = 9) else set })
        runCurrent()
        vm.startTimedRep(id, 0)
        runCurrent()
        at(220_000)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(vm.uiState.value.timedRep!!.event.awaitingCount)
        vm.changeTimedRepDraft("18")
        vm.logTimedRepCount()
        runCurrent()
        assertEquals(18, track(vm, id)[0].reps)
        assertEquals(0, track(vm, id)[0].seconds)
        assertNotNull(vm.uiState.value.workout!!.undo)
        vm.undoLastLog()
        runCurrent()
        val restored = track(vm, id)[0]
        assertEquals(7, restored.reps)
        assertEquals(9, restored.seconds)
        assertFalse(restored.done)
        assertNull(restored.startedAtMillis)
        assertNull(restored.completedAtMillis)
    }

    @Test
    fun `★ live rest extends undo and skipping retires it at later times`() = test {
        prepare(restEnabled = true)
        val vm = readyVm()
        vm.logFocusedSet()
        runCurrent()
        assertEquals(190_000L, repo.phoneRestFlow.first()!!.deadlineEpochMillis)
        at(189_999)
        assertNotNull(vm.uiState.value.workout!!.undo)
        vm.adjustRest(15)
        runCurrent()
        assertEquals(205_000L, repo.phoneRestFlow.first()!!.deadlineEpochMillis)
        at(204_999)
        assertNotNull(vm.uiState.value.workout!!.undo)
        vm.skipRest()
        runCurrent()
        assertNull(vm.uiState.value.workout!!.undo)
        at(300_000)
        assertNull(vm.uiState.value.workout!!.undo)
        vm.undoLastLog()
        runCurrent()
        assertTrue(vm.uiState.value.exercises.single().rows[0].done)
    }

    @Test
    fun `★ without rest undo expires at exactly ten seconds`() = test {
        prepare()
        val vm = readyVm()
        vm.logFocusedSet()
        runCurrent()
        at(109_999)
        assertNotNull(vm.uiState.value.workout!!.undo)
        at(110_000)
        assertNull(vm.uiState.value.workout!!.undo)
        vm.undoLastLog()
        runCurrent()
        assertTrue(vm.uiState.value.exercises.single().rows[0].done)
    }

    @Test
    fun `★ the last strength set cancels rest and shows all logged or unfinished cardio`() = test {
        for (cardio in listOf(false, true)) {
            prepare(listOf(bench(sets = 1)), restEnabled = true, cardio = cardio)
            val vm = readyVm()
            repo.setPhoneRest(PhoneRest(190_000, 90, "Old rest"))
            runCurrent()
            val cancellations = runtime.cancellations
            val arms = runtime.armed.size
            vm.logFocusedSet()
            runCurrent()
            assertNull(repo.phoneRestFlow.first())
            assertEquals(cancellations + 1, runtime.cancellations)
            assertEquals(arms, runtime.armed.size)
            assertEquals(
                if (cardio) WorkoutSelection.Shown.Group("cardio", false) else WorkoutSelection.Shown.AllLogged,
                vm.uiState.value.workout!!.shown,
            )
            vm.viewModelScope.cancel()
            repo.discardRun(requireNotNull(vm.uiState.value.runId))
        }
    }

    @Test
    fun `★ later watch completion retires undo while an unstamped watch tick leaves it valid`() = test {
        prepare()
        val vm = readyVm()
        val state = vm.uiState.value
        val id = state.exercises.single().programExerciseId
        vm.logFocusedSet()
        runCurrent()
        val applier = SetEditApplier(repo, syncStore, syncStore)
        val edit = SetEditDelta(
            dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 1,
            done = true, editedAtMillis = 100_001, planId = state.planId, runId = state.runId, exerciseId = "db_bench",
        )
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(edit))
        runCurrent()
        assertNotNull(vm.uiState.value.workout!!.undo)
        assertEquals(SetEditApplier.Outcome.APPLIED,
            applier.apply(edit.copy(setIndex = 2, editedAtMillis = 100_002, completedAtMillis = 100_002)))
        runCurrent()
        assertNull(vm.uiState.value.workout!!.undo)
        vm.undoLastLog()
        runCurrent()
        assertTrue(track(vm, id)[0].done)
    }

    @Test
    fun `★ record survives a fresh route handle and a foreign run record is ignored`() = test {
        prepare(listOf(bench(), ProgramExercise("plank")))
        val first = readyVm()
        first.logFocusedSet()
        runCurrent()
        val plankKey = "exercise:${first.uiState.value.exercises[1].programExerciseId}"
        first.selectWorkoutLift(plankKey)
        runCurrent()
        val workout = first.uiState.value.workout!!
        assertNotNull(workout.undo)
        first.viewModelScope.cancel()
        val restored = readyVm()
        assertEquals(workout, restored.uiState.value.workout)
        val record = requireNotNull(repo.workoutRecordFlow.first())
        repo.setWorkoutRecord(record.copy(runId = "another-run"))
        runCurrent()
        val ignored = restored.uiState.value.workout!!
        assertNull(ignored.undo)
        assertEquals(WorkoutSelection.Shown.Group("exercise:${record.undo!!.programExerciseId}", false), ignored.shown)
    }

    @Test
    fun `★ undo returns to its group and a second undo makes no changes`() = test {
        prepare(listOf(bench(sets = 1), ProgramExercise("plank")))
        val vm = readyVm()
        val id = vm.uiState.value.exercises[0].programExerciseId
        val before = track(vm, id)[0]
        vm.logFocusedSet()
        runCurrent()
        assertEquals("exercise:${vm.uiState.value.exercises[1].programExerciseId}",
            (vm.uiState.value.workout!!.shown as WorkoutSelection.Shown.Group).key)
        vm.undoLastLog()
        runCurrent()
        assertEquals(WorkoutSelection.Shown.Group("exercise:$id", false), vm.uiState.value.workout!!.shown)
        assertEquals(before, track(vm, id)[0])
        assertNull(repo.workoutRecordFlow.first()!!.undo)
        val record = repo.workoutRecordFlow.first()
        vm.undoLastLog()
        runCurrent()
        assertEquals(before, track(vm, id)[0])
        assertEquals(record, repo.workoutRecordFlow.first())
    }

    @Test
    fun `★ sheet ticks keep selection and undo target while next changes no sets`() = test {
        prepare(listOf(bench(sets = 1), ProgramExercise("plank")))
        val vm = readyVm()
        val id = vm.uiState.value.exercises[0].programExerciseId
        vm.logFocusedSet()
        runCurrent()
        val undo = repo.workoutRecordFlow.first()!!.undo
        vm.selectWorkoutLift("exercise:$id")
        runCurrent()
        vm.toggleDone(id, 0, true, Slot.MAIN)
        runCurrent()
        assertEquals(WorkoutSelection.Shown.Group("exercise:$id", true), vm.uiState.value.workout!!.shown)
        assertTrue(vm.uiState.value.workout!!.completedView)
        assertEquals(undo, repo.workoutRecordFlow.first()!!.undo)
        val sets = track(vm, id)
        vm.nextWorkoutLift()
        runCurrent()
        assertEquals(sets, track(vm, id))
        assertEquals("exercise:${vm.uiState.value.exercises[1].programExerciseId}",
            repo.workoutRecordFlow.first()!!.selectedKey)
        vm.toggleDone(id, 0, false, Slot.MAIN)
        runCurrent()
        assertEquals(undo, repo.workoutRecordFlow.first()!!.undo)
        assertNull(vm.uiState.value.workout!!.undo)
    }

    @Test
    fun `★ changed target values retire undo and the mutation rechecks persisted data`() = test {
        prepare()
        val vm = readyVm()
        val id = vm.uiState.value.exercises.single().programExerciseId
        vm.logFocusedSet()
        runCurrent()
        assertNotNull(vm.uiState.value.workout!!.undo)
        val stored = track(vm, id)
        val edited = stored.mapIndexed { index, set -> if (index == 0) set.copy(reps = 11) else set }
        repo.updateSets(requireNotNull(vm.uiState.value.runId), "A", id, Slot.MAIN, edited)
        vm.undoLastLog()
        runCurrent()
        assertEquals(edited, track(vm, id))
        assertNull(vm.uiState.value.workout!!.undo)
    }

    @Test
    fun `★ workout record round trips through settings and malformed JSON reads as empty`() = test {
        prepare()
        val vm = readyVm()
        vm.logFocusedSet()
        runCurrent()
        val record = requireNotNull(repo.workoutRecordFlow.first())
        val restored = SettingsStore(preferences)
        assertEquals(record, restored.workoutRecordFlow.first())
        for (encoded in listOf("{", "{}", "null", "{\"runId\":null}")) {
            preferences.edit { it[stringPreferencesKey("workout_record")] = encoded }
            runCurrent()
            assertNull(repo.workoutRecordFlow.first())
            assertNull(vm.uiState.value.workout!!.undo)
        }
        repo.setWorkoutRecord(record)
        assertEquals(record, restored.workoutRecordFlow.first())
        repo.setWorkoutRecord(null)
        assertNull(restored.workoutRecordFlow.first())
        assertFalse(preferences.data.first().contains(stringPreferencesKey("workout_record")))
    }

    @Test
    fun flagOffKeepsSupersetRestAndUnstampedTicksWithoutWorkoutStateOrRecordWrites() = test {
        prepare(listOf(bench(superset = true)), restEnabled = true)
        val vm = readyVm(WorkoutFlags())
        val id = roundTwo(vm)
        val rest = PhoneRest(190_000, 90, "Old rest")
        repo.setPhoneRest(rest)
        val record = WorkoutRecord("untouched", "cardio")
        repo.setWorkoutRecord(record)
        runCurrent()
        val cancellations = runtime.cancellations
        vm.toggleDone(id, 1, true, Slot.MAIN)
        runCurrent()
        assertTrue(track(vm, id)[1].done)
        assertFalse(track(vm, id, Slot.SS)[1].done)
        assertNull(track(vm, id)[1].completedAtMillis)
        assertEquals(rest, repo.phoneRestFlow.first())
        assertEquals(cancellations, runtime.cancellations)
        assertTrue(runtime.armed.isEmpty())
        assertNull(vm.uiState.value.workout)
        vm.logFocusedSet()
        vm.selectWorkoutLift("cardio")
        vm.nextWorkoutLift()
        vm.undoLastLog()
        runCurrent()
        assertEquals(record, repo.workoutRecordFlow.first())
        vm.toggleDone(id, 1, true, Slot.SS)
        runCurrent()
        assertEquals(90, runtime.armed.single().totalSeconds)
        assertNull(track(vm, id, Slot.SS)[1].completedAtMillis)
        assertNull(vm.uiState.value.workout)
    }

    private class WorkoutClock(var now: Long = 100_000) : Clock(), CardioClock {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun millis(): Long = now
        override fun wallMillis(): Long = now
        override fun elapsedRealtimeMillis(): Long = now
        override fun bootCount(): Int = 1
    }

    private object InertAlarm : CardioAlarm {
        override fun arm(deadlineElapsedMillis: Long, identity: String, onBoundary: () -> Unit) = Unit
        override fun cancel() = Unit
        override fun buzz() = Unit
    }

    private class RecordingRestRuntime : RestRuntime {
        override val available = true
        val armed = mutableListOf<PhoneRest>()
        var cancellations = 0
        override fun arm(rest: PhoneRest) { armed += rest }
        override fun cancel() { cancellations++ }
        override fun complete(): Boolean = true
    }
}
