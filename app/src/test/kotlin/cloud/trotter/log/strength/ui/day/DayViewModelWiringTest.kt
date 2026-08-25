package cloud.trotter.log.strength.ui.day

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.log.share.ShareCardService
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
import kotlinx.coroutines.test.advanceTimeBy
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
    private lateinit var shareCardService: ShareCardService
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
        shareCardService = ShareCardService(context, repo)
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
        restRuntime: cloud.trotter.log.strength.rest.RestRuntime = cloud.trotter.log.strength.rest.NoOpRestRuntime,
    ): DayViewModel =
        DayViewModel(repo, publisher, shareCardService, handle, kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()), FixedCardioClock(), InertCardioAlarm, restRuntime).also { vms += it }

    @Test
    fun firstTopSetEditDismissesTheMainHelperPermanently() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")
        val topIndex = track(squatId, Slot.MAIN)!!.indexOfFirst { it.kind == SetKind.TOP }

        assertFalse(repo.topSetHelperSeenFlow.first())
        assertTrue(vm.uiState.value.showMainHelper)

        vm.changeWeight(squatId, Slot.MAIN, topIndex, newDisplayWeight = 245.0)
        advanceUntilIdle()

        assertTrue(repo.topSetHelperSeenFlow.first())
        assertFalse(vm.uiState.value.showMainHelper)
        collect.cancel()
    }

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

    /**
     * The card's ⇄ chip (#122) end to end: [ExerciseCardState.position] through
     * the very [DayEditActions.onSwap] `SlotSwapSheet` invokes, down to
     * [TrackerRepository.swapExercise]. The slot row survives the swap (same
     * programExerciseId, so history stays keyed to it), its live sets are
     * discarded, and the seed pass refills from the new exercise's GOAL (§8.3).
     */
    @Test
    fun swappingFromACardKeepsTheSlotIdDropsItsLiveSetsAndReseedsFromTheNewGoal() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        val collectUi = launch { vm.uiState.collect {} }
        val collectEdit = launch { vm.dayEditState.collect {} }
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")

        vm.changeWeight(squatId, Slot.MAIN, index = 0, newDisplayWeight = 999.0)
        advanceUntilIdle()
        assertEquals(999.0, track(squatId, Slot.MAIN)!!.first().weightLb, 0.0)

        // Exactly what the chip does: the card's own position, handed to the
        // action DayScreen binds to the ViewModel.
        val card = vm.uiState.value.exercises.first { it.programExerciseId == squatId }
        val onSwap = DayEditActions(
            onSwap = vm::swapDaySlot,
            onAdd = {},
            onRemove = {},
            onSetSuperset = { _, _ -> },
            onRemoveSuperset = {},
            onResetToTemplate = {},
        ).onSwap
        onSwap(card.position, "hack_squat")
        advanceUntilIdle()

        assertEquals(
            "the swap must reuse the slot row, not replace it",
            squatId,
            slotId("hack_squat"),
        )
        assertEquals(
            "hack_squat",
            vm.dayEditState.value.slots.first { it.programExerciseId == squatId }.exerciseId,
        )
        // A fresh ramp off Hack Squat's own GOAL of 180 — the squat's 235 ramp
        // and the 999 edit on top of it are both gone.
        assertEquals(
            listOf(100.0, 125.0, 145.0, 160.0, 180.0, 135.0),
            track(squatId, Slot.MAIN)!!.map { it.weightLb },
        )
        collectUi.cancel()
        collectEdit.cancel()
    }

    @Test
    fun swapDaySlotClearsTheManualCollapseOverride() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        val collectUi = launch { vm.uiState.collect {} }
        val collectEdit = launch { vm.dayEditState.collect {} }
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")

        vm.toggleCollapse(squatId)
        advanceUntilIdle()
        assertTrue(
            "manual override should collapse the card",
            vm.uiState.value.exercises.first { it.programExerciseId == squatId }.collapsed,
        )

        val position = vm.dayEditState.value.slots.first { it.programExerciseId == squatId }.position
        vm.swapDaySlot(position, "hack_squat")
        advanceUntilIdle()
        assertFalse(
            "the stale override must not follow the slot onto the swapped-in exercise (#95)",
            vm.uiState.value.exercises.first { it.programExerciseId == squatId }.collapsed,
        )

        val sets = track(squatId, Slot.MAIN)!!.size
        repeat(sets) { i -> vm.toggleDone(squatId, index = i, checked = true, isSuperset = false) }
        advanceUntilIdle()
        assertTrue(
            "auto-collapse must still work on the swapped-in exercise",
            vm.uiState.value.exercises.first { it.programExerciseId == squatId }.collapsed,
        )

        collectUi.cancel()
        collectEdit.cancel()
    }

    // --- superset partner editing (#93) ---

    @Test
    fun setDaySlotSupersetSeedsThePartnerAlignedToTheLiveMainTrack() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        val collect = launch { vm.dayEditState.collect {} }
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")

        // Grow the main track past its seeded 6 rows before attaching a partner.
        vm.addSet(squatId, isSuperset = false)
        advanceUntilIdle()
        val mainBefore = track(squatId, Slot.MAIN)!!
        assertEquals(7, mainBefore.size)

        val position = vm.dayEditState.value.slots.first { it.programExerciseId == squatId }.position
        vm.setDaySlotSuperset(position, "rope_pushdown")
        advanceUntilIdle()

        val slot = vm.dayEditState.value.slots.first { it.programExerciseId == squatId }
        assertTrue(slot.isSuperset)
        assertEquals("rope_pushdown", slot.partnerExerciseId)
        // Row-aligned to the LIVE main track, extra set and all.
        assertEquals(7, track(squatId, Slot.SS)!!.size)
        assertEquals(mainBefore, track(squatId, Slot.MAIN))
        collect.cancel()
    }

    @Test
    fun setDaySlotSupersetOnAnExistingSupersetReseedsOnlyThePartnerTrack() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        val collect = launch { vm.dayEditState.collect {} }
        advanceUntilIdle()
        val curlId = slotId("ez_curl")
        assertEquals(listOf(50.0, 50.0), track(curlId, Slot.SS)!!.map { it.weightLb })

        val position = vm.dayEditState.value.slots.first { it.programExerciseId == curlId }.position
        vm.setDaySlotSuperset(position, "face_pull")
        advanceUntilIdle()

        assertEquals(
            "face_pull",
            vm.dayEditState.value.slots.first { it.programExerciseId == curlId }.partnerExerciseId,
        )
        // Reseeded from the new partner's own GOAL — none of rope_pushdown's 50s left.
        assertEquals(listOf(40.0, 40.0), track(curlId, Slot.SS)!!.map { it.weightLb })
        assertEquals(listOf(60.0, 60.0), track(curlId, Slot.MAIN)!!.map { it.weightLb })
        collect.cancel()
    }

    @Test
    fun removeDaySlotSupersetDropsThePartnerTrackAndKeepsTheMain() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        val collect = launch { vm.dayEditState.collect {} }
        advanceUntilIdle()
        val curlId = slotId("ez_curl")

        val position = vm.dayEditState.value.slots.first { it.programExerciseId == curlId }.position
        vm.removeDaySlotSuperset(position)
        advanceUntilIdle()

        val slot = vm.dayEditState.value.slots.first { it.programExerciseId == curlId }
        assertFalse(slot.isSuperset)
        assertNull(slot.partnerExerciseId)
        assertNull(track(curlId, Slot.SS))
        assertEquals(listOf(60.0, 60.0), track(curlId, Slot.MAIN)!!.map { it.weightLb })
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
    fun completeDayWithNothingLoggedDoesNotWriteOrAdvance() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.completeDay()
        advanceUntilIdle()

        assertTrue(repo.sessionSummariesFlow.first().isEmpty())
        assertEquals("A", repo.suggestedDayFlow.first())
        assertNull(vm.sessionReceipt.value)
        collect.cancel()
    }

    @Test
    fun completeDayPublishesTheNewlyRecordedSession() = runVmTest {
        insertProgram()
        val published = mutableListOf<Long>()
        val recording = object : SessionPublisher {
            override suspend fun publish(sessionId: Long) {
                published += sessionId
            }

            // The day flow never backfills — only the Log screen's offer does.
            override suspend fun publishAll(sessionIds: List<Long>) = false
        }
        val vm = newViewModel(publisher = recording)
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.toggleDone(slotId("bb_back_squat"), index = 0, checked = true, isSuperset = false)
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
    fun tickingWithPhoneRestTimerDisabledStartsNothing() = runVmTest {
        insertProgram()
        repo.setPhoneRestTimerEnabled(false)
        val runtime = object : cloud.trotter.log.strength.rest.RestRuntime {
            override val available = true
            override fun arm(rest: cloud.trotter.log.strength.domain.standards.PhoneRest) = Unit
            override fun cancel() = Unit
            override fun complete() = true
        }
        val vm = newViewModel(restRuntime = runtime)
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.toggleDone(slotId("bb_back_squat"), index = 0, checked = true, isSuperset = false)
        advanceUntilIdle()

        assertNull(repo.phoneRestFlow.first())
        collect.cancel()
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

    // --- keep-screen-on, now a real preference (#125) -------------------------

    @Test
    fun theKeepScreenOnToggleReachesDataStore() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        assertFalse("absent means off", repo.keepScreenOnFlow.first())

        vm.setKeepScreenOn(true)
        advanceUntilIdle()

        assertTrue(repo.keepScreenOnFlow.first())

        vm.setKeepScreenOn(false)
        advanceUntilIdle()

        assertFalse(repo.keepScreenOnFlow.first())
    }

    @Test
    fun theSwitchInTheUiStateReadsThePersistedPreference() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.keepScreenOn)

        vm.setKeepScreenOn(true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.keepScreenOn)
        collect.cancel()
    }

    /**
     * The bug #125 exists to kill. The flag used to live in [SavedStateHandle],
     * so it was scoped to one screen's one visit: a cold launch — or simply
     * walking to the Log and back, which builds this ViewModel afresh — reset it
     * to OFF while the lifter was still mid-workout. A new ViewModel with an
     * empty handle must now still see ON.
     */
    @Test
    fun keepScreenOnOutlivesTheViewModelThatSetIt() = runVmTest {
        insertProgram()
        newViewModel().setKeepScreenOn(true)
        advanceUntilIdle()

        val reborn = newViewModel(handle = SavedStateHandle())
        val collect = launch { reborn.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(reborn.uiState.value.keepScreenOn)
        collect.cancel()
    }

    // --- the remove-set undo window (#124) ------------------------------------
    //
    // Time matters here, so these advance the virtual clock deliberately:
    // `advanceUntilIdle` would run past the 5s window and close the offer.

    @Test
    fun undoPutsTheRemovedRowBackExactlyWhereItWas() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")
        // Give the row something of its own to lose: edited reps and a tick.
        vm.changeReps(squatId, Slot.MAIN, index = 1, newReps = 9)
        vm.toggleDone(squatId, index = 1, checked = true, isSuperset = false)
        advanceUntilIdle()
        val before = track(squatId, Slot.MAIN)!!

        vm.removeSet(squatId, index = 1, isSuperset = false)
        advanceTimeBy(1_000)

        assertEquals(before.size - 1, track(squatId, Slot.MAIN)!!.size)
        val offer = vm.removedSets.value.last()
        assertEquals(squatId, offer.programExerciseId)
        assertEquals(1, offer.index)

        vm.undoRemoveSet()
        advanceTimeBy(1_000)

        // Whole-row equality: kind, weight, reps, seconds and the tick.
        assertEquals(before, track(squatId, Slot.MAIN)!!)
        assertTrue("taking the undo closes the offer", vm.removedSets.value.isEmpty())
    }

    @Test
    fun undoOfTheTopRowRestoresTheCascadedTrackUntouched() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")
        // §11's cascade: the TOP edit re-derives every RAMP and the BACK-OFF.
        vm.changeWeight(squatId, Slot.MAIN, index = 4, newDisplayWeight = 245.0)
        advanceUntilIdle()
        val cascaded = track(squatId, Slot.MAIN)!!
        assertEquals(listOf(135.0, 170.0, 195.0, 220.0, 245.0, 185.0), cascaded.map { it.weightLb })

        vm.removeSet(squatId, index = 4, isSuperset = false) // the TOP row itself
        advanceTimeBy(1_000)
        assertEquals(listOf(135.0, 170.0, 195.0, 220.0, 185.0), track(squatId, Slot.MAIN)!!.map { it.weightLb })

        vm.undoRemoveSet()
        advanceTimeBy(1_000)

        // Nothing re-derived on the way out or back: removing a row is not an
        // edit, so the cascaded weights are exactly where they were.
        assertEquals(cascaded, track(squatId, Slot.MAIN)!!)
    }

    @Test
    fun twoRemovalsUndoInReverseOrderBackToTheOriginalList() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")
        val original = track(squatId, Slot.MAIN)!!

        // The second removal sits *after* the first, so its captured index is an
        // index into the already-shortened list — which is exactly what a strict
        // LIFO undo needs it to be.
        vm.removeSet(squatId, index = 1, isSuperset = false)
        advanceTimeBy(500)
        vm.removeSet(squatId, index = 3, isSuperset = false)
        advanceTimeBy(500)

        assertEquals(original.size - 2, track(squatId, Slot.MAIN)!!.size)
        assertEquals(listOf(1, 3), vm.removedSets.value.map { it.index })

        vm.undoRemoveSet()
        advanceTimeBy(500)
        assertEquals(listOf(1), vm.removedSets.value.map { it.index })

        vm.undoRemoveSet()
        advanceTimeBy(500)

        assertEquals(original, track(squatId, Slot.MAIN)!!)
        assertTrue(vm.removedSets.value.isEmpty())
    }

    @Test
    fun aSecondRemovalDoesNotOrphanTheFirstOffer() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")

        vm.removeSet(squatId, index = 0, isSuperset = false)
        advanceTimeBy(500)
        vm.removeSet(squatId, index = 0, isSuperset = false)
        advanceTimeBy(500)

        assertEquals("both removals are still on the stack", 2, vm.removedSets.value.size)
        // The shared window restarted on the second push, so the stack outlives
        // the deadline the first removal had set (t=5000; we are now at 5400).
        advanceTimeBy(4_400)
        assertEquals(2, vm.removedSets.value.size)
    }

    @Test
    fun undoReinstatesBothTracksOfASuperset() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val curlId = slotId("ez_curl")
        val mainBefore = track(curlId, Slot.MAIN)!!
        val partnerBefore = track(curlId, Slot.SS)!!

        vm.removeSet(curlId, index = 0, isSuperset = true)
        advanceTimeBy(1_000)
        assertEquals(1, track(curlId, Slot.MAIN)!!.size)
        assertEquals(1, track(curlId, Slot.SS)!!.size)

        vm.undoRemoveSet()
        advanceTimeBy(1_000)

        assertEquals(mainBefore, track(curlId, Slot.MAIN)!!)
        assertEquals(partnerBefore, track(curlId, Slot.SS)!!)
    }

    @Test
    fun theOfferExpiresAndAStaleUndoChangesNothing() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val squatId = slotId("bb_back_squat")

        vm.removeSet(squatId, index = 0, isSuperset = false)
        advanceTimeBy(1_000)
        assertEquals(1, vm.removedSets.value.size)
        val afterRemove = track(squatId, Slot.MAIN)!!

        advanceUntilIdle() // past the 5s window
        assertTrue("the offer must expire on its own", vm.removedSets.value.isEmpty())

        vm.undoRemoveSet()
        advanceUntilIdle()
        assertEquals(afterRemove, track(squatId, Slot.MAIN)!!)
    }

    @Test
    fun refusingToRemoveTheLastRowOffersNothingToUndo() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        advanceUntilIdle()
        val curlId = slotId("ez_curl")

        vm.removeSet(curlId, index = 0, isSuperset = true)
        advanceUntilIdle() // let that offer expire so it can't be mistaken for the next one
        assertEquals(1, track(curlId, Slot.MAIN)!!.size)

        vm.removeSet(curlId, index = 0, isSuperset = true)
        advanceTimeBy(1_000)

        assertEquals(1, track(curlId, Slot.MAIN)!!.size)
        assertTrue("nothing was removed, so nothing is offered back", vm.removedSets.value.isEmpty())
    }
}
