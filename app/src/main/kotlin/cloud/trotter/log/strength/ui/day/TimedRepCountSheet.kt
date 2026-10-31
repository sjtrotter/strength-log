package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.theme.dayAccent
import cloud.trotter.log.strength.ui.theme.onDayAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimedRepCountSheet(
    card: ExerciseCardState,
    event: TimedRepEvent,
    dayIndex: Int,
    actions: DayActions,
) {
    AppModalBottomSheet(
        onDismissRequest = actions.onCloseSetEditor,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.timed_rep_time_up), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.timed_rep_set_identity, card.title, event.setIndex + 1),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NumericSetField(
                identity = "timed-reps:${event.programExerciseId}:${event.setIndex}:${event.wallStart}",
                value = (event.count ?: 0).toDouble(),
                label = stringResource(R.string.set_edit_reps),
                field = stringResource(R.string.set_edit_reps),
                partnerName = null,
                modifier = Modifier.fillMaxWidth(),
                minimum = 0.0,
                step = { 1.0 },
                onChange = { actions.onTimedRepDraftChange(it.toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toString()) },
                textOverride = event.draft,
                onTextChange = actions.onTimedRepDraftChange,
            )
            val count = event.count
            if (card.timedRepAim != null && count != null) {
                Text(
                    stringResource(R.string.timed_rep_aim, count, card.timedRepAim),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val reps = pluralStringResource(R.plurals.timed_rep_count, count ?: 0, count ?: 0)
            val log = if (card.isSuperset) {
                stringResource(R.string.day_log_exercise_set, card.title, reps)
            } else stringResource(R.string.day_log_set, reps)
            Button(
                onClick = actions.onLogTimedRepCount,
                enabled = event.count != null,
                modifier = Modifier.fillMaxWidth().semantics { if (card.isSuperset) contentDescription = log },
                colors = ButtonDefaults.buttonColors(
                    containerColor = dayAccent(dayIndex),
                    contentColor = onDayAccent(dayIndex),
                ),
            ) {
                Text(log)
            }
        }
    }
}
