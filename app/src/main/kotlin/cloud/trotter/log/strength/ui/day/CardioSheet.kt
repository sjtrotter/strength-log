package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    executionBusy: Boolean = false,
) {
    val block = cardio.block ?: return
    val run = cardio.run
    val results = run?.resultSets ?: cardio.loggedSets
    val onDismiss = actions.onCloseAllSets
    var menuOpen by remember { mutableStateOf(false) }
    AppModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(cardioActivityName(block.activity), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.sheet_more)) }
                        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (!cardio.completed && run?.startedAt == null && !executionBusy && startEnabled) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.cardio_log_past)) }, onClick = {
                                    menuOpen = false
                                    onDismiss()
                                    actions.onOpenPastCardio()
                                })
                            }
                            if (run?.running != true && !cardio.completed && startEnabled) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.cardio_skip)) }, onClick = {
                                    menuOpen = false; onDismiss()
                                    actions.onSkipCardioSet(run?.action() ?: CardioSetAction(dayId, null, cardio.position))
                                })
                            }
                            if (cardio.canAddSet && run?.complete != true && (run != null || results.isEmpty())) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.cardio_add_set)) },
                                    onClick = { menuOpen = false; actions.onAddCardioSet() })
                            }
                            if (!cardio.completed && (run != null || startEnabled)) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.workout_end_cardio)) },
                                    onClick = { menuOpen = false; onDismiss(); onEnd() })
                            }
                        }
                    }
                }
                if (cardio.finishesDay) Text(stringResource(R.string.cardio_finishes_day, dayId),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            itemsIndexed(block.sets) { index, set ->
                val result = results.getOrNull(index)
                val now = index == cardio.position && !cardio.completed
                ListItem(
                    leadingContent = { Text((index + 1).toString(), modifier = Modifier.width(48.dp)) },
                    overlineContent = { Text(roleName(set.role), style = MaterialTheme.typography.bodyMedium) },
                    headlineContent = {
                        Text(set.aim?.let {
                            stringResource(R.string.cardio_end_aim, measureText(set.end), measureText(it))
                        } ?: measureText(set.end), style = MaterialTheme.typography.titleLarge)
                    },
                    colors = ListItemDefaults.colors(containerColor = if (now) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface),
                    modifier = Modifier.heightIn(min = 72.dp),
                    supportingContent = if (result != null || now) { {
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

        }
    }
}
