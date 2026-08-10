package cloud.trotter.log.strength.transfer.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.domain.model.LoggedSet
import cloud.trotter.log.strength.domain.model.Program
import cloud.trotter.log.strength.domain.model.ProgramDay
import cloud.trotter.log.strength.domain.model.ProgramExercise
import cloud.trotter.log.strength.domain.model.SetKind
import cloud.trotter.log.strength.domain.units.WeightUnit
import cloud.trotter.log.strength.transfer.csv.CsvHistoryService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The #171 regression: a CSV import must not cost the user their backups.
 *
 * CSV carries no bodyweight column — not even this app's own export — so an
 * imported session records none. That absence used to be written as a 0, which
 * the backup validator rejected outright: after any CSV import, every full
 * backup was unrestorable, and the user only found out on a new device.
 *
 * This drives the whole path end to end against the real data layer: log a
 * session, export the CSV, import it back, take a full backup, wipe, restore,
 * and require the restored state to equal what was there before.
 */
@RunWith(AndroidJUnit4::class)
class ImportedHistoryBackupTest : BackupTestHarness() {

    private suspend fun seedOneLoggedSession() {
        repository.setUnit(WeightUnit.LB)
        val day = ProgramDay(
            id = "A",
            title = "Day A",
            emphasisLine = "Lower",
            exercises = listOf(ProgramExercise("bb_back_squat", isMain = true, targetSets = 3)),
            cardio = null,
        )
        repository.replaceProgram(Program(listOf(day)))
        val squatId = db.programDao().exerciseAt("A", 0)!!.id
        repository.updateSets("A", squatId, Slot.MAIN, listOf(LoggedSet(245.0, 5, SetKind.TOP, done = true)))
        repository.advanceDay("A")
    }

    private fun csvService() = CsvHistoryService(repository)

    private suspend fun exportCsv(): String {
        val out = ByteArrayOutputStream()
        csvService().exportTo(out)
        return out.toString(Charsets.UTF_8.name())
    }

    /** Every exercise name in our own export resolves in the catalog, so the
     *  confirm step needs no pattern approvals. */
    private suspend fun importCsv(text: String) {
        val csv = csvService()
        csv.commit(csv.preview(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
    }

    /** Everything the restore has to reproduce here. */
    private data class State(
        val program: Program,
        val sessions: List<Any>,
        val sessionSets: Map<Long, List<Any>>,
    )

    private suspend fun captureState(): State {
        val sessions = repository.sessionsFlow.first()
        return State(
            program = repository.programFlow.first(),
            sessions = sessions,
            sessionSets = sessions.associate { it.id to db.sessionDao().setsForSession(it.id) },
        )
    }

    @Test
    fun csv_imported_history_survives_a_full_backup_and_restore() = runTest {
        seedOneLoggedSession()
        // A byte-identical re-import of our own export now deduplicates (#216),
        // which is correct — so make the file foreign: a different completion
        // time is a different workout, and the import must land it.
        importCsv(exportCsv().replace(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}"""), "2020-01-02 03:04:05"))

        val sessions = repository.sessionsFlow.first()
        assertEquals(2, sessions.size)
        // The logged session kept the bodyweight it recorded; its CSV twin never
        // had one to record.
        assertNotNull(sessions.first { !it.dayId.startsWith("csv:") }.bodyweightLb)
        assertNull(sessions.first { it.dayId.startsWith("csv:") }.bodyweightLb)

        val before = captureState()
        val backup = service.export()

        service.import(emptyBackupJson())
        assertTrue(repository.sessionsFlow.first().isEmpty())

        // Before #171 this threw BackupError.Inconsistent and restored nothing.
        service.import(backup)
        assertEquals(before, captureState())
    }
}
