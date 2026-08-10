package cloud.trotter.log.strength.ui.backup

import cloud.trotter.log.strength.data.prefs.RestoreInterruption
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.transfer.backup.BackupError
import cloud.trotter.log.strength.transfer.csv.CsvImportError
import cloud.trotter.log.strength.transfer.csv.CsvImportPreview
import cloud.trotter.log.strength.ui.text.BackupErrorKind
import cloud.trotter.log.strength.ui.text.UiText

/**
 * Everything the Data/Backup screen (PLAN.md A2, brief D9's `:app`-side UI
 * PR) renders. [pendingRestoreConfirm] gates the confirm-overwrite dialog for
 * a JSON restore that has already been read and validated (never a raw
 * exception the user has to interpret); [csvImport] gates the CSV
 * preview/confirm screen once a file has been parsed into a preview model.
 * Both are mutually exclusive with an in-flight [isBusy] operation.
 *
 * [isBusy] and [restoreInFlight] are different questions and the screen asks
 * them separately. [isBusy] means "an operation is running": it disables the
 * action buttons so a second file picker can't stack on the first. Only
 * [restoreInFlight] shuts the *exits* (#172), and only a confirmed full restore
 * sets it — that is the one operation whose two stores can end up disagreeing if
 * the screen goes away, and gating back on every export would be a regression
 * for operations that can't hurt anything.
 */
data class BackupUiState(
    val isBusy: Boolean = false,
    val restoreInFlight: Boolean = false,
    val message: StatusMessage? = null,
    val pendingRestoreConfirm: Boolean = false,
    val csvImport: CsvImportUiState? = null,
    val automatic: AutomaticBackupUiState = AutomaticBackupUiState(),
)

data class AutomaticBackupUiState(
    val enabled: Boolean = false,
    val folderName: String? = null,
    val detailLine: String? = null,
    val resultLine: String? = null,
)

/** A one-shot status line (export/import result or failure); [isError] picks
 *  the accent the screen renders it in. */
data class StatusMessage(val text: UiText, val isError: Boolean)

/**
 * The CSV import preview/confirm screen's state (issue #16's pure preview
 * model, rendered here). [approvedPatterns] starts pre-filled with each
 * [cloud.trotter.log.strength.transfer.csv.UnmatchedExerciseName]'s
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

    /** [cloud.trotter.log.strength.transfer.csv.CsvHistoryImporter.commit]
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
 *  route owns — mirrors [cloud.trotter.log.strength.ui.setup.SetupActions]'s shape. */
data class BackupActions(
    val onAutomaticBackupChange: (Boolean) -> Unit,
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

internal fun automaticBackupResultLine(
    lastSuccessAtMillis: Long?,
    lastAttemptFailed: Boolean,
    permissionLost: Boolean,
    nowMillis: Long,
): String? {
    if (permissionLost) return "Folder unavailable — choose it again"
    if (lastAttemptFailed) return "Last attempt failed — will retry"
    val success = lastSuccessAtMillis ?: return null
    val days = ((nowMillis - success).coerceAtLeast(0) / 86_400_000L).toInt()
    return when (days) {
        0 -> "Last backup: today"
        1 -> "Last backup: yesterday"
        else -> "Last backup: $days days ago"
    }
}

/**
 * Maps the typed core errors to plain user-facing copy (PLAN.md A2: "surface
 * the typed BackupError/CsvImportError cases as clear user-facing messages").
 * One `when` per sealed hierarchy so a new case is a compile error here, not a
 * silently generic message.
 */
object TransferErrorMessages {

    /** How far an interrupted restore got, in the user's terms (#172). None of
     *  these is a problem with the file they picked — which is what the generic
     *  I/O message used to imply — so each says what is true of the device now
     *  and what, if anything, is left for them to do. Shared with the wizard's
     *  first-run restore. */
    fun of(interruption: RestoreInterruption): UiText = UiText.BackupError(when (interruption) {
        is RestoreInterruption.NotStarted -> BackupErrorKind.RESTORE_NOT_STARTED
        is RestoreInterruption.SettingsPending -> BackupErrorKind.SETTINGS_PENDING
        is RestoreInterruption.CleanupPending -> BackupErrorKind.CLEANUP_PENDING
    })

    fun of(error: BackupError): UiText = when (error) {
        is BackupError.TooLarge -> UiText.BackupError(BackupErrorKind.BACKUP_TOO_LARGE, error.bytes)
        is BackupError.Malformed -> UiText.BackupError(BackupErrorKind.BACKUP_MALFORMED)
        is BackupError.UnsupportedSchemaVersion -> UiText.BackupError(BackupErrorKind.UNSUPPORTED_SCHEMA)
        is BackupError.InvalidPayload -> UiText.BackupError(BackupErrorKind.INVALID_PAYLOAD)
        is BackupError.DanglingExerciseReference -> UiText.BackupError(BackupErrorKind.DANGLING_EXERCISE, error.exerciseId)
        is BackupError.InvalidCustomExercise -> UiText.BackupError(BackupErrorKind.INVALID_CUSTOM_EXERCISE)
        is BackupError.Inconsistent -> UiText.BackupError(BackupErrorKind.INCONSISTENT)
    }

    fun of(error: CsvImportError): UiText = when (error) {
        is CsvImportError.TooLarge -> UiText.BackupError(BackupErrorKind.CSV_TOO_LARGE, error.bytes)
        is CsvImportError.Empty -> UiText.BackupError(BackupErrorKind.CSV_EMPTY)
        is CsvImportError.MalformedCsv -> UiText.BackupError(BackupErrorKind.CSV_MALFORMED)
        is CsvImportError.MissingColumns -> UiText.BackupError(BackupErrorKind.CSV_MISSING_COLUMNS, error.missing.joinToString(", "))
        is CsvImportError.AmbiguousWeightUnit -> UiText.BackupError(BackupErrorKind.CSV_AMBIGUOUS_UNIT, error.header)
        is CsvImportError.MalformedRow -> UiText.BackupError(BackupErrorKind.CSV_MALFORMED_ROW, "Row ${error.line}: ${error.detail}")
        is CsvImportError.MissingApproval -> UiText.BackupError(BackupErrorKind.CSV_MISSING_APPROVAL, error.names.joinToString(", "))
    }
}
