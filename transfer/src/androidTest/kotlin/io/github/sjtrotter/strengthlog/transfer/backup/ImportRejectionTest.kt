package io.github.sjtrotter.strengthlog.transfer.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sjtrotter.strengthlog.data.db.entity.Slot
import io.github.sjtrotter.strengthlog.domain.model.Equipment
import io.github.sjtrotter.strengthlog.domain.model.LoggedSet
import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.domain.model.Program
import io.github.sjtrotter.strengthlog.domain.model.ProgramDay
import io.github.sjtrotter.strengthlog.domain.model.ProgramExercise
import io.github.sjtrotter.strengthlog.domain.model.SetKind
import io.github.sjtrotter.strengthlog.domain.units.WeightUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every rejected import must leave the database exactly as it was — no partial
 * writes. Each test seeds a known state, fingerprints it, attempts a bad import,
 * asserts the typed [BackupError], then asserts the fingerprint is unchanged.
 */
@RunWith(AndroidJUnit4::class)
class ImportRejectionTest : BackupTestHarness() {

    private data class Fingerprint(
        val program: Program,
        val sessions: List<Any>,
        val catalog: List<Any>,
        val unit: WeightUnit,
        val suggestedDay: String?,
    )

    private suspend fun seed() {
        repository.setUnit(WeightUnit.LB)
        repository.addCustomExercise("Cable Hack Squat", MovementPattern.SQUAT_BILATERAL, listOf(Equipment.MACHINE), false, 80.0)
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

    private suspend fun fingerprint() = Fingerprint(
        program = repository.programFlow.first(),
        sessions = repository.sessionsFlow.first(),
        catalog = repository.catalogFlow.first().entries,
        unit = repository.unitFlow.first(),
        suggestedDay = repository.suggestedDayFlow.first(),
    )

    // kotlin.test isn't on the androidTest classpath (it would drag JUnit 5 into a
    // JUnit 4 runner), so the expected-error assertion is spelled out.
    private suspend inline fun <reified E : BackupError> assertThrowsBackupError(
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return
            throw AssertionError("Expected ${E::class.simpleName}, got $e", e)
        }
        throw AssertionError("Expected ${E::class.simpleName}, but nothing was thrown")
    }

    private suspend inline fun <reified E : BackupError> assertRejectedAndUntouched(
        block: suspend () -> Unit,
    ) {
        seed()
        val before = fingerprint()
        assertThrowsBackupError<E>(block)
        assertEquals(before, fingerprint())
    }

    @Test
    fun unknown_schema_version_is_rejected_and_db_untouched() = runTest {
        assertRejectedAndUntouched<BackupError.UnsupportedSchemaVersion> {
            // Rewrite whatever the current schemaVersion is to an unsupported one,
            // so this stays correct across version bumps (v1 backups still restore).
            val bad = service.export().replaceFirst(Regex("\"schemaVersion\":\\d+"), "\"schemaVersion\":999")
            service.import(bad)
        }
    }

    @Test
    fun oversized_file_is_rejected_and_db_untouched() = runTest {
        seed()
        val before = fingerprint()
        val valid = service.export()
        val tinyCapService = BackupService(repository, BackupCodec(maxBytes = 32))
        assertThrowsBackupError<BackupError.TooLarge> { tinyCapService.import(valid) }
        assertEquals(before, fingerprint())
    }

    @Test
    fun corrupt_json_is_rejected_and_db_untouched() = runTest {
        assertRejectedAndUntouched<BackupError.Malformed> {
            service.import("{ this is not a backup ")
        }
    }

    @Test
    fun dangling_exercise_reference_is_rejected_and_db_untouched() = runTest {
        assertRejectedAndUntouched<BackupError.DanglingExerciseReference> {
            // Break the program's exercise id; the session set keeps its own copy,
            // but the program slot reference is what validation must catch.
            val bad = service.export().replaceFirst("\"exerciseId\":\"bb_back_squat\"", "\"exerciseId\":\"zzz_bogus\"")
            service.import(bad)
        }
    }

    @Test
    fun poisoned_sets_json_is_rejected_and_db_untouched() = runTest {
        assertRejectedAndUntouched<BackupError.InvalidPayload> {
            // The escaped quotes target the setsJson *string payload* (the live
            // log), not the plain-JSON session sets. An unknown SetKind there would
            // otherwise import cleanly and then crash every logFlow collect.
            val bad = service.export().replaceFirst("\\\"kind\\\":\\\"TOP\\\"", "\\\"kind\\\":\\\"BOGUS\\\"")
            service.import(bad)
        }
    }
}
