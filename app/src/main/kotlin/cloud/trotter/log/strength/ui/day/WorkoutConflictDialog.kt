package cloud.trotter.log.strength.ui.day

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.components.AppAlertDialog
import cloud.trotter.log.strength.ui.components.DialogAction

@Composable
fun WorkoutConflictDialog(
    conflict: WorkoutConflict?,
    onDismiss: () -> Unit,
    onResume: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onDiscard: () -> Unit,
) {
    if (conflict == null) return
    val run = conflict.run
    if (run.draft) {
        DraftDropDialog(run.dayId.orEmpty(), onDrop = onDiscard, onDismiss = onDismiss)
        return
    }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (conflict.confirming) R.string.workout_discard_confirm else R.string.workout_open)) },
        text = { Text(stringResource(R.string.workout_identity, run.planName.orEmpty(), run.dayId.orEmpty(), run.dayTitle)) },
        confirmButton = {
            TextButton(onClick = if (conflict.confirming) onDiscard else onConfirmDiscard) {
                Text(stringResource(R.string.workout_discard_start))
            }
        },
        dismissButton = {
            TextButton(onClick = if (conflict.confirming) onDismiss else onResume) {
                Text(stringResource(if (conflict.confirming) R.string.setup_cancel_button else R.string.workout_resume))
            }
        },
    )
}

@Composable
internal fun DraftDropDialog(dayId: String, onDrop: () -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.draft_drop_title, dayId)) },
        text = { Text(stringResource(R.string.draft_drop_body)) },
        confirmButton = { DialogAction(stringResource(R.string.draft_drop_confirm), MaterialTheme.colorScheme.error, onDrop) },
        dismissButton = { DialogAction(stringResource(R.string.day_cancel_button), MaterialTheme.colorScheme.onSurfaceVariant, onDismiss) },
    )
}
