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
 * [TrackerRepository.personalRecords] (docs/briefs/performance-profile.md
 * Phase 1, the day-card "Best" chip): the SQL added to [SessionDao] alongside
 * [SessionDao.lastPerformedRows]. Robolectric + in-memory Room, same pattern
 * as [TrackerRepositoryHistoryReadTest] — the query is the thing under test,
 * not the reduction (that's [PersonalRecordTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackerRepositoryPersonalRecordTest {

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
            File.createTempFile("personal-record-settings", ".preferences_pb")
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

    private suspend fun importOneSession(completedAt: Long, dayTitle: String, sets: List<SessionSetEntity>) {
        repo.importSessionHistory(
            listOf(ImportedSession(session(completedAt, dayTitle), sets)),
            newCustomExercises = emptyList(),
        )
    }

    @Test
    fun anOlderHeavierSetBeatsANewerLighterOne() = runTest {
        importOneSession(1_000L, "Older — heavy", listOf(set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 245.0, 5, done = true)))
        importOneSession(2_000L, "Newer — lighter", listOf(set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 225.0, 5, done = true)))

        val result = repo.personalRecords(listOf("bb_back_squat"))

        assertEquals(PersonalRecord("bb_back_squat", 245.0, 5, 1_000L), result["bb_back_squat"])
    }

    @Test
    fun aWeightTieIsBrokenByMoreReps() = runTest {
        importOneSession(1_000L, "Session 1", listOf(set("bb_bench", "Barbell Bench Press", SetKind.WORK, 185.0, 5, done = true)))
        importOneSession(2_000L, "Session 2", listOf(set("bb_bench", "Barbell Bench Press", SetKind.WORK, 185.0, 8, done = true)))

        val result = repo.personalRecords(listOf("bb_bench"))

        assertEquals(PersonalRecord("bb_bench", 185.0, 8, 2_000L), result["bb_bench"])
    }

    @Test
    fun aWeightAndRepTieIsBrokenByTheEarliestAchievedAt() = runTest {
        importOneSession(2_000L, "Session — later", listOf(set("bb_bench", "Barbell Bench Press", SetKind.WORK, 185.0, 5, done = true)))
        importOneSession(1_000L, "Session — first", listOf(set("bb_bench", "Barbell Bench Press", SetKind.WORK, 185.0, 5, done = true)))

        val result = repo.personalRecords(listOf("bb_bench"))

        assertEquals(1_000L, result.getValue("bb_bench").achievedAtMillis)
    }

    @Test
    fun anUncheckedSetIsExcluded() = runTest {
        importOneSession(
            1_000L,
            "Session",
            listOf(set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 275.0, 5, done = false)),
        )

        val result = repo.personalRecords(listOf("bb_back_squat"))

        assertNull(result["bb_back_squat"])
    }

    @Test
    fun batchesAWholeDaysExerciseIdsInOneCall() = runTest {
        importOneSession(
            1_000L,
            "Session",
            listOf(
                set("bb_back_squat", "Barbell Back Squat", SetKind.TOP, 245.0, 5, done = true),
                set("bb_bench", "Barbell Bench Press", SetKind.WORK, 185.0, 5, done = true),
            ),
        )

        val result = repo.personalRecords(listOf("bb_back_squat", "bb_bench", "never_logged"))

        assertEquals(2, result.size)
        assertEquals(PersonalRecord("bb_back_squat", 245.0, 5, 1_000L), result["bb_back_squat"])
        assertEquals(PersonalRecord("bb_bench", 185.0, 5, 1_000L), result["bb_bench"])
        assertNull(result["never_logged"])
    }

    @Test
    fun anEmptyIdListIsAnEmptyMapWithoutQuerying() = runTest {
        assertEquals(emptyMap<String, PersonalRecord>(), repo.personalRecords(emptyList()))
    }
}
