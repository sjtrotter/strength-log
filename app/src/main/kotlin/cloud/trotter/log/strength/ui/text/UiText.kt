package cloud.trotter.log.strength.ui.text

import androidx.annotation.AnyRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.strength.R

/** User-visible copy selected below the Compose boundary without resolving it there. */
sealed interface UiText {
    data class BackupError(val kind: BackupErrorKind, val detail: Any? = null) : UiText
    data class BackupStatus(val kind: BackupStatusKind, val first: Int = 0, val second: Int = 0) : UiText
    data class FileAccessFailure(val detail: String?) : UiText
    data object FilePermissionLost : UiText
    data class LogBackfill(val running: Boolean, val count: Int) : UiText
    data class SetLabel(val kind: cloud.trotter.log.strength.domain.model.SetKind, val number: Int) : UiText
}

enum class BackupErrorKind { RESTORE_NOT_STARTED, SETTINGS_PENDING, CLEANUP_PENDING, BACKUP_TOO_LARGE,
    BACKUP_MALFORMED, UNSUPPORTED_SCHEMA, INVALID_PAYLOAD, DANGLING_EXERCISE, INVALID_CUSTOM_EXERCISE,
    INCONSISTENT, CSV_TOO_LARGE, CSV_EMPTY, CSV_MALFORMED, CSV_MISSING_COLUMNS, CSV_AMBIGUOUS_UNIT,
    CSV_MALFORMED_ROW, CSV_MISSING_APPROVAL }
enum class BackupStatusKind { BACKUP_EXPORTED, BACKUP_RESTORED, HISTORY_EXPORTED, HISTORY_IMPORTED }

@AnyRes
fun UiText.resourceId(): Int = when (this) {
    is UiText.BackupError -> when (kind) {
        BackupErrorKind.RESTORE_NOT_STARTED -> R.string.backup_error_restore_not_started
        BackupErrorKind.SETTINGS_PENDING -> R.string.backup_error_settings_pending
        BackupErrorKind.CLEANUP_PENDING -> R.string.backup_error_cleanup_pending
        BackupErrorKind.BACKUP_TOO_LARGE -> R.string.backup_error_too_large
        BackupErrorKind.BACKUP_MALFORMED -> R.string.backup_error_malformed
        BackupErrorKind.UNSUPPORTED_SCHEMA -> R.string.backup_error_unsupported_schema
        BackupErrorKind.INVALID_PAYLOAD -> R.string.backup_error_invalid_payload
        BackupErrorKind.DANGLING_EXERCISE -> R.string.backup_error_dangling_exercise
        BackupErrorKind.INVALID_CUSTOM_EXERCISE -> R.string.backup_error_invalid_custom_exercise
        BackupErrorKind.INCONSISTENT -> R.string.backup_error_inconsistent
        BackupErrorKind.CSV_TOO_LARGE -> R.string.backup_csv_error_too_large
        BackupErrorKind.CSV_EMPTY -> R.string.backup_csv_error_empty
        BackupErrorKind.CSV_MALFORMED -> R.string.backup_csv_error_malformed
        BackupErrorKind.CSV_MISSING_COLUMNS -> R.string.backup_csv_error_missing_columns
        BackupErrorKind.CSV_AMBIGUOUS_UNIT -> R.string.backup_csv_error_ambiguous_unit
        BackupErrorKind.CSV_MALFORMED_ROW -> R.string.backup_csv_error_malformed_row
        BackupErrorKind.CSV_MISSING_APPROVAL -> R.string.backup_csv_error_missing_approval
    }
    is UiText.BackupStatus -> when (kind) {
        BackupStatusKind.BACKUP_EXPORTED -> R.string.backup_status_exported
        BackupStatusKind.BACKUP_RESTORED -> R.string.backup_status_restored
        BackupStatusKind.HISTORY_EXPORTED -> R.string.backup_status_history_exported
        BackupStatusKind.HISTORY_IMPORTED -> R.string.backup_status_history_imported
    }
    is UiText.FileAccessFailure -> R.string.transfer_error_file_access
    UiText.FilePermissionLost -> R.string.transfer_error_permission
    is UiText.LogBackfill -> when {
        running -> R.string.log_backfill_publishing
        count == 1 -> R.string.log_backfill_publish_one
        else -> R.string.log_backfill_publish_many
    }
    is UiText.SetLabel -> when (kind) {
        cloud.trotter.log.strength.domain.model.SetKind.RAMP -> R.string.set_label_ramp
        cloud.trotter.log.strength.domain.model.SetKind.TOP -> R.string.set_label_top
        cloud.trotter.log.strength.domain.model.SetKind.BACKOFF -> R.string.set_label_backoff
        else -> R.string.set_label_plain
    }
}

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.BackupError -> stringResource(resourceId(), detail ?: "")
    is UiText.BackupStatus -> stringResource(resourceId(), first, second)
    is UiText.FileAccessFailure -> stringResource(resourceId(), detail.orEmpty())
    UiText.FilePermissionLost -> stringResource(resourceId())
    is UiText.LogBackfill -> if (running) stringResource(resourceId()) else stringResource(resourceId(), count)
    is UiText.SetLabel -> stringResource(resourceId(), number)
}
