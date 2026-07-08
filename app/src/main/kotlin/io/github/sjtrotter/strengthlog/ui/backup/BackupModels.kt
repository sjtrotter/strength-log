package io.github.sjtrotter.strengthlog.ui.backup

import io.github.sjtrotter.strengthlog.domain.model.MovementPattern
import io.github.sjtrotter.strengthlog.transfer.backup.BackupError
import io.github.sjtrotter.strengthlog.transfer.csv.CsvImportError
import io.github.sjtrotter.strengthlog.transfer.csv.CsvImportPreview

/**
 * Everything the Data/Backup screen (PLAN.md A2, brief D9's `:app`-side UI
 * PR) renders. [pendingRestoreConfirm] gates the confirm-overwrite dialog for
 * a JSON restore that has already been read and validated (never a raw
 * exception the user has to interpret); [csvImport] gates the CSV
 * preview/confirm screen once a file has been parsed into a preview model.
 * Both are mutually exclusive with an in-flight [isBusy] operation.
 */
data class BackupUiState(
    val isBusy: Boolean = false,
    val message: StatusMessage? = null,
    val pendingRestoreConfirm: Boolean = false,
    val csvImport: CsvImportUiState? = null,
)

/** A one-shot status line (export/import result or failure); [isError] picks
 *  the accent the screen renders it in. */
data class StatusMessage(val text: String, val isError: Boolean)

/**
 * The CSV import preview/confirm screen's state (issue #16's pure preview
 * model, rendered here). [approvedPatterns] starts pre-filled with each
 * [io.github.sjtrotter.strengthlog.transfer.csv.UnmatchedExerciseName]'s
 * suggested pattern so confirming without edits is a deliberate accept of the
 * suggestion, not a silent guess — the user always sees and confirms the
 * screen first (PLAN.md A2: "never silent guessing").
 */
data class CsvImportUiState(
    val preview: CsvImportPreview,
    val approvedPatterns: Map<String, MovementPattern>,
) {
    val matchedSetCount: Int get() = preview.sessions.sumOf { it.sets.size }
    val sessionCount: Int get() = preview.sessions.size

    /** [io.github.sjtrotter.strengthlog.transfer.csv.CsvHistoryImporter.commit]
     *  requires an approval for every unmatched name; this mirrors that gate so
     *  the confirm button disables instead of the commit throwing. */
    val canCommit: Boolean get() = preview.unmatchedNames.all { it.name in approvedPatterns }

    companion object {
        /** Seeds [approvedPatterns] with every unmatched name's suggestion. */
        fun of(preview: CsvImportPreview): CsvImportUiState =
            CsvImportUiState(preview, preview.unmatchedNames.associate { it.name to it.suggestedPattern })
    }
}

/** Callbacks the screen forwards to [BackupViewModel] / the SAF launchers the
 *  route owns — mirrors [io.github.sjtrotter.strengthlog.ui.setup.SetupActions]'s shape. */
data class BackupActions(
    val onExportBackupClick: () -> Unit,
    val onImportBackupClick: () -> Unit,
    val onExportCsvClick: () -> Unit,
    val onImportCsvClick: () -> Unit,
    val onConfirmRestore: () -> Unit,
    val onCancelRestore: () -> Unit,
    val onUnmatchedPatternChange: (String, MovementPattern) -> Unit,
    val onConfirmCsvImport: () -> Unit,
    val onCancelCsvImport: () -> Unit,
    val onDismissMessage: () -> Unit,
    val onBack: () -> Unit,
)

/**
 * Maps the typed core errors to plain user-facing copy (PLAN.md A2: "surface
 * the typed BackupError/CsvImportError cases as clear user-facing messages").
 * One `when` per sealed hierarchy so a new case is a compile error here, not a
 * silently generic message.
 */
object TransferErrorMessages {

    fun of(error: BackupError): String = when (error) {
        is BackupError.TooLarge ->
            "That file is too large to be a strength-log backup (${error.bytes} bytes)."
        is BackupError.Malformed ->
            "That file isn't a strength-log backup (not valid JSON, or the wrong shape)."
        is BackupError.UnsupportedSchemaVersion ->
            "That backup was written by a version of the app this build can't read."
        is BackupError.InvalidPayload ->
            "That backup contains data this build can't decode — it may be corrupt."
        is BackupError.DanglingExerciseReference ->
            "That backup references an exercise (${error.exerciseId}) it doesn't define."
        is BackupError.InvalidCustomExercise ->
            "That backup has an invalid custom exercise and can't be restored."
        is BackupError.Inconsistent ->
            "That backup's data is inconsistent and can't be safely restored."
    }

    fun of(error: CsvImportError): String = when (error) {
        is CsvImportError.TooLarge ->
            "That file is too large to import (${error.bytes} bytes)."
        is CsvImportError.Empty ->
            "That CSV file has no data rows to import."
        is CsvImportError.MalformedCsv ->
            "That file isn't valid CSV — it looks truncated or corrupt."
        is CsvImportError.MissingColumns ->
            "That CSV is missing required column(s): ${error.missing.joinToString(", ")}."
        is CsvImportError.AmbiguousWeightUnit ->
            "Can't tell whether '${error.header}' is lb or kg — add a Weight Unit column."
        is CsvImportError.MalformedRow ->
            "Row ${error.line}: ${error.detail}"
        is CsvImportError.MissingApproval ->
            "Pick a movement pattern for: ${error.names.joinToString(", ")}."
    }
}
