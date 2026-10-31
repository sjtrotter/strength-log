package cloud.trotter.log.strength.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cloud.trotter.log.strength.data.db.StrengthDatabase
import cloud.trotter.log.strength.data.db.entity.ExerciseLogEntity
import cloud.trotter.log.strength.data.db.entity.PlanEntity
import cloud.trotter.log.strength.data.db.entity.ProgramExerciseEntity
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.data.prefs.SettingsStore
import cloud.trotter.log.strength.data.serialization.SetJson
import cloud.trotter.log.strength.data.serialization.PrescriptionJson
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.PrescribedTarget
import cloud.trotter.log.strength.domain.model.Prescription
import cloud.trotter.log.strength.domain.model.SetKind
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
 * The one-shot reps→seconds fixup reinterprets the reps a user logged for TIMED
 * entries (plank, ...) as seconds, touching only those slots, never deleting, and
 * never running twice.
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
            ProgramExerciseEntity(0, PlanEntity.LEGACY_ID, "A", position, exerciseId, false, 3, "", false, null, ""),
        )

    private suspend fun writeLog(slotId: Long, sets: List<LoggedSet>) =
        db.programDao().upsertLog(
            ExerciseLogEntity(PlanEntity.LEGACY_ID, "A", slotId, "main", SetJson.encodeSets(sets), 1L),
        )

    private suspend fun setsFor(slotId: Long): List<LoggedSet> =
        SetJson.decodeSets(
            db.programDao().logsForDay(PlanEntity.LEGACY_ID, "A")
                .first { it.programExerciseId == slotId }.setsJson,
        )

    @Test
    fun `a prescribed plank keeps its rep count while its legacy partner is fixed`() = runTest {
        val id = db.programDao().insertExercise(
            ProgramExerciseEntity(
                0, PlanEntity.LEGACY_ID, "A", 0, "plank", false, 3, "", false, "plank", "",
                prescriptionJson = PrescriptionJson.encode(Prescription(PrescribedTarget.Reps(12))),
            ),
        )
        val main = listOf(LoggedSet(0.0, 12, SetKind.WORK, done = true))
        writeLog(id, main)
        db.programDao().upsertLog(
            ExerciseLogEntity(PlanEntity.LEGACY_ID, "A", id, Slot.SS, SetJson.encodeSets(main), 1L),
        )

        // A restore clears the flag, so prescribed rows must be safe on every run.
        repo.runLegacyTimedFixupIfNeeded()

        val logs = db.programDao().allLogs().associate { it.slot to SetJson.decodeSets(it.setsJson) }
        assertEquals(main, logs[Slot.MAIN])
        assertEquals(listOf(LoggedSet(0.0, 0, SetKind.WORK, done = true, seconds = 12)), logs[Slot.SS])
    }

    @Test
    fun `plank reps become seconds, squat untouched, and it never runs twice`() = runTest {
        val plankSlot = slot("plank", 0)
        val squatSlot = slot("bb_back_squat", 1)
        // Legacy shape: reps is the only available field, so a 45s hold sits there.
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
