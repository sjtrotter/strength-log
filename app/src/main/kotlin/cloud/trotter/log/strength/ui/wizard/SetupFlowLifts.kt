package cloud.trotter.log.strength.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.strength.R
import cloud.trotter.log.strength.data.serialization.PlanRecipeJson
import cloud.trotter.log.strength.domain.library.ExerciseLibrary
import cloud.trotter.log.strength.domain.planner.Planner
import cloud.trotter.log.strength.domain.progression.Effort
import cloud.trotter.log.strength.domain.units.WeightStepper
import cloud.trotter.log.strength.ui.components.AppCard
import cloud.trotter.log.strength.ui.components.NumberField
import cloud.trotter.log.strength.ui.day.EffortText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiftsStep(state: SetupFlowUiState, actions: SetupFlowActions) {
    val labels = listOf(R.string.setup_flow_row_squat, R.string.setup_flow_row_hinge,
        R.string.setup_flow_row_horizontal_press, R.string.setup_flow_row_overhead_press, R.string.setup_flow_row_pull)
    state.lifts.forEach { row ->
        key(row.row) {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(value = row.selectedId?.let { ExerciseLibrary.get(it).name } ?: stringResource(R.string.setup_flow_lift_dropped),
                    onValueChange = {}, readOnly = true, label = { Text(stringResource(labels[row.row])) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().testTag("setup-lift-row-${row.row}")
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
                ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    row.choices.forEach { id ->
                        DropdownMenuItem(text = { Text(ExerciseLibrary.get(id).name) }, onClick = {
                            actions.onMain(row.row, id)
                            expanded = false
                        })
                    }
                }
            }
        }
    }
    Planner.sprintLowerMainMissing(SetupFlowStateBuilder.answers(state.draft))?.let {
        SprintFloorLine(it)
    }
    Text(stringResource(R.string.setup_flow_lifts_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun RecentStep(state: SetupFlowUiState, actions: SetupFlowActions) {
    val unit = state.draft.unit
    state.recent.forEach { row ->
        key(row.exerciseId) {
            AppCard {
                Text(row.name, style = MaterialTheme.typography.titleMedium)
                if (row.perHand) Text(stringResource(R.string.setup_flow_per_hand), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(value = row.weightLb?.let(unit::fromLb),
                        onValueChange = { actions.onRecentWeight(row.exerciseId, it.takeIf { weight -> weight > 0 }?.let(unit::toLb)) },
                        onDraftChange = { actions.onRecentWeight(row.exerciseId, it?.takeIf { weight -> weight > 0 }?.let(unit::toLb)) },
                        label = stringResource(R.string.sheet_weight, unit.name.lowercase()),
                        modifier = Modifier.weight(1f).testTag("setup-recent-weight-${row.exerciseId}"),
                        maxValue = unit.fromLb(PlanRecipeJson.MAX_GOAL_LB), format = WeightStepper::format,
                        decimalInput = true, weightInput = true)
                    NumberField(value = row.reps?.toDouble(), onValueChange = { actions.onRecentReps(row.exerciseId, it.toInt()) },
                        onDraftChange = { actions.onRecentReps(row.exerciseId, it?.toInt()) },
                        label = stringResource(R.string.prescription_reps),
                        modifier = Modifier.weight(1f).testTag("setup-recent-reps-${row.exerciseId}"), minValue = 1.0, maxValue = 10.0)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Effort.entries.forEach { effort ->
                        SetupChip(row.effort == effort, { actions.onRecentEffort(row.exerciseId, effort) },
                            Modifier.testTag("setup-recent-effort-${row.exerciseId}-${effort.name}")) { EffortText(effort) }
                    }
                }
            }
        }
    }
}
