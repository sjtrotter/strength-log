package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.components.SheetCommitButton
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StartWorkoutSheet(dayId: String, dayIndex: Int, onStart: () -> Unit, onDismiss: () -> Unit) {
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = {
            Text(stringResource(R.string.start_sheet_title, dayId),
                modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        },
        body = {
            Text(stringResource(R.string.start_sheet_body), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        footer = {
            SheetCommitButton(label = stringResource(R.string.workout_start), onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = dayAccent(dayIndex), contentColor = onDayAccent(dayIndex)),
                cancelLabel = stringResource(R.string.start_sheet_not_yet), onCancel = onDismiss)
        },
    )
}
