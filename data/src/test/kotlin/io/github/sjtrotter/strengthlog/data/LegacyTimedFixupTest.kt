package io.github.sjtrotter.strengthlog.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.db.entity.ExerciseLogEntity
import io.github.sjtrotter.strengthlog.data.db.entity.ProgramExerciseEntity
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.data.serialization.SetJson
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one-shot reps→seconds fixup (tracking-types P3, Decision 5) reinterprets the
 * reps a user logged for entries now tracked as TIMED (plank, ...) as seconds,
 * touching only those slots, never deleting, and never running twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyTimedFixupTest {

    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var settings: SettingsStore
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("legacy-fixup-settings", ".preferences_pb")
        }
        settings = SettingsStore(dataStore)
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = settings,
        )
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private suspend fun slot(exerciseId: String, position: Int): Long =
        db.programDao().insertExercise(
            ProgramExerciseEntity(0, "A", position, exerciseId, false, 3, "", false, null, ""),
        )

    private suspend fun writeLog(slotId: Long, sets: List<LoggedSet>) =
        db.programDao().upsertLog(ExerciseLogEntity("A", slotId, "main", SetJson.encodeSets(sets), "2026-07-11", 1L))

    private suspend fun setsFor(slotId: Long): List<LoggedSet> =
        SetJson.decodeSets(db.programDao().logsForDay("A").first { it.programExerciseId == slotId }.setsJson)

    @Test
    fun `plank reps become seconds, squat untouched, and it never runs twice`() = runTest {
        val plankSlot = slot("plank", 0)
        val squatSlot = slot("bb_back_squat", 1)
        // Legacy shape: the only field the old UI had was reps, so a 45s hold sits there.
        writeLog(plankSlot, listOf(LoggedSet(0.0, 45, SetKind.WORK, done = true), LoggedSet(0.0, 30, SetKind.WORK)))
        writeLog(squatSlot, listOf(LoggedSet(235.0, 5, SetKind.TOP, done = true)))

        repo.runLegacyTimedFixupIfNeeded()

        assertEquals(
            listOf(
                LoggedSet(0.0, 0, SetKind.WORK, done = true, seconds = 45),
                LoggedSet(0.0, 0, SetKind.WORK, seconds = 30),
            ),
            setsFor(plankSlot),
        )
        // A WEIGHTED lift's log is left exactly as it was.
        assertEquals(listOf(LoggedSet(235.0, 5, SetKind.TOP, done = true)), setsFor(squatSlot))
        assertTrue(settings.legacyTimedFixupDoneFlow.first())

        // Second run is a no-op: the flag short-circuits it, so the already-carried
        // hold is never clobbered back to zero.
        repo.runLegacyTimedFixupIfNeeded()
        assertEquals(
            listOf(
                LoggedSet(0.0, 0, SetKind.WORK, done = true, seconds = 45),
                LoggedSet(0.0, 0, SetKind.WORK, seconds = 30),
            ),
            setsFor(plankSlot),
        )
    }
}
