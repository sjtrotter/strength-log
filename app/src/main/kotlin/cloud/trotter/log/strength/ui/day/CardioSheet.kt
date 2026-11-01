package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.model.CardioSetStatus
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkoutCardioSheet(
    cardio: CardioCardState,
    dayId: String,
    actions: DayActions,
    onEnd: () -> Unit,
    startEnabled: Boolean = true,
) {
    val block = cardio.block ?: return
    val run = cardio.run
    val results = run?.resultSets ?: cardio.loggedSets
    val onDismiss = actions.onCloseAllSets
    AppModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(cardioActivityName(block.activity), style = MaterialTheme.typography.titleLarge)
                    if (cardio.finishesDay) Text(stringResource(R.string.cardio_finishes_day, dayId),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(block.sets) { index, set ->
                val result = results.getOrNull(index)
                val now = index == cardio.position && !cardio.completed
                ListItem(
                    headlineContent = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(roleName(set.role))
                            Text(set.aim?.let {
                                stringResource(R.string.cardio_end_aim, measureText(set.end), measureText(it))
                            } ?: measureText(set.end))
                        }
                    },
                    trailingContent = if (result != null || now) { {
                        val status = if (result == null) stringResource(R.string.lift_sheet_now) else {
                            val name = statusName(result.status, false)
                            val metres = result.metres
                            if (result.status == CardioSetStatus.SHORTENED && metres != null) {
                                stringResource(R.string.workout_cardio_status_distance, name, metres)
                            } else name
                        }
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                    } } else null,
                )
            }
            cardio.distanceRuns.forEach { entry ->
                entry.drafts.filter { !it.saved }.forEach { draft -> item {
                    ListItem(headlineContent = { Text(stringResource(R.string.cardio_enter_distance)) },
                        supportingContent = { Text(stringResource(R.string.cardio_entry_identity,
                            cardioActivityName(entry.block.activity), draft.position + 1)) },
                        modifier = Modifier.heightIn(min = 48.dp).clickable {
                            onDismiss()
                            actions.onRequestCardioDistance(entry.action(draft.position))
                        })
                } }
            }
            item { HorizontalDivider() }
            if (run?.running != true && !cardio.completed && startEnabled) item {
                ListItem(headlineContent = { Text(stringResource(R.string.cardio_skip)) },
                    modifier = Modifier.heightIn(min = 48.dp).clickable {
                        onDismiss()
                        actions.onSkipCardioSet(run?.action() ?: CardioSetAction(dayId, null, cardio.position))
                    })
            }
            if (cardio.canAddSet && run?.complete != true && (run != null || results.isEmpty())) item {
                ListItem(headlineContent = { Text(stringResource(R.string.cardio_add_set)) },
                    modifier = Modifier.heightIn(min = 48.dp).clickable { actions.onAddCardioSet() })
            }
            if (!cardio.completed && (run != null || startEnabled)) item {
                ListItem(headlineContent = { Text(stringResource(R.string.workout_end_cardio)) },
                    modifier = Modifier.heightIn(min = 48.dp).clickable { onDismiss(); onEnd() })
            }
        }
    }
}
