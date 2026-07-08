package io.github.sjtrotter.strengthlog.ui.backup

import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.transfer.backup.BackupError
import io.github.sjtrotter.strengthlog.transfer.csv.CsvImportError
import io.github.sjtrotter.strengthlog.transfer.csv.CsvImportPreview
import io.github.sjtrotter.strengthlog.transfer.csv.PreviewSession
import io.github.sjtrotter.strengthlog.transfer.csv.PreviewSet
import io.github.sjtrotter.strengthlog.transfer.csv.UnmatchedExerciseName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure logic behind the Data/Backup screen: every typed [BackupError]/
 * [CsvImportError] case maps to non-blank, non-generic copy (PLAN.md A2:
 * "surface the typed BackupError cases as clear user-facing messages"), and
 * [CsvImportUiState] pre-seeds/gates commit exactly the way
 * [io.github.sjtrotter.strengthlog.transfer.csv.CsvHistoryImporter.commit]
 * requires — never silent-guessing, never blocking on an already-approved name.
 */
class BackupModelsTest {

    // --- error -> message mapping: every case, no generic fallback ------------

    @Test
    fun everyBackupErrorCase_mapsToADistinctNonBlankMessage() {
        val errors = listOf(
            BackupError.TooLarge(bytes = 100, maxBytes = 50),
            BackupError.Malformed("not json"),
            BackupError.UnsupportedSchemaVersion(found = 99, supported = 1),
            BackupError.InvalidPayload("bad setsJson"),
            BackupError.DanglingExerciseReference("custom_ghost"),
            BackupError.InvalidCustomExercise("bad id"),
            BackupError.Inconsistent("dangling pointer"),
        )
        val messages = errors.map { TransferErrorMessages.of(it) }

        messages.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(errors.size, messages.toSet().size, "every case should produce distinct copy")
    }

    @Test
    fun everyCsvImportErrorCase_mapsToADistinctNonBlankMessage() {
        val errors = listOf(
            CsvImportError.TooLarge(bytes = 100, maxBytes = 50),
            CsvImportError.Empty(),
            CsvImportError.MalformedCsv("unterminated quote"),
            CsvImportError.MissingColumns(listOf("Date", "Weight")),
            CsvImportError.AmbiguousWeightUnit("Weight"),
            CsvImportError.MalformedRow(line = 3, detail = "unparsable date 'x'"),
            CsvImportError.MissingApproval(listOf("Cable Hack Squat")),
        )
        val messages = errors.map { TransferErrorMessages.of(it) }

        messages.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(errors.size, messages.toSet().size, "every case should produce distinct copy")
    }

    @Test
    fun missingColumns_and_missingApproval_messages_name_every_offender() {
        val columns = TransferErrorMessages.of(CsvImportError.MissingColumns(listOf("Date", "Weight")))
        assertTrue("Date" in columns && "Weight" in columns)

        val approval = TransferErrorMessages.of(CsvImportError.MissingApproval(listOf("Cable Hack Squat", "Reverse Nordic")))
        assertTrue("Cable Hack Squat" in approval && "Reverse Nordic" in approval)
    }

    // --- CsvImportUiState: seeding + commit gate --------------------------------

    private val previewWithUnmatched = CsvImportPreview(
        sessions = listOf(
            PreviewSession(
                dayTitle = "Push Day",
                completedAt = 0L,
                sets = listOf(
                    PreviewSet("Bench Press", "bench_press", 0, 185.0, 8),
                    PreviewSet("Cable Hack Squat", null, 0, 135.0, 10),
                ),
            ),
        ),
        unmatchedNames = listOf(
            UnmatchedExerciseName("Cable Hack Squat", MovementPattern.SQUAT_BILATERAL),
        ),
    )

    @Test
    fun of_seedsApprovedPatterns_withEveryUnmatchedNamesSuggestion() {
        val state = CsvImportUiState.of(previewWithUnmatched)
        assertEquals(MovementPattern.SQUAT_BILATERAL, state.approvedPatterns.getValue("Cable Hack Squat"))
    }

    @Test
    fun canCommit_isTrue_assoonAs_seeded_withoutAnyUserEdit() {
        // The user still has to view and confirm the screen (never silent-
        // guessing), but the default suggestion alone is enough to enable
        // the confirm button once it's on screen.
        val state = CsvImportUiState.of(previewWithUnmatched)
        assertTrue(state.canCommit)
    }

    @Test
    fun canCommit_isFalse_whenAnUnmatchedNameHasNoApproval() {
        val state = CsvImportUiState(previewWithUnmatched, approvedPatterns = emptyMap())
        assertFalse(state.canCommit)
    }

    @Test
    fun canCommit_isTrue_whenFullyMatched_noUnmatchedNames() {
        val fullyMatched = previewWithUnmatched.copy(unmatchedNames = emptyList())
        val state = CsvImportUiState(fullyMatched, approvedPatterns = emptyMap())
        assertTrue(state.canCommit)
    }

    @Test
    fun matchedSetCount_and_sessionCount_countEverySetAcrossSessions() {
        val state = CsvImportUiState.of(previewWithUnmatched)
        assertEquals(1, state.sessionCount)
        assertEquals(2, state.matchedSetCount)
    }
}
