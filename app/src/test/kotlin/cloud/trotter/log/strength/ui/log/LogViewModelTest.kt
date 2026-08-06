package cloud.trotter.log.strength.ui.log

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.ImportedSession
import cloud.trotter.log.strength.data.TrackerRepository
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.SessionSetEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.db.entity.WorkoutSessionEntity
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.transfer.health.HealthConnectReader
import cloud.trotter.log.strength.ui.log.share.ShareCardService
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [LogViewModel] wiring (issue #14; journal sections per docs/briefs/journal.md):
 * the reverse-chronological list shape, the lazy expand-fetches-once-then-caches
 * behavior, and that the journal sections come off real history — including
 * collapsing to nothing on a fresh install. The pure grouping/format/derivation
 * logic itself is [LogScreenBuilderTest]'s and [JournalBuilderTest]'s job — this
 * is the Robolectric + in-memory Room seam, same pattern as
 * [cloud.trotter.log.strength.ui.day.DayViewModelWiringTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope
    private val vms = mutableListOf<LogViewModel>()

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
            File.createTempFile("log-vm-settings", ".preferences_pb")
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

    // No Health Connect provider under Robolectric — the null-object reader makes
    // every HC read a degrade-safe empty, isolating these tests to own-history
    // (and keeping androidx.health entirely out of :app's test classpath).
    private val healthReader = HealthConnectReader.unavailable()

    // Fixed so the journal's "which week / which month is now" is deterministic.
    private val clock = Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC)

    private val shareCardService by lazy { ShareCardService(ApplicationProvider.getApplicationContext(), repo) }

    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()): LogViewModel =
        LogViewModel(repo, healthReader, shareCardService, handle, clock).also { vms += it }

    private fun session(dayId: String, dayTitle: String, completedAt: Long) =
        WorkoutSessionEntity(id = 0, dayId = dayId, dayTitle = dayTitle, startedAt = null, completedAt = completedAt, bodyweightLb = 180)

    private fun set(exerciseId: String, name: String) = SessionSetEntity(
        id = 0, sessionId = 0, exerciseId = exerciseId, exerciseName = name, slot = Slot.MAIN,
        setIndex = 0, kind = SetKind.WORK.name, weightLb = 100.0, reps = 8, done = true,
    )

    private suspend fun seedTwoSessions() {
        repo.importSessionHistory(
            listOf(
                ImportedSession(session("A", "Lower", 1_000L), listOf(set("bb_back_squat", "Barbell Back Squat"))),
                ImportedSession(session("B", "Upper", 2_000L), listOf(set("bb_bench", "Barbell Bench Press"), set("bb_row", "Barbell Row"))),
            ),
            newCustomExercises = emptyList(),
        )
    }

    @Test
    fun listsSessionsNewestFirstWithNoGroupsUntilExpanded() = runVmTest {
        seedTwoSessions()
        val vm = newViewModel()
        // uiState is WhileSubscribed — it only reflects upstream state while
        // something is actually collecting it.
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val sessions = vm.uiState.value.sessions
        assertEquals(listOf("Upper", "Lower"), sessions.map { it.dayTitle })
        assertEquals(2, sessions[0].setCount)
        assertEquals(1, sessions[1].setCount)
        assertTrue(sessions.none { it.expanded })
        assertTrue(sessions.all { it.exerciseGroups == null })
        collect.cancel()
    }

    @Test
    fun expandingASessionFetchesAndGroupsItsSets() = runVmTest {
        seedTwoSessions()
        val vm = newViewModel()
        // uiState is WhileSubscribed — it only reflects upstream state while
        // something is actually collecting it.
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val upperId = vm.uiState.value.sessions.first { it.dayTitle == "Upper" }.sessionId

        vm.toggleExpanded(upperId)
        advanceUntilIdle()

        val expanded = vm.uiState.value.sessions.first { it.sessionId == upperId }
        assertTrue(expanded.expanded)
        assertEquals(listOf("Barbell Bench Press", "Barbell Row"), expanded.exerciseGroups?.map { it.exerciseName })
        collect.cancel()
    }

    @Test
    fun togglingAnExpandedSessionAgainCollapsesIt() = runVmTest {
        seedTwoSessions()
        val vm = newViewModel()
        // uiState is WhileSubscribed — it only reflects upstream state while
        // something is actually collecting it.
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val sessionId = vm.uiState.value.sessions.first().sessionId

        vm.toggleExpanded(sessionId)
        advanceUntilIdle()
        vm.toggleExpanded(sessionId)
        advanceUntilIdle()

        val item = vm.uiState.value.sessions.first { it.sessionId == sessionId }
        assertFalse(item.expanded)
        assertNull(item.exerciseGroups)
        collect.cancel()
    }

    @Test
    fun expandingASecondSessionCollapsesTheFirst() = runVmTest {
        seedTwoSessions()
        val vm = newViewModel()
        // uiState is WhileSubscribed — it only reflects upstream state while
        // something is actually collecting it.
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val (first, second) = vm.uiState.value.sessions.map { it.sessionId }

        vm.toggleExpanded(first)
        advanceUntilIdle()
        vm.toggleExpanded(second)
        advanceUntilIdle()

        val sessions = vm.uiState.value.sessions.associateBy { it.sessionId }
        assertFalse(sessions.getValue(first).expanded)
        assertTrue(sessions.getValue(second).expanded)
        collect.cancel()
    }

    @Test
    fun restoringWithAnExpandedSessionIdFetchesItsSets() = runVmTest {
        seedTwoSessions()
        // Simulate process death: a fresh VM is constructed with a
        // SavedStateHandle that already carries an expanded session id (as it
        // would survive), but expandedSets — an in-memory cache — starts empty.
        val probe = newViewModel()
        val probeCollect = launch { probe.uiState.collect {} }
        advanceUntilIdle()
        val upperId = probe.uiState.value.sessions.first { it.dayTitle == "Upper" }.sessionId
        probeCollect.cancel()

        val restoredHandle = SavedStateHandle(mapOf("log_expanded_session" to upperId))
        val vm = newViewModel(restoredHandle)
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val expanded = vm.uiState.value.sessions.first { it.sessionId == upperId }
        assertTrue(expanded.expanded)
        assertEquals(listOf("Barbell Bench Press", "Barbell Row"), expanded.exerciseGroups?.map { it.exerciseName })
        collect.cancel()
    }

    // --- journal sections (docs/briefs/journal.md) ---------------------------

    /** Day A with the squat as its ramped main, so the trajectory section has a
     *  lift to draw and the calendar has a day letter to carry. */
    private suspend fun insertProgram() {
        repo.replaceProgram(
            Program(
                listOf(
                    ProgramDay(
                        id = "A",
                        title = "Lower",
                        emphasisLine = "",
                        exercises = listOf(ProgramExercise("bb_back_squat", isMain = true, targetSets = 6)),
                        cardio = null,
                    ),
                ),
            ),
        )
    }

    private fun topSet(weightLb: Double) = SessionSetEntity(
        id = 0, sessionId = 0, exerciseId = "bb_back_squat", exerciseName = "Barbell Back Squat",
        slot = Slot.MAIN, setIndex = 0, kind = SetKind.TOP.name, weightLb = weightLb, reps = 5, done = true,
    )

    /** Two squat sessions inside the fixed clock's twelve-week window. */
    private suspend fun seedSquatHistory() {
        val july1 = 1_782_907_200_000L // 2026-07-01T12:00:00Z
        val july8 = 1_783_512_000_000L // 2026-07-08T12:00:00Z
        repo.importSessionHistory(
            listOf(
                ImportedSession(session("A", "Lower", july1), listOf(topSet(225.0))),
                ImportedSession(session("A", "Lower", july8), listOf(topSet(235.0))),
            ),
            newCustomExercises = emptyList(),
        )
    }

    @Test
    fun journalDerivesTrajectoryVolumeAndCalendarFromHistory() = runVmTest {
        insertProgram()
        seedSquatHistory()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val journal = vm.uiState.value.journal
        val trajectory = journal.trajectories.single()
        assertEquals("Barbell Back Squat", trajectory.exerciseName)
        assertEquals(listOf(225f, 235f), trajectory.points.map { it.value })
        assertTrue("235 is the pinned squat GOAL", trajectory.goalMet)

        val volume = journal.volume
        assertNotNull(volume)
        assertEquals(2, volume!!.bars.count { it.trained })

        val calendar = journal.calendar
        assertNotNull(calendar)
        assertEquals("JULY 2026", calendar!!.title)
        assertEquals("A", calendar.days.single { it.dayOfMonth == 8 }.dayLetter)
        collect.cancel()
    }

    @Test
    fun journalIsEmptyOnAFreshInstall() = runVmTest {
        insertProgram()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val journal = vm.uiState.value.journal
        assertTrue(journal.trajectories.isEmpty())
        assertNull(journal.volume)
        assertNull(journal.calendar)
        collect.cancel()
    }

    @Test
    fun pagingTheCalendarWalksBackwardAndNeverPastTheCurrentMonth() = runVmTest {
        insertProgram()
        seedSquatHistory()
        val vm = newViewModel()
        val collect = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.pageCalendar(-1)
        advanceUntilIdle()
        assertEquals("JUNE 2026", vm.uiState.value.journal.calendar?.title)

        vm.pageCalendar(1)
        vm.pageCalendar(1)
        advanceUntilIdle()
        assertEquals("JULY 2026", vm.uiState.value.journal.calendar?.title)
        collect.cancel()
    }

    @Test
    fun theCalendarMonthSurvivesProcessDeath() = runVmTest {
        insertProgram()
        seedSquatHistory()
        val restored = newViewModel(SavedStateHandle(mapOf("log_calendar_month_offset" to -1)))
        val collect = launch { restored.uiState.collect {} }
        advanceUntilIdle()

        assertEquals("JUNE 2026", restored.uiState.value.journal.calendar?.title)
        collect.cancel()
    }

    // --- share (#103, docs/briefs/session-share.md) --------------------------
    //
    // The happy path — render, write, build the ACTION_SEND intent — is
    // [cloud.trotter.log.strength.ui.log.share.ShareCardServiceTest]'s
    // job under a plain `runBlocking`: [ShareCardService.buildShareIntent]
    // hops onto Dispatchers.IO internally (§3's "off the main thread"), which
    // is invisible to this class's virtual-time `TestDispatcher` and its
    // `advanceUntilIdle`, so it can't be asserted deterministically here. This
    // pins the one thing that *is* this class's concern: a miss leaves
    // [LogViewModel.pendingShare] untouched rather than crashing or firing a
    // stale intent.

    @Test
    fun shareSessionOfAnUnknownSessionLeavesPendingShareNull() = runVmTest {
        val vm = newViewModel()
        vm.shareSession(999L)
        advanceUntilIdle()
        assertNull(vm.pendingShare.value)
    }
}
