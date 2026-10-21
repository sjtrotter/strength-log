package cloud.trotter.log.strength.ui.today

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.CardioSessionEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.day.DayViewModel
import cloud.trotter.log.strength.ui.day.FixedCardioClock
import cloud.trotter.log.strength.ui.day.InertCardioAlarm
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import cloud.trotter.log.strength.ui.text.TodayCoachKind
import cloud.trotter.log.strength.ui.text.UiText
import cloud.trotter.log.strength.time.CivilTime
import cloud.trotter.log.strength.time.CivilTimeSource
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
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
 * Wiring tests for [TodayViewModel] — the seams [TodayScreenBuilderTest] can't
 * reach because it only ever feeds hand-picked numbers straight into
 * [TodayScreenBuilder]'s pure functions. What's left uncovered there, and
 * covered here instead: [TodayViewModel]'s own `contextFlow` day-resolution
 * against a real [TrackerRepository.suggestedDayFlow], the live combine over
 * real seeded/ticked log rows a [DayViewModel] writes on the same day, and the
 * real [TrackerRepository.sessionSummariesFlow]/`topSetHistoryFlow` history
 * once a day is actually advanced. Same Robolectric + in-memory Room + temp
 * DataStore fixture as [cloud.trotter.log.strength.ui.day.DayViewModelWiringTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayViewModelWiringTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var shareCardService: ShareCardService
    private lateinit var storeScope: CoroutineScope
    private val vms = mutableListOf<ViewModel>()

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
            File.createTempFile("today-vm-settings", ".preferences_pb")
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

    /** [TodayViewModel.uiState] is `WhileSubscribed`, same as [DayViewModel.uiState]
     *  in the reference fixture — an active collector is required for `.value`
     *  to track updates at all. */
    private fun newTodayViewModel(
        date: LocalDate = repo.currentDate(),
        handle: SavedStateHandle = SavedStateHandle(),
    ): TodayViewModel =
        TodayViewModel(repo, FixedCivilTimeSource(date), handle).also { vm ->
            vms += vm
            vm.viewModelScope.launch { vm.uiState.collect {} }
        }

    private class FixedCivilTimeSource(date: java.time.LocalDate) : CivilTimeSource {
        override val civilTime = kotlinx.coroutines.flow.MutableStateFlow(
            CivilTime(date.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault(), date),
        )
        override fun refresh() = Unit
    }

    private fun newDayViewModel(handle: SavedStateHandle = SavedStateHandle()): DayViewModel =
        DayViewModel(repo, SessionPublisher.NoOp, shareCardService, handle, kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()), FixedCardioClock(), InertCardioAlarm).also { vm ->
            vms += vm
            vm.viewModelScope.launch { vm.uiState.collect {} }
        }

    @Test
    fun oneActivePlanDoesNotOpenThePickerEvenWithAnArchivedPlan() = runVmTest {
        insertProgram()
        val selected = checkNotNull(repo.selectedPlanIdFlow.first())
        val archived = repo.duplicatePlan(selected, "Archived")
        assertTrue(repo.archivePlan(archived))
        val today = newTodayViewModel()
        advanceUntilIdle()

        assertEquals(listOf(selected), today.uiState.value.activePlans.map { it.id })
        today.showPlanPicker()
        advanceUntilIdle()
        assertFalse(today.uiState.value.planPickerOpen)
    }

    @Test
    fun selectingFromTodayClosesThePickerAndKeepsEachPlansRotation() = runVmTest {
        insertProgram()
        val strength = checkNotNull(repo.selectedPlanIdFlow.first())
        repo.renamePlan(strength, "Strength")
        repo.advanceDay("A")
        val running = repo.createAuthoredPlan("Running", "Easy run")
        repo.addDay(running, "Intervals")
        val archived = repo.duplicatePlan(strength, "Archived")
        assertTrue(repo.archivePlan(archived))
        val today = newTodayViewModel()
        advanceUntilIdle()

        assertEquals(listOf(strength, running), today.uiState.value.activePlans.map { it.id })
        assertEquals(listOf("Day B", "Easy run"), today.uiState.value.activePlans.map { it.nextDayTitle })
        assertEquals("Strength", today.uiState.value.activePlans.single { it.selected }.name)
        assertEquals("Day B", today.uiState.value.dayTitle)
        today.showPlanPicker()
        advanceUntilIdle()
        assertTrue(today.uiState.value.planPickerOpen)

        today.selectPlan(running)
        advanceUntilIdle()
        assertEquals(running, repo.selectedPlanIdFlow.first())
        assertEquals("Running", today.uiState.value.activePlans.single { it.selected }.name)
        assertEquals("Easy run", today.uiState.value.dayTitle)
        assertFalse(today.uiState.value.planPickerOpen)

        repo.advanceDay("A")
        advanceUntilIdle()
        assertEquals("Intervals", today.uiState.value.dayTitle)
        today.showPlanPicker()
        today.selectPlan(strength)
        advanceUntilIdle()
        assertEquals("Day B", today.uiState.value.dayTitle)
        assertFalse(today.uiState.value.planPickerOpen)

        today.selectPlan(running)
        advanceUntilIdle()
        assertEquals("Intervals", today.uiState.value.dayTitle)
    }

    @Test
    fun planPickerSurvivesSavedStateRecreationAndCanBeDismissed() = runVmTest {
        insertProgram()
        val selected = checkNotNull(repo.selectedPlanIdFlow.first())
        repo.duplicatePlan(selected, "Running")
        val handle = SavedStateHandle()
        val first = newTodayViewModel(handle = handle)
        advanceUntilIdle()
        first.showPlanPicker()
        advanceUntilIdle()
        assertTrue(first.uiState.value.planPickerOpen)

        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        first.viewModelScope.cancel()
        val restored = newTodayViewModel(handle = restoredHandle)
        advanceUntilIdle()
        assertTrue(restored.uiState.value.planPickerOpen)
        assertEquals(listOf("My plan", "Running"), restored.uiState.value.activePlans.map { it.name })

        restored.dismissPlanPicker()
        advanceUntilIdle()
        assertFalse(restored.uiState.value.planPickerOpen)
        val dismissedHandle = SavedStateHandle(restoredHandle.keys().associateWith { restoredHandle.get<Any?>(it) })
        restored.viewModelScope.cancel()
        val dismissed = newTodayViewModel(handle = dismissedHandle)
        advanceUntilIdle()
        assertFalse(dismissed.uiState.value.planPickerOpen)
    }

    @Test
    fun selectingAnArchivedOrMissingPlanStillRefusesTheSwitchAndClosesThePicker() = runVmTest {
        insertProgram()
        val selected = checkNotNull(repo.selectedPlanIdFlow.first())
        val other = repo.duplicatePlan(selected, "Running")
        repo.duplicatePlan(selected, "Physio")
        val today = newTodayViewModel()
        advanceUntilIdle()
        today.showPlanPicker()
        advanceUntilIdle()
        assertTrue(today.uiState.value.planPickerOpen)

        assertTrue(repo.archivePlan(other))
        today.selectPlan(other)
        advanceUntilIdle()
        assertEquals(selected, repo.selectedPlanIdFlow.first())
        assertEquals("Day A", today.uiState.value.dayTitle)
        assertFalse(today.uiState.value.planPickerOpen)

        today.showPlanPicker()
        advanceUntilIdle()
        assertTrue(today.uiState.value.planPickerOpen)
        today.selectPlan(Long.MAX_VALUE)
        advanceUntilIdle()
        assertEquals(selected, repo.selectedPlanIdFlow.first())
        assertFalse(today.uiState.value.planPickerOpen)
    }

    @Test
    fun emptyAuthoredDaysRemainVisibleAndRemovingTheSuggestedDayMovesToday() = runVmTest {
        val plan = repo.createAuthoredPlan("Physio", "Day A")
        repo.publishCreatedAuthoredPlan(plan)
        repo.addDay(plan, "Day B")
        val vm = newTodayViewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hasProgram)
        assertEquals("Day A", vm.uiState.value.dayTitle)
        assertTrue(vm.uiState.value.lifts.isEmpty())
        assertTrue(repo.removeDay(plan, "A"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hasProgram)
        assertEquals("B", vm.uiState.value.dayId)
        assertEquals("Day B", vm.uiState.value.dayTitle)
        assertTrue(vm.uiState.value.lifts.isEmpty())
    }

    /** A 3-day rotation. Day A carries both seeding shapes on purpose: a
     *  weighted main, whose `targetSets` of 3 seeds to 6 rows (4 ramps + TOP +
     *  back-off, §11), and an accessory, which seeds to its target of 4. The
     *  main's mismatch is exactly what a `targetSets`-based preview would get
     *  wrong, so every set-count assertion below runs through it. */
    private suspend fun insertProgram() {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay(
                        id = "A", title = "Day A", emphasisLine = "",
                        exercises = listOf(
                            ProgramExercise("bb_back_squat", isMain = true, targetSets = 3),
                            ProgramExercise("ez_curl", targetSets = 4),
                        ),
                        cardio = null,
                    ),
                    ProgramDay(
                        id = "B", title = "Day B", emphasisLine = "",
                        exercises = listOf(ProgramExercise("ez_curl", targetSets = 2, superset = SupersetPartner("rope_pushdown"))),
                        cardio = null,
                    ),
                    ProgramDay(
                        id = "C", title = "Day C", emphasisLine = "",
                        exercises = listOf(ProgramExercise("plank", targetSets = 3)),
                        cardio = null,
                    ),
                ),
            ),
        )
    }

    private suspend fun slotId(dayId: String, exerciseId: String): Long =
        repo.daySlotsFlow(dayId).first().first { it.exercise.exerciseId == exerciseId }.programExerciseId

    // --- fresh program, no ticks -------------------------------------------------

    @Test
    fun freshProgram_offersTheSuggestedDayAsNextInRotation() = runVmTest {
        insertProgram()
        val today = newTodayViewModel()
        advanceUntilIdle()

        val state = today.uiState.value
        val suggestedDayId = repo.suggestedDayFlow.first()

        assertTrue(state.hasProgram)
        assertEquals(suggestedDayId, state.dayId)
        assertEquals("Day A", state.dayTitle)
        assertEquals(cloud.trotter.log.strength.ui.text.TodayActionKind.START, state.actionLabel.kind)
        assertEquals(suggestedDayId, state.actionLabel.dayId)

        val slotCount = repo.daySlotsFlow(checkNotNull(suggestedDayId)).first().size
        assertTrue("lifts must not be empty", state.lifts.isNotEmpty())
        assertEquals(slotCount, state.lifts.size)

        // Nothing has seeded a log yet, so these are previews — run through
        // :domain's seeding rules, not the slots' targetSets (3 and 4).
        assertEquals(listOf(6, 4), state.lifts.map { it.setCount })
        assertEquals(10, state.actionLabel.total)
        assertNull("no history yet", state.coachLine)
        assertNull("no derived life line yet", state.lifeLine)
    }

    // --- the preview invariant --------------------------------------------------

    /**
     * The one thing Today must never get wrong: the set count it advertises is
     * the set count the workout opens with. Asserted on both sides of the seam
     * — an unseeded day, where Today runs `:domain`'s seeding rules without
     * writing anything, and the same day once [DayViewModel] has actually
     * written those rows.
     */
    @Test
    fun previewedSetCount_matchesTheDayScreenBeforeAndAfterItSeeds() = runVmTest {
        insertProgram()
        val today = newTodayViewModel()
        advanceUntilIdle()

        // Unseeded: nothing has opened this day, so this number is a promise.
        val previewed = today.uiState.value.lifts.sumOf { it.setCount }

        val dayVm = newDayViewModel()
        advanceUntilIdle()
        val onFirstOpen = dayVm.uiState.value.exercises.sumOf { it.rows.size }

        assertEquals("Today promised a different workout than the day screen opens", onFirstOpen, previewed)
        // And it keeps agreeing once the rows are real.
        assertEquals(onFirstOpen, today.uiState.value.lifts.sumOf { it.setCount })
    }

    // --- ticked sets on the suggested day -----------------------------------------

    @Test
    fun continueState_reflectsSetsTickedOnTheSuggestedDay() = runVmTest {
        insertProgram()
        val today = newTodayViewModel()
        val dayVm = newDayViewModel()
        advanceUntilIdle() // DayViewModel's own seed pass fills day A's log rows

        val squatId = slotId("A", "bb_back_squat")
        repeat(3) { i -> dayVm.toggleDone(squatId, index = i, checked = true, isSuperset = false) }
        advanceUntilIdle()

        val state = today.uiState.value
        assertEquals(cloud.trotter.log.strength.ui.text.TodayActionKind.CONTINUE, state.actionLabel.kind)
        assertEquals(3, state.actionLabel.done)

        // 6 seeded rows for the main + 4 for the accessory, from the live logs.
        assertEquals(10, state.actionLabel.total)
        assertEquals(UiText.TodayProgress(3, 10), state.coachLine)
    }

    // --- coach line ---------------------------------------------------------

    @Test
    fun lastTrainedDateFollowsPlanSelection() = runVmTest {
        insertProgram()
        val firstPlan = repo.selectedPlanIdFlow.first()!!
        val secondPlan = repo.duplicatePlan(firstPlan, "Test prep")
        db.sessionDao().insertSessions(listOf(
            historySession(firstPlan, "2026-10-15"),
            historySession(secondPlan, "2026-10-18"),
        ))
        val today = newTodayViewModel(LocalDate.of(2026, 10, 20))
        advanceUntilIdle()
        assertEquals(UiText.TodayCoach(TodayCoachKind.DAYS, "15 Oct", 5), today.uiState.value.coachLine)

        repo.selectPlan(secondPlan)
        advanceUntilIdle()
        assertEquals(UiText.TodayCoach(TodayCoachKind.DAYS, "18 Oct", 2), today.uiState.value.coachLine)

        repo.selectPlan(firstPlan)
        advanceUntilIdle()
        assertEquals(UiText.TodayCoach(TodayCoachKind.DAYS, "15 Oct", 5), today.uiState.value.coachLine)
    }

    @Test
    fun weekLineCountsOnlyTheSelectedPlansSessions() = runVmTest {
        insertProgram()
        val firstPlan = checkNotNull(repo.selectedPlanIdFlow.first())
        val secondPlan = repo.duplicatePlan(firstPlan, "Test prep")
        db.sessionDao().insertSessions(listOf(
            historySession(firstPlan, "2026-10-19"),
            historySession(firstPlan, "2026-10-20"),
        ))
        val today = newTodayViewModel(LocalDate.of(2026, 10, 20))
        advanceUntilIdle()
        assertEquals(UiText.TodayWeekLife(2, 3, 0), today.uiState.value.lifeLine)

        repo.selectPlan(secondPlan)
        advanceUntilIdle()
        assertEquals(0, repo.selectedPlanSessionSummariesFlow.first().size)
        assertNull(today.uiState.value.lifeLine)

        repo.selectPlan(firstPlan)
        advanceUntilIdle()
        assertEquals(UiText.TodayWeekLife(2, 3, 0), today.uiState.value.lifeLine)
    }

    @Test
    fun onlyTheLegacyPlanCountsNullPlanWorkoutHistory() = runVmTest {
        insertProgram()
        assertEquals(PlanEntity.LEGACY_ID, repo.selectedPlanIdFlow.first())
        val secondPlan = repo.duplicatePlan(PlanEntity.LEGACY_ID, "Test prep")
        db.sessionDao().insertSessions(listOf(
            historySession(PlanEntity.LEGACY_ID, "2026-10-15"),
            historySession(null, "2026-10-19"),
        ))
        val today = newTodayViewModel(LocalDate.of(2026, 10, 20))
        advanceUntilIdle()
        assertEquals(UiText.TodayCoach(TodayCoachKind.YESTERDAY), today.uiState.value.coachLine)
        assertEquals(UiText.TodayWeekLife(1, 3, 1), today.uiState.value.lifeLine)

        repo.selectPlan(secondPlan)
        advanceUntilIdle()
        assertNull(today.uiState.value.coachLine)
        assertNull(today.uiState.value.lifeLine)
    }

    @Test
    fun cardioCountsForItsPlanAndNullPlanCardioCountsForLegacy() = runVmTest {
        insertProgram()
        val firstPlan = repo.selectedPlanIdFlow.first()!!
        val secondPlan = repo.duplicatePlan(firstPlan, "Test prep")
        db.sessionDao().insertSession(historySession(firstPlan, "2026-10-15"))
        db.cardioSessionDao().insertAll(listOf(
            cardioSession(firstPlan, "2026-10-18"),
            cardioSession(secondPlan, "2026-10-19"),
        ))
        val today = newTodayViewModel(LocalDate.of(2026, 10, 20))
        advanceUntilIdle()
        assertEquals(UiText.TodayCoach(TodayCoachKind.DAYS, "18 Oct", 2), today.uiState.value.coachLine)

        db.cardioSessionDao().insert(cardioSession(null, "2026-10-20"))
        advanceUntilIdle()
        assertEquals(UiText.TodayCoach(TodayCoachKind.TODAY), today.uiState.value.coachLine)

        repo.selectPlan(secondPlan)
        advanceUntilIdle()
        assertEquals(UiText.TodayCoach(TodayCoachKind.YESTERDAY), today.uiState.value.coachLine)
    }

    private fun historySession(planId: Long?, date: String) = WorkoutSessionEntity(
        id = 0, dayId = "A", dayTitle = "Day A", startedAt = null,
        completedAt = completionAt(date), bodyweightLb = 180, planId = planId,
    )

    private fun cardioSession(planId: Long?, date: String) = CardioSessionEntity(
        dayId = "A", mode = "OUTDOOR_RUN", hard = false, label = "Zone 2",
        startedAt = completionAt(date) - 60_000, completedAt = completionAt(date),
        seconds = 60, stepsCompleted = 1, planId = planId,
    )

    private fun completionAt(date: String): Long =
        LocalDate.parse(date).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun coachLine_showsHistoryOnAFreshDayAndProgressOnceASetIsLogged() = runVmTest {
        insertProgram()
        val today = newTodayViewModel()
        newDayViewModel() // triggers day A's seed pass
        advanceUntilIdle()

        repo.advanceDay("A")
        advanceUntilIdle()

        val history = today.uiState.value.coachLine
        assertEquals(UiText.TodayCoach(TodayCoachKind.TODAY), history)

        val dayVm = newDayViewModel()
        advanceUntilIdle()
        val curlId = slotId("B", "ez_curl")
        dayVm.toggleDone(curlId, index = 0, checked = true, isSuperset = true)
        advanceUntilIdle()

        assertEquals(UiText.TodayProgress(1, 2), today.uiState.value.coachLine)
    }
}
