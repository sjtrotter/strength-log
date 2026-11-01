package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.db.entity.Slot
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiftSheet(
    card: ExerciseCardState,
    slot: String,
    focus: WorkoutFocus?,
    actions: DayActions,
    canSwap: Boolean,
    weightSwap: WeightSwapAffordance?,
    onWeightSwap: (WeightSwapAffordance) -> Unit,
    onNote: () -> Unit,
    onDismiss: () -> Unit,
) {
    val partner = slot == Slot.SS
    val title = if (partner) card.partnerTitle.orEmpty() else card.title
    val target = if (partner) card.partnerHeaderTarget else card.headerTarget
    val last = if (partner) card.partnerLastTimeDisplay else card.lastTimeDisplay
    val date = if (partner) card.partnerLastTimeDate else card.lastTimeDate
    val support = listOfNotNull(
        target?.display?.takeIf { it.isNotBlank() && it.any { digit -> digit in '1'..'9' } }?.let {
            stringResource(if (target is HeaderTarget.Goal) R.string.lift_sheet_goal else R.string.lift_sheet_target, it)
        },
        last?.takeIf { it.isNotBlank() }?.let {
            if (date == null) stringResource(R.string.lift_sheet_last, it)
            else stringResource(R.string.lift_sheet_last_date, it, date)
        },
    ).joinToString(" ")
    AppModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    if (support.isNotEmpty()) Text(support, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (card.isSuperset) Text(
                        stringResource(R.string.lift_sheet_superset_with, if (partner) card.title else card.partnerTitle.orEmpty()),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(card.rows, key = { it.index }) { row ->
                row.values(slot)?.let { values ->
                    val value = setValue(values.weightDisplay, values.reps, values.seconds, card.tracking(slot),
                        if (partner) card.partnerTimedShowsWeight else card.timedShowsWeight)
                    val notation = if (partner) card.partnerSetLabels.getOrNull(row.index) ?: "${row.index + 1}" else row.kindLabel
                    val description = stringResource(R.string.set_done_description, title, notation, value)
                    ListItem(
                        leadingContent = { Checkbox(values.done,
                            onCheckedChange = { actions.onToggleDone(card.programExerciseId, row.index, it, slot) },
                            modifier = Modifier.testTag("lift-check-$slot-${row.index}").semantics { contentDescription = description }) },
                        headlineContent = {
                            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("lift-set-${row.index}")
                                .clickable { actions.onOpenSetEditor(card.programExerciseId, row.index) },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text(notation)
                                Text(value, style = MaterialTheme.typography.bodyLarge)
                            }
                        },
                        trailingContent = if (focus == WorkoutFocus(card.programExerciseId, slot, row.index)) {
                            { Text(stringResource(R.string.lift_sheet_now)) }
                        } else null,
                    )
                }
            }
            item {
                TextButton(onClick = { actions.onAddSet(card.programExerciseId, card.isSuperset) },
                    modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.day_add_set)) }
                HorizontalDivider()
            }
            if (canSwap) item {
                ListItem(headlineContent = { Text(stringResource(R.string.day_menu_swap)) },
                    modifier = Modifier.clickable { actions.onOpenSwapSheet(card.position, slot) })
            }
            weightSwap?.let { swap -> item {
                ListItem(headlineContent = { Text(stringResource(
                    if (swap.isRemove) R.string.day_remove_weight_button else R.string.day_add_weight_button)) },
                    modifier = Modifier.clickable { onWeightSwap(swap) })
            } }
            item {
                ListItem(headlineContent = { Text(stringResource(R.string.day_menu_note)) },
                    supportingContent = if (card.note.isNotBlank()) { { Text(card.note) } } else null,
                    modifier = Modifier.clickable(onClick = onNote))
            }
        }
    }
}
