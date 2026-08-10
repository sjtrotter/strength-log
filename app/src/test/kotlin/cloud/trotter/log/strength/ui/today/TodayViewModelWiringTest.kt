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
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.glance.GlanceLines
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.day.DayViewModel
import cloud.trotter.log.strength.ui.day.FixedCardioClock
import cloud.trotter.log.strength.ui.day.InertCardioAlarm
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    private fun newTodayViewModel(): TodayViewModel =
        TodayViewModel(repo, kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate())).also { vm ->
            vms += vm
            vm.viewModelScope.launch { vm.uiState.collect {} }
        }

    private fun newDayViewModel(handle: SavedStateHandle = SavedStateHandle()): DayViewModel =
        DayViewModel(repo, SessionPublisher.NoOp, shareCardService, handle, kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()), FixedCardioClock(), InertCardioAlarm).also { vm ->
            vms += vm
            vm.viewModelScope.launch { vm.uiState.collect {} }
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
        assertEquals("NEXT IN ROTATION", state.overline)
        assertEquals(cloud.trotter.log.strength.ui.text.TodayActionKind.START, state.actionLabel.kind)
        assertEquals(suggestedDayId, state.actionLabel.dayId)

        val slotCount = repo.daySlotsFlow(checkNotNull(suggestedDayId)).first().size
        assertTrue("lifts must not be empty", state.lifts.isNotEmpty())
        assertEquals(slotCount, state.lifts.size)

        // Nothing has seeded a log yet, so these are previews — run through
        // :domain's seeding rules, not the slots' targetSets (3 and 4).
        assertEquals(listOf(6, 4), state.lifts.map { it.setCount })
        assertEquals(GlanceLines.statLine(2, 0, 10), state.statLine)

        // One rotation mark per program day, exactly one of them next, and it's
        // the suggested day.
        assertEquals(repo.programFlow.first().days.size, state.rotation.size)
        val nextMarks = state.rotation.filter { it.isNext }
        assertEquals(1, nextMarks.size)
        assertEquals(suggestedDayId, nextMarks.first().dayId)

        assertNull("no history yet", state.lastSession)
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
        assertEquals("IN PROGRESS", state.overline)

        assertEquals(cloud.trotter.log.strength.ui.text.TodayActionKind.CONTINUE, state.actionLabel.kind)
        assertEquals(3, state.actionLabel.done)

        // 6 seeded rows for the main + 4 for the accessory, from the live logs.
        assertEquals(10, state.actionLabel.total)
        assertEquals(GlanceLines.statLine(2, 3, 10), state.statLine)
    }

    // --- last-session strip ---------------------------------------------------------

    @Test
    fun lastSessionStrip_appearsOnceADayIsCompleted() = runVmTest {
        insertProgram()
        val today = newTodayViewModel()
        newDayViewModel() // triggers day A's seed pass
        advanceUntilIdle()

        repo.advanceDay("A")
        advanceUntilIdle()

        val lastSession = today.uiState.value.lastSession
        assertNotNull("expected a last-session line once a day is completed", lastSession)
        assertTrue("expected a set count fragment in: $lastSession", lastSession!!.contains(" sets"))
        // Device-local "MMM d, yyyy" (LogScreenBuilder.dateDisplay) always carries
        // a comma before the year — assert the shape, not the exact date.
        assertTrue(
            "expected a comma-and-year date fragment in: $lastSession",
            Regex(""", \d{4}""").containsMatchIn(lastSession),
        )
    }
}
