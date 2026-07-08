package io.github.sjtrotter.strengthlog.transfer.csv

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.sjtrotter.strengthlog.data.ImportedSession
import io.github.sjtrotter.strengthlog.data.TrackerRepository
import io.github.sjtrotter.strengthlog.data.db.StrengthDatabase
import io.github.sjtrotter.strengthlog.data.db.entity.SessionSetEntity
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.data.db.entity.WorkoutSessionEntity
import io.github.sjtrotter.strengthlog.data.prefs.SettingsStore
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
 * [CsvHistoryService] end-to-end against a real in-memory Room DB +
 * TrackerRepository (Robolectric, not the emulator — D10 prefers JVM):
 * round-trip (export → import → same sessions), and every rejection path
 * asserting the database is left untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CsvHistoryServiceTest {

    private lateinit var db: StrengthDatabase
    private lateinit var repo: TrackerRepository
    private lateinit var service: CsvHistoryService
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File.createTempFile("csv-service-settings", ".preferences_pb")
        }
        repo = TrackerRepository(
            db = db,
            programDao = db.programDao(),
            sessionDao = db.sessionDao(),
            customExerciseDao = db.customExerciseDao(),
            settings = SettingsStore(dataStore),
        )
        service = CsvHistoryService(repo)
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private suspend fun seed(dayTitle: String, completedAt: Long, exerciseId: String, exerciseName: String, weightLb: Double, reps: Int) {
        val imported = ImportedSession(
            session = WorkoutSessionEntity(0, "csv:$dayTitle", dayTitle, null, completedAt, 0),
            sets = listOf(
                SessionSetEntity(0, 0, exerciseId, exerciseName, Slot.MAIN, 0, SetKind.WORK.name, weightLb, reps, done = true),
            ),
        )
        repo.importSessionHistory(listOf(imported), emptyList())
    }

    @Test
    fun `export of an empty history is just the header`() = runTest {
        val out = ByteArrayOutputStream()
        service.exportTo(out)
        val text = out.toString(Charsets.UTF_8.name())
        assertEquals(listOf(HISTORY_CSV_HEADER), Csv.parse(text))
    }

    @Test
    fun `export then import produces the same sessions`() = runTest {
        seed("Day A", 1_720_000_000_000L, "bb_back_squat", "Barbell Back Squat", 225.0, 5)
        seed("Day B", 1_720_100_000_000L, "bb_bench", "Barbell Bench Press", 185.0, 5)

        val out = ByteArrayOutputStream()
        service.exportTo(out)

        // A fresh device state — the round trip must reproduce the same
        // sessions from the CSV alone.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val freshDb = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        val freshScope = CoroutineScope(Dispatchers.IO + Job())
        val freshStore = PreferenceDataStoreFactory.create(scope = freshScope) {
            File.createTempFile("csv-service-fresh-settings", ".preferences_pb")
        }
        val freshRepo = TrackerRepository(freshDb, freshDb.programDao(), freshDb.sessionDao(), freshDb.customExerciseDao(), SettingsStore(freshStore))
        val freshService = CsvHistoryService(freshRepo)
        try {
            val preview = freshService.preview(ByteArrayInputStream(out.toByteArray()))
            assertTrue(preview.isFullyMatched)
            freshService.commit(preview)

            val sessions = freshRepo.sessionsFlow.first().sortedBy { it.completedAt }
            assertEquals(listOf("Day A", "Day B"), sessions.map { it.dayTitle })
            val history = freshRepo.exportSessionHistory()
            assertEquals(
                setOf("Barbell Back Squat" to 225.0, "Barbell Bench Press" to 185.0),
                history.sessionSets.map { it.exerciseName to it.weightLb }.toSet(),
            )
        } finally {
            freshDb.close()
            freshScope.cancel()
        }
    }

    @Test
    fun `kg weights round-trip export to import to export byte-identical`() = runTest {
        repo.setUnit(WeightUnit.KG)
        // Canonical storage is lb; these are exactly toLb(100 / 102.5 / 42.5 kg).
        val kgValues = listOf(100.0, 102.5, 42.5)
        val sets = kgValues.mapIndexed { index, kg ->
            SessionSetEntity(0, 0, "bb_back_squat", "Barbell Back Squat", Slot.MAIN, index, SetKind.WORK.name, WeightUnit.KG.toLb(kg), 5, done = true)
        }
        repo.importSessionHistory(
            listOf(ImportedSession(WorkoutSessionEntity(0, "csv:Day KG", "Day KG", null, 1_720_000_000_000L, 0), sets)),
            emptyList(),
        )

        val outA = ByteArrayOutputStream()
        service.exportTo(outA)
        val csvA = outA.toString(Charsets.UTF_8.name())

        // The kg display values survive the WeightUnit.fromLb SSOT exactly.
        val rows = Csv.parse(csvA).drop(1)
        assertEquals(listOf("100", "102.5", "42.5"), rows.map { it[5] })
        assertTrue(rows.all { it[6] == "kg" })

        // Re-importing that CSV (into a fresh KG device) and re-exporting is
        // byte-for-byte identical — toLb/fromLb reaches a fixed point, no drift.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val freshDb = Room.inMemoryDatabaseBuilder(context, StrengthDatabase::class.java).allowMainThreadQueries().build()
        val freshScope = CoroutineScope(Dispatchers.IO + Job())
        val freshStore = PreferenceDataStoreFactory.create(scope = freshScope) {
            File.createTempFile("csv-service-kg-settings", ".preferences_pb")
        }
        val freshRepo = TrackerRepository(freshDb, freshDb.programDao(), freshDb.sessionDao(), freshDb.customExerciseDao(), SettingsStore(freshStore))
        val freshService = CsvHistoryService(freshRepo)
        try {
            freshRepo.setUnit(WeightUnit.KG)
            freshService.commit(freshService.preview(ByteArrayInputStream(csvA.toByteArray())))
            val outB = ByteArrayOutputStream()
            freshService.exportTo(outB)
            assertEquals(csvA, outB.toString(Charsets.UTF_8.name()))
        } finally {
            freshDb.close()
            freshScope.cancel()
        }
    }

    @Test
    fun `an unmatched exercise name requires a confirmed pattern before anything commits`() = runTest {
        val text = "Date,Workout Name,Exercise Name,Weight,Weight Unit,Reps\n" +
            "2026-07-01 08:00:00,Day A,Mystery Move,100,lb,8\n"

        val preview = service.preview(ByteArrayInputStream(text.toByteArray()))
        assertEquals(listOf("Mystery Move"), preview.unmatchedNames.map { it.name })
        assertEquals(0, repo.sessionsFlow.first().size)

        // Committing without an approval throws and writes nothing.
        assertThrowsMissingApproval { service.commit(preview) }
        assertEquals(0, repo.sessionsFlow.first().size)
        assertEquals(0, repo.catalogFlow.first().entries.count { it.id.startsWith("custom_") })

        // Confirming creates the custom exercise and commits the session.
        service.commit(preview, mapOf("Mystery Move" to MovementPattern.H_PULL))
        assertEquals(1, repo.sessionsFlow.first().size)
        assertTrue(repo.catalogFlow.first().entries.any { it.name == "Mystery Move" })
    }

    @Test
    fun `a malformed CSV is rejected and the database stays empty`() = runTest {
        val badText = "Date,Workout Name,Exercise Name,Weight,Weight Unit,Reps\nnot-a-date,Day A,Squat,225,lb,5\n"
        assertThrowsMalformedRow { service.preview(ByteArrayInputStream(badText.toByteArray())) }
        assertEquals(0, repo.sessionsFlow.first().size)
    }

    private suspend fun assertThrowsMissingApproval(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("expected CsvImportError.MissingApproval")
        } catch (e: CsvImportError.MissingApproval) {
            // expected
        }
    }

    private suspend fun assertThrowsMalformedRow(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("expected CsvImportError.MalformedRow")
        } catch (e: CsvImportError.MalformedRow) {
            // expected
        }
    }
}
