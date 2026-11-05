package cloud.trotter.log.strength.ui.backup

import cloud.trotter.log.strength.data.prefs.RestoreInterruption
import cloud.trotter.log.strength.domain.model.MovementPattern
import cloud.trotter.log.strength.transfer.backup.BackupError
import cloud.trotter.log.strength.transfer.csv.CsvImportError
import cloud.trotter.log.strength.transfer.csv.CsvImportPreview
import cloud.trotter.log.strength.ui.text.BackupErrorKind
import cloud.trotter.log.strength.ui.text.UiText

/** The operation labels busy controls; only [BackupUiState.restoreInFlight] locks screen exits.
 * Reading a restore file uses [BackupOperation.Restore] without locking exits;
 * only a confirmed restore writes both stores and raises that separate lock. */
enum class BackupOperation { None, ExportBackup, ExportCsv, Restore, ImportCsv }

data class BackupUiState(
    val operation: BackupOperation = BackupOperation.None,
    val restoreInFlight: Boolean = false,
    val automaticChangeInFlight: Boolean = false,
    val message: StatusMessage? = null,
    val pendingRestoreConfirm: Boolean = false,
    val csvImport: CsvImportUiState? = null,
    val automatic: AutomaticBackupUiState = AutomaticBackupUiState(),
) {
    val isBusy: Boolean get() = operation != BackupOperation.None || automaticChangeInFlight
}

data class AutomaticBackupUiState(
    val enabled: Boolean = false,
    val folderName: String? = null,
    val detailLine: String? = null,
    val permissionLost: Boolean = false,
    val lastAttemptFailed: Boolean = false,
    val lastSuccessAtMillis: Long? = null,
)

/** A one-shot status line (export/import result or failure); [isError] picks
 *  the accent the screen renders it in. */
data class StatusMessage(val text: UiText, val isError: Boolean)

/** CSV preview suggestions remain unapproved until the user explicitly confirms
 * each unmatched exercise. [approvedPatterns] starts empty; choosing a pattern
 * or confirming the displayed suggestion approves that row. */
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
        /** Shows suggestions without approving any unmatched name. */
        fun of(preview: CsvImportPreview): CsvImportUiState =
            CsvImportUiState(preview, emptyMap())
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

/**
 * Maps the typed core errors to plain user-facing copy (PLAN.md A2: "surface
 * the typed BackupError/CsvImportError cases as clear user-facing messages").
 * One `when` per sealed hierarchy so a new case is a compile error here, not a
 * silently generic message.
 */
object TransferErrorMessages {

    /** How far an interrupted restore got, in the user's terms. None of these
     *  is a problem with the file they picked — so each says what is true of
     *  the device and what, if anything, is left for them to do. Shared with
     *  the wizard's first-run restore. */
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
