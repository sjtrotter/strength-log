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
import io.github.sjtrotter.strengthlog.transfer.health.SessionPublisher
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

    private fun newViewModel(
        handle: SavedStateHandle = SavedStateHandle(),
        publisher: SessionPublisher = SessionPublisher.NoOp,
    ): DayViewModel =
        DayViewModel(repo, publisher, handle).also { vms += it }

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
    fun supersetTickWithMissingPartnerTrackWritesMainOnly() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val lateralId = slotId("db_lateral")

        vm.toggleDone(lateralId, index = 0, checked = true, isSuperset = true)
        advanceUntilIdle()

        assertTrue(track(lateralId, Slot.MAIN)!![0].done)
        // No junk empty SS row: the partner side must stay unseeded.
        assertNull(track(lateralId, Slot.SS))
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

    // --- tracking types P4: seconds edit persists like reps/weight ------------

    @Test
    fun changeSecondsPersistsOnlyTheEditedRow() = runVmTest {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay(
                        id = "A", title = "Test", emphasisLine = "",
                        exercises = listOf(ProgramExercise("plank", targetSets = 3)),
                        cardio = null,
                    ),
                ),
            ),
        )
        val vm = newViewModel()
        advanceUntilIdle()
        val plankId = slotId("plank")

        vm.changeSeconds(plankId, Slot.MAIN, index = 1, newSeconds = 60)
        advanceUntilIdle()

        val sets = track(plankId, Slot.MAIN)!!
        assertEquals(listOf(45, 60, 45), sets.map { it.seconds })
        // Seconds never cascades — the other rows' own values are untouched,
        // and reps/weight (always 0/0 for a REPS-free TIMED track) stay put.
        assertTrue(sets.all { it.reps == 0 && it.weightLb == 0.0 })
    }

    // --- ADD WEIGHT / REMOVE WEIGHT pill wiring (§4.2) --------------------------

    @Test
    fun weightSwapAffordanceOffersAddWeight_andSwappingAppliesTheDeclaredPairedId() = runVmTest {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay(
                        id = "A", title = "Test", emphasisLine = "",
                        exercises = listOf(ProgramExercise("plank", targetSets = 3)),
                        cardio = null,
                    ),
                ),
            ),
        )
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val plankId = slotId("plank")
        val card = vm.uiState.value.exercises.first { it.programExerciseId == plankId }
        val swap = card.weightSwap
        assertEquals("weighted_plank", swap?.targetExerciseId)
        assertFalse(swap!!.isRemove)
        collect.cancel()

        // Tapping the pill is exactly this: swap the slot to the pill's own
        // targetExerciseId, at the card's own position — never re-derived.
        vm.swapDaySlot(card.position, swap.targetExerciseId)
        advanceUntilIdle()

        val collectEdit = launch { vm.dayEditState.collect {} }
        advanceUntilIdle()
        assertEquals(
            "weighted_plank",
            vm.dayEditState.value.slots.first { it.programExerciseId == plankId }.exerciseId,
        )
        collectEdit.cancel()
    }

    @Test
    fun weightSwapAffordanceOffersRemoveWeight_forTheDeclaredPairTargetItself() = runVmTest {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay(
                        id = "A", title = "Test", emphasisLine = "",
                        exercises = listOf(ProgramExercise("weighted_plank", targetSets = 3)),
                        cardio = null,
                    ),
                ),
            ),
        )
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val card = vm.uiState.value.exercises.first { it.programExerciseId == slotId("weighted_plank") }

        assertEquals("plank", card.weightSwap?.targetExerciseId)
        assertTrue(card.weightSwap!!.isRemove)
        collect.cancel()
    }

    // --- day-edit sheet wiring (#11, spec §8.3) --------------------------------
    // dayEditState is WhileSubscribed, like uiState — each test collects it (same
    // reason collapseOverrideSurvivesViewModelRecreation collects uiState) so
    // `.value` reflects the DB instead of the flow's un-started default.

    @Test
    fun swapDaySlotReplacesTheExerciseAndClearsItsLog() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        val collect = launch { vm.dayEditState.collect {} }
        advanceUntilIdle()
        val ghostPosition = vm.dayEditState.value.slots.first { it.exerciseId == "ghost_unknown" }.position

        vm.swapDaySlot(ghostPosition, "hack_squat")
        advanceUntilIdle()

        val hackId = slotId("hack_squat")
        assertEquals("hack_squat", vm.dayEditState.value.slots.first { it.programExerciseId == hackId }.exerciseId)
        // The swapped-in exercise is known, so the normal seed pass fills its log.
        assertEquals(3, track(hackId, Slot.MAIN)!!.size)
        collect.cancel()
    }

    @Test
    fun addDaySlotAppendsAKnownExerciseToTheEditState() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        val collect = launch { vm.dayEditState.collect {} }
        advanceUntilIdle()
        val before = vm.dayEditState.value.slots.size

        vm.addDaySlot("face_pull")
        advanceUntilIdle()

        assertEquals(before + 1, vm.dayEditState.value.slots.size)
        assertTrue(vm.dayEditState.value.slots.any { it.exerciseId == "face_pull" })
        assertEquals(3, track(slotId("face_pull"), Slot.MAIN)!!.size)
        collect.cancel()
    }

    @Test
    fun removeDaySlotIsANoOpAtTheMinimumOfThree() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        val collect = launch { vm.dayEditState.collect {} }
        advanceUntilIdle()
        // insertProgram's day A has 4 slots; remove once (down to 3) succeeds...
        val firstPosition = vm.dayEditState.value.slots.first().position
        vm.removeDaySlot(firstPosition)
        advanceUntilIdle()
        assertEquals(3, vm.dayEditState.value.slots.size)

        // ...a further remove at the floor is refused.
        val nextPosition = vm.dayEditState.value.slots.first().position
        vm.removeDaySlot(nextPosition)
        advanceUntilIdle()
        assertEquals(3, vm.dayEditState.value.slots.size)
        collect.cancel()
    }

    @Test
    fun resetDayToTemplateRegeneratesFromWizardAnswers() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        val collect = launch { vm.dayEditState.collect {} }
        advanceUntilIdle()
        assertTrue(vm.dayEditState.value.slots.any { it.exerciseId == "ghost_unknown" })

        vm.resetDayToTemplate()
        advanceUntilIdle()

        // Regenerated from the default wizard answers — the hand-built fixture's
        // unknown placeholder exercise is gone.
        assertFalse(vm.dayEditState.value.slots.any { it.exerciseId == "ghost_unknown" })
        assertTrue(vm.dayEditState.value.slots.isNotEmpty())
        collect.cancel()
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

    // --- Health Connect publish trigger (#17, D7) --------------------------------

    @Test
    fun completeDayPublishesTheNewlyRecordedSession() = runVmTest {
        insertProgram()
        val published = mutableListOf<Long>()
        val recording = object : SessionPublisher {
            override suspend fun publish(sessionId: Long) {
                published += sessionId
            }
        }
        val vm = newViewModel(publisher = recording)
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.completeDay()
        advanceUntilIdle()

        // Exactly the session advanceDay just wrote is handed to the publisher.
        val sessionId = repo.sessionSummariesFlow.first().first().session.id
        assertEquals(listOf(sessionId), published)
        collect.cancel()
    }

    // --- session-start stamp (session-start capture) ----------------------------

    @Test
    fun firstDoneTickStampsSessionStart_secondTickDoesNotRestamp() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")
        assertNull(repo.sessionStartedAtFlow.first())

        vm.toggleDone(squatId, index = 0, checked = true, isSuperset = false)
        advanceUntilIdle()
        val stampedAt = repo.sessionStartedAtFlow.first()
        assertTrue("the first tick must stamp a session start", stampedAt != null)

        vm.toggleDone(squatId, index = 1, checked = true, isSuperset = false)
        advanceUntilIdle()
        assertEquals("a later tick must not move the stamp", stampedAt, repo.sessionStartedAtFlow.first())
    }

    @Test
    fun uncheckingAFirstTickDoesNotStamp() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")

        vm.toggleDone(squatId, index = 0, checked = false, isSuperset = false)
        advanceUntilIdle()

        assertNull("un-ticking is not performing a set", repo.sessionStartedAtFlow.first())
    }

    @Test
    fun weightAndRepEditsDoNotStampSessionStart() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")

        vm.changeWeight(squatId, Slot.MAIN, index = 4, newDisplayWeight = 999.0)
        vm.changeReps(squatId, Slot.MAIN, index = 4, newReps = 3)
        advanceUntilIdle()

        assertNull("weight/rep edits are planning, not performing", repo.sessionStartedAtFlow.first())
    }

    @Test
    fun supersetRoundTickStampsSessionStart() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val curlId = slotId("ez_curl")

        vm.toggleDone(curlId, index = 0, checked = true, isSuperset = true)
        advanceUntilIdle()

        assertTrue(repo.sessionStartedAtFlow.first() != null)
    }

    @Test
    fun clearChecksClearsTheSessionStartStamp() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")
        vm.toggleDone(squatId, index = 0, checked = true, isSuperset = false)
        advanceUntilIdle()
        assertTrue(repo.sessionStartedAtFlow.first() != null)

        vm.clearChecks()
        advanceUntilIdle()

        assertNull(repo.sessionStartedAtFlow.first())
    }

    @Test
    fun completeDayWritesTheStampedStartIntoTheSessionAndConsumesIt() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")

        vm.toggleDone(squatId, index = 0, checked = true, isSuperset = false)
        advanceUntilIdle()
        val stampedAt = repo.sessionStartedAtFlow.first()

        vm.completeDay()
        advanceUntilIdle()

        val session = repo.sessionSummariesFlow.first().first().session
        assertEquals(stampedAt, session.startedAt)
        assertNull("advanceDay must consume the stamp", repo.sessionStartedAtFlow.first())
        collect.cancel()
    }
}
