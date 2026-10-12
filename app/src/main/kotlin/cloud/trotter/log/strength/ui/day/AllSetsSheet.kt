package cloud.trotter.log.strength.ui.day

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.domain.library.TrackingType
import cloud.trotter.log.strength.ui.components.AppModalBottomSheet
import cloud.trotter.log.strength.ui.text.resolve
import cloud.trotter.log.strength.ui.theme.DisplayXl
import cloud.trotter.log.strength.ui.theme.SetKindLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AllSetsSheet(
    card: ExerciseCardState,
    actions: DayActions,
    onEdit: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            text = card.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            items(card.rows, key = { it.index }, contentType = { "set" }) { row ->
                val value = setValue(row.weightDisplay, row.reps, row.seconds, card.tracking, card.timedShowsWeight)
                val description = stringResource(R.string.set_done_description, setLabel(row).resolve(), value)
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(row.kindLabel, style = SetKindLabel)
                    Column(
                        modifier = Modifier.weight(1f).clickable { onEdit(row.index) }.heightIn(min = 48.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(value, style = DisplayXl.copy(fontSize = 30.sp, fontFeatureSettings = "tnum"))
                        row.partner?.let { partner ->
                            val partnerValue = setValue(
                                weight = partner.weightDisplay,
                                reps = partner.reps,
                                seconds = partner.seconds,
                                tracking = card.partnerTracking ?: TrackingType.WEIGHTED,
                                timedWeight = card.partnerTimedShowsWeight,
                            )
                            Text(
                                text = stringResource(
                                    R.string.day_then_partner,
                                    "${card.partnerTitle.orEmpty()} $partnerValue",
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Checkbox(
                        checked = row.done,
                        onCheckedChange = {
                            actions.onToggleDone(card.programExerciseId, row.index, it, card.isSuperset)
                        },
                        modifier = Modifier.semantics { contentDescription = description },
                    )
                }
            }
            item(contentType = "add") {
                TextButton(
                    onClick = { actions.onAddSet(card.programExerciseId, card.isSuperset) },
                    modifier = Modifier.padding(horizontal = 20.dp).heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.day_add_set))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExercisesSheet(pages: List<WorkoutPage>, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn {
            items(pages.size, key = { pages[it].key }, contentType = { "exercise" }) { index ->
                val page = pages[index]
                ListItem(
                    headlineContent = { Text(page.title) },
                    supportingContent = { Text(stringResource(R.string.day_saved_of, page.done, page.total)) },
                    trailingContent = { Checkbox(page.allDone, onCheckedChange = null) },
                    modifier = Modifier.clickable { onSelect(index) },
                )
            }
        }
    }
}
