package cloud.trotter.log.strength.ui.day

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DaySheet(
    state: DayUiState,
    actions: DayActions,
    onEditDay: () -> Unit,
    onClearChecks: () -> Unit,
    onDismiss: () -> Unit,
    onDiscardWorkout: () -> Unit = {},
) {
    val selectedKey = (state.workout?.shown as? WorkoutSelection.Shown.Group)?.key
    AppModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.today_day_label, state.viewDayId.orEmpty()),
                        style = MaterialTheme.typography.titleLarge)
                    Text(listOf(state.dayTitle.takeIf(String::isNotBlank),
                        stringResource(R.string.day_sheet_progress, state.doneSets, state.totalSets))
                        .filterNotNull().joinToString(", "), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(state.pages.size, key = { state.pages[it].key }) { index ->
                val page = state.pages[index]
                val card = state.exercises.firstOrNull { it.programExerciseId == page.exerciseId }
                val paired = card?.isSuperset == true
                val selected = page.key == selectedKey
                val total = if (page.exerciseId == null) state.cardio?.block?.sets?.size ?: page.total else page.total
                val title = if (card?.isSuperset == true) stringResource(R.string.day_sheet_pair, page.title, card.partnerTitle.orEmpty()) else page.title
                ListItem(
                    overlineContent = if (paired) { { Text(stringResource(R.string.day_sheet_superset), style = MaterialTheme.typography.bodySmall) } } else null,
                    headlineContent = { Text(title) },
                    trailingContent = {
                        if (page.allDone) Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, null)
                            Text(stringResource(R.string.set_edit_done), style = MaterialTheme.typography.bodyMedium)
                        } else Text(when {
                            selected && paired -> stringResource(R.string.day_sheet_round_now,
                                (state.workout?.focus?.index ?: 0) + 1, total)
                            selected -> stringResource(R.string.day_sheet_lift_now, page.done, total)
                            else -> pluralStringResource(R.plurals.plan_sets, total, total)
                        }, style = MaterialTheme.typography.bodyMedium)
                    },
                    modifier = Modifier.clickable { actions.onSelectWorkoutLift(page.key); onDismiss() },
                )
            }
            item { HorizontalDivider() }
            item { ListItem(headlineContent = { Text(stringResource(R.string.day_menu_edit_day)) },
                modifier = Modifier.clickable { onDismiss(); onEditDay() }) }
            if (state.runOpen) item {
                ListItem(headlineContent = { Text(stringResource(R.string.day_sheet_clear_checks)) },
                    modifier = Modifier.clickable { onDismiss(); onClearChecks() })
            }
            if (state.runOpen) item {
                ListItem(headlineContent = { Text(stringResource(R.string.day_menu_clear_checks)) },
                    modifier = Modifier.clickable { onDismiss(); onDiscardWorkout() })
            }
        }
    }
}
