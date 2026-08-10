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
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SupersetPartner
import cloud.trotter.log.strength.transfer.health.SessionPublisher
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import cloud.trotter.log.strength.ui.today.TodayUiState
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
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two halves of "the day screen does not recompose the whole list to move
 * one tick" (#156). Both are invisible to every other test in this module — the
 * screen renders identically either way, it just costs four cards' worth of
 * work per tap instead of one — so they get a test that fails loudly rather
 * than a comment nobody re-reads.
 *
 * 1. The render models must be *declared* stable, or the Compose compiler falls
 *    back to comparing them by identity and a rebuilt-but-identical card never
 *    matches. That is what [stabilityBits] reads.
 * 2. The ViewModel must actually rebuild untouched cards *equal*, or declaring
 *    them stable buys nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScrollPathStabilityTest {

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
            .allowMainThreadQueries()
            .setQueryCoroutineContext(dispatcher)
            .build()
        storeScope = CoroutineScope(dispatcher + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("scroll-path-settings", ".preferences_pb")
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

    // --- 1: declared stability -------------------------------------------------

    /**
     * The Compose compiler writes its verdict about a class into a synthetic
     * `$stable` field: 0 means "known stable, compare it with `equals`", and
     * anything else means the recomposer must fall back to identity. A `List`
     * property is enough to make it non-zero on its own, which is why these
     * three carry `@Immutable`.
     */
    @Test
    fun theRenderModelsOnTheScrollPathAreStableToTheComposeCompiler() {
        for (klass in listOf(DayUiState::class.java, ExerciseCardState::class.java, TodayUiState::class.java)) {
            assertEquals(
                "${klass.simpleName} is no longer stable to the Compose compiler — every card on the " +
                    "day screen will recompose on every state change (#156). Did an @Immutable go missing, " +
                    "or did a mutable-looking property arrive?",
                0,
                stabilityBits(klass),
            )
        }
    }

    private fun stabilityBits(klass: Class<*>): Int =
        klass.getDeclaredField("\$stable").also { it.isAccessible = true }.getInt(null)

    /**
     * [DayUiState.doneSets]/[DayUiState.totalSets] are counted once per instance
     * instead of once per read (#156), which is only safe while they stay
     * *class-body* properties: those re-run their initializer on every
     * construction, `copy()` included. Promote either one to a constructor
     * parameter — an easy-looking tidy-up, since that is where every other
     * property lives — and `copy(exercises = …)` would carry the old counts
     * forward, leaving the header and the progress rule describing a day the
     * rows below them no longer show. `DestructiveActionsTest` already builds
     * exactly that `copy`.
     */
    @Test
    fun copyingInNewExercisesRecountsTheSets() {
        val threeRowsOneDone = card(id = 1, rows = 3, done = 1)
        val state = DayUiState(exercises = listOf(threeRowsOneDone))
        assertEquals(1, state.doneSets)
        assertEquals(3, state.totalSets)

        val changed = state.copy(exercises = listOf(card(id = 1, rows = 5, done = 4)))

        assertEquals("doneSets went stale across copy()", 4, changed.doneSets)
        assertEquals("totalSets went stale across copy()", 5, changed.totalSets)
    }

    private fun card(id: Long, rows: Int, done: Int) = ExerciseCardState(
        programExerciseId = id,
        position = 0,
        title = "Test",
        isMain = false,
        isSuperset = false,
        hasWarmupHint = false,
        goalDisplay = "100",
        perHand = false,
        allDone = false,
        collapsed = false,
        collapsedSummary = "",
        rows = List(rows) { i ->
            SetRowState(index = i, kindLabel = "${i + 1}", isTop = false, weightDisplay = 100.0, reps = 5, done = i < done)
        },
    )

    // --- 2: equal rebuilds -----------------------------------------------------

    @Test
    fun tickingOneCardLeavesEveryOtherCardEqual() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val squatId = slotId("bb_back_squat")
        val before = vm.uiState.value.exercises.associateBy { it.programExerciseId }
        vm.toggleDone(squatId, index = 0, checked = true, isSuperset = false)
        advanceUntilIdle()
        val after = vm.uiState.value.exercises.associateBy { it.programExerciseId }

        assertEquals("the day's slots changed under the test", before.keys, after.keys)
        assertNotEquals("the ticked card should have changed", before[squatId], after[squatId])
        for ((id, card) in after) {
            if (id == squatId) continue
            assertEquals(
                "card $id came back unequal from a tick on a different card, so Compose has to " +
                    "recompose it (#156)",
                before[id],
                card,
            )
        }
        collect.cancel()
    }

    // --- fixture ----------------------------------------------------------------

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun newViewModel(): DayViewModel =
        DayViewModel(repo, SessionPublisher.NoOp, shareCardService, SavedStateHandle(), kotlinx.coroutines.flow.MutableStateFlow(repo.currentDate()), FixedCardioClock(), InertCardioAlarm).also { vms += it }

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
                            ProgramExercise("face_pull", targetSets = 3),
                        ),
                        cardio = null,
                    ),
                ),
            ),
        )
    }

    private suspend fun slotId(exerciseId: String): Long =
        repo.daySlotsFlow("A").first().first { it.exercise.exerciseId == exerciseId }.programExerciseId
}
