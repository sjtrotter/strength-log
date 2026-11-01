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
import cloud.trotter.log.strength.data.serialization.SetJson
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

    private fun TestScope.readyVm(
        flags: WorkoutFlags = WorkoutFlags(true),
        savedState: SavedStateHandle = SavedStateHandle(),
    ): DayViewModel {
        val vm = DayViewModel(
            repo, SessionPublisher.NoOp, share, savedState, clock, InertAlarm, runtime,
            clock = clock, flags = flags,
        ).also { vms += it }
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    private fun TestScope.at(millis: Long) {
        val elapsed = millis - clock.now
        clock.now = millis
        advanceTimeBy(elapsed)
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

    private suspend fun markLegacyRowsDone(vm: DayViewModel) {
        val planId = requireNotNull(vm.uiState.value.planId)
        db.programDao().logsForDay(planId, "A").forEach { log ->
            db.programDao().upsertLog(log.copy(setsJson = SetJson.encodeSets(
                SetJson.decodeSets(log.setsJson).mapIndexed { index, set -> set.copy(done = index == 0) },
            )))
        }
    }

    @Test
    fun `legacy plan MAIN swap confirms before deleting either half logged rows`() = test {
        prepare(listOf(bench(superset = true)))
        val saved = SavedStateHandle()
        val vm = readyVm(savedState = saved)
        markLegacyRowsDone(vm)
        runCurrent()
        assertFalse(vm.uiState.value.runOpen)
        vm.swapDaySlot(0, "machine_chest")
        runCurrent()
        val pending = requireNotNull(vm.uiState.value.pendingPlanSwap)
        assertEquals(2, pending.doneCount)
        assertEquals(Slot.MAIN, pending.slot)
        assertEquals("db_bench", vm.uiState.value.exercises.single().exerciseId)
        val restored = readyVm(savedState = SavedStateHandle(mapOf("day_pending_plan_swap" to pending)))
        assertEquals(pending, restored.uiState.value.pendingPlanSwap)
        restored.confirmPlanSwap()
        runCurrent()
        assertNull(restored.uiState.value.pendingPlanSwap)
        assertEquals("machine_chest", restored.uiState.value.exercises.single().exerciseId)
        assertEquals(0, restored.uiState.value.doneSets)
    }

    @Test
    fun `legacy plan SS swap confirms only the partner logged count and can cancel`() = test {
        prepare(listOf(bench(superset = true)))
        val vm = readyVm()
        markLegacyRowsDone(vm)
        runCurrent()
        vm.setDaySlotSuperset(0, "bb_row")
        runCurrent()
        val pending = requireNotNull(vm.uiState.value.pendingPlanSwap)
        assertEquals(Slot.SS, pending.slot)
        assertEquals(1, pending.doneCount)
        assertEquals("Seated Cable Row", pending.title)
        vm.dismissPlanSwap()
        runCurrent()
        assertEquals("cable_row", vm.uiState.value.exercises.single().partnerExerciseId)
        assertNull(vm.uiState.value.pendingPlanSwap)
    }

    @Test
    fun `clear checks keeps the run and edited values including retired tracks`() = test {
        prepare(listOf(bench(superset = true)))
        val vm = readyVm()
        roundTwo(vm)
        vm.swapWorkoutSlot(0, "bb_row", Slot.SS)
        runCurrent()
        val before = requireNotNull(repo.openRunFlow.first())
        vm.clearTodayChecks()
        runCurrent()
        val after = requireNotNull(repo.openRunFlow.first())
        assertEquals(before.id, after.id)
        assertEquals(before.tracks.map { it.id }, after.tracks.map { it.id })
        before.tracks.zip(after.tracks).forEach { (old, cleared) ->
            assertEquals(old.retired, cleared.retired)
            assertEquals(old.sets.map { it.copy(done = false, startedAtMillis = null, completedAtMillis = null) }, cleared.sets)
        }
        assertNull(vm.uiState.value.swapUndo)
    }

    @Test
    fun `partner workout swap retires only SS sizes to MAIN and undo reopens original rows`() = test {
        prepare(listOf(bench(sets = 5, superset = true)))
        val vm = readyVm()
        val id = roundTwo(vm)
        val original = requireNotNull(repo.openRunFlow.first())
        val main = original.tracks.single { it.slot == Slot.MAIN }
        val partner = original.tracks.single { it.slot == Slot.SS }
        val focus = vm.uiState.value.workout?.shown
        vm.swapWorkoutSlot(0, "bb_row", Slot.SS)
        runCurrent()
        val swapped = requireNotNull(repo.openRunFlow.first())
        assertEquals(main, swapped.tracks.single { it.slot == Slot.MAIN })
        assertEquals(partner.copy(retired = true), swapped.tracks.single { it.id == partner.id })
        val replacement = swapped.tracks.single { it.slot == Slot.SS && !it.retired }
        assertEquals("bb_row", replacement.exerciseId)
        assertEquals(main.sets.size, replacement.sets.size)
        assertTrue(replacement.sets.none { it.done })
        assertEquals("db_bench", vm.uiState.value.exercises.single().exerciseId)
        assertEquals("bb_row", vm.uiState.value.exercises.single().partnerExerciseId)
        assertEquals(Slot.SS, vm.uiState.value.swapUndo?.slot)
        assertEquals(focus, vm.uiState.value.workout?.shown)
        vm.undoWorkoutSwap()
        runCurrent()
        val restored = requireNotNull(repo.openRunFlow.first())
        assertEquals(main, restored.tracks.single { it.slot == Slot.MAIN })
        assertEquals(partner, restored.tracks.single { it.id == partner.id })
        assertTrue(restored.tracks.single { it.id == replacement.id }.retired)
        assertEquals(partner.sets, track(vm, id, Slot.SS))
        assertNull(vm.uiState.value.swapUndo)
    }

    @Test
    fun `MAIN workout swap undo survives a fresh handle reopens prescription and leaves partner alone`() = test {
        prepare(listOf(bench(superset = true)))
        val vm = readyVm()
        val id = roundTwo(vm)
        val original = requireNotNull(repo.openRunFlow.first())
        val main = original.tracks.single { it.slot == Slot.MAIN }
        val partner = original.tracks.single { it.slot == Slot.SS }
        vm.swapWorkoutSlot(0, "machine_chest", Slot.MAIN)
        runCurrent()
        val swapped = requireNotNull(repo.openRunFlow.first())
        assertTrue(swapped.tracks.single { it.id == main.id }.retired)
        assertEquals(partner, swapped.tracks.single { it.id == partner.id })
        assertEquals(Slot.MAIN, vm.uiState.value.swapUndo?.slot)
        val restoredVm = readyVm(savedState = SavedStateHandle(mapOf(
            "day_swap_undo" to requireNotNull(vm.uiState.value.swapUndo),
        )))
        assertEquals(vm.uiState.value.swapUndo, restoredVm.uiState.value.swapUndo)
        restoredVm.undoWorkoutSwap()
        runCurrent()
        val restored = requireNotNull(repo.openRunFlow.first())
        assertEquals(main, restored.tracks.single { it.id == main.id })
        assertEquals(partner, restored.tracks.single { it.id == partner.id })
        assertEquals(main.sets, track(vm, id))
    }

    @Test
    fun `Lift Day and swap sheets restore from a fresh saved state without moving focus`() = test {
        prepare(listOf(bench(superset = true)))
        val saved = SavedStateHandle()
        val vm = readyVm(savedState = saved)
        val id = vm.uiState.value.exercises.single().programExerciseId
        val focus = vm.uiState.value.workout?.focus
        vm.openLiftSheet(id, Slot.SS)
        runCurrent()
        assertEquals(focus, vm.uiState.value.workout?.focus)
        val lift = requireNotNull(saved.get<OpenSheet>("day_open_sheet"))
        val restored = readyVm(savedState = SavedStateHandle(mapOf("day_open_sheet" to lift)))
        assertEquals(lift, restored.uiState.value.openSheet)
        restored.openDaySheet()
        runCurrent()
        val day = requireNotNull(restored.uiState.value.openSheet)
        val dayRestored = readyVm(savedState = SavedStateHandle(mapOf("day_open_sheet" to day)))
        assertEquals(day, dayRestored.uiState.value.openSheet)
        dayRestored.openSwapSheet(0, Slot.SS)
        runCurrent()
        val swap = requireNotNull(dayRestored.uiState.value.openSheet)
        assertEquals(OpenSheet.Kind.SWAP, swap.kind)
        assertEquals(Slot.SS, swap.slot)
        val swapRestored = readyVm(savedState = SavedStateHandle(mapOf("day_open_sheet" to swap)))
        assertEquals(swap, swapRestored.uiState.value.openSheet)
        assertEquals(focus, swapRestored.uiState.value.workout?.focus)
    }

    @Test
    fun `plan swaps retain both halves logged rows in retired tracks`() = test {
        prepare(listOf(bench(superset = true)))
        val vm = readyVm()
        roundTwo(vm)
        val original = requireNotNull(repo.openRunFlow.first())
        val main = original.tracks.single { it.slot == Slot.MAIN }
        val partner = original.tracks.single { it.slot == Slot.SS }
        vm.swapDaySlot(0, "machine_chest")
        runCurrent()
        vm.setDaySlotSuperset(0, "bb_row")
        runCurrent()
        val run = requireNotNull(repo.openRunFlow.first())
        assertEquals(main.copy(retired = true), run.tracks.single { it.id == main.id })
        assertEquals(partner.copy(retired = true), run.tracks.single { it.id == partner.id })
        assertNull(vm.uiState.value.swapUndo)
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
        val reads = clock.reads
        at(109_999)
        assertNotNull(vm.uiState.value.workout!!.undo)
        assertEquals(reads, clock.reads)
        at(110_000)
        assertNull(vm.uiState.value.workout!!.undo)
        val readsAtExpiry = clock.reads
        assertTrue(readsAtExpiry > reads)
        at(170_000)
        assertEquals(readsAtExpiry, clock.reads)
        vm.undoLastLog()
        runCurrent()
        assertTrue(vm.uiState.value.exercises.single().rows[0].done)
    }

    @Test
    fun `★ without an undo target the clock produces no reads or UI emissions for a minute`() = test {
        prepare()
        val vm = readyVm()
        val id = vm.uiState.value.exercises.single().programExerciseId
        vm.toggleDone(id, 0, true, Slot.MAIN)
        runCurrent()
        assertTrue(vm.uiState.value.runOpen)
        assertNull(repo.workoutRecordFlow.first()?.undo)
        val emissions = mutableListOf<DayUiState>()
        backgroundScope.launch { vm.uiState.collect { emissions += it } }
        runCurrent()
        val count = emissions.size
        val reads = clock.reads
        at(160_000)
        assertEquals(count, emissions.size)
        assertEquals(reads, clock.reads)
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
    fun `selection before a run survives route restoration and the first focused log`() = test {
        prepare(listOf(bench(), ProgramExercise("plank", targetSets = 2)))
        val savedState = SavedStateHandle()
        val first = readyVm(savedState = savedState)
        assertFalse(first.uiState.value.runOpen)
        assertNull(repo.openRunFlow.first())
        val selectedId = first.uiState.value.exercises[1].programExerciseId
        val selectedKey = "exercise:$selectedId"
        val shown = WorkoutSelection.Shown.Group(selectedKey, false)
        first.selectWorkoutLift(selectedKey)
        runCurrent()
        assertEquals(shown, first.uiState.value.workout!!.shown)
        assertEquals(selectedKey, first.uiState.value.selectedPageKey)
        assertNull(repo.workoutRecordFlow.first())
        first.viewModelScope.cancel()
        val restored = readyVm(savedState = savedState)
        assertFalse(restored.uiState.value.runOpen)
        assertEquals(shown, restored.uiState.value.workout!!.shown)
        restored.logFocusedSet()
        runCurrent()
        val run = requireNotNull(repo.openRunFlow.first())
        assertTrue(restored.uiState.value.runOpen)
        assertTrue(track(restored, selectedId)[0].done)
        val record = requireNotNull(repo.workoutRecordFlow.first())
        assertEquals(run.id, record.runId)
        assertEquals(selectedKey, record.selectedKey)
    }

    @Test
    fun `next before a run persists selection when a sheet tick creates the run`() = test {
        prepare(listOf(bench(), ProgramExercise("plank", targetSets = 2)))
        val vm = readyVm()
        val firstId = vm.uiState.value.exercises[0].programExerciseId
        val selectedKey = "exercise:${vm.uiState.value.exercises[1].programExerciseId}"
        val shown = WorkoutSelection.Shown.Group(selectedKey, false)
        vm.nextWorkoutLift()
        runCurrent()
        assertEquals(shown, vm.uiState.value.workout!!.shown)
        assertEquals(selectedKey, vm.uiState.value.selectedPageKey)
        assertNull(repo.openRunFlow.first())
        assertNull(repo.workoutRecordFlow.first())
        vm.toggleDone(firstId, 0, true, Slot.MAIN)
        runCurrent()
        assertTrue(vm.uiState.value.runOpen)
        assertTrue(track(vm, firstId)[0].done)
        assertEquals(selectedKey, repo.workoutRecordFlow.first()!!.selectedKey)
        assertNull(repo.workoutRecordFlow.first()!!.undo)
        assertEquals(shown, vm.uiState.value.workout!!.shown)
        vm.viewModelScope.cancel()
        assertEquals(shown, readyVm().uiState.value.workout!!.shown)
    }

    @Test
    fun `★ a run started by the watch keeps the selection made before it`() = test {
        prepare(listOf(bench(), ProgramExercise("plank", targetSets = 2)))
        val vm = readyVm()
        val state = vm.uiState.value
        val firstId = state.exercises[0].programExerciseId
        val selectedKey = "exercise:${state.exercises[1].programExerciseId}"
        vm.selectWorkoutLift(selectedKey)
        runCurrent()
        assertNull(repo.openRunFlow.first())
        val applier = SetEditApplier(repo, syncStore, syncStore)
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(SetEditDelta(
            dayId = "A", programExerciseId = firstId, slot = Slot.MAIN, setIndex = 0,
            done = true, editedAtMillis = 100_001, planId = state.planId, runId = state.runId, exerciseId = "db_bench",
        )))
        runCurrent()
        assertTrue(vm.uiState.value.runOpen)
        assertNull(repo.workoutRecordFlow.first())
        assertEquals(WorkoutSelection.Shown.Group(selectedKey, false), vm.uiState.value.workout!!.shown)
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
    fun `★ undo preserves a watch edit to another row between validation and writing`() = test {
        prepare(restEnabled = true)
        val vm = readyVm()
        val state = vm.uiState.value
        val id = state.exercises.single().programExerciseId
        val before = track(vm, id)[0]
        vm.logFocusedSet()
        runCurrent()
        assertNotNull(vm.uiState.value.workout!!.undo)
        val applier = SetEditApplier(repo, syncStore, syncStore)
        var watchRows: List<LoggedSet>? = null
        vm.beforeWorkoutUndoWrite = {
            vm.beforeWorkoutUndoWrite = null
            assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(SetEditDelta(
                dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 1,
                reps = 11, editedAtMillis = 100_001, planId = state.planId, runId = state.runId,
                exerciseId = "db_bench",
            )))
            watchRows = track(vm, id)
        }
        vm.undoLastLog()
        runCurrent()
        val expected = requireNotNull(watchRows).mapIndexed { index, row -> if (index == 0) before else row }
        assertEquals(11, expected[1].reps)
        assertEquals(expected, track(vm, id))
        assertNull(repo.phoneRestFlow.first())
        assertNull(repo.workoutRecordFlow.first()!!.undo)
    }

    @Test
    fun `★ undo keeps the watch stamp so a replayed watch tick stays stale`() = test {
        prepare()
        val vm = readyVm()
        val state = vm.uiState.value
        val id = state.exercises.single().programExerciseId
        vm.logFocusedSet()
        runCurrent()
        val applier = SetEditApplier(repo, syncStore, syncStore)
        val watchTick = SetEditDelta(
            dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0,
            done = true, editedAtMillis = 100_001, planId = state.planId, runId = state.runId, exerciseId = "db_bench",
        )
        assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(watchTick))
        runCurrent()
        assertNotNull(vm.uiState.value.workout!!.undo)
        vm.undoLastLog()
        runCurrent()
        assertEquals(100_001L, track(vm, id)[0].watchEditedAt)
        assertEquals(SetEditApplier.Outcome.STALE, applier.apply(watchTick))
        runCurrent()
        assertTrue(!track(vm, id)[0].done)
    }

    @Test
    fun `★ undo declines a concurrent target edit without canceling rest or clearing the record`() = test {
        prepare(restEnabled = true)
        val vm = readyVm()
        val state = vm.uiState.value
        val id = state.exercises.single().programExerciseId
        vm.logFocusedSet()
        runCurrent()
        val record = repo.workoutRecordFlow.first()
        val rest = repo.phoneRestFlow.first()
        assertNotNull(record?.undo)
        assertNotNull(rest)
        val cancellations = runtime.cancellations
        val applier = SetEditApplier(repo, syncStore, syncStore)
        var watchRows: List<LoggedSet>? = null
        vm.beforeWorkoutUndoWrite = {
            vm.beforeWorkoutUndoWrite = null
            assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(SetEditDelta(
                dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0,
                reps = 11, editedAtMillis = 100_001, planId = state.planId, runId = state.runId,
                exerciseId = "db_bench",
            )))
            watchRows = track(vm, id)
        }
        vm.undoLastLog()
        runCurrent()
        assertEquals(requireNotNull(watchRows), track(vm, id))
        assertEquals(record, repo.workoutRecordFlow.first())
        assertEquals(rest, repo.phoneRestFlow.first())
        assertEquals(cancellations, runtime.cancellations)
        assertNull(vm.uiState.value.workout!!.undo)
    }

    @Test
    fun `★ a watch value edit between the tick commit and record write leaves no valid undo`() = test {
        prepare()
        val vm = readyVm()
        val state = vm.uiState.value
        val id = state.exercises.single().programExerciseId
        val before = track(vm, id)[0]
        val applier = SetEditApplier(repo, syncStore, syncStore)
        vm.afterWorkoutTickWrite = {
            vm.afterWorkoutTickWrite = null
            assertTrue(track(vm, id)[0].done)
            assertEquals(100_000L, track(vm, id)[0].completedAtMillis)
            assertNull(repo.workoutRecordFlow.first()?.undo)
            assertEquals(SetEditApplier.Outcome.APPLIED, applier.apply(SetEditDelta(
                dayId = "A", programExerciseId = id, slot = Slot.MAIN, setIndex = 0,
                reps = before.reps + 1, editedAtMillis = 100_001, planId = state.planId, runId = state.runId,
                exerciseId = "db_bench",
            )))
        }
        vm.logFocusedSet()
        runCurrent()
        val recorded = requireNotNull(repo.workoutRecordFlow.first()?.undo)
        assertEquals(before.reps, recorded.after.reps)
        val edited = track(vm, id)
        assertEquals(before.reps + 1, edited[0].reps)
        assertNull(vm.uiState.value.workout!!.undo)
        vm.undoLastLog()
        runCurrent()
        assertEquals(edited, track(vm, id))
    }

    @Test
    fun `★ sheet ticks keep selection and undo target while next changes no sets`() = test {
        prepare(listOf(bench(sets = 1), ProgramExercise("plank", targetSets = 1),
            ProgramExercise("bb_back_squat", targetSets = 1)))
        val vm = readyVm()
        val id = vm.uiState.value.exercises[0].programExerciseId
        val selectedId = vm.uiState.value.exercises[1].programExerciseId
        vm.logFocusedSet()
        runCurrent()
        val undo = repo.workoutRecordFlow.first()!!.undo
        assertNotNull(undo)
        vm.selectWorkoutLift("exercise:$selectedId")
        runCurrent()
        assertFalse(track(vm, selectedId).single().done)
        vm.toggleDone(selectedId, 0, true, Slot.MAIN)
        runCurrent()
        assertTrue(track(vm, selectedId).single().done)
        assertEquals(WorkoutSelection.Shown.Group("exercise:$selectedId", true), vm.uiState.value.workout!!.shown)
        assertTrue(vm.uiState.value.workout!!.completedView)
        assertEquals(undo, repo.workoutRecordFlow.first()!!.undo)
        val sets = track(vm, selectedId)
        vm.nextWorkoutLift()
        runCurrent()
        assertEquals(sets, track(vm, selectedId))
        assertEquals("exercise:${vm.uiState.value.exercises[2].programExerciseId}",
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
        var reads = 0
            private set
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun millis(): Long {
            reads++
            return now
        }
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
