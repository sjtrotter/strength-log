package io.github.sjtrotter.strengthlog.ui.day

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.ProgramDay
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.model.SupersetPartner
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wiring tests for the [DayViewModel] seams the pure [DayScreenBuilder] tests
 * can't reach: the seeding trigger, mutation serialization (lost-update race),
 * paired-track writes, the empty-track guards, and the SavedStateHandle
 * collapse overrides. Robolectric + a real in-memory Room DB on the test
 * dispatcher, so `advanceUntilIdle` is deterministic end to end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayViewModelWiringTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private val vms = mutableListOf<DayViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries() // the test dispatcher runs on Robolectric's main thread
            .setQueryCoroutineContext(dispatcher)
            .build()
        storeScope = CoroutineScope(dispatcher + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("day-vm-settings", ".preferences_pb")
        }
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = SettingsStore(dataStore),
        )
    }

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        db.close()
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()): DayViewModel =
        DayViewModel(repo, handle).also { vms += it }

    /** Day A: a ramped main, an arms superset, an unknown-id slot, and a superset
     *  whose partner id is unknown (its SS track can never seed). */
    private suspend fun insertProgram() {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay(
                        id = "A",
                        title = "Test",
                        emphasisLine = "",
                        exercises = listOf(
                            ProgramExercise("bb_back_squat", isMain = true, targetSets = 6),
                            ProgramExercise("ez_curl", targetSets = 2, superset = SupersetPartner("rope_pushdown")),
                            ProgramExercise("ghost_unknown"),
                            ProgramExercise("db_lateral", targetSets = 2, superset = SupersetPartner("nope_unknown")),
                        ),
                        cardio = null,
                    ),
                ),
            ),
        )
    }

    private suspend fun slotId(exerciseId: String): Long =
        repo.daySlotsFlow("A").first().first { it.exercise.exerciseId == exerciseId }.programExerciseId

    private suspend fun track(id: Long, slot: String): List<LoggedSet>? =
        repo.logFlow("A").first().firstOrNull { it.programExerciseId == id && it.slot == slot }?.sets

    // --- seeding trigger (M2 rule: VM seeds once, then the log persists) -------

    @Test
    fun seedsEveryKnownSlotOnceIncludingSupersetPartner() = runVmTest {
        insertProgram()
        newViewModel()
        advanceUntilIdle()

        val squat = track(slotId("bb_back_squat"), Slot.MAIN)!!
        assertEquals(listOf(130.0, 165.0, 190.0, 210.0, 235.0, 175.0), squat.map { it.weightLb })
        assertEquals(listOf(60.0, 60.0), track(slotId("ez_curl"), Slot.MAIN)!!.map { it.weightLb })
        assertEquals(listOf(50.0, 50.0), track(slotId("ez_curl"), Slot.SS)!!.map { it.weightLb })
        // Unknown ids can't resolve a GOAL — no log rows for them.
        assertNull(track(slotId("ghost_unknown"), Slot.MAIN))
        assertNull(track(slotId("db_lateral"), Slot.SS))
    }

    @Test
    fun neverReseedsASlotTheLifterHasEdited() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")

        vm.changeWeight(squatId, Slot.MAIN, index = 4, newDisplayWeight = 245.0)
        advanceUntilIdle()
        // A program edit re-emits the day's slots, which re-runs the seed pass.
        repo.addExercise("A", ProgramExercise("face_pull", targetSets = 3))
        advanceUntilIdle()

        val squat = track(squatId, Slot.MAIN)!!
        // §11 cascade held: the edit survived the re-seed pass untouched.
        assertEquals(listOf(135.0, 170.0, 195.0, 220.0, 245.0, 185.0), squat.map { it.weightLb })
        assertEquals(listOf(40.0, 40.0, 40.0), track(slotId("face_pull"), Slot.MAIN)!!.map { it.weightLb })
    }

    // --- mutation serialization (lost-update race) -----------------------------

    @Test
    fun rapidTogglesOnTwoSetsBothPersist() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")

        vm.toggleDone(squatId, index = 0, checked = true, isSuperset = false)
        vm.toggleDone(squatId, index = 1, checked = true, isSuperset = false)
        advanceUntilIdle()

        val squat = track(squatId, Slot.MAIN)!!
        assertTrue("first rapid tick was clobbered", squat[0].done)
        assertTrue("second rapid tick was clobbered", squat[1].done)
    }

    // --- paired-track writes ----------------------------------------------------

    @Test
    fun supersetRoundTickUpdatesBothTracks() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val curlId = slotId("ez_curl")

        vm.toggleDone(curlId, index = 1, checked = true, isSuperset = true)
        advanceUntilIdle()

        assertTrue(track(curlId, Slot.MAIN)!![1].done)
        assertTrue(track(curlId, Slot.SS)!![1].done)
        assertFalse(track(curlId, Slot.MAIN)!![0].done)
    }

    @Test
    fun supersetAddAndRemoveKeepTracksAligned() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val curlId = slotId("ez_curl")

        vm.addSet(curlId, isSuperset = true)
        advanceUntilIdle()
        assertEquals(3, track(curlId, Slot.MAIN)!!.size)
        assertEquals(3, track(curlId, Slot.SS)!!.size)
        assertEquals(SetKind.EXTRA, track(curlId, Slot.SS)!!.last().kind)

        vm.removeSet(curlId, index = 0, isSuperset = true)
        advanceUntilIdle()
        assertEquals(2, track(curlId, Slot.MAIN)!!.size)
        assertEquals(2, track(curlId, Slot.SS)!!.size)
    }

    // --- empty-track guards ------------------------------------------------------

    @Test
    fun addSetOnAnUnseededTrackIsANoOp() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val ghostId = slotId("ghost_unknown")

        vm.addSet(ghostId, isSuperset = false)
        advanceUntilIdle()

        assertNull(track(ghostId, Slot.MAIN))
    }

    @Test
    fun addSetWithMissingPartnerTrackAddsToMainOnly() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val lateralId = slotId("db_lateral")

        vm.addSet(lateralId, isSuperset = true)
        advanceUntilIdle()

        assertEquals(3, track(lateralId, Slot.MAIN)!!.size)
        assertNull(track(lateralId, Slot.SS))
    }

    // --- collapse overrides survive process death (PLAN.md A6) -------------------

    @Test
    fun collapseOverrideSurvivesViewModelRecreation() = runVmTest {
        insertProgram()
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")
        assertFalse(vm.uiState.value.exercises.first { it.programExerciseId == squatId }.collapsed)

        vm.toggleCollapse(squatId)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.exercises.first { it.programExerciseId == squatId }.collapsed)
        collect.cancel()

        // Same SavedStateHandle, fresh ViewModel — the process-death analog.
        val revived = newViewModel(handle)
        val collectRevived = launch { revived.uiState.collect {} }
        advanceUntilIdle()
        assertTrue(
            "manual collapse override was lost across recreation",
            revived.uiState.value.exercises.first { it.programExerciseId == squatId }.collapsed,
        )
        collectRevived.cancel()
    }
}
