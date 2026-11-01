package cloud.trotter.log.strength.ui.day

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.components.AppAlertDialog

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
