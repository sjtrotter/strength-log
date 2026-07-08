package io.github.sjtrotter.strengthlog.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TrackerRepository.sessionSummariesFlow] and [TrackerRepository.lastPerformed]
 * (issue #14, the Log screen + day-card "last time" chip): the new SQL added
 * alongside [SessionDao]'s existing queries. Robolectric + in-memory Room, same
 * pattern as [TrackerRepositorySessionHistoryImportTest] — the query is the
 * thing under test, not the reduction (that's [LastPerformedTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackerRepositoryHistoryReadTest {

    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("history-read-settings", ".preferences_pb")
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
        db.close()
        storeScope.cancel()
    }

    private fun session(completedAt: Long, dayTitle: String = "Lower") =
        WorkoutSessionEntity(id = 0, dayId = "A", dayTitle = dayTitle, startedAt = null, completedAt = completedAt, bodyweightLb = 180)

    private fun set(exerciseId: String, name: String, kind: SetKind, weightLb: Double, reps: Int, done: Boolean) =
        SessionSetEntity(
            id = 0, sessionId = 0, exerciseId = exerciseId, exerciseName = name, slot = Slot.MAIN,
            setIndex = 0, kind = kind.name, weightLb = weightLb, reps = reps, done = done,
        )

    /** Older session: a full, all-done squat ramp plus a bench accessory set. */
    private suspend fun seedOlderSession() {
        repo.importSessionHistory(
            listOf(
                ImportedSession(
                    session(completedAt = 1_000L),
                    listOf(
                        set("bb_back_squat", "Barbell Back Squat", SetKind.RAMP, 130.0, 5, done = true),
                        set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 235.0, 5, done = true),
                        set("bb_back_squat", "Barbell Back Squat", SetKind.BACKOFF, 175.0, 8, done = true),
                        set("bb_bench", "Barbell Bench Press", SetKind.WORK, 185.0, 5, done = true),
                    ),
                ),
            ),
            newCustomExercises = emptyList(),
        )
    }

    /** Newer session: the TOP set was left unchecked, so it must not count as
     *  "last performed" — only the completed ramp row should. */
    private suspend fun seedNewerSessionWithAnUncheckedTop() {
        repo.importSessionHistory(
            listOf(
                ImportedSession(
                    session(completedAt = 2_000L, dayTitle = "Lower — heavier"),
                    listOf(
                        set("bb_back_squat", "Barbell Back Squat", SetKind.RAMP, 135.0, 5, done = true),
                        set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 245.0, 3, done = false),
                    ),
                ),
            ),
            newCustomExercises = emptyList(),
        )
    }

    // --- sessionSummariesFlow --------------------------------------------------

    @Test
    fun sessionSummariesAreNewestFirstWithAnAggregatedSetCount() = runTest {
        seedOlderSession()
        seedNewerSessionWithAnUncheckedTop()

        val summaries = repo.sessionSummariesFlow.first()

        assertEquals(2, summaries.size)
        assertEquals("Lower — heavier", summaries[0].session.dayTitle) // newest first
        assertEquals(2, summaries[0].setCount)
        assertEquals("Lower", summaries[1].session.dayTitle)
        assertEquals(4, summaries[1].setCount)
    }

    @Test
    fun aSessionWithNoSetsStillAppearsWithACountOfZero() = runTest {
        repo.importSessionHistory(
            listOf(ImportedSession(session(completedAt = 500L), emptyList())),
            newCustomExercises = emptyList(),
        )

        val summaries = repo.sessionSummariesFlow.first()
        assertEquals(1, summaries.size)
        assertEquals(0, summaries.single().setCount)
    }

    // --- lastPerformed (batched, "last time" chip) -----------------------------

    @Test
    fun lastPerformedPicksTheNewestSessionsHeaviestDoneSetPerExercise() = runTest {
        seedOlderSession()
        seedNewerSessionWithAnUncheckedTop()

        val result = repo.lastPerformed(listOf("bb_back_squat", "bb_bench", "ghost_unknown"))

        // Newest session's TOP was unchecked, so its completed ramp row wins —
        // not the older session's (heavier, but stale) TOP, and not the
        // unchecked 245x3.
        assertEquals(LastPerformed(135.0, 5), result["bb_back_squat"])
        assertEquals(LastPerformed(185.0, 5), result["bb_bench"])
        assertNull(result["ghost_unknown"])
    }

    @Test
    fun lastPerformedBatchesTheWholeDayInOneCall() = runTest {
        seedOlderSession()

        // A day with three exercise ids resolves in the one call — this test
        // exists to pin the *shape* of the read (one query, one round trip),
        // not just its correctness per id.
        val result = repo.lastPerformed(listOf("bb_back_squat", "bb_bench", "never_logged"))

        assertEquals(2, result.size)
        assertEquals(LastPerformed(235.0, 5), result["bb_back_squat"])
    }

    @Test
    fun lastPerformedOfAnEmptyIdListIsAnEmptyMapWithoutQuerying() = runTest {
        assertEquals(emptyMap<String, LastPerformed>(), repo.lastPerformed(emptyList()))
    }

    @Test
    fun sessionSetsReturnsAllRowsForOneSessionInInsertionOrder() = runTest {
        seedOlderSession()
        val sessionId = repo.sessionsFlow.first().single().id

        val sets = repo.sessionSets(sessionId)

        assertEquals(listOf("bb_back_squat", "bb_back_squat", "bb_back_squat", "bb_bench"), sets.map { it.exerciseId })
    }
}
